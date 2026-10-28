# Google Play: Data safety answers (draft)

Drafted 2026-10-28 from what the app does today (D105). Play Console › App content › Data safety, in its own words
(English). Not legal advice; Google's definitions are at support.google.com/googleplay/android-developer/answer/10787469.
The matching privacy policy is [privacy-policy.md](privacy-policy.md).

## Before publishing (blocks some answers below)

1. **Cleartext traffic is allowed app-wide** (`res/xml/network_security_config.xml`, left from PocketBase). The
   "encrypted in transit: Yes" answer needs it limited to debug builds (the emulator's `10.0.2.2`) or removed.
2. **A web page for account deletion.** Play requires a URL where people can ask to delete their account without the
   app. The in-app "Apagar minha conta" exists.
3. **Turning the photo opt-in off should delete what was sent** (open item, D103); the policy promises it.
4. Fill in the policy's contact e-mail and publish it on the web (the roqlab subdomain).
5. The LGPD legal read (D104), mainly on data that stays in a shared caderneta after a member deletes their account.

## Overview questions

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Account, purchase history, and ML Kit's diagnostics. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (after item 1) | Supabase and the SDKs use HTTPS. |
| Do you provide a way for users to request that their data is deleted? | **Yes** | In the app and on the web page (item 2). |
| Account creation methods | **Username and password** (e-mail and password) | Supabase Auth. The account is optional. |
| Delete account URL | [the web page from item 2] | |
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
| Financial info › Purchase history | Yes (receipts, items, prices, stores, payment method) | No | No | Optional (only with an account) | App functionality |
| Photos and videos › Photos | Yes (only with the opt-in, failed receipts, redacted) | No | No | Optional | Analytics (improving the reading of receipts) |
| App activity › Other user-generated content | Yes (product names, categories, edits) | No | No | Optional | App functionality |
| App info and performance › Diagnostics | Yes (ML Kit: error codes, events, performance) | No | No | Required (happens whenever the camera reads a receipt) | Analytics |
| App info and performance › Other app performance data | Yes (ML Kit: latency, input/output sizes) | No | No | Required | Analytics |
| Device or other IDs | Yes (ML Kit: a per-installation id) | No | No | Required | Analytics |

**Not collected:** location (the store's address is on the receipt, but the device's location is never read), contacts,
messages, audio, files outside the app, calendar, health, web browsing, installed apps, search history, financial
account numbers. The CPF and the buyer's name are removed from pages before they're stored (D104); card slips and
CPFs are covered in uploaded photos (D98).

## Things worth knowing

- **ML Kit is the only reason for "Required" rows.** The bundled barcode and text models send diagnostics to Google
  (developers.google.com/ml-kit/android-data-disclosure). If that's unwelcome, there's no switch for it in the
  bundled libraries; the alternative would be other OCR/barcode libraries.
- **Purchase history is the sensitive one.** Receipts reveal where and when someone shops. It's optional (the app
  works fully without an account) and never used beyond the app's functions; the listing's description can say so.
- **The nightly LLM worker** runs on the maintainer's PC with access to every caderneta (D80). Fine to disclose as
  the operator's own processing; at scale it should move or become opt-in (see `docs/costs.md`).
- If the photo upload's purpose is judged "App functionality" rather than "Analytics" by a reviewer, either is
  defensible; the policy text matches "improving the app".
