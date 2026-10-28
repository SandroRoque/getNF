# Portfolio brief: the getNF entry on roqlab.com.br

For an agent working in the portfolio repo (`/home/roque/Documents/Projects/roqlab`). Written 2026-10-28 (D105).
Everything here is safe to publish: the screenshots and numbers come from the made-up demo caderneta, not real
receipts.

## What's wrong with the current entry (`index.html`, `#getnf`)

- **The screenshot** (`assets/getnf.webp`) is the PocketBase web dashboard, retired in D73/D79. The product is now
  the Android app; there's no web dashboard.
- **"Extração e categorização acontecem no servidor"** is no longer true: the page is read on the phone (D49) and
  the app works without an account. Only categorization of new items runs online.
- **Tech note** "Android · Kotlin · PocketBase · JavaScript" is out of date.
- **The demo section** (month × category over three months) can stay as an idea but should use the numbers below,
  and the IPCI is a stronger thing to let people play with.
- The roqlab README says the Android screenshot was pending because no emulator was available; it's available now.

## Keep as is

- The name **getNF** for now. A new name is being chosen; renaming the entry twice is worse than waiting.
- Status "Em desenvolvimento": not on the Play Store yet.
- No link to the code repository.
- A link to the project's site, https://caderneta.roqlab.com.br (since 2026-10-28), in place of "Publicação em breve".
- The section structure (intro, product shot, flow, demo, "Por trás do produto" with three decisions, tech note).
- "Interface real · dados fictícios" captions: true for these screenshots.

## Suggested copy (Portuguese, the site's tone; adjust freely)

- **Eyebrow:** 02 / NOTAS E DESPESAS
- **Lead:** Da nota fiscal no papel ao preço de cada coisa que você compra.
- **Intro:** Um app Android que lê o QR Code da NFC-e, busca a nota na SEFAZ e transforma cada item em histórico:
  quanto você gasta, em quê, onde, e quanto os preços que você paga subiram. Para uma pessoa ou para a casa toda.
- **Flow:** 01 Ler o QR Code → 02 Buscar a nota na SEFAZ → 03 Ver gastos, preços e inflação pessoal
- **Product shot caption:** "Seus gastos do mês, por categoria." / "Interface real · dados fictícios"
- **Demo heading:** "Sua inflação, não a média." Copy: "A inflação oficial mede a cesta de todo mundo. O IPCI
  compara o preço que você pagou por cada produto com o da sua compra anterior, por quilo ou litro quando dá."
- **"Por trás do produto" heading:** "O celular primeiro. O dado honesto."
  1. **A captura se planeja sozinha.** Enquanto a câmera passa pela nota, o app lê as linhas impressas, monta a
     nota e guarda só as fotos que acrescentam algo. No fim, fica o menor conjunto de fotos que lê a nota inteira.
  2. **Funciona sem servidor.** A página da SEFAZ é lida no próprio celular; as fotos ficam nele. Com uma conta, a
     caderneta sincroniza e pode ser dividida com a família, cada um vendo tudo.
  3. **Inflação pessoal que não inventa número.** Cada produto é comparado com o próprio preço anterior, e as
     variações pesam pelo que você gastou. Trocar de marca muda o gasto, não a inflação. Mês com poucos dados
     comparáveis não tem leitura.
- **Tech note:** Android · Kotlin · Jetpack Compose · Room · Supabase · Postgres · Deno
- **Footer line:** "Da nota no papel à inflação pessoal, em evolução."

## Screenshots (`docs/screenshots/` in the getNF repo, 1080×2400 WebP)

| File | Shows | Suggested alt text |
|---|---|---|
| `inicio-light.webp` | Início: October R$ 798,77 by category, 3 decisions, personal inflation +0,9% | Tela inicial do getNF com os gastos de outubro por categoria e a inflação pessoal do mês, com dados fictícios. |
| `gastos-light.webp` | Gastos: six-month bars, categories with shares, top products | Tela de gastos com barras de maio a outubro e as categorias do mês. |
| `ipci-light.webp` | IPCI: +0,9%, chained index chart, month by month, how it's computed | Inflação pessoal de outubro com gráfico do índice encadeado e a variação de cada mês. |
| `produto-light.webp` | Café Montanha: R$ 58,20/kg, +16,1% since May, one price line per store | Histórico de preço de um café por quilo, com uma linha por loja. |
| `nota-light.webp` | A receipt: store, total, each line with quantity × price | Uma nota fiscal lida pelo app, com cada item, quantidade e preço. |
| `catalogo-light.webp` | Catálogo › Produtos: named products, category, purchases, "AUTO" marks | Catálogo de produtos com categoria, número de compras e total gasto. |
| `paravoce-light.webp` | Para você: three category suggestions with confidence | Sugestões de categoria aguardando confirmação, com o grau de confiança. |
| `*-dark.webp` | Início, IPCI and the product in dark mode | (same as light) |

Best single shot for the product figure: `inicio-light.webp` or `ipci-light.webp`. The current figure is landscape
(1360×1000) inside a window frame; these are phone portrait, so the figure needs a phone frame or a pair of shots
side by side. The site's getNF palette (`app.js`: accent `#255D4C`) is close to the app's; the app's own accents are
the green dot and the terracotta for price increases.

## Data for an interactive demo (from the demo caderneta)

Net spending by top-level category, R$ (October is up to the 27th):

| Month | Alimentos e mercado | Bebidas | Produtos para casa | Cuidados pessoais | Sem categoria |
|---|---|---|---|---|---|
| maio | 859,54 | 166,22 | 45,21 | 46,51 | 122,36 |
| junho | 751,78 | 114,59 | 78,05 | 28,46 | 66,07 |
| julho | 813,66 | 158,54 | 65,19 | 49,13 | 49,81 |
| agosto | 754,99 | 148,97 | 81,44 | 27,13 | 65,90 |
| setembro | 782,35 | 122,32 | 85,14 | 57,63 | 88,09 |
| outubro | 498,31 | 107,75 | 55,92 | 50,21 | 86,58 |

IPCI month by month (May is the base): junho −1,0% · julho +2,1% · agosto −0,6% · setembro +1,8% · outubro +0,9%.
The dips are months where a store discount lowered what was paid, which the app counts as a lower price.

Café Montanha 500 g, price per package: 02/05 25,06 · 16/05 25,30 · 23/05 25,11 · 06/06 25,45 · 04/07 26,19 ·
11/07 26,60 · 08/08 27,48 · 05/09 28,12 · 12/09 28,11 · 03/10 29,02 · 24/10 29,10.

## Don't claim

- That it's on the Play Store, open source, or free forever (it's free for now; nothing else is decided).
- Support beyond Santa Catarina: only the SC portal is read.
- That photos go to a server: they stay on the phone unless the user opts in to send failed ones.
- Any number from real use (the user's own receipts are private).

## Regenerating

The demo caderneta is `android/app/src/test/java/io/github/sandroroque/getnf/demo/DemoCaderneta.kt` (seeded, so the
same every run). See `docs/screenshots/README.md`.
