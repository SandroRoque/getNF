# Plano: geometria persistente e localização da captura

Planejado em 2026-10-15 após teste físico e discussão com o usuário. O primeiro slice está implementado no APK
debug; as etapas restantes continuam planejadas. Complementa [automatic-capture.md](automatic-capture.md) e mantém as regras de
privacidade, saída, recuperação e importação já definidas.

**Checkpoint atual, 2026-10-15:** o reparo e as proteções D31–D33 estão no APK instalado. Duas tentativas
físicas concluíram; a revisão encontrou um falso positivo de nitidez. Os registros abaixo preservam a evolução
do plano. Para evidência atual, ver [capture-validation.md](capture-validation.md); para o próximo trabalho
de qualidade e seleção, ver [capture-retention-plan.md](capture-retention-plan.md).

## Primeiro slice implementado — 2026-10-15

Escopo aprovado: visão geral → aproximação mantendo **topo e duas laterais**. Geometria, localização e cobertura
salva têm estados separados. Quatro pontos de um polígono cortado não contam como quatro lados físicos.

- A primeira visão dos quatro lados pode estabelecer geometria provisória mesmo desfocada. Outra visão geral
  compatível ou duas observações consecutivas compatíveis do topo confirmam a referência. Não concedem cobertura.
- A base usa largura 256 e não muda quando uma visão geral aparece depois de um início pelo meio.
- Topo + laterais permitem rotação/translação/escala somente em cenas próximas de frontais: desvio de
  perpendicularidade até 8° e variação de largura até 10%. Perspectiva incompatível exige conteúdo correspondente.
- ORB extrai descritores uma vez por frame, com escala normalizada pela largura do papel, lado máximo 960 e
  700 pontos. Correspondências são recíprocas, com teste de razão em pelo menos uma direção; RANSAC mantém
  mínimo de 12 inliers, 55% de consenso, distribuição espacial e limites projetivos. A extração serve também a
  frames sem qualidade para salvar. Âncoras são retidas por novidade de extensão (20%) ou escala (25%), até 100.
- A busca de arestas transversais usa Hough com orientação restrita e suporte efetivamente observado, evitando
  que linhas verticais de texto consumam o topo de uma visão geral desfocada. A máscara conservadora permanece
  obrigatória para avaliar nitidez, inclusive no JPEG.
- A interface indica “Formato da nota reconhecido” após confirmação. Topo visível pede foco ou que se segure
  a câmera, sem mandar avançar para cima. Ausência de destino não mostra um sinal de captura concluída.
- Geometria fica na memória da sessão/ViewModel. Não há alteração de banco nem retomada de fotos parciais.

Validação: 41 testes unitários e 19 instrumentados sintéticos no emulador, build e lint. Inclui imagem inteira
nítida com QR terminando com uma foto, visão geral desfocada seguida de topo nítido, âncora sem foto salva,
retorno à visão geral, perspectiva incompatível, lacuna de texto, salto entre extremos, QR tardio e rotação.
Não foram feitas consultas à SEFAZ. Medição física desta versão ainda pendente.

**Adiados:** localização geométrica pelo fundo e previsão de corredores de busca para realimentar a detecção.
Sem extremo conhecido nem correspondências de conteúdo, a localização continua perdida; o mapa é preservado.
As seções seguintes descrevem o plano completo, incluindo essas próximas etapas.

## Resultado esperado

O app aproveita uma visão geral da nota para estabelecer sua geometria, mesmo quando o texto ainda não pode
ser considerado capturado. Ao aproximar/percorrer a câmera, localiza cada frame nesse mapa e coleta as regiões
que faltam com qualidade suficiente. Uma visão geral nítida pode resolver toda a cobertura com uma imagem.
Não exigir que toda nota seja mostrada inteira: recibos longos também podem começar pelo meio/topo/fundo.

