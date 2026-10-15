# Validação da captura contínua por segmentos

Atualizada em 2026-10-14. Pixel 8 emulado, Android 16/API 36, imagens inteiramente sintéticas.
Não houve consulta à SEFAZ nem envio das imagens de teste ao servidor. Calibração física no Motorola pendente.
Esta validação substitui a do primeiro incremento de documento inteiro; o fallback manual foi removido.

## Build e testes locais

```bash
. tools/env.sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Build debug, 35 testes unitários e lint passaram. O lint mantém avisos de dependências/configuração existentes.
Os testes cobrem cobertura com lacunas, crédito de extremos somente em faixas válidas, QR tardio/conflitante,
avisos simultâneos, foco parcial, deduplicação e exclusão dos cantos externos à máscara. Persistência cobre
importação idempotente, fotos anteriores, recuperação após processo, commit interrompido, arquivo faltante,
recuperação danificada, sessão ativa e rejeição integral por limite de fotos.

## Testes nativos no emulador

Onze testes instrumentados passaram, incluindo a interface e o processamento nativo.

Instalar e selecionar explicitamente o emulador, sem executar tarefas que possam reinstalar o app no celular pessoal:

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm grant io.github.sandroroque.getnf android.permission.CAMERA
adb -s emulator-5554 shell am instrument -w -r \
  io.github.sandroroque.getnf.test/androidx.test.runner.AndroidJUnitRunner
```

`CaptureLifecycleTest` exige câmera em cena vazia, permissão concedida e rede desligada no emulador.
Não rodar a suíte com imagem de nota/QR fixada na câmera: esse teste espera que a captura permaneça aberta.

- Geometria completa e parcial: duas laterais, foco e extremos corretos sem exigir quatro cantos,
  inclusive com papel mais escuro que a mesa e recibo inclinado.
- Nota idêntica em fundo liso/texturizado, com a mesma máscara: escores de nitidez idênticos.
- Papel desfocado em fundo nítido: texto rejeitado. Cena vazia: nenhuma máscara confiável ou foco válido.
- Nota longa de 900×3000, vista em janelas de 900×1200: captura automática em cinco trechos, QR somente no final,
  conclusão e importação de JPEGs/máscaras, com solicitação única de upload substituída por callback de teste.
- Salto do topo ao fundo sem sobreposição: não conclui. Retorno ao conteúdo conhecido e varredura conectada
  permitem concluir. Esse teste detectou falso fechamento com fluxo óptico; o fallback foi removido.
- Saída incompleta com QR: conserva somente o código, sem anexar imagens temporárias.
- Rotação e reentrada: teste da interface verifica permanência na captura e uma nova sessão depois de sair.

O engine recebe os frames sintéticos diretamente; os testes de varredura longa verificam a visão nativa,
rastreamento, seleção, gravação e importação. Não simulam lentes, autofoco, exposição nem desempenho térmico real.
O teste de interface usa CameraX, mas a legibilidade de recibos físicos continua exigindo validação no telefone.

## Percurso pela câmera simulada

Com `receipt-fit.png` fictício em `imagefile:` e rede desligada, o app decodificou o QR pela câmera, desenhou
segmentos alinhados às bordas, salvou e validou um JPEG e retornou sozinho à tela inicial. A nota ficou com uma
foto e página SEFAZ pendente. Nenhuma consulta ou transmissão foi feita. Os dados fictícios do app foram removidos do emulador ao encerrar a validação.

Esse percurso revelou uma falsa rejeição da zona branca ao redor do QR: um piso de energia absoluta confundia
baixo contraste com desfoque. O critério final usa a razão de gradientes entre escalas; o teste de texto fraco nítido
passa e o teste de texto desfocado em fundo nítido continua recusando as faixas ruins.

## Migração

