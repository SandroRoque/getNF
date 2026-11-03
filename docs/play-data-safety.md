# Google Play: Data safety answers (draft)

Drafted 2026-10-28 from what the app does today (D105). Play Console › App content › Data safety, in its own words
(English). Not legal advice; Google's definitions are at support.google.com/googleplay/android-developer/answer/10787469.
The matching privacy policy is [privacy-policy.md](privacy-policy.md).

## Before publishing (blocks some answers below)

1. ~~Cleartext traffic allowed app-wide~~: done 2026-10-28. Release builds are HTTPS only; debug builds also allow
   plain HTTP to a local Supabase (`10.0.2.2`, `localhost`, `127.0.0.1`).
   ~~Release builds have no server built in~~: done 2026-10-28. The release build carries the online project.
2. ~~A web page for account deletion~~: live since 2026-10-28 at https://caderneta.roqlab.com.br/apagar-conta/.
   The privacy policy is at https://caderneta.roqlab.com.br/privacidade/ (Play Console › App content › Privacy
   policy).
3. ~~Turning the photo opt-in off should delete what was sent~~: done 2026-10-28 (`sync/PhotoWithdrawal`).
4. ~~Publish the policy with a contact e-mail~~: done (see item 2).
5. The LGPD legal read (D104), mainly on data that stays in a shared caderneta after a member deletes their account.

## Overview questions

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Account, purchase history, and ML Kit's diagnostics. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (after item 1) | Supabase and the SDKs use HTTPS. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | In the app and on the web page (item 2). |
| Account creation methods | **Username and password** (e-mail and password) | Supabase Auth. The account is optional. |
| Delete account URL | https://caderneta.roqlab.com.br/apagar-conta/ | |
| Is your app's data deleted when the account is deleted? | Yes, with the caveat in the policy | Data in a caderneta others remain in stays with the caderneta; backups expire in 14 days. |

Neither "Committed to Play Families Policy" nor "Independent security review" applies.

## Data types

"Collected" means it leaves the device. Data processed only on the phone isn't declared. **Nothing is "shared":**
Supabase, TypeSafe (Jev), Open Food Facts and Google (ML Kit) act as service providers, and members of a caderneta
see its data because the user invited them (a user-initiated transfer). Both are exempt from "sharing" in
Google's definitions.

| Category › type | Collected | Shared | Processed ephemerally | Required or optional | Purposes |
|---|---|---|---|---|---|
| Personal info › Email address | Yes | No | No | Optional (only with an account) | App functionality, Account management |
| Personal info › Name | Yes (display name in a caderneta) | No | No | Optional | App functionality |
| Personal info › User IDs | Yes (account id) | No | No | Optional | App functionality, Account management |
| Financial info › Purchase history | Yes (receipts, items, prices, stores, payment method; during the closed test, with the opt-in and without an account, each receipt's SEFAZ page, D161) | No | No | Optional | App functionality, Analytics |
| Photos and videos › Photos | Yes (only with an opt-in, redacted: D103's failed receipts; during the closed test, every capture's photos, D161) | No | No | Optional | Analytics (improving the reading of receipts) |
| App activity › Other user-generated content | Yes (product names, categories, edits; comments the person sends, D152) | No | No | Optional | App functionality, Analytics |
| App info and performance › Diagnostics | Yes (ML Kit: error codes, events, performance; the refused-rows report when the person sends it, D152) | No | No | Required (happens whenever the camera reads a receipt) | Analytics |
| App info and performance › Other app performance data | Yes (ML Kit: latency, input/output sizes) | No | No | Required | Analytics |
| App info and performance › Crash logs | Yes (only when the person sends the report, D152) | No | No | Optional | Analytics |
| Device or other IDs | Yes (ML Kit: a per-installation id; the app's own random install id, with a report) | No | No | Required | Analytics |

**Not collected:** location (the store's address is on the receipt, but the device's location is never read), contacts,
messages, audio, files outside the app, calendar, health, web browsing, installed apps, search history, financial
account numbers. The CPF and the buyer's name are removed from pages before they're stored (D104); card slips and
CPFs are covered in uploaded photos (D98).

## Things worth knowing

- **Changed 2026-11-03 (D161), saved in Play Console and sent for review the same day** (Claude, with the user's
  go-ahead): Purchase history gained Analytics; Photos was set to "processed ephemerally: Yes" in the console, against
  this table, and is now "No" (the photos are stored), so it shows on the listing. The rest matched. Photos now go
  with or without an account during the closed test (every capture's, once the person agrees), and the receipt's page
  goes with them, under the random install id: Photos stays "Optional", its description widens; Purchase history is no
  longer "only with an account".

- **Changed 2026-10-31 (D152): the form must be updated in Play Console before the closed test's build.** Comments,
  crash reports and refused-rows reports now go to the project's own server when the person taps send (they used to
  go through another app the person picked). That makes them collected: **Crash logs** is a new row; **Other
  user-generated content**, **Diagnostics** and **Device or other IDs** gain them (and Analytics). Still optional and
  never shared; the account isn't attached. The privacy policy has a section for them.

- **Checked 2026-10-31 (D140–D146), superseded by D152 above:** The crash report (D140), the refused-rows
  report (D145) and "Enviar comentário" (D146) leave the phone only when the person taps send and picks an app
  (Android's share sheet, or their own e-mail app once there's a contact address): a user-initiated transfer through
  another app, not data the app collects. The crash file itself stays on the phone. The barcode lookup (D130) searches
  only the phone's own databases. Nothing new goes to the server.

- **ML Kit is the only reason for "Required" rows.** The bundled barcode and text models send diagnostics to Google
  (developers.google.com/ml-kit/android-data-disclosure). If that's unwelcome, there's no switch for it in the
  bundled libraries; the alternative would be other OCR/barcode libraries.
- **Purchase history is the sensitive one.** Receipts reveal where and when someone shops. It's optional (the app
  works fully without an account) and never used beyond the app's functions; the listing's description can say so.
- **The nightly LLM worker** runs on the maintainer's PC with access to every caderneta (D80). Fine to disclose as
  the operator's own processing; at scale it should move or become opt-in (see `docs/costs.md`).
- If the photo upload's purpose is judged "App functionality" rather than "Analytics" by a reviewer, either is
  defensible; the policy text matches "improving the app".
