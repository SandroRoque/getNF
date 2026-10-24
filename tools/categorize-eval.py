#!/usr/bin/env python3
"""Measure how well local models categorize receipt items, against the categories the user picked by hand (D56).

  categorize-eval.py export            # hand-categorized products from the local PocketBase -> test-media/categorize/
  categorize-eval.py laya [--model M]  # Laya (laya-serve, Jev protocol), walking the category tree level by level
  categorize-eval.py llm  [--model M]  # an LLM through LM Studio's OpenAI-compatible server (key: $LMSTUDIO_API_KEY or server/.env.local)
  categorize-eval.py report            # compare the saved runs

The items are real purchases, so everything this writes stays in test-media/ (gitignored). Only the taxonomy's labels
are shown to the models: its comments name real items, which would leak the answers.
"""
import argparse, json, os, re, sys, time, urllib.request
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "test-media", "categorize")
ITEMS = os.path.join(OUT, "items.json")
TAXONOMY = os.path.join(ROOT, "taxonomy", "categories.txt")


def post(url, body, headers=None, timeout=300):
  req = urllib.request.Request(url, json.dumps(body).encode(), {"content-type": "application/json", **(headers or {})})
  with urllib.request.urlopen(req, timeout=timeout) as r:
    return json.load(r)


def get(url, headers=None):
  with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=60) as r:
    return json.load(r)


def taxonomy():
  """path -> label, and path -> children paths ("" is the root), in file order."""
  labels, children, stack = {}, defaultdict(list), []
  for raw in open(TAXONOMY, encoding="utf-8"):
    line = raw.split("#", 1)[0].rstrip()
    if not line.strip():
      continue
    depth = (len(line) - len(line.lstrip(" "))) // 2
    slug, label = line.strip().split(None, 1)
    stack[depth:] = [slug]
    path = ".".join(stack)
    labels[path] = label.strip()
    children[".".join(stack[:-1])].append(path)
  return labels, children


def lmstudio_key():
  """$LMSTUDIO_API_KEY, else the gitignored server/.env.local (never printed)."""
  return os.environ.get("LMSTUDIO_API_KEY") or env_local().get("LMSTUDIO_API_KEY")


def env_local():
  values = {}
  for line in open(os.path.join(ROOT, "server", ".env.local")) if os.path.exists(os.path.join(ROOT, "server", ".env.local")) \
      else open(os.path.join(ROOT, "..", "..", "..", "server", ".env.local")):
    if "=" in line and not line.lstrip().startswith("#"):
      k, v = line.strip().split("=", 1)
      values[k] = v
  return values


