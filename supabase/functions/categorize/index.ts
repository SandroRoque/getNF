// Suggests categories for new store items with Jev (stage 4b, D70; the setup measured in D67/D68).
//
// pg_cron calls this every minute while categorize_job has work (kick_categorize). Each run claims a batch, and for
// each item asks Jev one choice question over the user's leaf categories, with the item's words translated by the
// shared lexicon and, for a barcode, Open Food Facts' name. Confidence at or above THRESHOLD becomes the category
// ("automatic", not confirmed); below, a suggestion for the review list. A failure retries with backoff.
//
// A POST with {"check": {"user_id", "items": [{description, code_kind, code}]}} answers those items with the same
// code and writes nothing: the regression check (D69) runs through exactly what production runs.
//
// Sent to TypeSafe: the translated item description and Open Food Facts' name and categories. Nothing else.
// Secrets (supabase secrets set): JEV_API_KEY, CATEGORIZE_SECRET. Optional: JEV_URL, JEV_MODEL, THRESHOLD, BATCH.

import { createClient } from "npm:@supabase/supabase-js@2";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const JEV_URL = (Deno.env.get("JEV_URL") ?? "https://api.typesafe.ai").replace(/\/$/, "");
const JEV_MODEL = Deno.env.get("JEV_MODEL") ?? "jev-latest";
const THRESHOLD = Number(Deno.env.get("THRESHOLD") ?? "0.9");
const BATCH = Number(Deno.env.get("BATCH") ?? "25");
const INSTRUCTIONS = "What kind of product is this item from a Brazilian supermarket receipt?";
const OFF_FIELDS = "product_name,product_name_pt,brands,quantity,categories_tags";

type Job = { user_id: string; store_item_id: string; product_id: string; code_kind: string; code: string; description: string };
type Option = { key: string; id: string; text: string };

Deno.serve(async (req) => {
  if (req.headers.get("x-categorize-secret") !== Deno.env.get("CATEGORIZE_SECRET")) {
    return new Response("forbidden", { status: 403 });
  }
  const body = await req.json().catch(() => ({}));
  if (body.check) return json(await check(body.check.user_id, body.check.items));

  const { data: jobs, error } = await db.rpc("claim_categorize_jobs", { batch: BATCH });
  if (error) return json({ error: error.message }, 500);
  if (!jobs?.length) return json({ done: 0 });

  const expand = await lexicon();
  const options = new Map<string, Option[]>();
  const outcomes: Record<string, number> = {};
  for (const job of jobs as Job[]) {
    try {
      if (!options.has(job.user_id)) options.set(job.user_id, await leafCategories(job.user_id));
      const { chosen, confidence } = await answer(job, options.get(job.user_id)!, expand);
      const { data: outcome, error } = await db.rpc("apply_categorization", {
        uid: job.user_id, item: job.store_item_id, product: job.product_id, category: chosen.id,
        confidence, model: JEV_MODEL, threshold: THRESHOLD,
      });
      if (error) throw new Error(error.message);
      outcomes[outcome] = (outcomes[outcome] ?? 0) + 1;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      console.error(`job ${job.store_item_id}: ${message}`);
      await db.rpc("fail_categorize_job", { uid: job.user_id, item: job.store_item_id, error: message.slice(0, 500) });
      outcomes.failed = (outcomes.failed ?? 0) + 1;
    }
  }
  return json({ done: jobs.length, ...outcomes });
});

type Item = { description: string; code_kind: string; code: string };

async function answer(item: Item, choices: Option[], expand: (t: string) => string) {
  const state: Record<string, string> = { item: expand(item.description) };
  if (item.code_kind === "gtin") Object.assign(state, await openFoodFacts(item.code));
  const a = await jev(state, choices);
  const chosen = choices.find((o) => o.key === a.choice);
  if (!chosen) throw new Error(`Jev chose an unknown option: ${a.choice}`);
  return { chosen, confidence: a.confidence };
}

