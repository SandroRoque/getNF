// Reads new store items with Jev (stage 4b, D70; the setup measured in D67/D68; more questions in D108).
//
// pg_cron calls this every minute while categorize_job has work (kick_categorize). Each run claims a batch, and for
// each item asks Jev, in one request, up to three choice questions: its category, over the caderneta's leaf
// categories, when its product has none yet; how it's sold, when the unit doesn't settle it (reads.ts); and its net
// size, among the candidates code finds in the description, or none. The state is the item's words translated by the
// shared lexicon, its receipt text, unit and quantity, and for a barcode Open Food Facts' name. Confidence at or above
// THRESHOLD is applied ("automatic", not confirmed); below, a category or a size becomes a suggestion for the review
// list. A failure retries with backoff.
//
// A POST with {"check": {"ledger_id", "items": [{description, code_kind, code, unit?, qty_e4?}], "lexicon"?: [{key, en}]}}
// answers those items with the same code and writes nothing: the regression check (D69) runs through exactly what
// production runs. Extra lexicon entries are tried on top of the table, to test a proposed one before it goes live.
//
// Sent to TypeSafe: the item's description (translated and as printed), its unit and quantity, and Open Food Facts'
// name and categories. Not the store, prices or the receipt.
// Secrets (supabase secrets set): JEV_API_KEY, CATEGORIZE_SECRET. Optional: JEV_URL, JEV_MODEL, THRESHOLD, BATCH.

import { createClient } from "npm:@supabase/supabase-js@2";
import {
  type Candidate, NO_SIZE, NO_SIZE_TEXT, SIZE_INSTRUCTIONS, type SoldBy, SOLD_BY_CRITERIA, SOLD_BY_INSTRUCTIONS,
  sizeCandidates, soldByFromUnit,
} from "./reads.ts";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const JEV_URL = (Deno.env.get("JEV_URL") ?? "https://api.typesafe.ai").replace(/\/$/, "");
const JEV_MODEL = Deno.env.get("JEV_MODEL") ?? "jev-latest";
const THRESHOLD = Number(Deno.env.get("THRESHOLD") ?? "0.9");
const BATCH = Number(Deno.env.get("BATCH") ?? "25");
const INSTRUCTIONS = "What kind of product is this item from a Brazilian supermarket receipt?";
const OFF_FIELDS = "product_name,product_name_pt,brands,quantity,categories_tags";

type Job = {
  ledger_id: string; store_item_id: string; product_id: string; code_kind: string; code: string; description: string;
  wants_category: boolean; unit: string | null; qty_e4: number | null;
};
type Option = { key: string; id: string; text: string };

Deno.serve(async (req) => {
  if (req.headers.get("x-categorize-secret") !== Deno.env.get("CATEGORIZE_SECRET")) {
    return new Response("forbidden", { status: 403 });
  }
  const body = await req.json().catch(() => ({}));
  if (body.check) return json(await check(body.check.ledger_id, body.check.items, body.check.lexicon ?? []));

  const { data: jobs, error } = await db.rpc("claim_categorize_jobs", { batch: BATCH });
  if (error) return json({ error: error.message }, 500);
  if (!jobs?.length) return json({ done: 0 });

  const expand = await lexicon();
  const options = new Map<string, Option[]>();
  const outcomes: Record<string, number> = {};
  for (const job of jobs as Job[]) {
    try {
      if (job.wants_category && !options.has(job.ledger_id)) options.set(job.ledger_id, await leafCategories(job.ledger_id));
      const a = await answer(job, job.wants_category ? options.get(job.ledger_id)! : null, expand);
      if (a.category) {
        const { data: outcome, error } = await db.rpc("apply_categorization", {
          lid: job.ledger_id, item: job.store_item_id, product: job.product_id, category: a.category.chosen.id,
          confidence: a.category.confidence, model: JEV_MODEL, threshold: THRESHOLD,
        });
        if (error) throw new Error(error.message);
        outcomes[outcome] = (outcomes[outcome] ?? 0) + 1;
      }
      const { data: read, error } = await db.rpc("apply_reading", {
        lid: job.ledger_id, item: job.store_item_id, product: job.product_id,
        sold_by: a.soldBy?.value ?? null, sold_by_source: a.soldBy?.source ?? null, sold_by_confidence: a.soldBy?.confidence ?? null,
        size_asked: a.size != null, size_e6: a.size?.e6 ?? null, size_unit: a.size?.unit ?? null,
        size_confidence: a.size?.confidence ?? null, model: JEV_MODEL, threshold: THRESHOLD,
      });
      if (error) throw new Error(error.message);
      for (const r of String(read).split(" ")) outcomes[r] = (outcomes[r] ?? 0) + 1;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      console.error(`job ${job.store_item_id}: ${message}`);
      await db.rpc("fail_categorize_job", { lid: job.ledger_id, item: job.store_item_id, error: message.slice(0, 500) });
      outcomes.failed = (outcomes.failed ?? 0) + 1;
    }
  }
  return json({ done: jobs.length, ...outcomes });
});

type Item = { description: string; code_kind: string; code: string; unit?: string | null; qty_e4?: number | null };
type Answers = {
  category?: { chosen: Option; confidence: number };
  soldBy?: { value: SoldBy; source: "parser" | "jev"; confidence: number };
  size?: { e6: number | null; unit: string | null; confidence: number };
};