A observação do usuário sobre o QR fica incorporada: uma nota inteira supostamente nítida cujo QR não é lido
merece cautela. A ausência de leitura não prova, sozinha, que todo o texto esteja desfocado; tampouco permite
concluir a sessão. Avaliar conteúdo por região e orientar ao QR quando a cobertura estiver pronta.

## Diagnóstico anterior à implementação

- `DocumentObservation.polygon` pode representar uma faixa cortada pela imagem. Ter quatro pontos nesse polígono
  não significa ter detectado os quatro lados físicos. Essa distinção precisa ser explícita.
- `DocumentTracker.track` exige `doc.sharp` na inicialização. Portanto, não aproveita uma visão geral desfocada.
- `CaptureEngine` chama `keepAnchor()` ao selecionar um candidato de foto. A capacidade de localização fica
  dependente da seleção/qualidade das fotos, embora devesse servir também a frames não capturáveis.
- A detecção de bordas começa novamente a cada frame. A transformação estimada não orienta a próxima busca.
- O caminho atual já pode concluir com uma foto; falta torná-lo explícito, testá-lo e aproveitar sua geometria
  mesmo quando a qualidade dessa primeira visão não basta.
- A regra `sharp` significa haver alguma região nítida. Não é suficiente para declarar que a nota inteira foi
  capturada; a cobertura integral continua exigindo todas as regiões necessárias validadas e salvas.

## 1. Separar os três estados e fixar suas invariantes

Arquivos principais: `CapturePolicy.kt`, novo `ReceiptGeometry.kt`, `DocumentTracker.kt` e `CaptureViewModel.kt`.

Introduzir representações distintas:

| Estado | Conteúdo | O que autoriza |
| --- | --- | --- |
| Geometria da sessão | Referência de coordenadas, largura normalizada, extremos conhecidos, proporção estimada e confiança | Prever/localizar a nota |
| Localização do frame | Transformação frame→sessão, origem da estimativa, suporte observado, erro e confiança | Associar regiões a posições |
| Cobertura salva | Faixas nítidas, validadas no JPEG e persistidas, associadas às fotos | Contar informação capturada e concluir |

A geometria é atualizável e pode ser incompleta. A largura permanece na convenção existente de 256 unidades;
o comprimento pode ser descoberto aos poucos. Preservar a base de coordenadas da sessão para não deslocar
faixas já salvas quando uma referência melhor aparece. Uma referência ainda sem associação fica separada,
sem unir mapas por mera semelhança de proporções.

Distinguir arestas físicas observadas de limites do frame, projeções e extremos já conhecidos. Manter nitidez,
confiança geométrica e confiança de localização separadas. Nenhuma previsão concede cobertura.

Aceite: testes de política demonstram que geometria completa e localização confiável com texto desfocado
produzem zero cobertura; um polígono de faixa não é interpretado como documento inteiro.

## 2. Aproveitar a visão geral sem depender da nitidez

Arquivos: `DocumentVision.kt`, `DocumentTracker.kt`, novo `ReceiptGeometry.kt`.

- Reconhecer visão geral somente com LEFT/RIGHT/TOP/BOTTOM fisicamente detectados, interseções dentro do frame
  e geometria coerente. Confirmar consistência entre observações próximas; conservar a melhor referência.
- Estabelecer quadrilátero, transformação para a referência e estimativa da extensão sem exigir texto nítido.
  Perspectiva e curvatura reduzem confiança; razão entre comprimentos na imagem não é uma medida exata do papel.
- Distinguir um frame com pouco detalhe de um contorno incerto: desfoque de texto não impede necessariamente
  medir a geometria, mas bordas não localizadas com confiança não podem estabelecer uma referência.
- Criar/reter âncoras de localização independentemente de fotos. Só guardar descritores quando houver suporte
  suficiente; um frame sem descritores úteis pode fornecer geometria, mas não uma falsa âncora de conteúdo.
- Não basta retirar o teste `!doc.sharp`: hoje isso poderia inicializar o rastreador sem guardar uma âncora utilizável
  e deixá-lo sem conseguir registrar o frame seguinte. Resolver inicialização, retenção e recuperação em conjunto.

