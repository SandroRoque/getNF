# Bloco de notas de 2026-11-15, expandido

Nenhuma correção feita. Cada item traz: o que entendi, o que o código/a documentação mostram (verificado em
`claude/app-issues-expansion-337d74`, a partir de `19fb3f3`), o que ainda está ambíguo e uma direção sugerida.
Nenhum ticket foi aberto (`tools/tickets.py new` fica para quem for corrigir). Itens com 🔶 têm uma pergunta para o
usuário antes de mexer.

## Correções feitas (2026-11-15, branch `claude/tickets-notes-fixes`)

| # | Ticket | O que mudou | Decisão |
|---|---|---|---|
| 1 | T-60 | "Raízes e tubérculos" (`food.produce.roots_tubers`) e palavras no léxico do Jev; migrações ainda não aplicadas online | D205 |
| 3 | T-57 | "Sem categoria" e "(sem subcategoria)" filtram a lista de produtos | D205 |
| 4 | T-58 | IPCI sem leitura diz a regra, os números do último mês e o que ajuda; cada mês diz o limite que faltou | D205 |
| 7 | T-59 | Linha com fatia R$ 0,00 aparece "sem desconto", não "rateado" | D205 |
| 8 | — | "Terminar com o que foi lido" na saída da captura; os outros dois botões dizem o que fica | D203 |
| 9 | T-55 | Sergipe: HTTP liberado para portais `gov.br` (a página volta a abrir; continua não lida) | D204 |
| 10 | T-56 | Nota de outro estado avisa antes de abrir o portal ("Notas de São Paulo ainda não são lidas") | D204 |

Sem mudança: #2 (descartado), #5 e #6 (deixados de lado pelo usuário). Ler as notas de SP de verdade continua em aberto.

## Resumo

| # | Nota | Natureza | Confirmado no código? |
|---|---|---|---|
| 1 | Categorias: licor, balas, aipim/mandioca/tubérculos | taxonomia (seed) | sim, faltam nós |
| 2 | Navegação de meses em Gastos "esquisita" | UX | parcialmente; a nota está cortada 🔶 |
| 3 | "Sem categoria" não abre | bug de UX | sim: linha sem toque |
| 4 | "Ainda poucos produtos comprados de novo…" | texto/UX do IPCI | sim: mensagem vaga |
| 5 | Variação de preço olha um ponto só no passado | método do IPCI | sim: por desenho (D79) 🔶 |
| 6 | 10 un. em janeiro, 1 un. em dezembro | método do IPCI | sim: caso real do método |
| 7 | Produto sem desconto aparece "rateado" | semântica/UX | sim: por desenho, enganoso |
| 8 | Guardar só o código × descartar a captura | UX | sim: quase indistinguíveis 🔶 |
| 9 | Capturas da SEFAZ do Rio estão falhando | bug (SEFAZ-RJ/captura) | em parte; precisa de dados 🔶 |
| 10 | Nota de São Paulo abre a SEFAZ-SP e não entra | escopo (estado não suportado) | sim: é o esperado hoje |

## Atualização depois das respostas do usuário (vale mais que o texto abaixo)

- **#1 DECISÃO FINAL: balas/licor não muda nada** (a categoria que o pai viu não existe online; fica só a captura de
  tela dele, se aparecer de novo). **Resta apenas "Raízes e tubérculos"** (aipim, mandioca, batata, inhame).
  O parágrafo a seguir é o histórico da investigação.
