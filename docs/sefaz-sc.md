# SEFAZ-SC NFC-e portal: field notes

Observed on 2026-10-08 from a residential IP, using the receipt in `test-media/`.

## QR code payload

```
https://sat.sef.sc.gov.br/nfce/consulta?p=<chave 44 digits>|2|1|1|<40-hex hash>
```

This is the NFC-e QR code v2 online format: `chave | QR version | environment (1 = production) | CSC id | hash`.
The 44-digit **chave de acesso** is also printed under "Consulte pela Chave de Acesso" on the receipt, so it can be typed in if the QR code won't decode.

Chave layout, with a made-up example (`42 2606 11222333000181 65 109 000001234 1 12345678 9`):
UF (42 = SC) · AAMM · CNPJ of issuer · model (65 = NFC-e) · série · número · tpEmis · cNF · DV.

## Request flow

1. `GET /nfce/consulta?p=...` → **307** to `/tax.net/Sat.Dfe.NFCe.Web/Consultas/ConsultaPublicaNFCe.aspx?p=...`
2. → **302** to `/tax.NET/Sat.DFe.NFCe.Web/Consultas/NFCe_Detalhes.aspx?rq=<opaque token>`
3. → **200** "NFCe - Detalhes" page (server: Microsoft-IIS, ASP.NET WebForms)

**Challenge:** the first plain `curl` went straight through. A second request seconds later was redirected to
`/tax.NET/SecurityVerify.aspx?rq=...` ("S@T - Verificação para prosseguimento da operação"). That page is
SEFAZ's own form with an embedded **Cloudflare Turnstile** widget (`challenges.cloudflare.com/turnstile/v0/api.js`, `cf-turnstile`).
So the challenge appears intermittently, likely triggered by rate or a risk score, and is not on every request.
It isn't a Cloudflare edge interstitial: the responses come straight from IIS.

Detecting it: final URL contains `SecurityVerify.aspx`, or the body contains `cf-turnstile`.
Fixture: `test-media/pages/sc-securityverify-turnstile.html`.

## Details page structure

This is the standard national NFC-e consultation layout, also used by many other states.

- `#tabResult` holds the items. Each item is a `tr` with `id="Item + N"`:
  - `.txtTit`: description
  - `.RCod`: "(Código: ...)"
  - `.Rqtd`: quantity
  - `.RUN`: unit (UN, KG...)
  - `.RvlUnit`: unit price
  - `.valor`: line total
- `#totalNota`: `#linhaTotal` rows (item count, totals, discounts, "Valor a pagar"), plus `#linhaForma` (payment method)
- `.chave`: the chave de acesso
- Issuer, emission date and protocol appear in the info sections further down.
- Brazilian number format (`2,98`); text in ISO-8859-1/UTF-8. Check the `meta charset` when parsing.

As you noted, the page has **only the total discount**. The per-item discounts ("Desconto: 0,10") exist only on the paper.

## The "Download" button (XML): needs a digital certificate

`#Body_conFooter_btnDownload` ("Baixar documento XML") is an ASP.NET postback (`__doPostBack('ctl00$Body$conFooter$btnDownload','')`).
Tested 2026-10-08 with a plain POST of the page's form fields: **302 to `/tax.NET/RequestClientCertificate.aspx`**, i.e.
it asks for an ICP-Brasil client certificate (e-CPF/e-CNPJ). Consumers don't have one for this, so the XML is out:
the details page HTML (plus the photos) is all we get.

## In the app's WebView (2026-10-08)

First real run from the emulator (Android 16 WebView, residential IP): QR URL → the two redirects → `NFCe_Detalhes.aspx`
with no challenge, in about 2 s. `onPageFinished` fires **twice** for the details URL, so the app dedupes by URL.
The DOM-captured page had the same 20 items as the curl fixture.

On the real phone (same day), a second receipt got the **SecurityVerify** page. It rendered blank at first. The portal's
layout is `html, body, form { height: 100% }`, with the content inside `.sat-window-content { height: 100%; overflow: auto }`.
Compose's `AndroidView` gives the WebView `WRAP_CONTENT` layout params, which puts WebView in size-to-content mode, so
`html` resolved to 0px and everything was clipped into the container's 20px padding. With `MATCH_PARENT` the page renders.
The Turnstile then passed **on its own** (showed "Success!" without a click). The user only taps the portal's
**Validar** button, and the app picks up the details page from there.

## Rate limit: "Aplicativo temporariamente indisponível"

Seen 2026-10-08 23:36 on the phone (home network, same public IP as the dev PC):
`https://sat.sef.sc.gov.br/static/appMaximoRequisicoesExcedidas.htm`: "O sistema detectou um número excessivo de
requisições deste IP. Aguarde alguns minutos..." This is separate from the Turnstile: it's a per-IP block, and only waiting helps.

What this IP had sent in the preceding ~75 minutes: 4 WebView page loads (22:20 emulator; 22:38, 22:40, 22:42 phone, two of
them by Claude while debugging the blank challenge page), the Validar postback, then 5 plain HTTP requests from the PC
for the XML test (GET with 2 redirects + 2 POSTs) between 22:57 and 23:31. A WebView load also pulls the portal's scripts
and stylesheets, so each "page view" is many requests if the limit counts those. The threshold and window are unknown.
The app shows a "wait a few minutes" message for this page (`SefazPage.Kind.RateLimited`).

**Measured 2026-10-09 00:03** (phone on mobile data, so a different IP; same WebView cookies): it went straight through,
challenge auto-passed → Validar → details. So the block is per IP, not per device or session. That one visit cost
`app.sat.sef.sc.gov.br=43, sat.sef.sc.gov.br=16` (plus 7 to Cloudflare): **~59 requests to SEFAZ for one receipt**, of
which only ~5 are pages/postbacks. The rest are the portal's scripts, styles and images, mostly re-fetched despite the cache.
The app now skips the ones it doesn't need (D16). Still unknown: whether the home IP is shared (CGNAT: check the
router's WAN address for 100.64.0.0/10), and whether the limit counts asset requests at all.

**Second block, 2026-10-09 00:18, on mobile data** (with D16's asset blocking active). The visits on that IP were 00:03
(challenge + Validar), 00:14, 00:15:10 (challenge + Validar), 00:15:58 (a re-scan of an already saved receipt, a wasted
lookup and since fixed), 00:17:31, then blocked at 00:18:01. Visits without a challenge fetched just **1** request from SEFAZ
(the 3-hop redirect chain behind it), and they still counted. In both blocks the limit was reached after roughly
5–7 hits on the lookup itself (page loads + Validar postbacks) within about an hour, no matter how many assets were fetched.
**Working hypothesis: about 5 lookups per IP per hour.** Asset blocking keeps visits light but doesn't raise that budget.
Plan for ~5 receipts per hour per network (home Wi-Fi and mobile data are separate budgets).

## Etiquette

Be gentle with the portal: one receipt at a time, a human in the loop, no scraping loops.
Each automated probe raises the chance of a challenge, and enough of them gets the whole network blocked for a while (above).
Debugging and experiments should use the saved pages, not the live portal. The phone and the dev PC share one IP.