A migração `1792100000_capture_photo_limit.js` foi aplicada, revertida e reaplicada com PocketBase 0.40.5 em um
banco temporário vazio, sem copiar notas pessoais. Eleva somente `receipts.photos.maxSelect` de 20 para 100.
O rollback foi também exercitado com uma nota fictícia de 21 imagens e recusou reduzir o limite. Essa validação não
representa implantação/reinício do servidor em uso.

## Próxima validação física

O celular estava desconectado na entrega. No Motorola edge 50 pro, testar começar pelo meio, visitar extremos em ambas as ordens, QR tardio, interrupção
com/sem QR, rotação, perda e retomada de sobreposição, aproximação/desfoque, texto pequeno, recibos térmicos,
sombras, reflexos, dobras e fundos pouco contrastantes. Confirmar o alinhamento dos segmentos sobre o preview,
legibilidade das imagens e ausência de conclusão prematura. O APK debug está em
`android/app/build/outputs/apk/debug/app-debug.apk`.


## Medição no Motorola — 2026-10-15

O teste físico relatou contorno atualizado com intervalos acima de cinco segundos e foco/enquadramento difícil
com a nota estreita, em retrato, a cerca de 30 cm. A validação sintética anterior não representou esse custo.
Instrumentação `CapturePerf` registra apenas duração de etapas, sem imagem, texto, chave ou payload de QR.

No aparelho, o refinamento GrabCut na cena de 720 pixels levou 1189–3170 ms em amostras observadas; QR levou
13–20 ms e nitidez cerca de 52–72 ms. A interface só recebia bordas após esse refinamento, QR e processamento.

Correções:

- Refinar a máscara dentro da região da nota, em grade de até 240 pixels, com inset conservador antes de ampliar;
  a qualidade permanece na resolução original e exclui também a vizinhança de filtros. Amostras físicas após essa
  mudança mostraram máscara de 20–91 ms e visão de 260–319 ms; houve também amostras de máscara até 389 ms.
  São tempos de processamento observados, não garantia de FPS ou latência ponta a ponta.
- Publicar segmentos detectados antes da máscara, QR e escrita. Manter somente o frame mais recente.
- Copiar luminância por linha e ler pixels da geometria em lote, reduzindo operações por pixel no Kotlin/JNI.
- Medir qualidade uma vez, depois do QR, preservando sua exclusão da avaliação de texto.
- Retirar limiares de área relativos à cena inteira: tiles/faixas usam quantidade mínima de pixels da própria máscara.
  A exigência anterior de 12% da largura da imagem descartava extremos e prejudicava notas estreitas.
- Preservar autofoco contínuo, retirando disparos periódicos de AF a cada três segundos. Toque para focar e pinça
  para zoom explícitos; controles Focar, Lanterna e zoom 1×/2×. Pedidos manuais de foco expiram após dois segundos.

Build, 35 testes unitários, lint e 12 testes instrumentados passaram após essas mudanças, incluindo um novo caso
com papel ocupando aproximadamente 22% da largura e muita mesa dos dois lados. Os testes nativos foram executados
somente no emulador. A melhora do foco e dos controles ainda exige observação pelo usuário no telefone.