- **#1 Licor e balas (corrigido pelo usuário, 2ª resposta):** não é pedido de uma categoria de licores. A palavra
  "licor" **não deve aparecer junto de balas**; o rótulo certo para balas seria "Balas e doces" ou "Balas e
  bombons", conforme as categorias vizinhas. No repositório e no Supabase online **nenhuma categoria contém
  "licor"** (busca em código, migrações e na tabela `category`): o rótulo hoje é "Chocolates e balas" (em
  `food.snacks_sweets`, ao lado de Biscoitos, Salgadinhos, Sorvetes; o pai se chama "Lanches e doces" e há "Bolos e
  doces" em outro ramo). Quem viu foi o **pai do usuário** (caderneta compartilhada); o Supabase tem 304 linhas de categoria e 76 rótulos
  distintos, nenhum com "licor", então a categoria deve existir só no telefone dele (criada ou renomeada à mão e
  ainda sem sincronizar, ou sincronização parada) ou ele lembra mal. Pedir uma captura de tela. Origem do "licor"
  ainda desconhecida: o usuário viu em algum lugar (talvez um produto tipo
  "bombom de licor" mal categorizado, a sugestão do Jev ou um texto na tela); perguntar onde. Quanto ao nome:
  "doces" colide com o pai e com "Bolos e doces"; "Balas e bombons" ou manter "Chocolates e balas" são os que não
  colidem. Decisão de nome é do usuário. Licor (bebida) pode ir para Destilados ou ganhar nó próprio, **separado e
  sem relação com balas**; é opcional. Continua faltando **Raízes e tubérculos** (aipim, mandioca, batata, inhame).
- **#2 Meses em Gastos:** o usuário não lembra; talvez seja um bug que gravou em vídeo. **Descartado** até aparecer
  de novo (as observações do código ficam como pistas).
- **#5 Variação contra um ponto só:** o usuário não lembra, **ignorar** (#6 segue como caso de teste, sem pressa).
- **#7 Rateado:** o problema é outro do que escrevi: o rótulo **"rateado" continua em linhas cujo desconto já foi
  verificado pelo OCR das fotos**. Causa provável, confirmada em `Derive.lineDiscounts`: toda linha que o leitor
  de fotos não marcou como `photo` recebe a fonte `PRORATED`, mesmo quando o total já foi inteiro achado
  (`total − known = 0`, então a fatia é R$ 0,00). Com veredito COMPLETO (`PhotoDiscounts.Verdict.COMPLETE`) as
  demais linhas **não têm desconto**, mas a tela (`ReceiptScreen.discountSource`) e as contas (IPCI "com desconto
  rateado", Gastos "desconto rateado (estimado)") só olham a fonte. Correção natural: fonte sem rateio (ou
  "sem desconto") quando a fatia é zero e o total está fechado; só mostrar "rateado" onde há valor estimado.
  Conferir também o caso parcial ("O resto continua rateado"): aí o rótulo é verdadeiro só nas linhas com fatia > 0.
- **#8 Captura longa:** o problema real não é o texto do diálogo: numa sessão longa o usuário vê o texto da
  nota aparecer certo na tela, **a captura não termina por algum motivo** e a única saída é jogar tudo fora. Isso
  junta o D202 (fim da nota sem borda), T-51 e T-53 (RJ, contingência e itens a peso). O que a captura deveria
  fazer quando está "quase completa": oferecer **"Terminar assim mesmo"** (usar o que foi lido, marcar linhas
  faltantes), em vez de só "Guardar o código"/"Descartar". Dados: `docs/capture-bench.md` e o
  vídeo/dump da sessão. Investigar com os dumps do telefone, não com o portal.
- **#9 "Rio":** **confirmado, eram notas de Sergipe (UF 28)**, não do Rio. No Supabase há, em 2026-11-13: 3 capturas `abandonada`, 3 páginas `outro_estado` e 1 nota `salva` de Sergipe (versões 0.1.3 e
  0.1.5). **Causa dupla:** (1) Sergipe não é lido (só SC e RJ); (2) o QR de Sergipe aponta para
  `http://www.nfce.se.gov.br/…` **em HTTP puro**, e desde o D105 o app proíbe tráfego em texto claro
  (`network_security_config.xml`: `cleartextTrafficPermitted="false"`), então o WebView mostra "Página da Web não
  disponível" e **nem a página chega**. Notas do Rio (UF 33) também aparecem como `outro_estado` (4), compatível com o T-5, corrigido no código 6;
  não verifiquei datas por versão. Abrir ticket novo: Sergipe, página nunca carrega.
- **#10 São Paulo:** conferido no Supabase: há 2 páginas de SP (UF 35) como `outro_estado` (códigos 6 e 8) e 2
  notas `salva`. **É NFC-e, não CF-e-SAT:** a página é "Consulta Resumida NFC-e" em
  `www.nfce.fazenda.sp.gov.br` e tem a **mesma família de marcação da SC/RJ** (`tabResult`, `txtTit`,
  `RCod`, `Qtde`, `totalNota`, "Valor total"), 4 itens. Parece **viável ler SP** com `NfcePage` e pouca
  adaptação, sem nova coleta no portal: as duas páginas já estão na caixa. Falta classificar o endereço de SP como
  `Kind.Details` e conferir se a página "Resumida" traz o suficiente (código do produto, unidade, desconto). A UF
  chave 35 também permite avisar antes de abrir o portal.
