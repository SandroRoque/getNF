# Google Play Console: setting up the app

Answers for "Finish setting up your app", drafted 2026-10-30 when the app was created in the console (D113). Not
legal advice. Data safety has its own page: [play-data-safety.md](play-data-safety.md).

## Sending a build

Test and release › Testing › **Internal testing** › Testers: create an e-mail list (the user's own address to begin
with), save, and copy the opt-in link. Then Releases › Create new release › upload
`android/app/build/outputs/bundle/release/app-release.aab` (`cd android && ./gradlew :app:bundleRelease`), a
release name equal to `versionName`, notes in pt-BR, Save › Review release › Start rollout. Open the opt-in link on the phone,
accept, and install from Play. Internal testing doesn't need the 12 testers for 14 days that personal accounts need
before production; it's also not reviewed the way production is.

The phone's Play app may show an empty black sheet for the opt-in link. Accepting on the PC's browser (same Google
account) works; then install from the PC's Play page or open
`https://play.google.com/store/apps/details?id=br.com.roqlab.caderneta` on the phone.

The app was first created as `br.com.roqlab.caderneta1` (the "1" came from nowhere anyone could tell), got two
internal releases and an install, then was deleted and created again as `br.com.roqlab.caderneta` (D113). An id with
any install is never freed, so the "1" one is gone for good; its versionCodes don't carry over.

Each new upload needs a higher `versionCode` (`android/app/build.gradle.kts`). A code is taken as soon as a bundle is
uploaded, even into a draft that's discarded: to change anything, build again with the next code. Released so far:
0.0.1 (code 1, the app said 1.0) and code 2, which went out as "1.1" after a rebuild as 0.0.2 was refused for reusing
code 2. Next: code 3, `0.0.3`.

## App content

| Task | Answer | Why |
|---|---|---|
| Privacy policy | https://caderneta.roqlab.com.br/privacidade/ | |
| Sign-in details | Some functionality is restricted; give a review login | The app works without an account, but cadernetas and sync need one. Google asks for credentials whenever any part needs a login. A dedicated account (made by the user in the app, not a personal one), with instructions: "Toque no ícone da conta no alto de Início › Entrar ou criar conta, e entre". Add: "Sem uma nota fiscal de Santa Catarina, toque em Ver um exemplo na primeira tela (ou em Início) para ver o app com notas inventadas" (D115). |
| Ads | No | |
| Content rating | Category "All other app types"; no to violence, sexual content, language, drugs, gambling; users interact or share content: **yes** (a caderneta's members see each other's receipts); shares location: no; digital purchases: no | Expected result: Livre/Everyone, with "Users interact". |
| Target audience | 18 and over; doesn't appeal to children | It's about purchases and money; any age under 13 brings the Families policy. |
| Data safety | See [play-data-safety.md](play-data-safety.md) | |
| Government apps | No | The app reads SEFAZ's public consultation page but isn't made by or for a government. The listing says so (below). |
| Financial features | None of the listed features (no banking, loans, payments, investments, crypto) | If the form offers personal finance or budgeting, that one fits; it tracks spending from receipts and moves no money. |
| Health | No | |

## Organised and presented

- **Category:** Finance (spending from receipts). Shopping is the alternative.
- **Contact:** the same public e-mail as the site (`tools/build-web.py --contact`); website https://caderneta.roqlab.com.br.
- **Store listing** (needs, besides text): the 512×512 icon `docs/brand/icon-512.png`, the 1024×500 feature graphic
  `docs/brand/feature-graphic.png` (both from `tools/make-icons.py`, D114), and 2–8 phone screenshots. Screenshots:
  [screenshots/](screenshots/), from the demo caderneta, taken before D112 (they may show the old "JEV" mark).

### Listing text (draft, pt-BR)

The name and positioning are still being discussed (D105); this is enough for internal testing.

- **Short description** (≤ 80): Escaneie o QR da nota fiscal e veja seus gastos e sua inflação pessoal.
- **Full description:**

> Aponte a câmera para o QR code da nota fiscal (NFC-e) e o app guarda a nota inteira: cada item, o preço, a loja e
> os descontos. Com o tempo, você vê quanto gasta por categoria, como o preço de cada produto mudou e qual é a sua
> inflação pessoal, calculada com o que você realmente compra.
>
> • Lê notas fiscais de consumidor de Santa Catarina.
> • Descontos por item, lidos da foto da nota.
> • Gastos por mês, por categoria, por produto e por loja.
> • Funciona sem conta. Com conta, os itens novos ganham categoria sozinhos (você confirma ou corrige), as notas
>   ficam salvas online e podem ser compartilhadas com a família numa caderneta.
>
> Privacidade: as fotos ficam no seu celular. O CPF e o nome do comprador são removidos antes de salvar. Você pode
> apagar sua conta e seus dados quando quiser, no app ou em caderneta.roqlab.com.br/apagar-conta.
>
> Este app não é da SEFAZ nem de nenhum órgão do governo. Ele abre a página pública de consulta da nota, a mesma que
> você abriria pelo navegador.
