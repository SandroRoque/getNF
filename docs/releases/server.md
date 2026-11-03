# Server changes (D158)

One line per migration or Edge Function change after `20261031000000_feedback_tickets.sql`, when it's written and
again when it's applied online. `tools/release.py check` fails for a migration that isn't listed here.

Format: `- <file>: <what> · compat: <which versionCodes still work, how checked> · applied: <YYYY-MM-DD> | pending`

Supported clients: every versionCode that reached someone through Play and hasn't been retired in docs/rollout.md
(today: 2 on the user's and the father's phones, 3 on internal testing).
- 20261103000000_test_contributions.sql + Edge Function `contribute` (D161): a private bucket and a table the function writes; nothing for anon or authenticated · compat: additive, codes 2 and 3 don't call it (pgTAP with the migration, local function exercised) · applied: 2026-11-03