- A pasta `test-media/contributions/` (ignorada pelo git) agora tem as 39 contribuições baixadas para investigar.

## 1. Categorias que faltam

**Entendi:** três lacunas na árvore padrão: licores, balas e a raiz aipim/mandioca (e, por extensão, tubérculos:
batata, batata-doce, inhame, cará).

**Código:** a taxonomia vem de `supabase/migrations/…_taxonomy.sql` (a última, `20261030220648`).
- `beverages.alcoholic` tem Cerveja, Vinho e **Destilados** (descrição "Spirits, liquor"). Licor (Amarula, Licor 43,
  Aperol) não é destilado em sentido estrito, e o usuário não o acha em lugar nenhum.
- `food.snacks_sweets.chocolate_candy` é **"Chocolates e balas"**: balas já têm casa, mas junto com chocolate.
  Ou o pedido é separar (Balas, Chocolates), ou a nota é de uma sessão antiga. 🔶 Qual dos dois?
- `food.produce` tem Frutas, "Legumes e verduras" e "Folhas e ervas". Aipim e batata hoje caem em "Legumes e
  verduras" ou ficam sem categoria. Falta "Raízes e tubérculos" (ou o equivalente).

**Perguntas de implementação (não de gosto):**
- A taxonomia é "um seed que só acrescenta" (D-log, taxonomia). Como um nó novo chega às cadernetas que já existem
  e ao telefone: pela sincronização da tabela `category` ou só em cadernetas novas? Precisa ser respondido antes.
- O Jev (função `categorize`, léxico em `supabase/functions/categorize/reads.ts`) só sugere nós que existem: o
  léxico precisa aprender "aipim", "mandioca", "licor" etc., senão o nó novo fica vazio.
- Produtos já categorizados errado (aipim em "Legumes") não se movem sozinhos. O worker do LLM está suspenso
  (D168); uma recategorização única por regra seria o caminho.
- O usuário pode criar categorias à mão (Catálogo › Categorias)? Se pode, estas três são conveniência de
  seed, não bloqueio, e a prioridade cai.

**Direção:** novo seed com `food.produce.roots_tubers` ("Raízes e tubérculos"), `beverages.alcoholic.liqueur`
("Licores"), e decidir sobre balas × chocolates; léxico do Jev; teste pgTAP/Deno do léxico; migração de produtos
existentes.

## 2. Navegação de meses em Gastos

**A nota está cortada** ("quando seleciona um…"): provavelmente "um mês/um ano/uma categoria". 🔶 Completar.

**O que o código mostra** (`ui/spending/SpendingScreen.kt`) e pode explicar o "esquisito":
1. O mês só se escolhe tocando numa barra de 22 dp; não há setas, nem deslizar, nem "mês anterior/próximo". Com
   mais de 12 períodos, `periods.takeLast(12)` esconde o resto: **meses antigos não são alcançáveis**.
2. `Spending.periods` só contém meses **com notas** (`distinct().sorted()`); um mês sem nota não ganha barra, e as
   barras vizinhas parecem meses seguidos. O comentário de `Bars` diz "um espaço significa sem notas", mas o código
   não desenha espaço nenhum.
3. A descida na árvore (`path`) e a lente (Categorias/Lojas) **sobrevivem à troca de mês**. Escolhendo outro mês, o
   caminho "Tudo › Mercearia" pode apontar para uma categoria sem gasto nesse mês (lista vazia, trilha de
   navegação obsoleta).
4. Trocar Mês↔Ano zera o período (`selectedKey = null`) mas não o `path`.
5. A barra do mês selecionado é só mais escura; o resto da tela (título "Maio de 2026 · 3 notas") fica longe do
   gráfico no telefone.

**Direção:** setas ‹ › ao lado do rótulo do período (e/ou barras roláveis), zerar ou validar `path` ao trocar de
período, mostrar meses sem nota como lacuna. Verificar na emulador com a caderneta do usuário antes de decidir
qual dos cinco incomoda.

## 3. "Sem categoria" não abre

