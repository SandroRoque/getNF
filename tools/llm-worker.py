#!/usr/bin/env python3
"""The LLM worker (stage 8, D80, docs/stage-8-plan.md): runs on the user's PC with LM Studio, suggests, never decides.

  python3 tools/llm-worker.py                 # every task, every user, on the online project
  python3 tools/llm-worker.py --local         # against the local Supabase (npx supabase start)
  python3 tools/llm-worker.py --only audit --dry-run --limit 5

Tasks, per user:
  audit   checks automatic (model-set, unconfirmed) categories: never-checked first, then by spending, plus a random
          sample that measures the real error rate (D69). A disagreement becomes a suggestion: the product goes to
          "Para escolher" on the phone.
  learn   for each new correction, proposes the rule it implies (kept for the audit's prompt) and abbreviations; an
          abbreviation joins the shared lexicon only if Jev's check on the user's confirmed items doesn't get worse
          and the corrected item comes out right (the regression gate, D69).
  links   proposes store items in other stores that are the same product (D44, D66), for the user to accept.
  sizes   proposes pack sizes the parser couldn't read.
  describe drafts a one-line description for categories the user created without one (D75).
  report  writes the health report (D65, D69) the phone shows in Ajustes.

Nothing leaves the PC except to the user's own Supabase project, and, for the regression gate, the item
descriptions Jev already sees (D74). Reads SUPABASE_URL, SUPABASE_SECRET_KEY and LMSTUDIO_API_KEY from
server/.env.local and never prints them. The model is loaded for the run and unloaded after, unless it was loaded
already.
"""
import argparse, datetime, json, os, random, re, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = "swift-1.5-qwen3.8-27b-gsq-rco@iq2_s"
IDENT = "getnf-llm"
LMS = os.path.expanduser("~/.lmstudio/bin/lms")
LLM_NAME = "llm:swift-1.5"


def env():
  for path in (os.path.join(ROOT, "server", ".env.local"), os.path.join(ROOT, "..", "..", "..", "server", ".env.local")):
    if os.path.exists(path):
      return dict(l.strip().split("=", 1) for l in open(path) if "=" in l and not l.lstrip().startswith("#"))
  sys.exit("no server/.env.local")