Aceite: visão geral desfocada estabelece extensão, aproximar com um extremo visível permite localização,
e as primeiras faixas só recebem crédito depois de aparecerem nítidas e serem salvas.

## 3. Localizar por geometria e por conteúdo, com evidência explícita

Arquivo principal: `DocumentTracker.kt`.

Usar estas fontes de localização, com comparação entre elas quando disponíveis:

1. **Quatro lados físicos:** localizar diretamente a nota inteira e verificar consistência com a referência.
2. **Duas laterais e extremo conhecido:** usar os cantos do extremo, largura e direção das laterais para estimar
   pose/escala e posição longitudinal. Começar por um modelo restrito de rotação, translação e escala em cenas
   próximas de frontais; aceitar somente se as arestas observadas forem compatíveis com esse modelo. Três linhas
   não determinam uma homografia arbitrária: perspectiva forte exige correspondências adicionais ou relocalização.
3. **Conteúdo sobreposto:** manter ORB/RANSAC para localizar frames sem extremos visíveis e para confirmar/corrigir
   estimativas geométricas. Retenção de âncoras passa a depender de qualidade de registro e novidade espacial,
   com limite de memória, sem ficar condicionada a salvar uma foto.
4. **Sem suporte suficiente:** localização incerta/perdida. Preservar o mapa, orientar retorno a região reconhecida
   e não creditar posições estimadas por largura apenas.

A identidade do extremo precisa ser conhecida pela continuidade, referência ou conteúdo; não supor que qualquer
linha horizontal perto do limite superior da imagem é o topo físico. Conferir erros de reprojeção, distribuição
espacial das correspondências, escala, orientação e coerência dos extremos. Divergências impedem ampliar a
cobertura até serem resolvidas; não apagar fotos válidas nem fundir sessões silenciosamente.

Manter a proteção contra linhas de produtos repetidas e saltos sem sobreposição. Não reintroduzir fluxo óptico
isolado como prova de continuidade. Projeções por movimento recente são úteis para busca, mas não bastam para
concluir que uma lacuna foi fotografada.

Aceite: localização e cobertura continuam ao sair o topo do enquadramento, inclusive com nenhum extremo visível
quando há conteúdo correspondente. Sem extremos e sem correspondências, o app conserva o progresso e pede retomada.

## 4. Usar o mapa para orientar a detecção seguinte

Arquivos: `ReceiptCameraAnalyzer.kt`, `DocumentVision.kt`, `DocumentTracker.kt` e `CaptureViewModel.kt`.

- Projetar a última geometria usando transformações recentes confiáveis; fornecer corredores de busca ao detector.
- Procurar bordas observadas perto da previsão e ajustar a pose com a medição atual. Usar a previsão como hipótese,
  nunca como segmento detectado nem como máscara válida por si só.
- Ao perder coerência, ampliar a busca e voltar à detecção global em baixa resolução. Fazer verificações globais
  periódicas para evitar seguir um objeto errado, inclusive troca de nota.
- Não transportar uma máscara antiga como verdade sobre pixels atuais. Atualizar/intersectar a região com
  evidência do frame atual e margem conservadora de incerteza antes de medir foco.
- Manter análise limitada ao frame mais recente, publicar bordas cedo e preservar o refinamento recortado/limitado
  da máscara. Evitar repetir extração ORB no mesmo frame e buscar primeiro âncoras espacialmente relevantes.

Aceite: segmentos observados acompanham o papel; falha de previsão recupera por busca global; a máscara não migra
para a mesa e a cena texturizada não melhora artificialmente a nitidez. Medir a latência antes e depois no Motorola.

## 5. Selecionar capturas e orientar pelo que efetivamente falta

Arquivos: `CaptureViewModel.kt`, `CapturePolicy.kt`, `CapturedPhotoValidator.kt` e `ScanScreen.kt`.

