# Captura contínua e automática de recibos

Implementação do fluxo por segmentos em 2026-10-14. Substitui o primeiro incremento de documento inteiro
(D26). Testes automatizados usam documentos sintéticos, sem consultar a SEFAZ. Duas capturas no Motorola
concluíram em 2026-10-15; a revisão dos JPEGs encontrou uma imagem borrada aceita, portanto a qualidade ainda
precisa ser corrigida e calibrada.
Resultados e comandos em [capture-validation.md](capture-validation.md).

## Experiência implementada

A câmera continua analisando enquanto o usuário percorre a nota. Duas laterais detectadas e regiões com conteúdo
nítido permitem coletar trechos; topo, fundo e QR podem aparecer em momentos diferentes, inclusive depois das
primeiras imagens. Não há disparador manual nem exigência de caber a nota inteira em um frame.

O preview desenha segmentos do contorno observado de uma região validada, em pé ou deitada.
“Mostrar área analisada” preenche a máscara efetivamente usada na nitidez, incluindo a exclusão do QR.
No debug, “Inspecionar frame” congela uma imagem com contorno, referência aproximada e máscara, sem exportar.
Resultados de contorno, máscara e orientação são publicados juntos para o mesmo frame, antes da gravação do JPEG. Os avisos são simultâneos, conforme a necessidade,
com breve estabilização para evitar oscilação: centralizar, ajustar distância/foco, iluminação, recuperar sobreposição
e procurar partes pendentes. Há um único destino de movimento por vez, e os indicadores de topo, fundo, cobertura
e QR permanecem visíveis. Não se trata de uma sequência de etapas que bloqueie a análise dos outros sinais.

O app fecha a câmera somente depois de importar uma captura completa: QR válido associado à área da nota,
topo/fundo confirmados e cobertura conectada de qualidade entre os dois. Ver os dois extremos sem demonstrar
continuidade não basta. A busca da página SEFAZ permanece uma ação explícita da tela inicial.

## Pipeline local

1. CameraX fornece preview e `ImageAnalysis` com alvo 1920×1440 e resolução inferior como primeira alternativa.
   Um executor serial limita a uma análise em andamento e aplica intervalo mínimo de 180 ms; frames antigos são
   descartados. O trabalho de visão e escrita fica fora da interface.
2. OpenCV propõe regiões em cinza com dimensão máxima 720, usando limiarização em ambas as polaridades e
   contornos de gradiente. Exige suporte de transição ao longo da borda e laterais observáveis; componentes do
   fundo que tocam as laterais do frame são descartados. Contornos completos ou cortados fornecem a máscara,
   independente do quadrilátero aproximado para registro. Como alternativa, pares de linhas exigem transição
   fotométrica nas duas laterais; GrabCut permanece limitado a uma região de até 240 pixels nesse caminho.
   Hipóteses brutas não são publicadas como bordas da nota. Sem localização confiável, a nitidez não é válida.
   O eixo do papel normaliza imagem, QR, máscara e fotos; mudanças de orientação exigem registro de conteúdo.
3. Após a detecção do QR, a nitidez é medida uma vez na resolução original, exclusivamente dentro da máscara erodida. A erosão exclui a vizinhança de
   todos os filtros; o fundo nunca entra nos gradientes usados nas estatísticas. Tiles com conteúdo comparam energia
   de gradiente em duas escalas, e faixas desfocadas não recebem crédito de cobertura. O QR decodificado é separado
   da avaliação do texto; não compensa texto desfocado. Não se aplica OCR a todo frame.
4. ML Kit QR embarcado identifica a chave em paralelo lógico com qualidade e cobertura. O QR deve estar dentro
   da nota. Um QR diferente bloqueia a conclusão até voltar ao documento original.
5. ORB com correspondências filtradas e homografia RANSAC registra o conteúdo sobreposto em coordenadas da nota.
   Falha de registro pede retorno ao trecho reconhecido. Fluxo óptico isolado foi excluído: linhas repetidas podem
   produzir movimento falso e conectar uma lacuna. As âncoras guardam descritores/transformações, com limite de 100.
6. Uma janela de 300 ms escolhe o melhor candidato útil. Salva-se exatamente o frame avaliado, em JPEG, e uma
   máscara PNG separada. A imagem JPEG decodificada é novamente avaliada com a mesma máscara. Somente faixas
   aprovadas e persistidas entram na cobertura. Topo/fundo exigem duas observações concordantes.
7. Trechos redundantes são evitados; nova cobertura, extremo ainda pendente ou melhoria relevante de qualidade
   justificam outra imagem. O conjunto final é ordenado, sem costurar ou substituir as imagens originais.

Esses critérios são heurísticas de captura, não prova de reconhecimento de cada caractere. Os limiares precisam
ser calibrados com papel térmico, movimento, pouca luz, sombras, reflexos e fundos pouco contrastantes no aparelho.

## Persistência, interrupção e saída

Cada tentativa usa diretório privado temporário, manifesto atômico e identificador único. Antes de concluir,
as imagens não são anexadas à nota e não são enviadas. O estado em ViewModel sobrevive à rotação da tela.

- Sair sem QR descarta toda a tentativa.
- Sair com QR e captura incompleta oferece: guardar somente o código e descartar estas imagens; descartar a
  tentativa inteira; continuar capturando. Todas as fotos e informações anteriores da nota são preservadas.