def log(*a):
  print(datetime.datetime.now().strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


class Supabase:
  """The Data API as the service role (sees every user; the code keeps each user's rows to that user)."""

  def __init__(self, url, key, dry=False):
    self.url, self.key, self.dry = url.rstrip("/"), key, dry

  def call(self, method, path, body=None, params=None, prefer=None):
    q = ("?" + urllib.parse.urlencode(params, safe=",.()*:")) if params else ""
    headers = {"apikey": self.key, "content-type": "application/json"}
    if not self.key.startswith("sb_"):
      headers["Authorization"] = "Bearer " + self.key
    if prefer:
      headers["Prefer"] = prefer
    req = urllib.request.Request(self.url + path + q, json.dumps(body).encode() if body is not None else None, headers, method=method)
    try:
      with urllib.request.urlopen(req, timeout=120) as r:
        text = r.read().decode()
        return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
      raise RuntimeError(f"{method} {path}: {e.code} {e.read().decode()[:300]}") from None

  def select(self, table, **params):
    rows, offset = [], 0
    while True:
      page = self.call("GET", f"/rest/v1/{table}", params={"select": "*", **params, "limit": 1000, "offset": offset})
      rows += page
      if len(page) < 1000:
        return rows
      offset += 1000

  def write(self, method, table, body, **params):
    if self.dry:
      log("  (dry run)", method, table, json.dumps(body, ensure_ascii=False)[:160])
      return None
    # An insert with on_conflict is an upsert: PostgREST needs to be told to merge.
    prefer = "resolution=merge-duplicates,return=minimal" if "on_conflict" in params else "return=minimal"
    return self.call(method, f"/rest/v1/{table}", body, params, prefer=prefer)

  def users(self):
    return self.call("GET", "/auth/v1/admin/users", params={"per_page": 1000})["users"]

  def check(self, secret, user_id, items, lexicon=()):
    """The categorize function's check mode (D74): Jev's answers, nothing written."""
    req = urllib.request.Request(self.url + "/functions/v1/categorize", json.dumps(
      {"check": {"user_id": user_id, "items": items, "lexicon": list(lexicon)}}).encode(),
      {"content-type": "application/json", "x-categorize-secret": secret}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as r:
      return json.load(r)["answers"]


class Llm:
  def __init__(self, key, url="http://127.0.0.1:1234"):
    self.key, self.url, self.loaded_here = key, url, False

  def __enter__(self):
    loaded = subprocess.run([LMS, "ps"], capture_output=True, text=True).stdout
    if IDENT not in loaded:
      log("loading", MODEL)
      subprocess.run([LMS, "load", MODEL, "--gpu", "max", "-c", "8192", "--parallel", "1", "--no-speculative-draft-mtp",
                      "--identifier", IDENT, "-y"], check=True, capture_output=True)
      self.loaded_here = True
    return self

  def __exit__(self, *exc):
    if self.loaded_here:
      subprocess.run([LMS, "unload", IDENT], capture_output=True)
      log("unloaded the model")

  def ask(self, system, user, max_tokens=2048):
    """One JSON answer. The model may think aloud first; the last JSON object in the reply is the answer."""
    body = {"model": IDENT, "temperature": 0, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    req = urllib.request.Request(self.url + "/v1/chat/completions", json.dumps(body).encode(),
                                 {"content-type": "application/json", "Authorization": f"Bearer {self.key}"})
    with urllib.request.urlopen(req, timeout=900) as r:
      text = json.load(r)["choices"][0]["message"].get("content") or ""
    for m in reversed(list(re.finditer(r"\{(?:[^{}]|\{[^{}]*\})*\}", text, re.S))):
      try:
        return json.loads(m.group(0))
      except json.JSONDecodeError:
        continue
    return {}


class User:
  """One user's catalog, read once per run."""

  def __init__(self, db, uid):
    self.db, self.uid = db, uid
    f = {"user_id": f"eq.{uid}"}
    self.categories = [c for c in db.select("category", **f) if not c["deleted"]]
    self.products = {p["id"]: p for p in db.select("product", **f) if not p["deleted"]}
    self.items = [s for s in db.select("store_item", **f) if not s["deleted"]]
    self.lines = db.select("receipt_line", **f)
    self.issuers = {i["cnpj"]: i["name"] for i in db.select("issuer", **f)}
    self.rules = [r["text"] for r in db.select("category_rule", **f)]
    seed = {n["path"]: n.get("description_en") for n in db.select("taxonomy_node")}
    byid = {c["id"]: c for c in self.categories}
    parents = {c["parent_id"] for c in self.categories}
    def path(c):
      chain = []
      while c:
        chain.append(c["label"])
        c = byid.get(c["parent_id"])
      return " > ".join(reversed(chain))
    self.path = {c["id"]: path(c) for c in self.categories}
    self.leaves = {c["id"]: c for c in self.categories if c["id"] not in parents}
    self.describe = {c["id"]: c.get("description") or seed.get(c.get("seed_path")) or "" for c in self.categories}
    item_product = {s["id"]: s["product_id"] for s in self.items}
    self.spent, self.units = {}, {}
    for l in self.lines:
      pid = l["product_override_id"] or item_product.get(l["store_item_id"])
      self.spent[pid] = self.spent.get(pid, 0) + l["gross_cents"] - l["discount_cents"]
      self.units.setdefault(l["store_item_id"], set()).add((l["unit"] or "").upper())
    self.items_of = {}
    for s in self.items:
      self.items_of.setdefault(s["product_id"], []).append(s)
    self.lookups = {}
    gtins = [s["code"] for s in self.items if s["code_kind"] == "gtin"]
    for chunk in range(0, len(gtins), 100):
      for b in db.select("barcode_lookup", gtin=f"in.({','.join(gtins[chunk:chunk + 100])})"):
        if b["product"]:
          self.lookups[b["gtin"]] = b["product"]

  def words(self, pid):
    """What the receipts and Open Food Facts call a product."""
    out = []
    for s in self.items_of.get(pid, []):
      store = self.issuers.get(s["store_cnpj"], "") if s["code_kind"] == "store" else ""
      o = self.lookups.get(s["code"])
      name = f" (Open Food Facts: {o.get('product_name_pt') or o.get('product_name')})" if o else ""
      out.append(f"\"{s['description']}\"{name}" + (f" na loja {store}" if store else ""))
    return "; ".join(out)

  def category_list(self):
    return "\n".join(f"- {self.path[cid]}: {self.describe[cid]}" for cid in self.leaves)

  def by_path(self, text):
    text = (text or "").strip().lower()
    for cid in self.leaves:
      if self.path[cid].lower() == text or self.path[cid].lower().endswith(text) and text:
        return cid
    return None


AUDIT = """Você confere a categoria de gasto de produtos de notas fiscais brasileiras (NFC-e). As descrições vêm
abreviadas como na nota. Escolha sempre uma das categorias da lista (o caminho completo, exatamente como escrito).

Categorias:
{categories}
{rules}
Responda só com JSON: {{"categoria": "<caminho>", "concorda": true|false}}"""


def audit(db, llm, u, limit, sample, now):
  auto = [p for p in u.products.values() if p.get("category_review") == "automatic" and p.get("category_source") != "user"
          and p.get("category_id") in u.path and u.spent.get(p["id"])]
  # Never checked first, then the biggest spending: a wrong category on an expensive item distorts reports most.
  order = sorted(auto, key=lambda p: (p.get("llm_checked_at") is not None, -u.spent.get(p["id"], 0)))
  chosen = order[:limit]
  rest = [p for p in auto if p not in chosen]
  sampled = random.sample(rest, min(sample, len(rest)))
  rules = ("\nRegras desta casa (aprendidas com as correções do usuário):\n" + "\n".join(f"- {r}" for r in u.rules) + "\n") if u.rules else ""
  system = AUDIT.format(categories=u.category_list(), rules=rules)
  agree = disagree = 0
  for p in chosen + sampled:
    current = u.path[p["category_id"]]
    a = llm.ask(system, f"Produto: {u.words(p['id'])}\nCategoria atual: {current}\nEstá certa?")
    llm_cat = u.by_path(a.get("categoria"))
    ok = llm_cat is None or llm_cat == p["category_id"]
    db.write("POST", "audit_log", {"user_id": u.uid, "product_id": p["id"], "category_id": p["category_id"],
                                   "llm_category_id": llm_cat, "agree": ok, "sample": p in sampled, "model": LLM_NAME})
    change = {"llm_checked_at": now}
    if not ok and not p.get("suggestion_category_id"):
      change.update(suggestion_category_id=llm_cat, suggestion_model=LLM_NAME, suggestion_confidence=None, suggestion_at=now)
    db.write("PATCH", "product", change, id=f"eq.{p['id']}", user_id=f"eq.{u.uid}")
    agree += ok; disagree += not ok
    log(f"  audit {'ok  ' if ok else 'DIFF'} {u.words(p['id'])[:50]} | {current} -> {u.path.get(llm_cat, '-')}")
  return {"audited": agree + disagree, "disagreed": disagree}


LEARN = """O usuário corrigiu a categoria de um produto de nota fiscal brasileira. Descubra o que a correção ensina:
- uma regra curta da casa sobre um caso de fronteira, válida para outros produtos ("frios fatiados, mesmo de frango ou
  porco, são frios"; "polpa de fruta congelada é suco"). Só se a correção mostrar uma preferência que o nome da
  categoria não deixa óbvia; se a categoria certa é a evidente para esse produto, responda null;
- abreviações da descrição que um leitor não entenderia, com o significado (só se tiver certeza).

Responda só com JSON: {"regra": "<frase>" ou null, "abreviacoes": [{"abreviacao": "IOG", "pt": "iogurte", "en": "yogurt"}]}"""


def learn(db, llm, u, since, secret, now):
  corrected = [p for p in u.products.values() if p.get("category_review") == "corrected" and p.get("category_at")
               and (since is None or p["category_at"] > since) and p.get("category_id") in u.path]
  if not corrected:
    return {"corrections": 0, "rules": 0, "abbreviations_added": 0, "abbreviations_refused": 0}
  # The regression set: what the user confirmed or corrected, plus the categories imported from before the app
  # (checked by the user in the evaluation, D56).
  gate = [p for p in u.products.values() if p.get("category_id") in u.leaves
          and (p.get("category_source") == "user" or p.get("category_source") == "import") and u.items_of.get(p["id"])]
  items = [{"description": u.items_of[p["id"]][0]["description"], "code_kind": u.items_of[p["id"]][0]["code_kind"],
            "code": u.items_of[p["id"]][0]["code"]} for p in gate]
  # The check answers with the option keys the function builds: the seed path, or `user.<id prefix>` (D74).
  option = {c["id"]: c.get("seed_path") or f"user.{c['id'][:8]}" for c in u.categories}
  def score(lexicon=()):
    answers = db.check(secret, u.uid, items, lexicon)
    return [a.get("category") == option.get(gate[i]["category_id"]) for i, a in enumerate(answers)]
  baseline = score() if secret and items else None
  rules = added = refused = 0
  for p in corrected:
    words = u.words(p["id"])
    guess = u.path.get(p.get("suggestion_category_id")) or "(não registrado)"
    a = llm.ask(LEARN, f"Produto: {words}\nO modelo tinha dito: {guess}\nO usuário escolheu: {u.path[p['category_id']]}")
    if a.get("regra"):
      db.write("POST", "category_rule", {"user_id": u.uid, "text": str(a["regra"])[:300], "product_id": p["id"], "model": LLM_NAME})
      rules += 1
      log("  rule:", a["regra"])
    for ab in a.get("abreviacoes") or []:
      key = str(ab.get("abreviacao", "")).upper().strip()
      if not key or key not in " ".join(s["description"].upper() for s in u.items_of.get(p["id"], [])) or not ab.get("en"):
        continue
      entry = {"key": key, "en": str(ab["en"]), "pt": str(ab.get("pt") or ab["en"])}
      if baseline is None:
        refused += 1; continue
      trial = score([{"key": key, "en": entry["en"]}])
      fixed = next((trial[i] for i, g in enumerate(gate) if g["id"] == p["id"]), True)
      if sum(trial) >= sum(baseline) and fixed:
        db.write("POST", "lexicon", {**entry, "source": "llm"}, on_conflict="key")
        baseline = trial; added += 1
        log(f"  lexicon + {key} = {entry['en']} ({sum(trial)}/{len(trial)} right)")
      else:
        refused += 1
        log(f"  lexicon refused {key} = {entry['en']} ({sum(trial)} vs {sum(baseline)} right)")
  return {"corrections": len(corrected), "rules": rules, "abbreviations_added": added, "abbreviations_refused": refused}


SAME = """Duas descrições de itens de notas fiscais brasileiras, de lojas diferentes. São o MESMO produto (mesmo item,
mesma variedade e mesma forma de venda; marcas diferentes NÃO são o mesmo produto; banana caturra e banana prata
NÃO são o mesmo produto)?

Responda só com JSON: {"mesmo": true|false, "confianca": <0 a 1>, "motivo": "<curto>"}"""


def tokens(text):
  return {w for w in re.findall(r"[A-Z]{3,}", text.upper()) if w not in {"KG", "UN", "GRANEL", "PCT", "CX"}}


def links(db, llm, u, limit):
  known = {(s["store_item_id"], s["product_id"]) for s in db.select("link_suggestion", user_id=f"eq.{u.uid}")}
  weight = lambda units: "peso" if units & {"KG", "L"} else "unidade"
  candidates = []
  for a in u.items:
    for b in u.items:
      if a["product_id"] >= b["product_id"] or a["store_cnpj"] == b["store_cnpj"]:
        continue
      if a["code_kind"] == "gtin" and b["code_kind"] == "gtin":
        continue  # two barcodes are two products
      if weight(u.units.get(a["id"], set())) != weight(u.units.get(b["id"], set())):
        continue
      ta, tb = tokens(a["description"]), tokens(b["description"])
      overlap = len(ta & tb) / max(1, len(ta | tb))
      if overlap >= .34:
        # Link the less-bought item into the more-bought product.
        small, big = sorted([a, b], key=lambda s: u.spent.get(s["product_id"], 0))
        if (small["id"], big["product_id"]) not in known:
          candidates.append((overlap, small, big))
  proposed = 0
  for overlap, small, big in sorted(candidates, key=lambda c: -c[0])[:limit]:
    a = llm.ask(SAME, f"A: \"{small['description']}\" ({u.issuers.get(small['store_cnpj'], 'outra loja')})\n"
                      f"B: \"{big['description']}\" ({u.issuers.get(big['store_cnpj'], 'outra loja')})")
    if a.get("mesmo") and float(a.get("confianca") or 0) >= .6:
      db.write("POST", "link_suggestion", {"user_id": u.uid, "store_item_id": small["id"], "product_id": big["product_id"],
                                           "confidence": float(a["confianca"]), "reason": str(a.get("motivo") or "")[:200],
                                           "model": LLM_NAME}, on_conflict="user_id,store_item_id,product_id")
      proposed += 1
      log(f"  same: {small['description']} = {big['description']}")
  return {"link_candidates": len(candidates), "links_proposed": proposed}


SIZE = """Descrição de um item de nota fiscal brasileira. Quanto vem na embalagem, em kg ou litros? Gramas viram kg
(500G = 0,5 kg), ml viram litros. "1K" é 1 kg. Se a descrição não diz o tamanho, ou se o número não é o conteúdo (a
capacidade de uma mala, o tamanho de uma roupa), responda null.

Responda só com JSON: {"quantidade": <número> ou null, "unidade": "kg"|"l"|null}"""


def sizes(db, llm, u, limit, now):
  todo = []
  for p in u.products.values():
    if p.get("size_source") or p.get("size_suggestion_model"):
      continue
    items = u.items_of.get(p["id"], [])
    if not items or any(s.get("parsed_size_e6") for s in items) or any(u.units.get(s["id"], set()) & {"KG", "L"} for s in items):
      continue
    if re.search(r"\d", " ".join(s["description"] for s in items)):
      todo.append(p)
  proposed = 0
  for p in sorted(todo, key=lambda p: -u.spent.get(p["id"], 0))[:limit]:
    a = llm.ask(SIZE, u.items_of[p["id"]][0]["description"])
    amount, unit = a.get("quantidade"), a.get("unidade")
    ok = isinstance(amount, (int, float)) and amount > 0 and unit in ("kg", "l")
    db.write("PATCH", "product", {"size_suggestion_e6": round(amount * 1_000_000) if ok else None,
                                  "size_suggestion_unit": unit if ok else None, "size_suggestion_model": LLM_NAME,
                                  "size_suggestion_at": now}, id=f"eq.{p['id']}", user_id=f"eq.{u.uid}")
    proposed += ok
    log(f"  size: {u.items_of[p['id']][0]['description']} -> {amount if ok else '-'} {unit if ok else ''}")
  return {"sizes_checked": min(limit, len(todo)), "sizes_proposed": proposed}


DESCRIBE = """Escreva em uma linha, em português, para que serve uma categoria de gastos domésticos, citando exemplos
típicos de itens. É o que um modelo de classificação vai ler para decidir se um item cabe nela.

Responda só com JSON: {"descricao": "<uma linha>"}"""


def describe(db, llm, u):
  todo = [c for c in u.categories if not c.get("seed_path") and not c.get("description") and not c.get("description_suggestion")]
  for c in todo:
    a = llm.ask(DESCRIBE, f"Categoria: {u.path[c['id']]}")
    if a.get("descricao"):
      db.write("PATCH", "category", {"description_suggestion": str(a["descricao"])[:200]}, id=f"eq.{c['id']}", user_id=f"eq.{u.uid}")
      log(f"  describe {u.path[c['id']]}: {a['descricao']}")
  return {"descriptions_drafted": len(todo)}


def report(db, u, results, now):
  audits = db.select("audit_log", user_id=f"eq.{u.uid}", sample="eq.true", order="at.desc")[:50]
  auto = [p for p in u.products.values() if p.get("category_review") == "automatic" and u.spent.get(p["id"])]
  unchecked = [p for p in auto if not p.get("llm_checked_at")]
  checked = sorted(p["llm_checked_at"] for p in auto if p.get("llm_checked_at"))
  backups = sorted(f for f in os.listdir(os.path.expanduser("~/getnf-backups/supabase")) if f.endswith(".dump")) \
    if os.path.isdir(os.path.expanduser("~/getnf-backups/supabase")) else []
  backup_age = (time.time() - os.path.getmtime(os.path.expanduser("~/getnf-backups/supabase/" + backups[-1]))) / 3600 if backups else None
  r = {
    **results,
    "automatic_unconfirmed": len(auto),
    "never_checked": len(unchecked),
    "oldest_check": checked[0] if checked else None,
    "to_pick": sum(1 for p in u.products.values() if not p.get("category_id") and u.spent.get(p["id"])),
    "sample_size": len(audits),
    "sample_error_rate": round(sum(not a["agree"] for a in audits) / len(audits), 3) if audits else None,
    "backup_age_hours": round(backup_age, 1) if backup_age is not None else None,
    "model": LLM_NAME,
  }
  db.write("POST", "worker_report", {"user_id": u.uid, "report": r})
  return r


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--local", action="store_true")
  p.add_argument("--only", choices=["audit", "learn", "links", "sizes", "describe", "report"], action="append")
  p.add_argument("--limit", type=int, default=30, help="per task and user (audit, links, sizes)")
  p.add_argument("--sample", type=int, default=8, help="random already-checked products re-audited for the error rate")
  p.add_argument("--dry-run", action="store_true")
  args = p.parse_args()
  e = env()
  if args.local:
    out = subprocess.run(["npx", "supabase", "status", "-o", "env"], cwd=ROOT, capture_output=True, text=True).stdout
    local = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
    db = Supabase(local["API_URL"].strip('"'), local["SECRET_KEY"].strip('"'), args.dry_run)
    secret = os.environ.get("GETNF_CATEGORIZE_SECRET")  # the local function's, if it's being served
  else:
    db = Supabase(e["SUPABASE_URL"], e["SUPABASE_SECRET_KEY"], args.dry_run)
    secret = e.get("CATEGORIZE_SECRET") or os.environ.get("GETNF_CATEGORIZE_SECRET")
  tasks = args.only or ["audit", "learn", "links", "sizes", "describe", "report"]
  state_path = os.path.expanduser("~/.local/state/getnf-llm-worker.json")
  state = json.load(open(state_path)) if os.path.exists(state_path) else {}
  now = datetime.datetime.now(datetime.timezone.utc).isoformat()
  needs_llm = any(t != "report" for t in tasks)
  with (Llm(e["LMSTUDIO_API_KEY"]) if needs_llm else _Nothing()) as llm:
    for user in db.users():
      uid = user["id"]
      u = User(db, uid)
      log(f"user {user.get('email', uid)[:3]}…: {len(u.products)} products, {len(u.categories)} categories")
      results = {}
      if "audit" in tasks: results.update(audit(db, llm, u, args.limit, args.sample, now))
      if "learn" in tasks: results.update(learn(db, llm, u, state.get(uid, {}).get("learned_until"), secret, now))
      if "links" in tasks: results.update(links(db, llm, u, args.limit))
      if "sizes" in tasks: results.update(sizes(db, llm, u, args.limit, now))
      if "describe" in tasks: results.update(describe(db, llm, u))
      if "report" in tasks: log("  report", json.dumps(report(db, User(db, uid), results, now), ensure_ascii=False))
      if "learn" in tasks and not args.dry_run:
        state.setdefault(uid, {})["learned_until"] = now
  if not args.dry_run:
    os.makedirs(os.path.dirname(state_path), exist_ok=True)
    json.dump(state, open(state_path, "w"))


class _Nothing:
  def __enter__(self): return None
  def __exit__(self, *a): pass


if __name__ == "__main__":
  main()