**Documento inteiro:** se geometria física completa e conteúdo inteiro aprovado, selecionar o melhor de poucos
frames numa janela curta e limitada. Reusar a janela existente como ponto inicial; não exigir estabilidade indefinida
nem salvar todos os frames. Validar o JPEG com sua máscara e conceder cobertura integral com uma foto, quando basta.

**Documento parcial:** salvar quando houver localização confiável e nova faixa nítida, preenchimento de lacuna ou
melhoria relevante de qualidade. A extensão geométrica conhecida define onde falta informação; não implica que
aquelas partes já foram fotografadas. Guardar espaço de sobreposição suficiente para comprovar continuidade.

**QR pendente:** conservar fotos aprovadas, pedir aproximação/centralização do QR e continuar analisando. Não
repetir a captura de toda a nota somente porque a identificação está pendente. Concluir apenas com QR válido,
associação à mesma nota e cobertura persistida completa. Não usar QR decodificado como prova de nitidez do texto.

**Interface:** continuar mostrando avisos simultâneos, um único destino de movimento e segmentos realmente
observados. Orientar pela lacuna relevante mais próxima; não mandar voltar ao topo só porque ele sumiu do frame
quando sua posição e cobertura já são conhecidas. Não exigir visão geral como etapa inicial obrigatória.

**Persistência:** manter JPEGs, máscaras, faixas e importação idempotente existentes. Acrescentar apenas metadados
locais opcionais que sejam necessários para interpretar posições/qualidade e a referência usada, compatíveis com
manifestos antigos. A geometria de trabalho vive no ViewModel; não criar retomada de imagens parciais após morte
do processo. Preservar saída sem QR e escolhas de guardar somente o código/descartar/continuar com QR. Sem troca de banco nem
nova migração PocketBase prevista para este incremento.

Aceite: nota inteira nítida conclui com uma foto e QR; nota inteira desfocada seguida de aproximação coleta somente
faixas válidas; QR tardio conclui sem exigir refazer cobertura; iniciar pelo meio continua funcionando.

## 6. Validar comportamento, máscara e desempenho antes da entrega

Ampliar testes de política e os testes instrumentados de visão/rastreamento/engine:

- Quatro lados desfocados: aprende geometria e não credita cobertura.
- Quatro lados nítidos e QR: termina com uma foto; uma região de texto desfocada bloqueia cobertura integral.
- Visão geral seguida de zoom com topo; repetir com fundo e percurso inverso.
- Trecho central sem extremos: localiza por conteúdo; sem conteúdo correspondente, declara perda e não fecha lacuna.
- Começo pelo meio sem nunca mostrar a nota inteira; extremo reconhecido reaparece depois.
- Salto entre extremos, linhas repetidas, troca de documento e QR conflitante: não misturar regiões nem fechar cedo.
- Perspectiva, giro, mudança de escala, papel estreito e fundo texturizado: rejeitar estimativa incompatível.
- Geometria nova após faixas salvas: manter base/posições e impedir duplicação ou reinterpretação silenciosa.
- QR tardio/ilegível, pausa, rotação e saídas: preservar as regras existentes e todos os dados anteriores.
- Máscaras após previsão/relocalização: fundo e vizinhanças de filtros continuam excluídos da nitidez.

Executar build, unitários, lint e instrumentados somente no emulador. Depois, no Motorola, conferir overlays,
qualidade das fotos, captura integral, visão geral→aproximação e perda/retomada com notas físicas. Nunca consultar
a SEFAZ para desenvolvimento nem instalar o runner de testes no telefone pessoal.

Registrar intervalos entre análises, tempo até publicar bordas, tempos de máscara/qualidade/registro/escrita,
mediana/p95/máximo, estabilidade das posições e encerramentos incorretos. Meta inicial: bordas acompanhando o
movimento em atualizações abaixo de 250 ms no cenário de teste habitual; não aceitar reaparecimento de bloqueios
multissegundo. É uma meta a demonstrar no aparelho, não desempenho já garantido. Medir separadamente janelas
de seleção/gravação, para que não ocultem atraso no feedback. Dimensionar buscas e retenção de âncoras pelo resultado.