**Confirmado.** No `SpendingScreen` a linha de slice só tem `onClick` quando `deeper` (categoria com filhos). A
linha **Sem categoria** (`categoryId == null`) e a "X (sem subcategoria)" ficam sem toque. Pior: a lista
"Produtos" logo abaixo, no nível de cima (`node == null`), lista **todos** os produtos do período, não os sem
categoria. Também em `InicioScreen` (`onClick = onSpending`) e na linha de aviso "R$ X sem categoria"
(`CaveatLine`), que informa um problema e não leva a lugar nenhum.

**Direção:** tocar em "Sem categoria" filtra a lista de produtos para os sem categoria (e, de lá, abre o produto
para categorizar); idem "(sem subcategoria)". A aba **Para você** já reúne decisões pendentes: checar se
produtos sem categoria entram lá, para não duplicar o fluxo.

## 4. "Ainda poucos produtos comprados de novo para medir preços"

**Não faz sentido, e dá para dizer por quê.** O texto aparece (`ui/spending/IpciScreen.kt`) quando há ≥2 meses mas
nenhum mês tem leitura. Um mês só tem leitura com **≥ 3 produtos recomprados** e **≥ 30% do gasto** neles
(`Ipci.MIN_PRODUCTS`, `MIN_COVERAGE`). Problemas do texto:
- "Poucos" não diz quantos nem quantos faltam.
- "Comprados de novo" é jargão: o usuário pensa em "notas", não em "produtos com dois preços".
- Não diz o que fazer (esperar mais notas? corrigir produtos duplicados?).
- Pode estar errado: se os produtos existem mas estão **duplicados** (mesmo item com dois códigos, D44), é falta de
  vínculo, não de compra.
- A lista "Mês a mês" diz "poucos dados" em cada linha, sem o motivo (cobertura baixa ou <3 produtos).

**Direção:** mostrar números e o motivo: "Para medir, preciso de pelo menos 3 produtos que você comprou em dois
meses diferentes, somando 30% do gasto. Em outubro: 1 produto, 12%." Distinguir os dois limites.

## 5. Variação calculada contra um único ponto do passado 🔶

**Como entendi a nota:** "será que estou minimizando o problema de medir variação de preço contra um único ponto
no passado? Mesmo (ou principalmente) para o primeiro [preço]." A última frase ("seja o primeiro") está
ambígua: pode ser *o primeiro preço do produto*, ou *se for a primeira compra*. 🔶 Confirmar a leitura.

**O que o método faz** (`data/Ipci.kt`, D79): cada produto do mês é comparado com **o último preço mensal que ele
teve, não importa quão antigo**. É por desenho; o problema é real. Consequências:
1. **Intervalo longo vira variação de um mês.** Comprado em janeiro e só em dezembro: onze meses de inflação
   entram como a variação de dezembro, e o índice salta lá, não ao longo do ano. O "% em 12 meses" soma saltos
   que não aconteceram onde aparecem.
2. **Um ponto só carrega o ruído.** Uma promoção, uma loja diferente ou um tamanho de embalagem diferente vira a
   base do próximo mês. Um preço atípico gera dois movimentos falsos (subida e descida).
3. **A primeira compra não entra:** o produto só "entra" na segunda compra (D79). Com recibos esparsos (a conta do
   usuário tinha quatro meses em dois anos), quase todo mês dá "poucos dados".
4. **Peso só do mês corrente:** já é o T-49 (aberto, "o usuário decide"): sem peso do mês anterior o índice deriva
   quando o preço volta ao que era.

**Opções, sem escolher:** (a) repartir a variação de um intervalo por igual nos meses dele (taxa geométrica por mês);
(b) cortar vínculos com mais de N meses; (c) usar a mediana das últimas k compras como base; (d) pesos médios dos
dois meses (Törnqvist) — resolve o T-49 junto. Recomendação: (a)+(d), por serem as menos arbitrárias e
manterem o índice contínuo. Alterar o método é mudar o D79: precisa de um D novo e da decisão do usuário.

## 6. 10 unidades em janeiro, 1 em dezembro

**É o caso concreto da nota 5, e vale como teste.** Como o código trata:
- O preço do mês é *líquido do mês ÷ quantidade do mês*, por unidade/kg/l (`basisOf`, `quantity`). Então a
  quantidade **não** entra na razão: preço unitário de janeiro (médio das 10) × preço unitário de dezembro.