CameraX já oferece foco/exposição/zoom e integração com Camera2. A medição encontrou um gargalo no processamento
da máscara; não justificou substituir a API de câmera. Consultas técnicas:
[configuração](https://developer.android.com/media/camera/camerax/configuration),
[arquitetura/interoperabilidade](https://developer.android.com/media/camera/camerax/architecture).


## Referência geométrica e aproximação pelo topo — 2026-10-15

Primeiro slice de [capture-geometry-plan.md](capture-geometry-plan.md) implementado. Build debug, lint e
41 testes unitários passaram. Os 19 testes instrumentados passaram no `emulator-5554`, com rede desligada e
notas inteiramente sintéticas. Nenhum runner foi instalado no telefone pessoal e nenhuma consulta SEFAZ ocorreu.

Casos novos: distinção entre quatro pontos e quatro arestas físicas; transformações do topo sob zoom/translação/
giro; recusa de perspectiva incompatível; visão geral sem descritores úteis; visão geral desfocada → topo nítido
sem creditar o restante; âncoras de conteúdo independentes de foto salva e sem crescimento em câmera parada;
visão geral tardia sem mudar coordenadas; imagem inteira nítida + QR concluindo com uma foto; geometria completa
com lacuna de texto impedindo conclusão. Regressões existentes de salto sem sobreposição, percurso longo com
QR tardio, máscara, papel estreito/escuro e rotação também passaram.

Durante o desenvolvimento, a visão geral desfocada revelou perda do topo na busca Hough probabilística;
a busca transversal passou a restringir orientação e exigir suporte real. A normalização de escala e o filtro de
correspondências foram ajustados após os testes detectarem associação incorreta de linhas repetidas. A versão
final passa o caso de salto entre extremos sem fechar cobertura. Esses resultados são sintéticos, não prova de
correspondência perfeita em qualquer recibo real.

Ainda medir no Motorola: visão geral → aproximação pelo topo, localização ao sair o topo, perda/retomada,
segmentos sobre o preview, legibilidade dos JPEGs e latência por etapa. Localização geométrica pelo fundo e
previsão de corredores de busca estão adiadas. Sem correspondências ou topo compatível, o app preserva o mapa
e pede retomada. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Acompanhamento físico da versão com geometria — 2026-10-15, 11:30–11:33

APK instalado no Motorola preservando os dados. Acompanhamento por capturas pontuais da tela e logs locais de
etapas, sem consultas ao portal. A captura falhou também com a nota em pé: segmentos amarelos atravessaram o
papel em diagonal ou acompanharam elementos do fundo; topo, fundo e cobertura não foram confirmados. O QR
estava identificado e havia um trecho salvo, sem progresso observado. Não considerar esta versão validada para
uso físico apesar dos testes sintéticos aprovados.

Na orientação normal, os logs alternaram `located=false` e `located=true, sharp=true, tracked=false`, com geometria
não confirmada e uma âncora. Portanto, houve frames aprovados em nitidez que não conseguiram localização; não
atribuir toda a falha ao foco. A tentativa anterior com papel deitado expôs também a limitação da busca de
laterais quase verticais. O usuário informou falha prévia com a nota em pé, reproduzida neste acompanhamento.

Amostra recente nessa orientação: 13 medições de visão, mediana 159 ms, máximo 293 ms; bordas, mediana 95 ms,
máximo 115 ms. Cinco medições de qualidade, mediana 74 ms, máximo 78 ms; rastreamento máximo 30 ms. Na amostra
anterior com nota deitada, dez medições de visão tiveram mediana 269,5 ms e máximo 581 ms, com máscara chegando
a 502 ms. São durações de etapas em janelas curtas, não latência ponta a ponta nem validação de FPS. Não se
observou processamento de cinco segundos nessas amostras.

Próximo diagnóstico: distinguir arestas escolhidas incorretamente de erro na transformação entre análise e
preview; verificar como a seleção do par de laterais reage ao fundo texturizado e a bordas do papel dobradas.
Reproduzir essas condições em fixtures sintéticas. Manter esta falha física como bloqueador; não ajustar apenas
limiares de nitidez nem concluir que aprovar testes sintéticos resolve a captura real. As imagens pessoais ficaram
somente em arquivos temporários fora do repositório; este registro não contém texto fiscal nem identificadores.


## Reparo de região, máscara e orientação — 2026-10-15

Build debug, lint sem erros, 41 unitários e 30 instrumentados passaram. Os instrumentados foram executados
explicitamente no `emulator-5554`; nenhum runner foi instalado no Motorola. Rede do emulador desligada e
fixtures inteiramente sintéticas. Não houve consulta SEFAZ.

Novos casos cobrem papel irregular sobre textura diagonal quase vertical e faixa forte; máscara sem pixels de
fundo na fixture, preservando pelo menos 95% do interior de referência; fundo texturizado sozinho sem papel;
nota deitada e recorte parcial largo; máscara exata com exclusão de QR e vizinhanças; perspectiva forte sem falsa
similaridade; matrizes de projeção nas 16 combinações de rotações, com crop/zoom; giro da nota sem mudar os
extremos do mapa; captura deitada terminando com uma foto canônica; controles de máscara e inspeção com
recriação da Activity. Regressões anteriores de lacuna, sobreposição, QR tardio e persistência passaram.

O primeiro teste de máscara irregular detectou inclusão de fundo; a margem de segurança passou a dois pixels
da grade de detecção e o teste final passou com zero pixels externos. O novo limite de referência é testado
separadamente do contorno visual, que representa a borda observada e não a erosão para nitidez. A troca de
região alterou a amostragem ORB; o filtro de razão foi endurecido para 0,6 sem reduzir os mínimos de consenso.

A tentativa de transferir uma captura pessoal para o emulador foi bloqueada pela revisão automática por
sensibilidade do conteúdo e autorização insuficiente para aquele destino. Não houve tentativa alternativa de
transferência. A validação offline ficou restrita às cenas sintéticas; imagens pessoais não viraram fixtures.

Ainda pendentes: aceite visual no Motorola com “Mostrar área analisada” e “Inspecionar frame”, progresso e
conclusão em notas físicas e atualização p95 abaixo de 500 ms no cenário acompanhado. Não inferir esse aceite
ou a meta de latência a partir dos testes instrumentados.

O APK deste reparo foi instalado no Motorola com `install -r`, preservando os dados; somente o APK do app,
sem runner de testes. A conferência visual no emulador confirmou os controles de máscara/inspeção. Aguardando
nova observação física da nota para avaliar a qualidade do contorno e o progresso.

A instrumentação distingue `region`, `overlayReady` (entrada do analisador até publicação do frame coerente),
`overlayInterval` (intervalo entre publicações), qualidade, registro e escrita. O frame visual é publicado antes
de codificar/sincronizar JPEG. Esses tempos não incluem toda a exposição/transporte da câmera ou apresentação
na tela; a meta visual p95 precisa de observação no aparelho, não apenas desses contadores.


### Primeiro acompanhamento físico após o reparo

O contorno visível acompanhou o papel e suas dobras em uma vista deitada, substituindo as linhas diagonais
sobre o fundo. A captura ainda ficou presa no registro: um trecho salvo, sem extremos confirmados; uma nova
tentativa reconheceu o QR, mas ficou com zero âncoras e nenhum trecho. Não declarar o fluxo aprovado.

Na primeira janela foram observadas 30 medições de visão: mediana 31 ms, p95 111 ms, máximo 172 ms. A qualidade
teve 28 medições, mediana 67,5 ms e máximo 80 ms; registro teve mediana 27 ms e máximo 31 ms. A máscara estava
oculta no preview observado, portanto esse acompanhamento confirma o contorno, não a exclusão visual do fundo
na máscara nem sua suficiência para concluir. Uma inspeção congelada capturou a mesa após afastar a câmera;
não foi usada como evidência de nitidez/máscara da nota.

A investigação do estado sem âncoras levou a D32: não inicializar mapa parcial sem descritores; permitir substituir
referência sem âncoras somente antes de qualquer foto salva/candidata. Os dois testes de recuperação e preservação
passaram. Resultado final: build, lint, 41 unitários e 32 instrumentados sintéticos passaram. APK corrigido instalado
no Motorola com dados preservados; nova validação de progresso e máscara ainda pendente.

### Segunda tentativa física e novas proteções

A versão com D32 ainda não concluiu: houve foto salva sem âncora e perda intermitente da região em uma nota
horizontal. As amostras da nova instrumentação tiveram 34 publicações, com `overlayReady` mediano 235,5 ms,
p95 362 ms, máximo 407 ms; `overlayInterval` mediano 241 ms, p95 367 ms, máximo 407 ms. A visão teve mediana
217,5 ms, p95 316 ms, máximo 344 ms. A maior parte dessas amostras percorreu o fallback por linhas; a região
primária, em dez amostras, levou mediana 36,5 ms, máximo 44 ms. Sem conclusão de cobertura, esses tempos não
constituem aceite funcional ou medição de apresentação na tela.

D33 impede a seleção/gravação sem âncora utilizável, cobre o fallback horizontal e reorganiza a interface em
paisagem. A opção de máscara agora sobrevive à rotação; a imagem observada antes mostrava o botão “Mostrar
área analisada”, portanto não houve evidência física de máscara ativada naquela captura.

### Versão com D33

Build, lint, 41 unitários e 35 instrumentados passaram. O novo teste de trecho claro sobre mesa clara expôs o
limiar alto de Canny no fallback; a busca de bordas passou a 8/24, sem alterar os critérios de foco. Passaram o
fallback em ambas as orientações, a recuperação antes de qualquer foto, a recusa de salvar sem âncora e o teste
de visibilidade/preservação da máscara em paisagem. APK novamente instalado no Motorola com dados preservados.
Não houve runner de testes no aparelho. A confirmação física de conclusão ainda está pendente nesta versão.

### Capturas físicas concluídas e revisão dos JPEGs — 2026-10-15

Com D33, duas tentativas físicas concluíram e voltaram à lista. A inspeção do manifesto privado confirmou lotes
de quatro e duas imagens acrescentados à mesma nota, preservando as imagens anteriores; upload da versão local
estava atualizado. Nenhuma consulta fiscal nova foi feita. Os diretórios temporários de sessão estavam encerrados.

Na janela imediatamente anterior ao primeiro encerramento, 12 medições de região/visão tiveram mediana 50 ms,
máximo 61 ms; 11 publicações tiveram `overlayReady` mediano 245 ms, máximo 268 ms e `overlayInterval` mediano
257 ms, máximo 453 ms. A escrita teve três amostras, mediana 176 ms, máximo 192 ms. A janela é curta e não cobre
todos os cenários; intervalos de publicação não são medidas completas de apresentação no display.

A pedido do usuário, os seis JPEGs e respectivas máscaras foram lidos do armazenamento final do telefone para
arquivos temporários locais e revisados em resolução original. Não houve transferência para o emulador nem
inclusão de imagem, chave, CPF ou conteúdo fiscal no repositório. As quatro imagens do primeiro lote permitiram
ler visualmente o conteúdo principal. No segundo lote, uma imagem tinha borrão pronunciado e outra era inferior
às melhores, embora o conteúdo principal fosse reconhecível. As três máscaras inspecionadas visualmente seguiam
a área do papel e excluíam o fundo. Isso confirma progresso da detecção/persistência, mas expõe uma falsa aprovação
de qualidade: a foto borrada recebeu score 1,324 e foi aceita. Não declarar o critério de legibilidade validado.

O fluxo atual captura/valida imagens e QR; não extrai produtos/descontos das fotos. A extração estruturada continua
no parser da página fiscal já salva. A revisão foi visual, sem alegação de OCR perfeito. Próximo problema concreto:
rejeitar borrão de movimento/baixa legibilidade mesmo quando a máscara do papel está correta, sem aumentar um
limiar global que prejudique texto térmico fraco. Não apagar as imagens do usuário nem descartar silenciosamente
as evidências já guardadas ao tratar essa falha.

## Benchmark sintético de legibilidade — 2026-10-15 (D37)

`FocusBenchmarkTest` (instrumentado) gera recibos sintéticos 1440×1920 com texto conhecido: tinta normal e
térmica fraca, fontes 20/28/36 px, uma cena com QR. Aplica 16 degradações (nenhuma; desfoque gaussiano σ 1, 2 e
3,5; borrão de movimento de 5, 9, 15 e 25 px a 0°, 45° e 90°), com e sem JPEG 92. O gabarito vem do OCR do ML Kit
comparado ao texto conhecido: a faixa é legível quando todas as suas linhas têm ≥ 90% dos caracteres corretos.
Faixas avaliadas: 3.008 com texto, 1.532 legíveis; as pontas do papel ficam de fora. Resultados no logcat
(`FocusBenchmark`). Rodar sozinho:
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.sandroroque.getnf.ui.scan.FocusBenchmarkTest`
(cerca de 6 min no emulador).

| Critério | Ilegíveis aprovadas | Legíveis rejeitadas |
| --- | --- | --- |
| Métrica anterior (razão combinada X+Y, limiar 1,18) | 440 | 787 |
| Razão por direção, limiar 1,18 | 55 | 1.194 |
| Razão por direção, limiar 1,40 | 1 | 1.467 |
| OCR com confiança ≥ 0,7 (sem gabarito) | 3 | 354 |
| OCR com confiança ≥ 0,8 (sem gabarito) | 0 | 562 |
| Adotado: pré-filtro por direção ≥ 0,7 + OCR ≥ 0,8 | 0 | 594 |

Diagnóstico: a razão de gradientes mede borrão em pixels, mas legibilidade depende do borrão relativo ao tamanho
da letra. Texto de 36 px nítido era reprovado (score 0,92) e texto de 20 px com borrão de 9 px aprovado; nenhum
limiar separa os dois. O borrão de movimento passava porque X e Y eram somados. A cena com QR mostrou o segundo
defeito: texto fraco borrado classificado como papel em branco era liberado porque o QR contava como conteúdo.

OCR levou mediana de 1,6 s (máx. 2,9 s) por imagem no emulador; não representa o Motorola. Limites: cenas
sintéticas com fonte monoespaçada e sem ruído de sensor; o gabarito também é OCR, então mede legibilidade para
máquina, não para pessoas. A confiança do ML Kit por linha é usada sem calibração no aparelho.

## Fotos reais pelo critério de OCR — 2026-10-15 (D38)

As 10 fotos de captura do Motorola (lotes `5663` das 00:42, ainda salvas deitadas por versão anterior e giradas
90° só para a avaliação; `cf32` e `5b11`, os seis do D34) foram copiadas com as máscaras para uma pasta temporária
fora do repositório e avaliadas no emulador por `CaptureEvidenceEvalTest`, que só roda com arquivos postos em
`files/eval/` via `run-as` e registra apenas números. Gabarito visual (revisão em resolução original): `5b11-0`
borrada por movimento, ilegível; `5b11-1` mais suave, legível; as outras oito legíveis.

- Com o critério do D37 (toda linha ≥ 0,8), nenhuma faixa de nenhuma foto passava: a confiança por linha do ML Kit
  em papel térmico real fica entre 0,5 e 0,85 (medianas por imagem de 0,57 a 0,72). A captura não concluiria.
- A foto borrada não produziu nenhuma linha de OCR; a suave teve mediana 0,51.
- Regra adotada (D38): mediana ≥ 0,5 por faixa; conteúdo sem linha lida passa entre faixas de texto legível, no QR
  ou na ponta do papel. Resultado: borrada 0 de 13 faixas com conteúdo; suave 9 de 15; legíveis de 12/13 a 16/19.
- OCR no emulador: cerca de 1,7–2,1 s por foto real (uma de 3,9 s). Ainda sem medição no Motorola.
- Benchmark sintético com a regra nova: 111 de 1.476 faixas ilegíveis aceitas, 107 delas parcialmente legíveis
  (50–89% dos caracteres) e 4 graves (< 50%); 129 de 1.532 legíveis rejeitadas (antes: 594).
