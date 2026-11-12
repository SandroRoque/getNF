"""The PC backup's file mirror (D191, T-21), against a fake Storage: no network, no real files."""
import datetime, importlib.util, io, json, os, tempfile, unittest, urllib.error

spec = importlib.util.spec_from_file_location("backup", os.path.join(os.path.dirname(__file__), "..", "supabase-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class FakeStorage:
  def __init__(self):
    self.files = {}      # path -> (bytes, updated_at)
    self.vanish = set()  # listed, but gone by the time they're fetched

  def objects(self, e, prefix=""):
    for path, (data, updated) in sorted(self.files.items()):
      yield path, len(data), updated

  def storage(self, e, path, body=None):
    name = path.removeprefix("object/receipts/")
    if name in self.vanish or name not in self.files:
      raise urllib.error.HTTPError(path, 400, "NoSuchKey", {}, io.BytesIO(b"{}"))
    return self.files[name][0]


class MirrorTest(unittest.TestCase):
  def setUp(self):
    self.tmp = tempfile.TemporaryDirectory()
    self.out = self.tmp.name
    self.files = os.path.join(self.out, "files")
    os.makedirs(self.files)
    self.fake = FakeStorage()
    backup.objects, backup.storage = self.fake.objects, self.fake.storage

  def tearDown(self):
    self.tmp.cleanup()

  def run_on(self, day):
    return backup.mirror({}, self.files, today=datetime.date.fromisoformat(day))

  def test_a_file_deleted_online_leaves_the_mirror_and_goes_after_keep_days(self):
    self.fake.files = {"l/a/page.html": (b"a", "1"), "l/b/page.html": (b"b", "1")}
    self.assertEqual(self.run_on("2026-11-01"), (2, 2, 0))
    del self.fake.files["l/b/page.html"]
    self.assertEqual(self.run_on("2026-11-02"), (0, 1, 1))
    self.assertFalse(os.path.exists(os.path.join(self.files, "l/b/page.html")))
    kept = os.path.join(self.out, "files-deleted", "2026-11-02", "l/b/page.html")
    self.assertTrue(os.path.exists(kept))
    self.run_on("2026-11-16")  # 14 days later: still within KEEP
    self.assertTrue(os.path.exists(kept))
    self.run_on("2026-11-17")
    self.assertFalse(os.path.exists(kept))
    self.assertTrue(os.path.exists(os.path.join(self.files, "l/a/page.html")))

  def test_a_file_gone_between_listing_and_download_is_skipped(self):
    self.fake.files = {"l/a/page.html": (b"a", "1"), "l/b/page.html": (b"b", "1")}
    self.fake.vanish = {"l/a/page.html"}
    fetched, total, _ = self.run_on("2026-11-01")
    self.assertEqual((fetched, total), (1, 2))
    index = json.load(open(os.path.join(self.files, ".index.json")))
    self.assertEqual(list(index), ["l/b/page.html"])

  def test_check_needs_a_recent_mirror_too(self):
    open(os.path.join(self.out, "db-2026-11-01_033000.dump"), "w").write("x")
    self.assertEqual(backup.check(self.out), 2)  # a fresh dump, but the mirror never finished
    self.fake.files = {"l/a/page.html": (b"a", "1")}
    self.run_on("2026-11-01")
    self.assertEqual(backup.check(self.out), 0)


if __name__ == "__main__":
  unittest.main()
