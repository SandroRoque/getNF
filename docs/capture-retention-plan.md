# Qualidade e retenção de capturas — próximo incremento

Registrado em 2026-10-15. Planejamento; nenhuma nova exclusão de imagens foi implementada.
O usuário aceita preservar muitas imagens por NF durante o desenvolvimento. A política definitiva deve
selecionar capturas legíveis e eliminar redundância sem perder informação.

## Ponto de partida verificado

Duas tentativas no Motorola concluíram e acrescentaram quatro e duas imagens à mesma NF. As fotos anteriores
foram preservadas. A revisão visual dos seis JPEGs finais, de 1440×1920, encontrou quatro imagens com conteúdo
principal legível, uma claramente borrada por movimento e outra mais fraca. A imagem borrada recebeu score
1,324 e passou. Máscaras inspecionadas seguiam o papel e excluíam o fundo: máscara correta não garante leitura.
Ver [capture-validation.md](capture-validation.md) para evidência e limites dessa revisão.

Hoje há seleção de candidatos úteis dentro de uma sessão, mas não há descarte após gravar nem deduplicação
entre sessões. Repetir uma NF acrescenta fotos ao mesmo registro. Android tem limite técnico de 100 fotos por
nota; a migração PocketBase de 20 para 100 foi aplicada ao servidor em uso em 2026-10-15, após backup (D36).
Isso não constitui a política final de retenção nem uma promessa de armazenamento ilimitado.
A extração estruturada ainda vem do HTML fiscal; as fotos não passam por OCR de produtos/descontos neste fluxo.

## 1. Corrigir a aceitação de imagens ilegíveis

Criar cenas sintéticas com texto, descontos, papel térmico fraco e QR, variando escala, desfoque e movimento
em várias direções. Medir o comportamento do score atual e localizar os falsos positivos antes de escolher
novos limiares. Avaliar somente conteúdo dentro da máscara conservadora, excluindo QR, fundo e bordas.

Comparar medidas locais sensíveis a borrão direcional e preservação de traços, além da energia global de
alta frequência. Avaliar regiões distintas do texto: um trecho bom não deve compensar outro ilegível.
O QR reconhecido não comprova nitidez do texto. Evitar simplesmente elevar um limiar global que rejeite
texto térmico fraco ou reduza a captura a distâncias impraticáveis.

Primeira entrega: benchmark reproduzível, diagnóstico do falso positivo e alteração pequena com regressões
sintéticas. Conferir também o JPEG decodificado, que é a evidência realmente armazenada. A validação física
posterior continua necessária; os exemplos pessoais permanecem fora do repositório.

**Diagnóstico da revisão de código (D35):** o score junta os gradientes X e Y (`Core.magnitude`). Borrão de
movimento apaga só as bordas na direção do movimento, e as perpendiculares mantêm a razão acima de 1,18; é a causa
provável do JPEG borrado aceito. Segundo caminho: tiles com desvio < 5 são tratados como papel vazio, e o borrão
reduz o contraste do texto térmico fraco; faixas vazias contam como utilizáveis e o QR sozinho ativa `anyContent`.
O benchmark deve cobrir os dois casos. Proposta a avaliar: razão por orientação (pior caso) e OCR embarcado nos
candidatos como critério de legibilidade, reutilizável no OCR de descontos.

**Resultado (D37):** o benchmark sintético está em `FocusBenchmarkTest`. A razão de gradientes não separa
legível de ilegível em tamanhos de letra diferentes; a decisão passou ao OCR embarcado em cada candidato salvo,
com o gradiente só como pré-filtro. Falta a calibração no Motorola. Números em capture-validation.md.

## 2. Classificar redundância sem apagar

Propor metadados por imagem: sessão/frame, dimensões, máscara, regiões de conteúdo avaliadas, medidas de
qualidade, transformação/confiança de registro, cobertura e extremos observados, presença do QR e versão do
avaliador. Distinguir medições disponíveis hoje de metadados que ainda precisam ser persistidos/migrados.

Simular seleção dentro da sessão e entre sessões da mesma NF usando conteúdo registrado, cobertura e escala.
Hash de arquivo sozinho não identifica fotos visualmente redundantes. Fotografias de papel semelhante ou
linhas repetidas não bastam para comprovar correspondência. Sem registro confiável, conservar ambas.

Preservar a melhor evidência legível para cada região, inclusive topo/fundo, detalhes pequenos e descontos;
uma visão geral e um close podem ser complementares. Uma imagem inferior pode conter informação exclusiva.
Não apagar a única evidência de cobertura nem deixar a seleção final depender de uma foto borrada. Preservar
QR legível quando necessário, sem deixar sua qualidade substituir a avaliação do texto.

Primeira entrega: relatório de simulação com imagens propostas para manter/descartar e razões por região,
sem mudar os arquivos. Comparar cobertura e qualidade do conjunto antes/depois. Os critérios definitivos e
o momento de descarte ainda precisam ser escolhidos com base nesses resultados.

## 3. Só depois habilitar política de retenção

Antes de remover arquivos, definir política explícita e revisar a simulação. Coordenar manifestação local,
referências de fotos/máscaras, cobertura, recuperação de sessão, versão de upload e referências no servidor;
interrupção ou nova tentativa não pode perder evidência nem deixar referências quebradas. Usar atualização
consistente e recuperável; não apagar originais enquanto a seleção não estiver validada.

**Pré-requisito identificado em D35 e confirmado em D36:** o upload atual substitui a lista de fotos do servidor pela do celular.
Apagar uma foto localmente a apagaria também no servidor, que é hoje o único backup. Antes de qualquer descarte,
o envio de evidências precisa só acrescentar (com identificação por conteúdo), e máscaras/metadados precisam
sair do celular. Isso pertence ao desenho da etapa 1 do roadmap.

Também proposto em D35: fazer a seleção final depois da sessão, como evolução deste passo, deixando à captura
em tempo real apenas a garantia de cobertura suficiente.

Não introduzir limpeza silenciosa retroativa neste incremento. A retenção liberal durante desenvolvimento
preserva material para diagnosticar erros e comparar avaliadores. Limites de armazenamento e comportamento
quando atingidos devem ser tratados explicitamente em uma entrega futura.

## Trabalho que pode avançar sem o celular

- Gerar benchmark sintético de borrão e avaliar scores por região/direção.
- Projetar metadados e simular seleção em sequências com sobreposição, lacunas e capturas repetidas.
- Testar preservação de cobertura exclusiva, detalhe/escala e falhas de registro no emulador.
- Documentar resultados e critérios antes de voltar à validação física.

Critério de aceite futuro: imagens rejeitadas não podem ser necessárias para recuperar texto ou cobertura;
o conjunto mantido deve sustentar leitura de todas as regiões. Testes sintéticos sustentam regressões,
mas não substituem conferência física de recibos térmicos com movimento, iluminação e distância variadas.
