"""The LLM worker's fixes before it's switched back on (T-26): no network, no LM Studio."""
import importlib.util, os, re, tempfile, types, unittest, urllib.parse
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("worker", os.path.join(os.path.dirname(__file__), "..", "llm-worker.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class Db:
  def __init__(self): self.writes = []
  def write(self, *a, **k): self.writes.append((a, k))


def ledger(by_path):
  return types.SimpleNamespace(lid="l", products={"p": {"id": "p", "category_review": "automatic", "category_source": "jev",
                                                        "category_id": "c"}},
                               path={"c": "Food", "d": "Drinks"}, spent={"p": 100}, rules=[], category_list=lambda: "Food",
                               words=lambda _: "SYNTHETIC", by_path=by_path)


class AuditTest(unittest.TestCase):  # R8
  def test_an_answer_without_a_valid_category_writes_nothing(self):
    for answer in ({}, {"categoria": "Not a category"}):
      db = Db()
      result = w.audit(db, types.SimpleNamespace(ask=lambda *_: answer), ledger(lambda _: None), 1, 0, "2026-01-01T00:00:00Z")
      self.assertEqual(db.writes, [], answer)
      self.assertEqual(result, {"audited": 0, "disagreed": 0, "unanswered": 1})

  def test_a_valid_disagreement_is_logged_and_suggested(self):
    db = Db()
    w.audit(db, types.SimpleNamespace(ask=lambda *_: {"categoria": "Drinks"}), ledger(lambda _: "d"), 1, 0, "2026-01-01T00:00:00Z")
    log = [a[2] for a, _ in db.writes if a[1] == "audit_log"]
    self.assertEqual([(x["agree"], x["llm_category_id"]) for x in log], [(False, "d")])


class FakeRest:
  """PostgREST's order, limit and the keyset `or` the worker sends; a "sync" updates rows between pages."""
  def __init__(self, n):
    self.rows = [{"id": "%05d" % i, "v": 0} for i in range(n)]
    self.calls = 0

  def call(self, method, path, body=None, params=None, prefer=None):
    self.calls += 1
    if self.calls == 2:  # between the first and second page, the phone syncs: 800 rows change (and move, unordered)
      for r in self.rows[:800]: r["v"] += 1
      self.rows = self.rows[800:] + self.rows[:800]
    rows = sorted(self.rows, key=lambda r: r["id"]) if params["order"] == "id.asc" else self.rows
    if "or" in params:
      last = re.match(r'\(id\.gt\."(\w+)"\)', params["or"]).group(1)
      rows = [r for r in rows if r["id"] > last]
    return [dict(r) for r in rows[:params["limit"]]]


class SelectTest(unittest.TestCase):  # R16
  def test_rows_changing_between_pages_are_read_once_each(self):
    db = w.Supabase("http://x", "sb_synthetic")
    fake = FakeRest(2500)
    db.call = fake.call
    rows = db.select("product", ledger_id="eq.l")
    self.assertEqual(len(rows), 2500)
    self.assertEqual(len({r["id"] for r in rows}), 2500)

  def test_a_composite_key_continues_after_the_last_row(self):
    self.assertEqual(w.after(("chave", "line_no"), ["42", 3]), '(chave.gt."42",and(chave.eq."42",line_no.gt."3"))')


class ModelTest(unittest.TestCase):  # R21
  def setUp(self):
    self.tmp = tempfile.TemporaryDirectory()
    self.loaded, self.log = False, []
    def run(cmd, **k):
      if cmd[1] == "load": self.loaded = True; self.log.append("load")
      if cmd[1] == "unload": self.loaded = False; self.log.append("unload")
      return types.SimpleNamespace(stdout=w.IDENT if self.loaded else "")
    self.patch = patch.object(w.subprocess, "run", side_effect=run)
    self.patch.start()

  def tearDown(self):
    self.patch.stop()
    self.tmp.cleanup()

  def llm(self): return w.Llm("k", state_dir=self.tmp.name)

  def test_the_first_run_out_leaves_the_model_to_the_other(self):
    a, b = self.llm().__enter__(), self.llm().__enter__()
    a.__exit__(None, None, None)
    self.assertTrue(self.loaded, "the run still going lost its model")
    b.__exit__(None, None, None)
    self.assertFalse(self.loaded)
    self.assertEqual(self.log, ["load", "unload"])

  def test_a_model_the_user_loaded_stays(self):
    self.loaded = True
    with self.llm(): pass
    self.assertTrue(self.loaded)
    self.assertEqual(self.log, [])


if __name__ == "__main__":
  unittest.main()
