#!/usr/bin/env python3
"""Measure how well local models categorize receipt items, against the categories the user picked by hand (D56).

  categorize-eval.py export            # hand-categorized products from the local PocketBase -> test-media/categorize/
  categorize-eval.py laya [--model M]  # Laya (laya-serve, Jev protocol), walking the category tree level by level
  categorize-eval.py llm  [--model M]  # an LLM through LM Studio's OpenAI-compatible server (key: $LMSTUDIO_API_KEY)
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
  items = [{"description": p["description"], "key_kind": p["key"].split(":", 1)[0], "category": paths[p["category"]]}
           for p in all_records("products") if p.get("category")]
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


LLM_PROMPT = """Você categoriza itens de notas fiscais de supermercado brasileiras. As descrições vêm abreviadas \
como saem na nota (ex.: "QJO" = queijo, "REQ" = requeijão, "LTE" = leite).

Categorias (caminho: rótulo). Escolha sempre a mais específica que se aplica:
{categories}

Responda só com JSON: {{"categoria": "<caminho>", "confianca": <0 a 1>}}"""


def llm(args):
  labels, children = taxonomy()
  items = load_items()
  categories = "\n".join(f"{p}: {labels[p]}" for p in labels)
  system = LLM_PROMPT.format(categories=categories)
  results, started = [], time.time()
  for item in items:
    t = time.time()
    key = os.environ.get("LMSTUDIO_API_KEY")
    r = post(args.url.rstrip("/") + "/v1/chat/completions", {
      "model": args.model, "temperature": 0, "max_tokens": args.max_tokens,
      "messages": [{"role": "system", "content": system}, {"role": "user", "content": item["description"]}],
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
  save(f"llm-{args.model.replace('/', '_')}", results, started)


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
  m.add_argument("--max-tokens", type=int, default=2048); m.set_defaults(fn=llm)
  r = sub.add_parser("report"); r.set_defaults(fn=report)
  args = p.parse_args()
  args.fn(args)


if __name__ == "__main__":
  main()
