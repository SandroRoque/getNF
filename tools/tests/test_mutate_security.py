"""mutate-security.py's reading of conditions (D208): how a refusal or a policy is weakened. No database."""
import importlib.util, os, unittest

spec = importlib.util.spec_from_file_location("mutate", os.path.join(os.path.dirname(__file__), "..", "mutate-security.py"))
mutate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mutate)

FUNCTION = """CREATE OR REPLACE FUNCTION public.f(lid uuid) RETURNS void LANGUAGE plpgsql AS $function$
declare mine text := public.my_role(lid);
begin
  if mine is null or mine = 'member' or (f.role = 'manager' and mine <> 'owner') then
    raise exception 'not allowed as %', f.role using errcode = '42501';
  end if;
  if not found then raise exception 'not a member'; end if;
  if found then return; end if;
  update t set x = 1 where user_id = (select auth.uid());
end $function$"""


class Conditions(unittest.TestCase):
  def test_or_is_split_only_outside_parentheses_and_quotes(self):
    self.assertEqual(mutate.split_top("a is null or (b = 1 or c = 2) or d = ' or '", "or"), ["a is null", "(b = 1 or c = 2)", "d = ' or '"])

  def test_enclosing_parentheses_go_but_not_a_pair_at_each_end(self):
    self.assertEqual(mutate.unwrap("((a = 1) AND (b = 2))"), "(a = 1) AND (b = 2)")
    self.assertEqual(mutate.unwrap("(a = 1) AND (b = 2)"), "(a = 1) AND (b = 2)")

  def test_a_refusal_loses_each_alternative_in_turn_or_goes_when_it_has_one(self):
    found = dict(mutate.guard_mutants(FUNCTION))
    self.assertEqual(sorted(found), ["caller1=anyone", "guard1-without-or1", "guard1-without-or2", "guard1-without-or3", "guard2=gone"])
    self.assertIn("if mine = 'member' or (f.role = 'manager' and mine <> 'owner') then", found["guard1-without-or1"])
    self.assertIn("if mine is null or mine = 'member' then", found["guard1-without-or3"])
    self.assertNotIn("not a member", found["guard2=gone"])
    self.assertIn("if found then return; end if;", found["guard2=gone"])  # an `if` that refuses nothing is left alone
    self.assertIn("where true;", found["caller1=anyone"])

  def test_a_policy_becomes_true_loses_a_condition_or_takes_members_for_managers(self):
    expr = "((ledger_id IN ( SELECT my_managed_ledgers() AS my_managed_ledgers)) AND (actor = ( SELECT auth.uid() AS uid)))"
    found = dict(mutate.clause_mutants(expr))
    self.assertEqual(sorted(found), ["members-for-managers", "true", "without-and1", "without-and2"])
    self.assertEqual(found["without-and2"], "(ledger_id IN ( SELECT my_managed_ledgers() AS my_managed_ledgers))")
    self.assertIn("my_ledgers()", found["members-for-managers"])


if __name__ == "__main__":
  unittest.main()