## Ordem de entrega

Implementar 1–3 primeiro: modelo e localização, com testes, preservando o detector e a política atuais enquanto
se verifica o mapa. Em seguida 4: realimentação da detecção e medição da latência. Integrar 5: seleção integral,
faixas e orientação. Concluir com 6 e atualização dos documentos de validação. Cada incremento deve ser revisável;
a entrega ao telefone ocorre depois de testes e geração do APK, sem declarar validação física antecipadamente.

## Próximo slice após falha física: localizar o papel antes de ampliar o rastreamento

O acompanhamento de 2026-10-15 reprovou a captura real. Priorizar este reparo antes de localização pelo fundo
ou corredores previstos. Hipóteses devem ser separadas com evidência; o desalinhamento visual não prova sozinho
se a origem está na detecção ou na projeção para o preview.

1. **Diagnóstico no mesmo frame:** gerar visualização local do frame analisado com segmentos, polígono e máscara,
   comparada ao overlay no preview. Verificar transformação para tela sob rotação, proporções/crop e zoom; manter
   associação de cada resultado ao respectivo frame. Não publicar nem colocar imagens pessoais no repositório.
2. **Selecionar bordas do papel:** a pontuação atual favorece linhas longas, pares largos e sobreposição; isso não
   demonstra que delimitam papel. Avaliar candidatos por suporte local, mudança entre interior/exterior ao longo
   da borda e coerência com uma região de papel, sem pressupor papel sempre mais claro. Usar contornos/regiões
   como evidência complementar para vistas completas, preservando pares de bordas para trechos cortados. Rejeitar
   candidatos contraditos pela máscara. A máscara atual apenas remove pixels do quadrilátero inicial e não pode
   recuperar papel que ficou fora dele; não tentar corrigir uma escolha errada só no refinamento posterior.
3. **Estabilidade e orientação:** confirmar associações entre frames e aproveitar referências confiáveis para
   desambiguar candidatos; redetectar quando perder suporte. Testar nota deitada e inclinada, sem fixar a direção
   longitudinal à vertical da tela. Manter direções e coordenadas de captura coerentes ao mudar a orientação.
4. **Entrega verificável:** reproduzir fundo texturizado com linha forte, papel amassado/bordas irregulares,
   enquadramento parcial e orientação variável. Conferir as capturas privadas do teste físico offline e criar
   fixtures públicas sintéticas equivalentes. Testar máscara contra fundo e nitidez enganosa. Só então testar
   cobertura/rastreamento, medir a latência no Motorola e instalar para nova tentativa.

Aceite físico: segmentos acompanham as bordas reais, máscara exclui o fundo, trecho nítido permite progresso,
perda de localização não credita cobertura e a captura termina com evidência de topo a fundo e QR. Não basta
reduzir avisos nem aprovar novamente os testes sintéticos anteriores. Não trocar API de câmera nem introduzir
modelo neural neste slice sem diagnóstico que demonstre essa necessidade.


## Reparo de região e visualização implementado — 2026-10-15

O próximo slice descrito acima foi implementado após escolha do usuário: contorno sempre visível com controle
para máscara, e suporte a nota em pé/deitada. Regiões/contornos são a fonte primária; linhas são alternativa com
validação fotométrica. Contorno irregular, quadrilátero de registro e máscara conservadora são independentes.
Matrizes explícitas unificam as coordenadas; rotação canônica acompanha QR, nitidez, bitmaps e registro. “Inspecionar
frame” mantém somente em memória uma imagem congelada com os resultados dessa imagem. Não há exportação.

Passaram 41 testes unitários, 35 instrumentados sintéticos, build e lint. A revisão automática bloqueou o uso de
uma captura pessoal como fixture no emulador; o reparo foi validado com cenas sintéticas equivalentes, mantendo
imagens pessoais fora do repositório. O teste físico da nova versão continua necessário; o relato de falha anterior
não foi substituído por uma declaração de sucesso. Ver D31 e capture-validation.md.