def export(args):
  creds = env_local()
  base = args.server.rstrip("/")
  token = post(f"{base}/api/collections/_superusers/auth-with-password",
               {"identity": creds["PB_ADMIN_EMAIL"], "password": creds["PB_ADMIN_PASSWORD"]})["token"]
  auth = {"Authorization": token}

  def all_records(collection):
    page, rows = 1, []
    while True:
      r = get(f"{base}/api/collections/{collection}/records?perPage=500&page={page}", auth)
      rows += r["items"]
      if page >= r["totalPages"]:
        return rows
      page += 1

  paths = {c["id"]: c["path"] for c in all_records("categories")}
  receipts = {r["id"]: r for r in all_records("receipts")}
  lines = all_records("receipt_items")
  by_receipt, by_product = defaultdict(list), defaultdict(list)
  for l in lines:
    by_receipt[l["receipt"]].append(l)
    by_product[l["product"]].append(l)
  items = []
  for p in all_records("products"):
    if not p.get("category"):
      continue
    seen = by_product.get(p["id"], [])
    first = seen[0] if seen else None
    receipt = receipts.get(first["receipt"]) if first else None
    items.append({
      "id": p["id"], "description": p["description"], "key_kind": p["key"].split(":", 1)[0],
      "gtin": p.get("gtin") or None, "category": paths[p["category"]],
      "unit": first and first["unit"], "qty": first and first["qty"], "unit_price": first and first["unit_price"],
      "store": receipt and receipt.get("store_name"),
      "receipt_items": [l["description"] for l in sorted(by_receipt[first["receipt"]], key=lambda l: l["line"])
                        if l["product"] != p["id"]] if first else [],
    })
  os.makedirs(OUT, exist_ok=True)
  json.dump(items, open(ITEMS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
  print(f"{len(items)} categorized products, {len(set(i['category'] for i in items))} categories -> {ITEMS}")


def load_items():
  return json.load(open(ITEMS, encoding="utf-8"))


INSTRUCTIONS = ("Item de uma nota fiscal de supermercado no Brasil, com a descrição abreviada como sai na nota. "
                "Em qual categoria de gasto ele se encaixa?")
HERE = "_here"


def laya(args):
  """Walk the tree: choose among a node's children (plus "none of these, just <node>" below the top), until a leaf."""
  labels, children = taxonomy()
  items = load_items()
  results, started = [], time.time()
  for n, item in enumerate(items):
    node, steps = "", []
    while children.get(node):
      options = {p.rsplit(".", 1)[-1]: labels[p] for p in children[node]}
      if node:
        options[HERE] = f"Outro tipo de {labels[node]}"
      body = {"state": {"descricao": item["description"]},
              "questions": {"c": {"type": "choice", "instructions": INSTRUCTIONS, "criteria": options}}}
      if args.model:
        body["model"] = args.model
      t = time.time()
      answer = post(args.url.rstrip("/") + "/v1/systemone", body)
      a = answer["answers"]["c"] if "answers" in answer else answer["c"]
      choice = a.get("choice") or a.get("answer") or a.get("value")
      steps.append({"options": len(options), "choice": choice, "confidence": a.get("confidence"),
                    "ms": round((time.time() - t) * 1000), "model": answer.get("model")})
      if choice == HERE:
        break
      node = f"{node}.{choice}" if node else choice
    results.append({**item, "predicted": node, "steps": steps})
    if n == 0:
      print("first answer:", json.dumps(answer, ensure_ascii=False)[:600], file=sys.stderr)
  save(f"laya-{args.model or 'auto'}", results, started)


LLM_PROMPT = """Você categoriza itens de notas fiscais brasileiras (NFC-e) em categorias de gasto. As descrições vêm \
abreviadas como saem na nota (ex.: "QJO" = queijo, "REQ" = requeijão, "LTE" = leite, "FGO" = frango, "D A" = \
desodorante aerossol, "PAP" = papel). Use também a loja, a unidade, o preço e os outros itens da mesma nota: eles dizem \
que tipo de loja é e o que faz sentido ali.

Categorias (caminho: rótulo). Escolha sempre a mais específica que se aplica:
{categories}
{notes}
Responda só com JSON: {{"categoria": "<caminho>", "confianca": <0 a 1>}}"""


def brl(value):
  return f"R$ {value:.2f}".replace(".", ",") if isinstance(value, (int, float)) else "?"


def describe(item, context):
  text = f"Item: {item['description']}"
  if not context:
    return text
  code = f"código de barras {item['gtin']}" if item.get("gtin") else "código interno da loja"
  text += f"\nCódigo: {code}\nUnidade: {item.get('unit') or '?'}, quantidade {item.get('qty')}, preço unitário {brl(item.get('unit_price'))}"
  text += f"\nLoja: {item.get('store') or '?'}"
  others = item.get("receipt_items") or []
  if others:
    text += "\nOutros itens da mesma nota: " + "; ".join(others[:40])
  return text


def embed(args, texts):
  key = lmstudio_key()
  r = post(args.url.rstrip("/") + "/v1/embeddings", {"model": args.embed_model, "input": texts},
           {"Authorization": f"Bearer {key}"} if key else None)
  return [d["embedding"] for d in r["data"]]


def cosine(a, b):
  dot = sum(x * y for x, y in zip(a, b))
  na = sum(x * x for x in a) ** .5; nb = sum(y * y for y in b) ** .5
  return dot / (na * nb or 1)


def llm(args):
  labels, children = taxonomy()
  items = load_items()
  categories = "\n".join(f"{p}: {labels[p]}" for p in labels)
  notes = ""
  if args.notes:
    notes = "\nRegras desta casa para casos de fronteira:\n" + open(args.notes, encoding="utf-8").read().strip() + "\n"
  system = LLM_PROMPT.format(categories=categories, notes=notes)
  vectors = embed(args, [f"search_document: {i['description']}" for i in items]) if args.examples else None
  key = lmstudio_key()
  results, started = [], time.time()
  for n, item in enumerate(items):
    user = describe(item, args.context)
    if args.examples:
      # The user's confirmed items, most similar first. Never the item itself: in the app it would be new.
      near = sorted((j for j in range(len(items)) if j != n), key=lambda j: -cosine(vectors[n], vectors[j]))[:args.examples]
      user = "Itens parecidos que o usuário já categorizou:\n" + "\n".join(
        f"- {items[j]['description']} -> {items[j]['category']}" for j in near) + "\n\n" + user
    t = time.time()
    r = post(args.url.rstrip("/") + "/v1/chat/completions", {
      "model": args.model, "temperature": 0, "max_tokens": args.max_tokens,
      "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }, {"Authorization": f"Bearer {key}"} if key else None, timeout=600)
    text = r["choices"][0]["message"].get("content") or ""
    m = re.search(r"\{[^{}]*\}", text, re.S)
    try:
      parsed = json.loads(m.group(0)) if m else {}
    except json.JSONDecodeError:
      parsed = {}
    predicted = str(parsed.get("categoria", "")).strip()
    results.append({**item, "predicted": predicted, "confidence": parsed.get("confianca"),
                    "ms": round((time.time() - t) * 1000), "raw": text[-300:], "valid": predicted in labels,
                    "tokens": r.get("usage")})
    print(f"{len(results):3}/{len(items)} {item['category']:35} -> {predicted}", file=sys.stderr)
  tag = "".join(["+ctx" if args.context else "", f"+ex{args.examples}" if args.examples else "", "+notes" if args.notes else ""])
  save(f"llm-{args.model.replace('/', '_')}{tag}", results, started)


def score(results):
  exact = sum(r["predicted"] == r["category"] for r in results)
  top = sum(r["predicted"].split(".")[0] == r["category"].split(".")[0] for r in results)
  second = sum(r["predicted"].split(".")[:2] == r["category"].split(".")[:2] for r in results)
  return exact, second, top


def save(name, results, started):
  os.makedirs(OUT, exist_ok=True)
  path = os.path.join(OUT, f"run-{name}.json")
  json.dump(results, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
  exact, second, top = score(results)
  n = len(results)
  print(f"{name}: exact {exact}/{n} ({exact / n:.0%}), level 2 {second / n:.0%}, top level {top / n:.0%}, "
        f"{time.time() - started:.0f}s -> {path}")


def report(args):
  for f in sorted(os.listdir(OUT)):
    if f.startswith("run-"):
      results = json.load(open(os.path.join(OUT, f), encoding="utf-8"))
      exact, second, top = score(results)
      n = len(results)
      print(f"{f[4:-5]:45} exact {exact / n:5.0%}  level 2 {second / n:5.0%}  top {top / n:5.0%}  (n={n})")


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  sub = p.add_subparsers(dest="cmd", required=True)
  e = sub.add_parser("export"); e.add_argument("--server", default="http://127.0.0.1:8090"); e.set_defaults(fn=export)
  l = sub.add_parser("laya"); l.add_argument("--url", default="http://127.0.0.1:8003"); l.add_argument("--model"); l.set_defaults(fn=laya)
  m = sub.add_parser("llm"); m.add_argument("--url", default="http://127.0.0.1:1234"); m.add_argument("--model", required=True)
  m.add_argument("--max-tokens", type=int, default=2048)
  m.add_argument("--context", action="store_true", help="add unit, price, store and the receipt's other items")
  m.add_argument("--examples", type=int, default=0, help="show the N most similar categorized items (never the item itself)")
  m.add_argument("--embed-model", default="text-embedding-nomic-embed-text-v1.5")
  m.add_argument("--notes", help="a file of boundary rules to add to the prompt")
  m.set_defaults(fn=llm)
  r = sub.add_parser("report"); r.set_defaults(fn=report)
  args = p.parse_args()
  args.fn(args)


if __name__ == "__main__":
  main()