- O que fica errado: **desconto por atacado/leve-mais** (10 saem mais baratas por unidade) é lido como inflação em
  dezembro, quando só se comprou uma. Mesmo efeito de promoção "3 por 2".
- O peso do vínculo é o gasto **do mês corrente**: dezembro (1 un.) pesa pouco; janeiro (10 un.) não pesa, porque
  é o mês de origem. Assimétrico: é a mesma raiz do T-49.
- Quando um mês tem duas lojas, o preço é a média ponderada por quantidade; uma única loja barata puxa o resto.
- `D144` (preço de embalagem) trata tamanho, não quantidade comprada: não resolve isto.

**Direção:** testar o caso nos testes do `Ipci` (10 un. a R$ 2,00 em janeiro, 1 un. a R$ 2,50 em dezembro: o que se
espera?). Decidir se compras com quantidade muito diferente (razão > k) são excluídas do vínculo ou só sinalizadas.
Questão aberta: é um ruído tolerável ou um viés? Só dá para responder com os dados reais.

## 7. Produto sem desconto aparece "rateado"

**Por desenho, mas enganoso.** A SEFAZ-SC só informa o desconto **da nota** (um total). `Derive` reparte esse total
entre todas as linhas "abertas" (`allocateCents` sobre `lineCents`, `Derive.kt:119`) e marca cada uma
`prorated`; só quem o leitor de fotos achou (ou o usuário digitou) vira `photo`/`manual`. Resultado: **um produto
que não teve desconto nenhum recebe uma fatia e o rótulo "rateado"**, e o preço líquido dele (e o IPCI)
carrega esse valor inventado. É o que o usuário viu. Aparece em `ReceiptScreen` ("rateado" por linha) e em
`SpendingScreen`/`IpciScreen` ("desconto rateado (estimado)", "com desconto rateado").

Pontos a esclarecer/decidir: 🔶
- Quando o desconto é por **item** impresso na nota (SC mostra "Desconto" em linhas próprias?), ele já é "da
  página" (`html`); o rateio só existe quando a página só dá o total. Verificar em quais notas aparece.
- Para uma linha com 0 de rateio (nota sem desconto), o rótulo deveria nem existir.
- O mais honesto: marcar "sem desconto confirmado" separado de "parte de R$ X rateada", ou repartir só por linhas
  candidatas (as da foto sem leitura) em vez de todas.

Isso mexe no D54/D101 (rateio e leitura de fotos): decisão do usuário.

## 8. "Guardar só o código" × "descartar a captura" 🔶

**Quase não faz diferença para quem usa, e o texto explica mal.** `ScanScreen` (diálogo "Sair sem terminar?")
oferece "Sair guardando só o código" e "Descartar a tentativa". Em `LiveCapture.exit`: ambas descartam as imagens
da tentativa; a única diferença é que `KEEP_CODE` registra a nota (`receipts.open(chave, qrUrl)`) e enfileira o
envio, ficando como "nota esperando a página". `DISCARD` não deixa nada. Além disso, fechar a tela já faz
`KEEP_CODE` (`close()`), então o caminho padrão é guardar.

Por que parece igual: o diálogo diz "As imagens são descartadas" para os dois, nunca diz "a nota fica esperando a
página" nem "a nota some"; e o rótulo "o código" é interno (a chave/QR). O usuário vê, no fim, a mesma tela.
**O que o usuário esperava?** 🔶 (a) A diferença visível (nota na lista × nada); (b) mais uma opção, "guardar as
fotos até aqui"; (c) tirar um dos dois.

