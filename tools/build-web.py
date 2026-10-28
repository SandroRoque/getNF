#!/usr/bin/env python3
"""Builds the static web pages (D105) into web/dist/, ready to upload to any static host.

Today: web/apagar-conta/, the account deletion page Play asks for. It needs the Supabase project's URL and
publishable key (public by design, but kept out of the repo like the app's): from server/.env.local, or with --local
from the running local Supabase (`npx supabase start`), for testing. Values are never printed.

  tools/build-web.py --contact contato@exemplo.com.br --privacy https://.../privacidade/
  tools/build-web.py --local            # against the local Supabase
"""
import argparse, base64, json, os, re, shutil, subprocess, sys
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "web")
OUT = os.path.join(SRC, "dist")


def env_local():
  for path in (os.path.join(ROOT, "server", ".env.local"), os.path.join(ROOT, "..", "..", "..", "server", ".env.local")):
    if os.path.exists(path):
      return dict(line.strip().split("=", 1) for line in open(path) if "=" in line and not line.lstrip().startswith("#"))
  sys.exit("no server/.env.local")


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


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--local", action="store_true", help="use the running local Supabase (testing)")
  p.add_argument("--app", default="getNF", help="the app's name on the pages")
  p.add_argument("--contact", default="[e-mail de contato]", help="where people write when they can't sign in")
  p.add_argument("--privacy", default="#", help="the privacy policy's URL")
  a = p.parse_args()

  e = local_supabase() if a.local else env_local()
  url, key = e.get("SUPABASE_URL", "").rstrip("/"), e.get("SUPABASE_PUBLISHABLE_KEY", "")
  if not url or not key:
    sys.exit("missing SUPABASE_URL or SUPABASE_PUBLISHABLE_KEY")
  if not a.local and not url.startswith("https://"):
    sys.exit("the project URL must be HTTPS")
  if not publishable(key):
    sys.exit("the key isn't a publishable key; a secret key must never go in a web page")
  if "[" in a.contact:
    print("warning: no --contact e-mail; the page shows a placeholder", file=sys.stderr)

  origin = "{0.scheme}://{0.netloc}".format(urlparse(url))
  shutil.rmtree(OUT, ignore_errors=True)
  for page in ("apagar-conta",):
    src, dst = os.path.join(SRC, page), os.path.join(OUT, page)
    shutil.copytree(src, dst)
    html_path = os.path.join(dst, "index.html")
    html = open(html_path, encoding="utf-8").read()
    for k, v in {"%CONNECT%": origin, "%APP%": a.app, "%CONTACT%": a.contact, "%PRIVACY%": a.privacy}.items():
      html = html.replace(k, v)
    open(html_path, "w", encoding="utf-8").write(html)
    with open(os.path.join(dst, "config.js"), "w", encoding="utf-8") as f:
      f.write("window.GETNF = " + json.dumps({"url": url, "key": key}) + ";\n")
  print(f"built {os.path.relpath(OUT, ROOT)}/ ({'local Supabase' if a.local else 'online project'})")


main()
