#!/usr/bin/env python3
"""Measure how well local models categorize receipt items, against the categories the user picked by hand (D56).

  categorize-eval.py laya [--model M]  # Laya (laya-serve, Jev protocol), walking the category tree level by level
  categorize-eval.py llm  [--model M]  # an LLM through LM Studio's OpenAI-compatible server (key: $LMSTUDIO_API_KEY or .env.local)
  categorize-eval.py synth --model M   # LLM-written receipt-style items per category (training data)
  categorize-eval.py trainset ...      # Laya training rows (JSONL for laya-train) from labeled items
  categorize-eval.py report            # compare the saved runs

The items are real purchases, so everything this writes stays in test-media/ (gitignored). Only the taxonomy's labels
are shown to the models: its comments name real items, which would leak the answers. test-media/categorize/items.json
was exported from the retired PocketBase (D56) and is kept as the fixed answer key.
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
  """$LMSTUDIO_API_KEY, else the gitignored .env.local (never printed)."""
  return os.environ.get("LMSTUDIO_API_KEY") or env_local().get("LMSTUDIO_API_KEY")


def env_local():
  values = {}
  for line in open(os.path.join(ROOT, ".env.local")) if os.path.exists(os.path.join(ROOT, ".env.local")) \
      else open(os.path.join(ROOT, "..", "..", "..", ".env.local")):
    if "=" in line and not line.lstrip().startswith("#"):
      k, v = line.strip().split("=", 1)
      values[k] = v
  return values


def load_items():
  return json.load(open(ITEMS, encoding="utf-8"))


INSTRUCTIONS = ("Item de uma nota fiscal de supermercado no Brasil, com a descrição abreviada como sai na nota. "
                "Em qual categoria de gasto ele se encaixa?")
HERE = "_here"


def lexicon(path, lang):
  """Phrases first (longest first), then single tokens; unknown tokens are kept (often a brand)."""
  phrases, tokens = [], {}
  for line in open(path, encoding="utf-8"):
    if not line.strip() or line.startswith("#"):
      continue
    key, pt, en = line.rstrip("\n").split("\t")
    (phrases.append((key, pt if lang == "pt" else en)) if " " in key else tokens.__setitem__(key, pt if lang == "pt" else en))
  phrases.sort(key=lambda kv: -len(kv[0]))
  def expand(text):
    words = text.upper().split()
    out, i = [], 0
    while i < len(words):
      for key, value in phrases:
        k = key.split()
        if words[i:i + len(k)] == k:
          out.append(value); i += len(k); break
      else:
        out.append(tokens.get(words[i], words[i])); i += 1
    return " ".join(out)
  return expand


def laya(args):
  """Walk the tree: choose among a node's children (plus "none of these, just <node>" below the top), until a leaf."""
  labels, children = taxonomy()
  items = load_items()
  expand = lexicon(args.lexicon, args.lang) if args.lexicon else None
  described = {}
  if args.descriptions:
    for line in open(args.descriptions, encoding="utf-8"):
      if line.strip():
        path, text = line.rstrip("\n").split("\t")
        described[path] = text
  instructions = INSTRUCTIONS if args.lang == "pt" else \
    "What kind of product is this item from a Brazilian supermarket receipt?"
  # A hosted Jev-protocol service (TypeSafe's Jev) takes a bearer key, read like lmstudio_key() and never printed.
  key = (os.environ.get(args.key_env) or env_local().get(args.key_env)) if args.key_env else None
  if args.key_env and not key:
    sys.exit(f"{args.key_env} is not set (environment or .env.local)")
  headers = {"Authorization": f"Bearer {key}"} if key else None
  leaves = [p for p in labels if not children.get(p)]
  off = json.load(open(os.path.join(OUT, "off.json"), encoding="utf-8")) if args.lookup else {}
  results, started, tokens = [], time.time(), 0
  for n, item in enumerate(items):
    text = expand(item["description"]) if expand else item["description"]
    if args.clean:
      # Sizes and counts ("250G", "KG", "30UN") carry no category and seem to distract the encoder.
      text = " ".join(w for w in text.split() if not re.fullmatch(r"\d+([.,]\d+)?[A-Z]*|KG|UN|UND|PC|L|ML|G", w.upper())) or text
    state = ({"item": text} if args.lang == "en" else {"descricao": text})
    if args.context:
      state["contexto"] = describe(item, True)
    found = off.get(item.get("gtin") or "")
    if found:
      # What Open Food Facts calls the barcode (D60): a full name, and its own categories when it has them.
      state["product_name"] = " ".join(x for x in (found.get("product_name_pt") or found.get("product_name"),
                                                   found.get("brands"), found.get("quantity")) if x)
      tags = [t.split(":", 1)[-1].replace("-", " ") for t in found.get("categories_tags") or []]
      if tags:
        state["product_categories"] = ", ".join(tags[-4:])
    node, steps = "", []
    if args.flat:
      # One question over every leaf: the hosted model takes up to 255 options (Laya's head budget is ~20).
      options = {p.replace(".", "__"): f"{described.get(p, labels[p])} ({p})" for p in leaves}
      body = {"state": state, "questions": {"c": {"type": "choice", "instructions": instructions, "criteria": options}}}
      if args.model:
        body["model"] = args.model
      t = time.time()
      answer = post(args.url.rstrip("/") + "/v1/systemone", body, headers)
      tokens += (answer.get("usage") or {}).get("input_tokens") or 0
      a = answer["answers"]["c"]
      node = (a.get("choice") or "").replace("__", ".")
      steps.append({"options": len(options), "choice": node, "confidence": a.get("confidence"),
                    "ms": round((time.time() - t) * 1000)})
    while not args.flat and children.get(node):
      options = {p.rsplit(".", 1)[-1]: described.get(p, labels[p]) for p in children[node]}
      if node and not args.no_here:
        options[HERE] = ("Other " if args.lang == "en" else "Outro tipo de ") + described.get(node, labels[node])
      body = {"state": state,
              "questions": {"c": {"type": "choice", "instructions": instructions, "criteria": options}}}
      if args.model:
        body["model"] = args.model
      t = time.time()
      answer = post(args.url.rstrip("/") + "/v1/systemone", body, headers)
      tokens += (answer.get("usage") or {}).get("input_tokens") or 0
      a = answer["answers"]["c"]
      choice = a.get("choice")
      steps.append({"options": len(options), "choice": choice, "confidence": a.get("confidence"),
                    "ms": round((time.time() - t) * 1000)})
      if choice == HERE:
        break
      node = f"{node}.{choice}" if node else choice
    results.append({**item, "input": text, "predicted": node, "steps": steps,
                    "confidence": min(s["confidence"] or 0 for s in steps) if steps else 0})
  tag = "".join([f"-{args.lang}", "+lex" if args.lexicon else "", "+desc" if args.descriptions else "",
                 "+nohere" if args.no_here else "", "+clean" if args.clean else "",
                 "+flat" if args.flat else "", "+ctx" if args.context else "",
                 "+off" if args.lookup else "", f"+{args.tag}" if args.tag else ""])
  if tokens:
    print(f"{tokens} input tokens reported")
  save(f"laya-{args.model or 'auto'}{tag}", results, started)


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


