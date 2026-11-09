// Receives testers' receipt photos and pages during the closed test (D161), signed in or not.
//
// POST ?install=<uuid>&item=<uuid>&file=<name>, the file as the body: stored in the private `contributions` bucket at
// <install>/<item>/<name> (sending one again replaces it). Names: p00.webp… (photos, blanked on the phone, D98),
// page.html (the SEFAZ page, buyer removed, D104) and meta.json, sent last.
// POST ?install=<uuid>&withdraw=1: everything from that install is deleted (the person turned it off), and that id is
// refused from then on (D172): a send still in flight can't land after the withdrawal.
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
    // Recorded first: a send that stores its file after this point sees it and takes the file back (below).
    const { error: mark } = await db.from("contribution_withdrawal").upsert({ install_id: request.install });
    if (mark) return answer(500, mark.message);
    // Storage itself is listed, not the rows: an object whose row is missing still goes (T-9).
    let removed = 0;
    for (;;) {
      const { data: names, error } = await db.rpc("contribution_objects", { install: request.install, n: 1000 });
      if (error) return answer(500, error.message);
      if (names.length === 0) break;
      for (let i = 0; i < names.length; i += 500) {
        const { error: e } = await db.storage.from(BUCKET).remove(names.slice(i, i + 500));
        if (e) return answer(500, e.message);
      }
      removed += names.length;
    }
    const { error: e } = await db.from("contribution_file").delete().eq("install_id", request.install);
    if (e) return answer(500, e.message);
    return answer(200, `removed ${removed}`);
  }

  const body = new Uint8Array(await req.arrayBuffer());
  const refused = parse(params, req.headers.get("content-type"), body.length);
  if (typeof refused === "string") return answer(400, refused);

  if (await withdrawn(request.install)) return answer(400, "withdrawn");

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
  // A withdrawal recorded while this file was on its way: the file goes again, and the app drops it (T-10).
  if (await withdrawn(request.install)) {
    await db.storage.from(BUCKET).remove([path]);
    await db.from("contribution_file").delete().match({ install_id: request.install, item_id: request.item, name: request.name });
    return answer(400, "withdrawn");
  }
  return answer(200, "ok");
});

async function withdrawn(install: string): Promise<boolean> {
  const { data, error } = await db.from("contribution_withdrawal").select("install_id").eq("install_id", install).maybeSingle();
  if (error) throw error;
  return data != null;
}
