#!/usr/bin/env python3
"""Builds the project's site (D105) from web/ into web/dist/, ready for `npx wrangler@4.150.0 deploy` in web/.

- `/`: the landing page.
- `/privacidade/`: docs/privacy-policy.md, rendered with cmark (its draft note dropped, its blanks filled in).
- `/apagar-conta/`: deleting an account without the app, which Play asks for. It needs the Supabase project's URL
  and publishable key (public by design, kept out of the repo like the app's): from .env.local, or with
  --local from the running local Supabase, for testing. Values are never printed.
- `/nova-senha/`: where the reset-password e-mail lands; sets the new password. Same URL and key.

  tools/build-web.py --contact <e-mail>
  tools/build-web.py --local --contact teste@example.test
"""
import argparse, base64, datetime, json, os, re, shutil, subprocess, sys
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "web")
OUT = os.path.join(SRC, "dist")
SKIP = {"dist", "README.md", "wrangler.jsonc", ".wrangler"}


def env_local():
  for path in (os.path.join(ROOT, ".env.local"), os.path.join(ROOT, "..", "..", "..", ".env.local")):
    if os.path.exists(path):
      return dict(line.strip().split("=", 1) for line in open(path) if "=" in line and not line.lstrip().startswith("#"))
  sys.exit("no .env.local")


def local_supabase():
  out = subprocess.run(["npx", "supabase", "status", "-o", "env"], cwd=ROOT, capture_output=True, text=True).stdout
  values = dict(re.findall(r'^([A-Z_]+)="?([^"\n]*)"?$', out, re.M))
  if not values.get("API_URL"):
    sys.exit("the local Supabase isn't running: npx supabase start")
  return {"SUPABASE_URL": values["API_URL"], "SUPABASE_PUBLISHABLE_KEY": values.get("PUBLISHABLE_KEY") or values["ANON_KEY"]}


def publishable(key):
  """The same rule as the release build: a publishable key or an anon JWT, never a secret one."""
  if key.startswith("sb_publishable_"):
    return True
  parts = key.split(".")
  if len(parts) != 3:
    return False
  try:
    payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
  except ValueError:
    return False
  return payload.get("role") == "anon"


def policy_html(app, contact, site, date):
  text = open(os.path.join(ROOT, "docs", "privacy-policy.md"), encoding="utf-8").read()
  # The draft note for us (a blockquote right under the title) isn't part of the published policy.
  text = re.sub(r"\n(> .*\n)+", "\n", text, count=1)
  for blank, value in {"[nome do app]": app, "[e-mail de contato]": contact, "[data da publicação]": date,
                       "[endereço da página de exclusão]": f"{site}/apagar-conta/"}.items():
    text = text.replace(blank, value)
  left = re.findall(r"\[[^\]]*\](?!\()", text)
  if left:
    sys.exit(f"privacy policy still has blanks: {left}")
  return subprocess.run(["cmark"], input=text, capture_output=True, text=True, check=True).stdout


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--local", action="store_true", help="the deletion page talks to the running local Supabase (testing)")
  p.add_argument("--app", default="Caderneta", help="the app's name on the pages")
  p.add_argument("--site", default="https://caderneta.roqlab.com.br", help="where the site lives")
  p.add_argument("--contact", required=True, help="the public contact e-mail")
  p.add_argument("--date", default=datetime.date.today().strftime("%d/%m/%Y"), help="the policy's date")
  a = p.parse_args()

  e = local_supabase() if a.local else env_local()
  url, key = e.get("SUPABASE_URL", "").rstrip("/"), e.get("SUPABASE_PUBLISHABLE_KEY", "")
  if not url or not key:
    sys.exit("missing SUPABASE_URL or SUPABASE_PUBLISHABLE_KEY")
  if not a.local and not url.startswith("https://"):
    sys.exit("the project URL must be HTTPS")
  if not publishable(key):
    sys.exit("the key isn't a publishable key; a secret key must never go in a web page")

  shutil.rmtree(OUT, ignore_errors=True)
  shutil.copytree(SRC, OUT, ignore=lambda d, names: [n for n in names if d == SRC and n in SKIP])
  fills = {"%CONNECT%": "{0.scheme}://{0.netloc}".format(urlparse(url)), "%APP%": a.app, "%CONTACT%": a.contact,
           "%PRIVACY%": "/privacidade/", "%POLICY%": policy_html(a.app, a.contact, a.site, a.date)}
  for folder, _, files in os.walk(OUT):
    for name in files:
      if name.endswith(".html"):
        path = os.path.join(folder, name)
        html = open(path, encoding="utf-8").read()
        for k, v in fills.items():
          html = html.replace(k, v)
        if re.search(r"%[A-Z]+%", html):
          sys.exit(f"unfilled placeholder in {os.path.relpath(path, OUT)}")
        open(path, "w", encoding="utf-8").write(html)
  for page in ("apagar-conta", "nova-senha"):
    with open(os.path.join(OUT, page, "config.js"), "w", encoding="utf-8") as f:
      f.write("window.GETNF = " + json.dumps({"url": url, "key": key}) + ";\n")
  print(f"built {os.path.relpath(OUT, ROOT)}/ ({'local Supabase' if a.local else 'online project'})")


main()
