# Descontos por item e OCR no Android

Avaliação de viabilidade em 2026-10-14. OCR local para recuperar descontos ainda é uma proposta; a associação de
descontos e sua precisão nas fotos do projeto não foram implementadas ou medidas. ML Kit OCR latino embarcado
foi usado no primeiro incremento da captura automática (D26) e removido no fluxo por segmentos (D28); a dependência
ainda está declarada no catálogo, sem uso. Desde D37 ele decide a legibilidade de cada candidato salvo na captura; ainda não interpreta descontos.

## Objetivo

A página atualmente suportada fornece o desconto total; as fotos podem conter descontos por item que não aparecem
no HTML. Recuperar esses valores melhora a distribuição de gastos e o preço líquido observado por produto,
importantes para o IPCI e para o histórico compartilhado. O OCR complementa o parser HTML no próprio Android.
O novo objetivo de [captura contínua automática](automatic-capture.md) deve fornecer imagens legíveis e cobertura
completa, inclusive relações de sobreposição para não duplicar descontos ao processar trechos do mesmo recibo.

## Opções

- **ML Kit Text Recognition, modelo latino embarcado:** opção prática para uma prova de conceito. O modelo vai
  no aplicativo e fica disponível sem download inicial; a extração executa no dispositivo. A documentação estima
  cerca de 4 MB adicionais por escrita/arquitetura. O projeto já usa ML Kit para códigos de barras, mas OCR exige
  uma dependência separada. É um SDK do Google, não a escolha de motor open source.
- **Tesseract com Tesseract4Android:** alternativa open source para Android, com integração nativa e arquivos de
  modelo de idioma a distribuir. Avaliar tamanho, desempenho e precisão nas mesmas imagens antes de escolher.

Não presumir que um motor seja mais preciso que outro sem comparar as fotos reais. Recorte, orientação, foco,
resolução e impressão térmica afetam a leitura. Nenhum dos dois fornece sozinho o entendimento da estrutura da nota.

## Fluxo proposto

1. Usar o HTML para estabelecer a lista de itens, códigos, quantidades, valores brutos e desconto total.
2. Executar OCR nas fotos salvas, preservando texto e posição dos trechos relevantes. Tratar sobreposição entre fotos
   para não contar o mesmo desconto duas vezes.
3. Identificar linhas de desconto e associá-las aos itens pela combinação de código, descrição, ordem e posição,
   sem confiar apenas na proximidade visual. Tratar ambiguidades como pendências.
4. Conferir valores em centavos: descontos não negativos, limites dos itens e soma comparada com o desconto total.
   Não aplicar uma leitura conflitante só para fechar a soma; conciliação aritmética não prova associação correta.
5. Mostrar o recorte de evidência junto da sugestão para o usuário confirmar ou corrigir, priorizando casos ambíguos.
6. Preservar descontos confirmados; ratear apenas o saldo não identificado entre itens elegíveis, quando consistente.
   Se não houver saldo distribuível ou os dados conflitarem, mostrar a pendência em vez de forçar o rateio.

Desconto global do cupom não deve ser tratado como desconto explicitamente observado de um produto. Desconto
já refletido no valor de uma linha não deve ser subtraído novamente. As regras precisam respeitar o layout e
a semântica dos documentos suportados.

## Consequências para os dados e a interface

- Separar origem do valor (HTML, OCR de foto, entrada manual ou rateio) de estado de revisão/validação.
- Guardar evidência e versão da extração para descontos lidos da foto; a leitura OCR não equivale a confirmação humana.
- Preservar correções manuais ao reprocessar HTML ou fotos.
- Distinguir preço líquido observado de preço líquido com desconto estimado por rateio, nas análises locais e coletivas.
- Oferecer entrada manual do desconto mesmo sem OCR. Fotos e texto OCR permanecem locais no modo local;
  compartilhamento segue o contrato que exclui dados do comprador.

## Entrega incremental sugerida

Primeiro definir descontos e correções no modelo (etapa 1 do roadmap) e oferecer edição manual (etapa 4).
Avaliar OCR como incremento das etapas 3–4, sem bloquear o parser HTML ou a entrega do cadastro manual.
A prova deve comparar descontos e associações com uma revisão humana em fotos salvas, incluindo descontos
parciais, notas sem desconto, fotos sobrepostas e leitura ilegível. Não consultar a SEFAZ para esses testes.

## Referências

- [ML Kit: OCR no Android e modelo embarcado](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
- [Tesseract](https://github.com/tesseract-ocr/tesseract).
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android).
- [Comportamento da página SEFAZ suportada](sefaz-sc.md).
