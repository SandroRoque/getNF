# Running costs: an estimate

Written 2026-10-28 for the free/crowdfunded question (D105). These are estimates from today's usage and published
prices, not a bill. A dashboard of real costs is wanted later.

## What runs, and who pays for it

| Piece | What it does | Paid how |
|---|---|---|
| Supabase (São Paulo) | Database, auth, storage, the `categorize` function, pg_cron | Free tier today (D63); Pro US$25/month |
| Jev (TypeSafe) | Categorizes new catalog items (D67, D74) | Per token: US$0.042 per million input tokens (D67) |
| Open Food Facts | Product names for GTINs, fed to Jev (D68) | Free |
| SEFAZ portal | The receipt pages | Free, but rate-limited per IP |
| LLM worker | Nightly audit and suggestions (D80) | The user's PC with LM Studio: electricity only |
| Backups | `tools/supabase-backup.py` at 03:30 | The user's PC and disk |
| Google Play | Publishing | US$25 once |
| Web home | Subdomain of roqlab.com.br on Cloudflare | Free; a `.com.br` of its own is R$40/year at registro.br |

## Measured today (one user, 11 receipts, 4 months)

- Database: 16 MB in total, of which 1.9 MB is the app's tables. Most of it is Supabase's own schemas and fixed per
  caderneta (the taxonomy: 76 categories).
- Storage: 18 MB in 276 files. Pages average **44 KB** per receipt. Photos average 120 KB each, but 145 of them are
  from before D103; now photos go up only when the user opts in, and only for receipts the app failed with.
- Categorize: 12 jobs, all done. The function is called only while jobs are waiting, so an idle minute costs nothing.
- A line takes about 1 KB of database with its indexes (an upper bound at this size).

## Assumptions per household (one caderneta)

- 20 receipts a month (a couple a day at most, per D105), about 14 lines each.
- Database: ~25 KB per receipt (lines, header, new catalog rows), so **~0.5 MB a month, ~6 MB a year**.
- Storage: pages at 44 KB, plus opted-in photos of failed receipts: **~1 MB a month, ~12 MB a year**.
- Egress: deltas on each sync, other members' pages, a full download on a new phone: ~2 MB a month.
- Jev: about 50 new items a month early on, fewer later (a household rebuys the same things). At ~2,500 tokens per
  item with context, **~US$0.005 per household a month**.

## By size

| | 10 households | 100 households | 1,000 households |
|---|---|---|---|
| Database after a year | ~60 MB | ~600 MB | ~6 GB |
| Storage after a year | ~120 MB | ~1.2 GB | ~12 GB |
| Egress a month | ~20 MB | ~200 MB | ~2 GB |
| Supabase plan | Free fits for years | Pro: Free's 500 MB database and 1 GB storage run out within the first year | Pro fits; database overage after ~16 months at US$0.125/GB |
| Supabase a month | US$0 | US$25 | US$25–30 (a Small instance if Micro is tight: +US$5) |
| Jev a month | < US$0.10 | ~US$0.50 | ~US$5 |
| **Total a month** | **~US$0** | **~US$26** | **~US$30–40** |

Plus US$25 once for Play, and R$40/year if the project gets its own domain.

**In short:** the cost is a fixed ~US$25/month from about 50–100 households on, and each household after that adds
about US$0.01–0.02/month. Roughly **US$300–450 a year covers up to a thousand households.** Edge function calls
(at most one a minute, about 43,000 a month) and sign-ins (50,000 a month free, 100,000 on Pro) stay far inside the
quotas at every size.

## What the table doesn't cover

- **The nightly LLM worker doesn't scale.** It runs on the user's PC with a local model, one pass per caderneta. For
  a few households that's fine; for hundreds it needs either a hosted model (an unknown cost, likely cents per
  household a month) or to become a per-caderneta opt-in. To decide before opening up.
- **Backups depend on the user's PC.** The free tier has no backups of its own; Pro keeps 7 days of daily backups,
  which would back up the PC's copy.
- **Vendor risk on Jev:** a small hosted API priced in D67; a price change or shutdown means falling back to the LLM
  or another classifier. The cost is small either way; the work isn't.
- **The free tier pauses** a project after a week without requests. With people syncing daily it won't, but a quiet
  test period could trigger it.
- **No support, moderation or legal costs** are counted (the LGPD read in D104/D105 may cost money).
- Prices checked 2026-10-28 at supabase.com/pricing; Jev's from D67 (2026-10-25). Amounts in US dollars; convert at
  the day's rate.
