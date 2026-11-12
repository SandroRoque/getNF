"""blank-buyers.py's walk over the pages online (T-24), against a fake PostgREST and Storage: no network."""
import importlib.util, io, json, os, re, unittest, urllib.error, urllib.parse

spec = importlib.util.spec_from_file_location("blank", os.path.join(os.path.dirname(__file__), "..", "blank-buyers.py"))
blank = importlib.util.module_from_spec(spec)
spec.loader.exec_module(blank)

BUYER = "<h4>Consumidor</h4><ul><li>CPF: 123.456.789-09</li></ul>"


class FakeServer:
  def __init__(self, n, missing=()):
    # Two cadernetas, chaves in order; PostgREST caps an answer at 1,000 rows whatever the limit asked.
    self.rows = sorted(({"ledger_id": "l%d" % (i % 2), "chave": "%044d" % i, "page_path": "p/%d.html" % i} for i in range(n)),
                       key=lambda r: (r["ledger_id"], r["chave"]))
    self.missing = set(missing)
    self.put = {}

  def get(self, path):
    if path.startswith("/storage/v1/object/receipts/"):
      name = path.removeprefix("/storage/v1/object/receipts/")
      if name in self.missing: raise urllib.error.HTTPError(path, 400, "NoSuchKey", {}, io.BytesIO(b"{}"))
      return (BUYER if name.endswith("7.html") else "<p>sem consumidor</p>").encode()
    q = urllib.parse.parse_qs(path.split("?", 1)[1])
    rows = self.rows
    if "or" in q:
      lid, _, chave = re.match(r"\(ledger_id\.gt\.(\w+),and\(ledger_id\.eq\.(\w+),chave\.gt\.(\w+)\)\)", q["or"][0]).groups()
      rows = [r for r in rows if (r["ledger_id"], r["chave"]) > (lid, chave)]
    return json.dumps(rows[:min(int(q["limit"][0]), 1000)]).encode()


class BlankBuyersTest(unittest.TestCase):
  def test_every_page_is_read_past_the_first_thousand(self):
    fake = FakeServer(1100)
    paths = list(blank.pages(fake.get))
    self.assertEqual(len(paths), 1100)
    self.assertEqual(len(set(paths)), 1100)

  def test_a_missing_object_is_counted_not_fatal(self):
    fake = FakeServer(30, missing={"p/3.html"})
    total, changed, missing = blank.run(fake.get, lambda p, b: fake.put.__setitem__(p, b), write=True)
    self.assertEqual((total, changed, missing), (30, 3, 1))  # 7, 17 and 27 have a buyer
    self.assertTrue(all(b"123.456" not in b for b in fake.put.values()))

  def test_a_dry_run_writes_nothing(self):
    fake = FakeServer(10)
    blank.run(fake.get, lambda p, b: self.fail("wrote"), write=False)


if __name__ == "__main__":
  unittest.main()
