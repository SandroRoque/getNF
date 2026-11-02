# Server changes (D158)

One line per migration or Edge Function change after `20261031000000_feedback_tickets.sql`, when it's written and
again when it's applied online. `tools/release.py check` fails for a migration that isn't listed here.

Format: `- <file>: <what> · compat: <which versionCodes still work, how checked> · applied: <YYYY-MM-DD> | pending`

Supported clients: every versionCode that reached someone through Play and hasn't been retired in docs/rollout.md
(today: 2 on the user's and the father's phones, 3 on internal testing).

