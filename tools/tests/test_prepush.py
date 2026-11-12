"""The pre-push hook runs check.sh for a push that only changes the server (T-44): in a throwaway repo."""
import os, subprocess, unittest


class PrePushTest(unittest.TestCase):
  def test_a_migration_only_push_runs_the_checks(self):
    script = os.path.join(os.path.dirname(__file__), "prepush_server_only.sh")
    r = subprocess.run(["bash", script], capture_output=True, text=True)
    self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
    self.assertIn("OK: check.sh ran", r.stdout)


if __name__ == "__main__":
  unittest.main()
