// Deleting an account from the web (D105), for Play's "delete account URL". Does what the app's
// LedgerService.deleteMe does: the files of cadernetas where this person is the only member and their own uploads
// elsewhere (Storage can't be cleared from SQL), then delete_me, which applies the leaving rules and removes the login.
// Plain fetch against the project's REST APIs, with the publishable key from config.js; the session lives only in
// memory and the password is never kept.
"use strict";

const BUCKET = "receipts";
const cfg = window.GETNF;
const $ = (id) => document.getElementById(id);
let session = null;

class ApiError extends Error {
  constructor(status, data) {
    super((data && (data.msg || data.message || data.error_description || data.error)) || `HTTP ${status}`);
    this.status = status;
    this.code = data && (data.error_code || data.code);
  }
}

async function call(path, { method = "GET", body, auth = true } = {}) {
  const headers = { apikey: cfg.key, "content-type": "application/json" };
  if (auth && session) headers.authorization = `Bearer ${session.token}`;
  const r = await fetch(cfg.url + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (!r.ok) throw new ApiError(r.status, data);
  return data;
}

const rest = (table, query) => call(`/rest/v1/${table}?${new URLSearchParams(query)}`);
const rpc = (fn, args = {}) => call(`/rest/v1/rpc/${fn}`, { method: "POST", body: args });

/** Every object under `folder` (`<caderneta>/`), at any depth. */
async function listObjects(folder) {
  const found = [];
  for (let offset = 0; ; ) {
    const page = await call(`/storage/v1/object/list/${BUCKET}`, { method: "POST", body: { prefix: folder, limit: 1000, offset } });
    for (const o of page) {
      // Folders come back without an id.
      if (o.id == null) found.push(...(await listObjects(`${folder}${o.name}/`)));
      else found.push(`${folder}${o.name}`);
    }
    if (page.length < 1000) return found;
    offset += page.length;
  }
}

async function deleteObjects(paths) {
  for (let i = 0; i < paths.length; i += 500) {
    await call(`/storage/v1/object/${BUCKET}`, { method: "DELETE", body: { prefixes: paths.slice(i, i + 500) } });
  }
}

/** The cadernetas this person is in, with their name and whether anyone else is there. */
async function myLedgers() {
  const mine = await rest("ledger_member", { select: "ledger_id,role", user_id: `eq.${session.userId}` });
  const out = [];
  for (const m of mine) {
    const [ledger] = await rest("ledger", { select: "name", id: `eq.${m.ledger_id}` });
    const members = await rest("ledger_member", { select: "user_id", ledger_id: `eq.${m.ledger_id}` });
    out.push({ id: m.ledger_id, name: ledger ? ledger.name : "(sem nome)", alone: members.length === 1 });
  }
  return out;
}

function say(text, error = false) {
  $("estado").textContent = text;
  $("estado").classList.toggle("erro", error);
}

function explain(e) {
  if (e instanceof ApiError) {
    if (e.code === "invalid_credentials" || e.status === 400) return "E-mail ou senha incorretos.";
    if (e.code === "email_not_confirmed") return "Este e-mail ainda não foi confirmado. Confirme pelo link recebido e tente de novo.";
    if (e.status === 429) return "Muitas tentativas. Espere alguns minutos e tente de novo.";
    return `Não deu certo (${e.message}). Tente de novo ou escreva para o contato acima.`;
  }
  return "Sem conexão com o servidor. Verifique a internet e tente de novo.";
}

async function signIn(event) {
  event.preventDefault();
  const button = event.submitter || $("entrar").querySelector("button");
  button.disabled = true;
  say("Entrando…");
  try {
    const s = await call("/auth/v1/token?grant_type=password", {
      method: "POST", auth: false, body: { email: $("email").value.trim(), password: $("senha").value },
    });
    $("senha").value = "";
    session = { token: s.access_token, userId: s.user.id, email: s.user.email };
    await review();
  } catch (e) {
    say(explain(e), true);
  } finally {
    button.disabled = false;
  }
}

async function review() {
  say("Lendo suas cadernetas…");
  const [ledgers, blocked] = await Promise.all([myLedgers(), rpc("ledgers_to_hand_over")]);
  $("entrar").hidden = true;
  $("resumo").hidden = false;
  $("quem").textContent = `Conta: ${session.email}`;
  const list = $("cadernetas");
  list.replaceChildren(...ledgers.map((l) => {
    const li = document.createElement("li");
    li.textContent = l.alone ? `${l.name}: só você, será apagada com tudo o que tem.` : `${l.name}: tem outras pessoas, você sai dela.`;
    return li;
  }));
  if (ledgers.length === 0) list.replaceChildren(Object.assign(document.createElement("li"), { textContent: "Nenhuma caderneta." }));
  const mustHandOver = blocked.map((b) => b.name);
  $("bloqueio").hidden = mustHandOver.length === 0;
  $("confirmar").hidden = mustHandOver.length > 0;
  $("bloqueadas").textContent = mustHandOver.join(", ");
  say("");
}

async function deleteAccount() {
  $("apagar").disabled = true;
  $("cancelar").disabled = true;
  say("Apagando a conta…");
  try {
    for (const l of await myLedgers()) {
      const files = l.alone
        ? await listObjects(`${l.id}/`)
        : (await rest("receipt_photo", { select: "object_path,mask_path", ledger_id: `eq.${l.id}`, created_by: `eq.${session.userId}` }))
            .flatMap((p) => [p.object_path, p.mask_path].filter(Boolean));
      if (files.length) await deleteObjects(files);
    }
    await rpc("delete_me");
    session = null;
    $("resumo").hidden = true;
    say("Conta apagada. Obrigado por ter usado o app.");
  } catch (e) {
    say(explain(e), true);
    $("apagar").disabled = !$("entendo").checked;
    $("cancelar").disabled = false;
  }
}

function cancel() {
  const token = session && session.token;
  session = null;
  if (token) fetch(`${cfg.url}/auth/v1/logout`, { method: "POST", headers: { apikey: cfg.key, authorization: `Bearer ${token}` } }).catch(() => {});
  $("resumo").hidden = true;
  $("entrar").hidden = false;
  $("entendo").checked = false;
  $("apagar").disabled = true;
  say("Nada foi apagado.");
}

if (!cfg || !cfg.url || !cfg.key) {
  say("Esta página não está configurada. Peça a exclusão pelo e-mail acima.", true);
} else {
  $("entrar").hidden = false;
  $("entrar").addEventListener("submit", signIn);
  $("entendo").addEventListener("change", (e) => { $("apagar").disabled = !e.target.checked; });
  $("apagar").addEventListener("click", deleteAccount);
  $("cancelar").addEventListener("click", cancel);
}