SYNTH_PROMPT = """Gere {n} descrições de itens diferentes como aparecem impressas numa NFC-e brasileira, todas da \
categoria de gasto abaixo. Escreva como o sistema da loja escreve: MAIÚSCULAS, sem acentos, palavras abreviadas \
(QJO, REQ, LTE, FGO, BISC, DET, PAP...), com marca e tamanho quando houver (200G, 1L, KG). Varie produtos, marcas e \
abreviações; inclua casos fáceis e difíceis. Só itens desta categoria. Uma descrição por linha, sem numeração.

Categoria: {path} ({label}){note}"""


def synth(args):
  """Receipt-style training items per leaf category, written by the LLM. Never the test items themselves."""
  labels, children = taxonomy()
  test = {i["description"].upper() for i in load_items()}
  described = dict(l.rstrip("\n").split("\t") for l in open(args.descriptions, encoding="utf-8") if l.strip()) \
    if args.descriptions else {}
  key = lmstudio_key()
  out_path = os.path.join(OUT, f"synth-{args.model}.jsonl")
  done = set()
  if os.path.exists(out_path):
    done = {json.loads(l)["category"] for l in open(out_path, encoding="utf-8")}
  leaves = [p for p in labels if not children.get(p) and p not in done]
  with open(out_path, "a", encoding="utf-8") as out:
    for n, path in enumerate(leaves):
      note = f"\nInclui: {described[path]}" if path in described else ""
      t = time.time()
      r = post(args.url.rstrip("/") + "/v1/chat/completions", {
        "model": args.model, "temperature": .8, "max_tokens": args.max_tokens,
        "messages": [{"role": "user", "content": SYNTH_PROMPT.format(n=args.per_category, path=path, label=labels[path], note=note)}],
      }, {"Authorization": f"Bearer {key}"} if key else None, timeout=900)
      text = r["choices"][0]["message"].get("content") or ""
      lines = []
      for line in text.splitlines():
        line = re.sub(r"^[\s\-*\d.)]+", "", line).strip().strip("`")
        if 3 <= len(line) <= 60 and line.upper() == line and line.upper() not in test:
          lines.append(line)
      for line in dict.fromkeys(lines):
        out.write(json.dumps({"description": line, "category": path}, ensure_ascii=False) + "\n")
      out.flush()
      print(f"{n + 1:3}/{len(leaves)} {path:40} {len(set(lines)):3} items  {time.time() - t:.0f}s", file=sys.stderr)


