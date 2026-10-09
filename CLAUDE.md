# getNF: notes for Claude

Read `README.md`, then `docs/decisions.md` (the decision log), before making design choices. Add a new
entry to `docs/decisions.md` for every non-trivial decision. Never rewrite old entries.

## The user

New to modern Android (last touched it in the Java-only era). Wants to stay hands-off during development:
drive the app yourself in the emulator, and only ask for things that need hands or a decision
(BIOS, sudo, physical phone, real receipts, solving a Turnstile that won't pass in the emulator).
Explain Android-specific concepts briefly when they come up.

## Environment

- Always `. tools/env.sh` first (SDK tools aren't on the default PATH). `tools/doctor.sh` shows readiness.
- Gradle project is in `android/`: `cd android && ./gradlew assembleDebug testDebugUnitTest`.
- Emulator: `tools/emulator.sh [--headless] [--camera <video-or-photo>]`. The back camera plays that file. Restart to change it.
- Inspect/drive the device with the `android` CLI (`android screen`, `android layout`, `android run`) or adb.
  See `.claude/skills/android-cli`.
- PocketBase: `docker compose -f server/compose.yaml up -d`, admin creds in `server/.env.local`.
  From the emulator: `http://10.0.2.2:8090`. Schema changes go in `server/pb_migrations/` as migrations.
- `sudo` needs a password, so you can't run it. Put root steps in `tools/setup-system.sh` and ask the user to run it.

## Data & privacy

- `test-media/` holds real receipts and is gitignored. Don't commit it, and don't copy it into tracked test dirs as-is.
  Make reduced/sanitized fixtures if tests need them.
- Don't hammer `sat.sef.sc.gov.br`. Each automated request raises the chance of a Turnstile challenge.
  Use the saved pages in `test-media/pages/` for parser work.
