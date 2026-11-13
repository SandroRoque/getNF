# Google Play Console: setting up the app

Answers for "Finish setting up your app", drafted 2026-10-30 when the app was created in the console (D113). Not
legal advice. Data safety has its own page: [play-data-safety.md](play-data-safety.md).

## Sending a build

**Since D159, through the API:** `tools/play.py status` shows the tracks; `tools/play.py upload <aab> --track internal
--name <versionName> --notes-file <file> --commit` sends a bundle recorded in `docs/releases/<code>.md`; `promote
<code> --track alpha … --commit` puts it on the closed track (that sends it for review). Credentials:
`tools/setup-play-api.sh`, then the invite in Users and permissions. The steps below are the console's way.

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
code 2. Next: code 3, `0.1.0` (the closed test's first build).

After uploading, tag the commit the bundle was built from (commit first: never build a release from uncommitted
changes) and push the tag (D139):

```bash
git tag -a play/3 -m "Play closed testing: versionCode 3, versionName 0.1.0" && git push origin play/3
```

Every bundle has a manifest in [releases/](releases/) (its sha, contents, checks and Play events); the way from
cutting one to production is [rollout.md](rollout.md) (D158).

`git tag -n1 -l 'play/*'` lists what went out; `git diff play/2 play/3` is what changed between two releases.

## Opening the closed test (D153)

What makes the site's two steps work: a closed track whose testers are the Google Group, with a reviewed release.

1. **Before:** every "Set up your app" task on the Dashboard done (App content below, the store listing with its
   icon, feature graphic and screenshots). A closed release can't be sent for review without them.
2. Test and release › Testing › **Closed testing** › the default track (or Create track) › Manage track.
3. **Countries / regions:** Brazil, and only Brazil on every track. Never the EEA or the UK: the account is a
   non-trader because of that (D166).
4. **Testers:** Google Groups, `testadores-da-caderneta@googlegroups.com`; feedback URL or e-mail:
   `contato@roqlab.com.br`. Save. The page then shows the opt-in link ("Join on the web"): it must match the site's
   `https://play.google.com/apps/testing/br.com.roqlab.caderneta`.
5. **Releases › Create new release:** either "Add from library" (a bundle already uploaded to internal testing,
   promoted as is) or a new bundle (the next versionCode, tagged `play/<code>`). Release name = versionName, notes in
   pt-BR (below). Next › Save › Publishing overview › **Send changes for review**.
6. **Review:** Google reviews the first closed release; it can take from hours to several days. Until it's approved
   the opt-in link says the app isn't available. After that, anyone in the group opts in and installs. Joining the
   group only makes someone eligible: they count once they accept the opt-in link.
7. **The count (corrected 2026-11-02, D158):** when applying for production, at least 12 testers must be opted in
   and must each have been opted in **continuously for the preceding 14 days**. It's per tester: someone who leaves
   and rejoins starts their own 14 days again, and swapping people while keeping 12 a day doesn't add up. New
   releases during the test don't restart it. Meeting it lets the user apply; Google decides, and can ask for more
   testing (too few testers, or too little use). Recruit more than 12, so a dropout doesn't cost two weeks
   ([Play's page](https://support.google.com/googleplay/android-developer/answer/14151465)).
8. **The phone's Play account (2026-11-03, D162):** the phone has several Google accounts and Play shows the app only
   to the one opted in to a test ("Item not found" otherwise); switch Play's account (the avatar) before checking a
   build there. A sideload can't replace the Play install: Play re-signs with its own key (certificate 5228…), the
   upload key is ab75…, and Android refuses an update signed by another key (only an uninstall, which wipes the
   receipts, would get past it).
9. **The user's own account:** an account opted in to internal testing can't receive a closed (or open) test. To
   check the closed track as a tester sees it, opt out of internal testing first, then use the closed opt-in link
   ([Play's page](https://support.google.com/googleplay/android-developer/answer/9845334)).

## App content

| Task | Answer | Why |
|---|---|---|
| Privacy policy | https://caderneta.roqlab.com.br/privacidade/ | |
| Sign-in details | Some functionality is restricted; give a review login | The app works without an account, but cadernetas and sync need one. Google asks for credentials whenever any part needs a login. A dedicated account (made by the user in the app, not a personal one), with instructions: "Toque no ícone da conta no alto de Início › Entrar ou criar conta, e entre". Add: "Sem uma nota fiscal de Santa Catarina ou do Rio de Janeiro, toque em Ver um exemplo na primeira tela (ou em Início) para ver o app com notas inventadas" (D115). |
| Ads | No | |
| Content rating | Contact contato@roqlab.com.br; category "All other app types"; downloaded rating-relevant content: no; users interact or share content: **yes** (a caderneta's members see each other's receipts), user content the primary source: no, public nudity or violence: no, can block users: **yes** (an owner removes a member), can report: no, chat moderation: no, limited to invited people: **yes**; content from outside the download: no (the SEFAZ page is the person's own receipt); age-restricted products: no; shares location: no; digital purchases: no; cash rewards: no; browser or search: no; news or education: no | **Submitted 2026-11-02:** ClassInd Livre, ESRB Everyone, PEGI 3, IARC 3+, all with "Users interact". |
| Target audience | 18 and over only; "restrict minors" left off | It's about purchases and money; 13–15 already brings a Families-policy warning (some countries count them as children), and teens' data would need its own paragraph in the privacy policy (LGPD). Teens can still install it. **Saved 2026-11-02.** |
| Data safety | See [play-data-safety.md](play-data-safety.md) | |
| Advertising ID | No (**saved 2026-11-02**) | The release APK asks for no `AD_ID` permission (checked with aapt2 on 0.1.0) and the app shows no ads. |
| Government apps | No | The app reads SEFAZ's public consultation page but isn't made by or for a government. The listing says so (below). |
| Financial features | "My app doesn't provide any financial features" | The form has no budgeting or personal-finance option; the app tracks spending from receipts and moves no money. **Saved 2026-11-02.** |
| Health | "My app does not have any health features" | **Saved 2026-11-02.** |

## Organised and presented

- **Category:** Finance (spending from receipts). Shopping is the alternative.
- **Contact:** `contato@roqlab.com.br` (D154), the same as the site (`tools/build-web.py --contact`); website https://caderneta.roqlab.com.br.
- **Store listing** (needs, besides text): the 512×512 icon `docs/brand/icon-512.png`, the 1024×500 feature graphic
  `docs/brand/feature-graphic.png` (both from `tools/make-icons.py`, D114), and 2–8 phone screenshots. Screenshots:
  [screenshots/play/](screenshots/play/), from the demo caderneta, retaken 2026-10-31 after D141–D146, in upload
  order (Início, Gastos, a product, the IPCI, Para você, a receipt, Catálogo, Início in dark mode).
  Play's form asks for 16:9 or 9:16 and these are 1:2 (1080×2160): the uploads are
  [screenshots/play-9x16/](screenshots/play-9x16/), the same shots padded at the sides to 1215×2160 in the screen's
  own background (2026-11-02, ffmpeg `pad`).

### Listing text (pt-BR, for the closed test, 2026-10-31)

Name: **Caderneta** (kept for now, D105). Checked against the app as of D146; nothing promised that it doesn't do.

- **Short description** (75 of 80): Escaneie a nota fiscal e acompanhe seus gastos, seus preços e sua inflação.
- **Full description** (about 1,500 of 4,000):

> Aponte a câmera para o QR Code da nota fiscal (NFC-e) e o Caderneta guarda a nota inteira: cada item, o preço, a
> loja e os descontos. Com o tempo, você vê para onde vai o dinheiro e como mudam os preços do que você compra.
>
> • Gastos por mês e por ano, por categoria, por produto e por loja.
> • O histórico de preço de cada produto, loja a loja, com o menor preço à vista.
> • Pelo código de barras: aponte a câmera para um produto e veja quanto você pagou por ele e onde.
> • Sua inflação pessoal (IPCI), calculada com o que você realmente compra.
> • Descontos por item, lidos da foto da nota.
> • Funciona sem conta. Com uma conta, os itens novos ganham categoria sozinhos (você confirma ou corrige), as notas
>   ficam guardadas online e dá para dividir uma caderneta com quem mora com você.
>
> Por enquanto, lê as notas fiscais de consumidor de Santa Catarina e do Rio de Janeiro. Sem uma nota de SC ou do
> RJ, toque em "Ver um exemplo" para conhecer o app com notas inventadas.
>
> Privacidade: as fotos das notas ficam no seu celular. Só durante o teste fechado, e só se você aceitar, elas são
> enviadas para melhorar a leitura, com o cartão e o CPF cobertos; na versão pública, não. O CPF e o nome do
> comprador são removidos antes de salvar.
> Você pode apagar sua conta e seus dados quando quiser, no app ou em caderneta.roqlab.com.br/apagar-conta.
>
> Este app não é da SEFAZ nem de nenhum órgão do governo. Ele abre a página pública de consulta da nota, a mesma que
> você abriria pelo navegador.

- **Release notes, 0.1.0** (code 3; Play shows up to 500):

> Primeira versão do teste fechado. Novidades: lê também as notas do Rio de Janeiro; procure um produto pelo código
> de barras; uma compra por nota, mesmo quando a loja registra o item duas vezes; o preço por unidade em cada compra;
> deslize o dedo no gráfico para ver cada preço; abas e botões mais fáceis de tocar; o app avisa quando fechou sozinho
> e o que não sincronizou; envie comentários em Ajustes › Enviar comentário.

- **Release notes, 0.1.1** (code 4; Play shows up to 500):

> Só durante o teste fechado, o app pergunta uma vez se pode enviar as fotos de cada captura e a página da SEFAZ de
> cada nota, para melhorar a leitura. Vão pelo Wi-Fi, em preto e branco, com o cartão, o CPF e o operador cobertos, e
> sem a sua conta. É temporário: na versão pública, as fotos ficam só no celular. Dá para desligar em Ajustes, e o
> que já foi enviado é apagado. O nome do operador nas notas do RJ agora também é coberto.

- **Release notes, 0.1.2** (code 5; Play shows up to 500):

> Correções na sincronização de cadernetas divididas: quando duas pessoas editam o mesmo produto, cada uma mantém o que mudou (o nome de uma e o tamanho da outra, por exemplo); um item que sai de uma nota sai também nos outros celulares; e cadernetas com muitas notas agora são baixadas por inteiro.

- **Release notes, 0.1.3** (code 6; Play shows up to 500; the first draft was too technical, rewritten with the user):

> Correção: a SEFAZ de Santa Catarina bloqueia a sua internet por um tempo depois de umas 5 consultas por hora, e o app deve avisar antes disso. Ao ler o QR Code, esse aviso chegava tarde ou nem aparecia; agora aparece na hora certa. E, para quem aceitou enviar as fotos do teste, as notas do Rio de Janeiro não mandam mais uma tela de carregamento desnecessária junto.

- **Release notes, 0.1.4** (code 7; Play shows up to 500; approved by the user, uploaded 2026-11-12):

> Novidade: dá para apagar uma nota (Mais › Apagar, na nota); numa caderneta dividida, ela some dos outros celulares também. Correções: o acréscimo de uma nota entra nos gastos, e um desconto digitado não passa mais do desconto da nota. A correção feita num celular não some mais quando outro lê a nota de novo. Cópia de segurança e sincronização mais confiáveis. Para quem envia as fotos do teste, mais dados pessoais ficam cobertos, e desligar o envio apaga tudo de vez.

- **Release notes, 0.1.5** (code 8; Play shows up to 500; approved by the user, uploaded 2026-11-12):

> Correções: o desconto de cada item agora é lido das fotos em qualquer formato de nota, inclusive as do Rio que imprimem "desconto item" com o valor na coluna da direita. Na captura, o total de linhas não cresce mais ao passar de novo pelos itens vendidos a peso. Quando faltam poucas linhas, a tela diz qual falta, com o texto da própria nota, e aponta para ela; lidas todas, pede para mostrar o fim do papel.

- **Closed testing › testers** (D153): Google Groups, `testadores-da-caderneta@googlegroups.com`
  (https://groups.google.com/g/testadores-da-caderneta: anyone can join, members and conversations visible to
  managers only, posts by managers only). The opt-in link the site sends people to is
  `https://play.google.com/apps/testing/br.com.roqlab.caderneta`; check it against the one the console shows.
- **Closed testing › feedback channel** (shown to testers): `contato@roqlab.com.br` (D154). The app's
  own "Enviar comentário" goes to the server's inbox (D152), which is the channel to point testers to.
- **Review login instructions** (App content › App access), in English for the reviewer:

> The app works without an account; the account above unlocks cadernetas and sync. Sign in: account icon at the top
> of Início › Entrar ou criar conta › Entrar. Without a receipt from Santa Catarina or Rio de Janeiro (Brazil), tap
> "Ver um exemplo" on the first screen (or on Início while there are no receipts) to explore the app with made-up
> receipts. Barcode lookup: Catálogo › "Pelo código de barras" (finds products already bought in the receipts on the
> phone).

  The field takes 500 characters (this is 462). The account: `contato+revisao@roqlab.com.br` (subaddressing on, its
  mail reaches the user's Gmail), name "Review account"; the password only in the console and the user's password
  manager.