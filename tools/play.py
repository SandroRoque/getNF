#!/usr/bin/env python3
"""Google Play Developer API for the Caderneta (D159): read the console's state and send bundles without the browser.

  tools/play.py status                    tracks, their releases and the closed tracks' testers
  tools/play.py bundles                   bundles uploaded so far (versionCode, sha256)
  tools/play.py listing                   the store listings (title, short description)
  tools/play.py upload <aab> --track internal --name 0.1.1 --notes-file notes.txt [--commit]
  tools/play.py promote <code> --track alpha --name 0.1.1 --notes-file notes.txt [--commit]

Every change is made in an "edit" that is validated and then thrown away, unless --commit is given: without it,
promote is a dry run that Google checks, and upload only says what it would send (an uploaded bundle takes its
versionCode for good, even from an edit that's discarded). With --commit the edit is applied; on a closed track, with
managed publishing off, that sends the release for review. `upload` takes only a bundle recorded in a
manifest's `aab:` line (docs/releases/<code>.md): the code is the manifest that records this bundle's sha256, so what
goes up is what was checked.

Credentials: a service account's JSON key at ~/.config/getnf/play-api.json (or $PLAY_API_KEY), made by
tools/setup-play-api.sh and invited in Play Console › Users and permissions. The key never goes in the repo or in
output. Only the standard library and the `openssl` command (to sign the token).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "br.com.roqlab.caderneta"
KEY = Path(os.environ.get("PLAY_API_KEY", Path.home() / ".config" / "getnf" / "play-api.json"))
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
LANGUAGE = "pt-BR"


def b64url(data: bytes) -> str:
  return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def sign_rs256(message: bytes, private_key_pem: str) -> bytes:
  """RS256 with the openssl command; the key goes through a 0600 temp file that's deleted at once."""
  fd, path = tempfile.mkstemp(suffix=".pem")
  try:
    os.fchmod(fd, 0o600)
    os.write(fd, private_key_pem.encode())
    os.close(fd)
    out = subprocess.run(["openssl", "dgst", "-sha256", "-sign", path], input=message, capture_output=True)
    if out.returncode != 0:
      sys.exit("openssl could not sign the token: " + out.stderr.decode()[:200])
    return out.stdout
  finally:
    os.unlink(path)


def jwt(key: dict, now: int) -> str:
  header = b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": key.get("private_key_id", "")}).encode())
  claims = b64url(json.dumps({"iss": key["client_email"], "scope": SCOPE, "aud": key["token_uri"],
                              "iat": now, "exp": now + 3600}).encode())
  signing_input = f"{header}.{claims}".encode()
  return f"{header}.{claims}.{b64url(sign_rs256(signing_input, key['private_key']))}"


def token() -> str:
  if not KEY.exists():
    sys.exit(f"no key at {KEY}: run tools/setup-play-api.sh, then invite the service account in Play Console (D159)")
  key = json.loads(KEY.read_text())
  body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                 "assertion": jwt(key, int(time.time()))}).encode()
  try:
    with urllib.request.urlopen(urllib.request.Request(key["token_uri"], body), timeout=30) as r:
      return json.load(r)["access_token"]
  except urllib.error.HTTPError as e:
    sys.exit(f"token refused: {e.code} {e.read().decode()[:300]}")


class Play:
  def __init__(self):
    self.auth = {"Authorization": "Bearer " + token()}
    self.edit = None

  def call(self, method, url, body=None, data=None, content_type="application/json"):
    headers = dict(self.auth)
    if body is not None:
      data = json.dumps(body).encode()
    if data is not None:
      headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data, headers, method=method)
    try:
      with urllib.request.urlopen(req, timeout=600) as r:
        text = r.read().decode()
        return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
      detail = e.read().decode()
      try:
        detail = json.loads(detail)["error"]["message"]
      except (ValueError, KeyError):
        detail = detail[:400]
      if self.edit:
        self.discard()
      sys.exit(f"{method} {url.split(PACKAGE)[-1]}: {e.code} {detail}")

  def begin(self):
    self.edit = self.call("POST", f"{API}/edits", {})["id"]
    return f"{API}/edits/{self.edit}"

  def discard(self):
    edit, self.edit = self.edit, None
    if edit:
      try:
        urllib.request.urlopen(urllib.request.Request(f"{API}/edits/{edit}", headers=self.auth, method="DELETE"), timeout=30)
      except urllib.error.URLError:
        pass

  def finish(self, commit: bool):
    url = f"{API}/edits/{self.edit}"
    self.call("POST", f"{url}:validate")
    if commit:
      self.call("POST", f"{url}:commit")
      self.edit = None
      print("committed")
    else:
      self.discard()
      print("validated by Google and discarded (dry run; --commit to apply)")


