# Server changes (D158)

One line per migration or Edge Function change after `20261031000000_feedback_tickets.sql`, when it's written and
again when it's applied online. `tools/release.py check` fails for a migration that isn't listed here.

Format: `- <file>: <what> · compat: <which versionCodes still work, how checked> · applied: <YYYY-MM-DD> | pending`

Supported clients: every versionCode that reached someone through Play and hasn't been retired in docs/rollout.md
(today: 2 on the user's and the father's phones, 3 on internal testing).
- 20261103000000_test_contributions.sql + Edge Function `contribute` (D161): a private bucket and a table the function writes; nothing for anon or authenticated · compat: additive, codes 2 and 3 don't call it (pgTAP with the migration, local function exercised) · applied: 2026-11-03
- 20261109000000_contribution_withdrawal.sql + Edge Function `contribute` (D172, T-9, T-10): a withdrawal is recorded first and refuses that install id from then on; it removes what Storage lists under the install, not what the rows say; a file stored while a withdrawal lands is taken back · compat: additive for codes 2–6 (withdraw is the same call); codes 4–6 keep their id after turning contributions on again, so what they send after a withdrawal is refused (400) and dropped, never stored (pgTAP; the function under Deno against the local stack: 1,001 objects with 500 rows, a late send, a withdrawal landing mid-send) · applied: 2026-11-09 (db push and the function deployed; smoke test online with a throwaway id: withdraw 200, a send after it 400, nothing stored, its record removed)
- 20261109010000_line_surcharge.sql (D175, T-13): `receipt_line.surcharge_cents`, a line's share of the receipt's surcharge · compat: additive with a default; codes 2–6 don't send it, and their upsert names only the columns they know, so a line they push again keeps it; they ignore it when pulling (pgTAP; `SupabaseLocalTest` with the migration) · must be applied before a build with Room 15 is uploaded · applied: pending
- 20261111000000_line_discount_source_default.sql (D182, review L1): `receipt_line.discount_source` gets a default, so a line upsert that leaves out the corrections it didn't change passes NOT NULL · compat: additive; codes 2–6 always name the column (pgTAP `line_merge.test.sql` and `SupabaseLocalTest` against the local stack, 2026-11-11) · must be applied before a build with D182 is uploaded · applied: pending