async function check(userId: string, items: Item[]) {
  const [expand, choices] = await Promise.all([lexicon(), leafCategories(userId)]);
  const out = [];
  for (const item of items) {
    try {
      const { chosen, confidence } = await answer(item, choices, expand);
      out.push({ category: chosen.key.replaceAll("__", "."), confidence });
    } catch (e) {
      out.push({ error: e instanceof Error ? e.message : String(e) });
    }
  }
  return { model: JEV_MODEL, threshold: THRESHOLD, answers: out };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** The user's categories that have no children: what an item can be assigned to, described as in D67's eval. */
async function leafCategories(userId: string): Promise<Option[]> {
  const { data: cats, error } = await db.from("category").select("id, seed_path, label, description, parent_id")
    .eq("user_id", userId).eq("deleted", false);
  if (error) throw new Error(error.message);
  const { data: seed } = await db.from("taxonomy_node").select("path, description_en");
  const described = new Map((seed ?? []).map((n) => [n.path, n.description_en]));
  const parents = new Set(cats!.map((c) => c.parent_id).filter(Boolean));
  return cats!.filter((c) => !parents.has(c.id)).map((c) => {
    const name = c.seed_path ?? `user.${c.id.slice(0, 8)}`;
    // The user's own description first (D75), then the seed's, then the label.
    const text = c.description || described.get(c.seed_path) || c.label;
    return { key: name.replaceAll(".", "__"), id: c.id, text: `${text} (${name})` };
  });
}

/** Phrases (longest first), then single words; unknown words are kept (often a brand). As tools/categorize-eval.py. */
async function lexicon(): Promise<(text: string) => string> {
  const { data, error } = await db.from("lexicon").select("key, en");
  if (error) throw new Error(error.message);
  const phrases = data!.filter((r) => r.key.includes(" ")).map((r) => ({ words: r.key.split(" "), en: r.en }))
    .sort((a, b) => b.words.join(" ").length - a.words.join(" ").length);
  const tokens = new Map(data!.filter((r) => !r.key.includes(" ")).map((r) => [r.key, r.en]));
  return (text) => {
    const words = text.toUpperCase().split(/\s+/).filter(Boolean);
    const out: string[] = [];
    for (let i = 0; i < words.length;) {
      const phrase = phrases.find((p) => p.words.every((w, k) => words[i + k] === w));
      if (phrase) { out.push(phrase.en); i += phrase.words.length; } else { out.push(tokens.get(words[i]) ?? words[i]); i++; }
    }
    return out.join(" ");
  };
}

/** Open Food Facts' name and last categories for a barcode, looked up once and kept for everyone (D60, D68). */
async function openFoodFacts(gtin: string): Promise<Record<string, string>> {
  const { data: cached } = await db.from("barcode_lookup").select("product").eq("gtin", gtin).maybeSingle();
  let product = cached?.product ?? null;
  if (!cached) {
    const r = await fetch(`https://world.openfoodfacts.org/api/v2/product/${gtin}.json?fields=${OFF_FIELDS}`, {
      headers: { "User-Agent": "getNF/0.1 (personal receipt app)" },
    });
    if (r.ok || r.status === 404) {
      const body = await r.json();
      product = body.status === 1 ? body.product : null;
      await db.from("barcode_lookup").upsert({ gtin, product });
    } // otherwise: try again next time, the item still gets categorized now
  }
  if (!product) return {};
  const out: Record<string, string> = {};
  const name = [product.product_name_pt || product.product_name, product.brands, product.quantity].filter(Boolean).join(" ");
  if (name) out.product_name = name;
  const tags = (product.categories_tags ?? []).map((t: string) => t.split(":").pop()!.replaceAll("-", " "));
  if (tags.length) out.product_categories = tags.slice(-4).join(", ");
  return out;
}

async function jev(state: Record<string, string>, choices: Option[]): Promise<{ choice: string; confidence: number }> {
  const r = await fetch(`${JEV_URL}/v1/systemone`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${Deno.env.get("JEV_API_KEY")}` },
    body: JSON.stringify({
      model: JEV_MODEL, state,
      questions: { c: { type: "choice", instructions: INSTRUCTIONS, criteria: Object.fromEntries(choices.map((o) => [o.key, o.text])) } },
    }),
  });
  if (!r.ok) throw new Error(`Jev ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const c = (await r.json()).answers.c;
  return { choice: c.choice, confidence: Number(c.confidence ?? 0) };
}