def notes(path: str | None) -> list:
  if not path:
    return []
  text = Path(path).read_text().strip()
  if len(text) > 500:
    sys.exit(f"release notes are {len(text)} characters; Play takes 500")
  return [{"language": LANGUAGE, "text": text}]


def manifest_sha(code: int) -> str | None:
  m = ROOT / "docs" / "releases" / f"{code}.md"
  if not m.exists():
    return None
  found = re.search(r"^aab:\s*sha256:([0-9a-f]{64})\s*$", m.read_text(), re.M)
  return found.group(1) if found else None


def code_for(digest: str) -> int:
  """The versionCode whose manifest records this bundle's sha256: only a checked bundle goes up."""
  for m in sorted((ROOT / "docs" / "releases").glob("*.md")):
    if m.stem.isdigit() and manifest_sha(int(m.stem)) == digest:
      return int(m.stem)
  sys.exit(f"no docs/releases/<code>.md records aab sha256:{digest}: record the bundle you checked first")


def show_release(r):
  codes = ",".join(r.get("versionCodes", [])) or "-"
  frac = f" {r['userFraction']:.0%}" if "userFraction" in r else ""
  print(f"    {r.get('name', '?'):<12} codes {codes:<8} {r.get('status', '?')}{frac}")


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  sub = p.add_subparsers(dest="cmd", required=True)
  sub.add_parser("status")
  sub.add_parser("bundles")
  sub.add_parser("listing")
  for name in ("upload", "promote"):
    s = sub.add_parser(name)
    s.add_argument("aab" if name == "upload" else "code")
    s.add_argument("--track", required=True, help="internal, alpha (the closed track), beta, production")
    s.add_argument("--name", required=True, help="the release name, = versionName")
    s.add_argument("--notes-file")
    s.add_argument("--commit", action="store_true")
  a = p.parse_args()

  if a.cmd == "upload":
    a.digest = hashlib.sha256(Path(a.aab).read_bytes()).hexdigest()
    a.code = code_for(a.digest)
    if not a.commit:
      # An uploaded bundle takes its versionCode for good, even in an edit that's thrown away (play-console.md).
      print(f"would upload code {a.code} (sha256:{a.digest}) to {a.track} as {a.name}; nothing sent, --commit to do it")
      return
  play = Play()
  url = play.begin()
  if a.cmd == "status":
    for t in play.call("GET", f"{url}/tracks").get("tracks", []):
      print(t["track"])
      for r in t.get("releases", []):
        show_release(r)
      if t["track"] not in ("production", "internal"):
        testers = play.call("GET", f"{url}/testers/{t['track']}")
        if testers.get("googleGroups"):
          print("    testers: " + ", ".join(testers["googleGroups"]))
    play.discard()
  elif a.cmd == "bundles":
    for b in play.call("GET", f"{url}/bundles").get("bundles", []):
      print(f"{b['versionCode']:>4}  sha256:{b.get('sha256', '?')}")
    play.discard()
  elif a.cmd == "listing":
    for l in play.call("GET", f"{url}/listings").get("listings", []):
      print(f"{l['language']}: {l.get('title')} | {l.get('shortDescription')}")
    play.discard()
  elif a.cmd == "upload":
    code = a.code
    up = play.call("POST", f"{UPLOAD}/edits/{play.edit}/bundles?uploadType=media", data=Path(a.aab).read_bytes(),
                   content_type="application/octet-stream")
    print(f"uploaded code {up['versionCode']} sha256:{up.get('sha256')}")
    play.call("PUT", f"{url}/tracks/{a.track}", {"track": a.track, "releases": [
      {"name": a.name, "versionCodes": [str(code)], "status": "completed", "releaseNotes": notes(a.notes_file)}]})
    play.finish(a.commit)
  elif a.cmd == "promote":
    play.call("PUT", f"{url}/tracks/{a.track}", {"track": a.track, "releases": [
      {"name": a.name, "versionCodes": [str(a.code)], "status": "completed", "releaseNotes": notes(a.notes_file)}]})
    play.finish(a.commit)


if __name__ == "__main__":
  main()