def trainset(args):
  """Laya training rows from labeled items (`path -> "A|B|C"` in a Python file's ITEMS): one choice question per tree
  level, worded exactly as `laya --lang en --no-here` asks it. Items equal to a test item are dropped."""
  import importlib.util as iu
  spec = iu.spec_from_file_location("items", args.items); mod = iu.module_from_spec(spec); spec.loader.exec_module(mod)
  labels, children = taxonomy()
  test = {i["description"].upper() for i in load_items()}
  expand = lexicon(args.lexicon, "en") if args.lexicon else (lambda t: t)
  desc = dict(l.rstrip("\n").split("\t") for l in open(args.descriptions, encoding="utf-8") if l.strip()) if args.descriptions else {}
  instructions = "What kind of product is this item from a Brazilian supermarket receipt?"
  items = rows = 0
  with open(args.out, "w", encoding="utf-8") as out:
    for path, blob in mod.ITEMS.items():
      assert path in labels and not children.get(path), f"not a leaf category: {path}"
      for d in (x.strip() for x in blob.split("|")):
        if not d or d.upper() in test:
          continue
        items += 1
        node = ""
        for part in path.split("."):
          options = {c.rsplit(".", 1)[-1]: desc.get(c, labels[c]) for c in children[node]}
          out.write(json.dumps({"state": {"item": expand(d)}, "questions": {"c": {
            "type": "choice", "instructions": instructions, "criteria": options}}, "expected": {"c": part}},
            ensure_ascii=False) + "\n")
          rows += 1
          node = f"{node}.{part}" if node else part
  print(f"{items} items, {rows} rows -> {args.out}")


OFF_FIELDS = "product_name,product_name_pt,generic_name_pt,generic_name,brands,quantity,categories_tags"


def lookup(args):
  """Open Food Facts by GTIN, once per code, cached in test-media (barcodes only are sent)."""
  path = os.path.join(OUT, "off.json")
  cache = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
  codes = sorted({i["gtin"] for i in load_items() if i.get("gtin")} - set(cache))
  for n, code in enumerate(codes):
    req = urllib.request.Request(f"https://world.openfoodfacts.org/api/v2/product/{code}.json?fields={OFF_FIELDS}",
                                 headers={"User-Agent": "getNF-categorize-eval/0.1 (personal receipt app, research)"})
    try:
      with urllib.request.urlopen(req, timeout=30) as r:
        body = json.load(r)
    except urllib.error.HTTPError as e:
      body = json.load(e) if e.code == 404 else {"status": 0, "error": e.code}
    cache[code] = body.get("product") if body.get("status") == 1 else None
    json.dump(cache, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    time.sleep(.8)  # well under OFF's 100 product reads per minute
    print(f"{n + 1}/{len(codes)} {'found' if cache[code] else 'not found'}", file=sys.stderr)
  found = sum(1 for c in cache.values() if c)
  print(f"{found}/{len(cache)} barcodes known to Open Food Facts -> {path}")


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
  l = sub.add_parser("laya"); l.add_argument("--url", default="http://127.0.0.1:8003"); l.add_argument("--model")
  l.add_argument("--lang", choices=["pt", "en"], default="pt", help="language of the question and descriptions")
  l.add_argument("--lexicon", help="token/phrase -> pt/en expansions (TSV)")
  l.add_argument("--descriptions", help="category path -> description (TSV)")
  l.add_argument("--no-here", action="store_true", help="always descend to a leaf")
  l.add_argument("--clean", action="store_true", help="drop sizes and counts from the item text")
  l.add_argument("--tag", help="extra name for the run")
  l.add_argument("--key-env", help="name of the variable holding a bearer key (e.g. TYPESAFE_API_KEY)")
  l.add_argument("--lookup", action="store_true", help="add Open Food Facts' name and categories (from `lookup`)")
  l.add_argument("--flat", action="store_true", help="one question over all leaf categories instead of the tree walk")
  l.add_argument("--context", action="store_true", help="add unit, price, store and the receipt's other items to the state")
  l.set_defaults(fn=laya)
  m = sub.add_parser("llm"); m.add_argument("--url", default="http://127.0.0.1:1234"); m.add_argument("--model", required=True)
  m.add_argument("--max-tokens", type=int, default=2048)
  m.add_argument("--context", action="store_true", help="add unit, price, store and the receipt's other items")
  m.add_argument("--examples", type=int, default=0, help="show the N most similar categorized items (never the item itself)")
  m.add_argument("--embed-model", default="text-embedding-nomic-embed-text-v1.5")
  m.add_argument("--notes", help="a file of boundary rules to add to the prompt")
  m.set_defaults(fn=llm)
  g = sub.add_parser("synth"); g.add_argument("--url", default="http://127.0.0.1:1234"); g.add_argument("--model", required=True)
  g.add_argument("--per-category", type=int, default=25); g.add_argument("--max-tokens", type=int, default=4096)
  g.add_argument("--descriptions", help="category path -> description (TSV), as a hint")
  g.set_defaults(fn=synth)
  t = sub.add_parser("trainset"); t.add_argument("--items", required=True); t.add_argument("--out", required=True)
  t.add_argument("--lexicon"); t.add_argument("--descriptions"); t.set_defaults(fn=trainset)
  o = sub.add_parser("lookup"); o.set_defaults(fn=lookup)
  r = sub.add_parser("report"); r.set_defaults(fn=report)
  args = p.parse_args()
  args.fn(args)


if __name__ == "__main__":
  main()