- Após morte do processo, uma tentativa incompleta conserva somente o código identificado e descarta imagens.
  Um lote que já iniciou o commit é recuperado de forma idempotente. Falhas de recuperação preservam os arquivos
  e não impedem carregar notas anteriores.
- A importação publica todos os trechos em uma única atualização do manifesto da nota. O identificador da sessão
  impede duplicação após reinício. Os metadados locais registram máscara, polígono, faixas, extremos e qualidade.
- O limite é de 100 fotos por nota, considerando fotos anteriores. A migração PocketBase altera apenas esse limite;
  não troca o banco. Seu rollback recusa voltar a 20 se houver uma nota acima de 20 fotos.

Parser local, OCR de descontos, catálogo/manual, IPCI e compartilhamento coletivo continuam incrementos separados.
Esta implementação não modifica essas políticas nem implementa o modelo final de contribuição.

## Fontes

- [ML Kit Document Scanner: capacidades](https://developers.google.com/ml-kit/vision/doc-scanner).
- [Scanner no Android: integração, interface e recursos baixados](https://developers.google.com/ml-kit/vision/doc-scanner/android).
- [CameraX: análise de imagens](https://developer.android.com/media/camera/camerax/analyze).
- [OpenCV: bordas e contornos](https://docs.opencv.org/4.x/df/d0d/tutorial_find_contours.html).


Atualização de desempenho em 2026-10-15: medições no Motorola identificaram GrabCut como gargalo (segundos por
frame). O recorte/grade limitada reduziu esse custo para dezenas a centenas de milissegundos nas amostras físicas.
Foram removidos limiares de cobertura relativos à largura da mesa e pedidos repetidos de AF. A interface oferece
foco manual, toque para focar, lanterna e zoom, preservando AF contínuo. Ver os números e limites em
[capture-validation.md](capture-validation.md).

## Referência geométrica independente da captura (2026-10-15)

Primeiro slice implementado: aproveitar os quatro lados, mesmo com texto desfocado, e localizar a aproximação
mantendo topo e laterais em cenas próximas de frontais. Geometria não concede cobertura; faixas continuam
exigindo nitidez, validação do JPEG e persistência. A interface sinaliza a referência confirmada e orienta foco/
estabilidade quando o topo já está visível. Localização geométrica pelo fundo e previsão de busca continuam
planejadas. O desenho completo é:

- Quatro lados visíveis e toda a área com conteúdo nítido: selecionar o melhor de poucos frames consecutivos,
  validar a imagem salva e considerar a cobertura completa com uma imagem. A identificação por QR continua
  independente; não exigir varredura quando já existe evidência de cobertura integral.
- Quatro lados localizados, mas texto sem qualidade suficiente: guardar geometria, extensão e proporção estimada,
  sem creditar cobertura. Esse frame serve para localizar futuras capturas, mesmo que não sirva ao OCR/arquivo.
- Enquanto as duas laterais e um extremo conhecido estiverem visíveis, usar essa referência para estimar escala,
  orientação e posição ao longo da nota. Confirmar a associação com conteúdo sobreposto quando disponível.
- Se nenhum extremo estiver visível, continuar por correspondências de conteúdo entre frames/âncoras. Duas
  laterais e largura conhecida não determinam sozinhas a translação ao longo da nota. Não adivinhar posição nem
  fechar lacunas quando faltar evidência suficiente.
- Proporção obtida de uma imagem é uma estimativa sujeita a perspectiva, inclinação e curvatura do papel.
  Manter confiança geométrica separada da qualidade do conteúdo e reconciliar novas observações dos extremos.
- Não obrigar uma visão inicial da nota inteira: ela é uma oportunidade de calibração; notas muito longas devem
  continuar aceitando início pelo meio/topo/fundo e descoberta incremental da extensão.

As limitações de inicialização por nitidez e de âncoras dependentes de fotos foram removidas neste slice.
Redetectamos as bordas a cada análise; o registro por ORB/RANSAC ainda não realimenta a previsão de onde
procurar as bordas no frame seguinte.

O desenho do incremento deve separar: geometria persistente da nota; localização/confiança do frame atual;
e mapa de faixas efetivamente nítidas, validadas e salvas. A geometria inicial não pode ser contabilizada como
cobertura nem liberar conclusão com texto desfocado. Predições de borda devem ser distinguidas dos segmentos
observados; não desenhar bordas extrapoladas como se tivessem sido detectadas.

Plano detalhado deste incremento: [capture-geometry-plan.md](capture-geometry-plan.md).


## Retenção durante o desenvolvimento — 2026-10-15

O filtro de candidatos evita alguns frames redundantes na sessão, mas não há limpeza após gravar nem
deduplicação entre sessões. Repetir uma NF acrescenta imagens ao mesmo registro. O usuário aceita preservar
muitas imagens enquanto desenvolvemos; não apagar evidências para resolver o falso positivo de nitidez.
O limite técnico atual de 100 fotos não define a política final de retenção. A migração correspondente foi
validada isoladamente e ainda não foi aplicada ao servidor em uso.

Heurísticas futuras devem rejeitar capturas ruins e selecionar as melhores entre redundantes sem perder
cobertura exclusiva, detalhes legíveis ou descontos. Primeiro avaliar em simulação, com razões de seleção
visíveis. Plano e critérios em [capture-retention-plan.md](capture-retention-plan.md); nenhuma exclusão nova
está implementada neste checkpoint.