**Direção:** textos que digam o resultado ("Guardar a nota para ler depois: ela aparece em Notas esperando a
página" × "Não guardar nada"); considerar fundir em uma saída padrão + um "Recomeçar" (já existe, D150).

## 9. Capturas da SEFAZ do Rio estão falhando 🔶

**A nota é curta; "captura" pode ser três coisas** e a investigação depende de qual:
1. **A captura da câmera** (ler o papel): já há dois tickets abertos de RJ: **T-51** (nota de contingência curta
   não completa: comprovante entra no papel, linhas duplicadas, marcadores não achados) e **T-53** (contagem de
   linhas cresce a cada passada nos itens a peso, valor impresso entre as linhas, formato RJ). Seguem abertos
   no momento desta nota (D200–D202 mexeram na captura; não verifiquei se mudam estes dois). O usuário pode estar
   vendo essas.
2. **A página da SEFAZ-RJ** (`resultadoQRCode2.faces`): o D155 lê a página como a de SC, sem orçamento nem bloqueio
   (`SefazPage.isSefaz` só reconhece SC). O portal do RJ tem verificação automática (F5/TSPD) que passa no
   WebView, mas pode ter mudado. Sintomas possíveis: "Carregando…" para sempre, "Não foi possível realizar a
   consulta" (nota de contingência), ou a página sem `tabResult`.
3. **Receber a nota depois:** o código 6 corrigiu o T-5 (URL com `|` do RJ era tratada como "outro estado").
   Quem ainda está no código 5 sofre.

**Faltam dados:** qual versão, notas de contingência ou normais, o que aparece na tela, se há um relato em
`tools/tickets.py inbox` (hoje vazio) e se o usuário está sem o código 8. Nenhuma consulta ao portal foi feita
(orçamento de IP, CLAUDE.md). Para investigar: repetir pelas capturas já salvas em
`test-media/live-dumps/emulator/` e pelos fixtures `rj-*`, sem tocar o portal.

## 10. Nota de São Paulo (23/08/2026): abre a SEFAZ-SP e não adiciona

**É o comportamento atual e documentado, não uma falha pontual.** O app só lê **SC e RJ** (D155; D166 repete: "reads
Brazilian NFC-e (SC, RJ)"). Para outro estado, o QR abre o portal no WebView e **para ali** (D155: "Other
states' QR codes still open their portal and stop there"): `SefazPage.classify` só reconhece o endereço de SC e o
do RJ como `Details`; o de SP cai em `Kind.Other`, e `PageScreen` não salva nada. Só quando o usuário aceitou a
contribuição de teste a página vai à caixa de contribuições como `outro_estado` (`TestContribution.OtherState`).

Pontos que tornam o caso pior que "não suportado":
- O usuário **vê** a SEFAZ-SP abrir normalmente: a tela promete a nota e nada acontece, **sem aviso**.
- A UF está na chave (os 2 primeiros dígitos, 35 = SP): o app **sabe** que é de outro estado antes de abrir o
  portal e poderia dizer "Notas de São Paulo ainda não são lidas".
- D107 já avisa que SP é outro caso: **CF-e-SAT/MFE** em parte do estado (outro documento, outro formato), e a
  NFC-e de SP tem o próprio layout. O usuário tem uma nota de agosto/2026: é NFC-e ou CF-e-SAT? 🔶
- A Play está restrita ao Brasil (D166), então notas de outros estados vão aparecer com os testadores.

**Direção, em ordem de custo:** (1) avisar antes de abrir o portal ("São Paulo ainda não é suportado") e oferecer
guardar a foto; (2) lembrar o estado na nota esperando e mostrar no card; (3) suportar SP de verdade, o que exige o
formato real (fixtures via `tools/make-nfce-fixture.py`, uma nota do usuário, e o orçamento de consultas).
Qual das três é decisão de produto. O item (1) é pequeno e corrige a sensação de "bug".

## Sugestão de ordem

1. **Pequenos e certos:** #3 (Sem categoria tocável), #4 (texto do IPCI), #10-(1) (aviso de estado não suportado),
   #8 (textos do diálogo).
2. **Seed/taxonomia (#1):** depende da resposta sobre como um nó novo chega às cadernetas.
3. **Decisões do usuário antes de código:** #5, #6, #7 (método do IPCI e rateio; viram D novo) e #2, #9, #8 (faltam
   dados).
4. **Para investigar com dados:** #9 (RJ) e o formato de SP.

## Perguntas para o usuário

- #1 balas: separar de chocolates, ou só licor e raiz?
- #2 a nota está cortada: o que acontece ao selecionar "um…"?
- #5 como ler "seja o primeiro"?
- #8 o que você esperava de diferente entre os dois?
- #9 o que falha, em quais notas (normais ou contingência), em qual versão?
- #10 a nota de SP é NFC-e ou CF-e-SAT? Há outras de fora de SC/RJ em que valha apostar?