/** One request for every question this item needs; `choices` null when its product needs no category. */
async function answer(item: Item, choices: Option[] | null, expand: (t: string) => string): Promise<Answers> {
  const out: Answers = {};
  const questions: Record<string, unknown> = {};
  if (choices) {
    questions.category = { type: "choice", instructions: INSTRUCTIONS, criteria: Object.fromEntries(choices.map((o) => [o.key, o.text])) };
  }
  const settled = soldByFromUnit(item.unit ?? null, item.qty_e4 ?? null);
  if (settled) out.soldBy = { value: settled, source: "parser", confidence: 1 };
  else questions.sold_by = { type: "choice", instructions: SOLD_BY_INSTRUCTIONS, criteria: SOLD_BY_CRITERIA };
  // Sold by weight or volume, the line's quantity is already the measure; a size in the name doesn't price it.
  const candidates: Candidate[] = settled === "weight" || settled === "volume" ? [] : sizeCandidates(item.description);
  if (candidates.length) {
    questions.size = {
      type: "choice", instructions: SIZE_INSTRUCTIONS,
      criteria: { ...Object.fromEntries(candidates.map((c) => [c.key, c.text])), [NO_SIZE]: NO_SIZE_TEXT },
    };
  }
  if (!Object.keys(questions).length) return out;

  const state: Record<string, string> = { item: expand(item.description), "receipt text": item.description };
  if (item.unit) state["unit on the receipt"] = item.unit;
  if (item.qty_e4 != null) state["quantity on the receipt"] = String(item.qty_e4 / 10_000);
  if (item.code_kind === "gtin") Object.assign(state, await openFoodFacts(item.code));
  const a = await jev(state, questions);

  if (a.category) {
    const chosen = choices!.find((o) => o.key === a.category.choice);
    if (!chosen) throw new Error(`Jev chose an unknown category: ${a.category.choice}`);
    out.category = { chosen, confidence: a.category.confidence };
  }
  if (a.sold_by) {
    if (!(a.sold_by.choice in SOLD_BY_CRITERIA)) throw new Error(`Jev chose an unknown way of selling: ${a.sold_by.choice}`);
    out.soldBy = { value: a.sold_by.choice as SoldBy, source: "jev", confidence: a.sold_by.confidence };
  }
  if (a.size) {
    const c = candidates.find((x) => x.key === a.size.choice);
    if (!c && a.size.choice !== NO_SIZE) throw new Error(`Jev chose an unknown size: ${a.size.choice}`);
    out.size = { e6: c?.e6 ?? null, unit: c?.unit ?? null, confidence: a.size.confidence };
  }
  return out;
}

async function check(ledgerId: string, items: Item[], extra: { key: string; en: string }[]) {
  const [expand, choices] = await Promise.all([lexicon(extra), leafCategories(ledgerId)]);
  const out = [];
  for (const item of items) {
    try {
      const a = await answer(item, choices, expand);
      out.push({
        category: a.category!.chosen.key.replaceAll("__", "."), confidence: a.category!.confidence,
        sold_by: a.soldBy ?? null, size: a.size ?? null,
      });
    } catch (e) {
      out.push({ error: e instanceof Error ? e.message : String(e) });
    }
  }
  return { model: JEV_MODEL, threshold: THRESHOLD, answers: out };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** The caderneta's categories that have no children: what an item can be assigned to, described as in D67's eval. */
async function leafCategories(ledgerId: string): Promise<Option[]> {
  const { data: cats, error } = await db.from("category").select("id, seed_path, label, description, parent_id")
    .eq("ledger_id", ledgerId).eq("deleted", false);
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
async function lexicon(extra: { key: string; en: string }[] = []): Promise<(text: string) => string> {
  const { data: rows, error } = await db.from("lexicon").select("key, en");
  if (error) throw new Error(error.message);
  const overridden = new Set(extra.map((e) => e.key.toUpperCase()));
  const data = [...rows!.filter((r) => !overridden.has(r.key)), ...extra.map((e) => ({ key: e.key.toUpperCase(), en: e.en }))];
  const phrases = data.filter((r) => r.key.includes(" ")).map((r) => ({ words: r.key.split(" "), en: r.en }))
    .sort((a, b) => b.words.join(" ").length - a.words.join(" ").length);
  const tokens = new Map(data.filter((r) => !r.key.includes(" ")).map((r) => [r.key, r.en]));
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

type JevAnswer = { choice: string; confidence: number };

async function jev(state: Record<string, string>, questions: Record<string, unknown>): Promise<Record<string, JevAnswer>> {
  const r = await fetch(`${JEV_URL}/v1/systemone`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${Deno.env.get("JEV_API_KEY")}` },
    body: JSON.stringify({ model: JEV_MODEL, state, questions }),
  });
  if (!r.ok) throw new Error(`Jev ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const answers = (await r.json()).answers ?? {};
  return Object.fromEntries(Object.keys(questions).map((k) => {
    const a = answers[k];
    if (!a) throw new Error(`Jev gave no answer to ${k}`);
    return [k, { choice: a.choice, confidence: Number(a.confidence ?? 0) }];
  }));
}
