// Receives testers' receipt photos and pages during the closed test (D161), signed in or not.
//
// POST ?install=<uuid>&item=<uuid>&file=<name>, the file as the body: stored in the private `contributions` bucket at
// <install>/<item>/<name> (sending one again replaces it). Names: p00.webp… (photos, blanked on the phone, D98),
// page.html (the SEFAZ page, buyer removed, D104) and meta.json, sent last.
// POST ?install=<uuid>&withdraw=1: everything from that install is deleted (the person turned it off).
//
// Answers: 200 done; 400 refused for good (the app drops the file); 429 over a limit (the app tries again later);
// 5xx the app retries. The install id is random, kept by the app, and never tied to an account.

import { createClient } from "npm:@supabase/supabase-js@2";
import { overLimit, parse } from "./rules.ts";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const BUCKET = "contributions";

const answer = (status: number, message: string) =>
  new Response(JSON.stringify({ message }), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return answer(405, "POST only");
  const params = new URL(req.url).searchParams;
  const length = req.headers.get("content-length");
  const request = parse(params, req.headers.get("content-type"), length == null ? null : Number(length));
  if (typeof request === "string") return answer(400, request);

  if (request.kind === "withdraw") {
    const { data: rows, error } = await db.from("contribution_file").select("item_id,name").eq("install_id", request.install);
    if (error) return answer(500, error.message);
    const paths = rows.map((r) => `${request.install}/${r.item_id}/${r.name}`);
    for (let i = 0; i < paths.length; i += 500) {
      const { error: e } = await db.storage.from(BUCKET).remove(paths.slice(i, i + 500));
      if (e) return answer(500, e.message);
    }
    const { error: e } = await db.from("contribution_file").delete().eq("install_id", request.install);
    if (e) return answer(500, e.message);
    return answer(200, `removed ${paths.length}`);
  }

  const body = new Uint8Array(await req.arrayBuffer());
  const refused = parse(params, req.headers.get("content-type"), body.length);
  if (typeof refused === "string") return answer(400, refused);

  const dayAgo = new Date(Date.now() - 86_400_000).toISOString();
  const hourAgo = new Date(Date.now() - 3_600_000).toISOString();
  const today = await db.from("contribution_file").select("bytes").eq("install_id", request.install).gt("received_at", dayAgo);
  const hour = await db.from("contribution_file").select("*", { count: "exact", head: true }).gt("received_at", hourAgo);
  if (today.error || hour.error) return answer(500, (today.error ?? hour.error)!.message);
  const over = overLimit({ files: today.data.length, bytes: today.data.reduce((s, r) => s + r.bytes, 0) }, hour.count ?? 0, body.length);
  if (over) return answer(429, over);

  const path = `${request.install}/${request.item}/${request.name}`;
  const { error: up } = await db.storage.from(BUCKET).upload(path, body, { contentType: request.type, upsert: true });
  if (up) return answer(500, up.message);
  const { error: row } = await db.from("contribution_file").upsert({
    install_id: request.install, item_id: request.item, name: request.name, bytes: body.length,
    received_at: new Date().toISOString(),
  });
  if (row) return answer(500, row.message);
  return answer(200, "ok");
});
