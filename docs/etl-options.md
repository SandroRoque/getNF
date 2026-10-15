# Onde executar a extração e o ETL

Discussão registrada em 2026-10-14. Este documento preserva a comparação feita ao considerar ETL em VPS.
**Decisão posterior do usuário: parser no Android**, com revisão manual e análises locais independentes.
Um serviço remoto poderá atender compartilhamento e curadoria opcionais; sua hospedagem ainda não foi escolhida.
Ver [local-and-shared-modes.md](local-and-shared-modes.md). As alternativas abaixo são contexto da decisão.

## O que o processamento atual faz

`server/pb_hooks/lib/nfce_page.js` extrai itens, estabelecimento, datas e totais do HTML já capturado. É JavaScript
sem dependências, baseado em expressões regulares. `derive.js` trata identificação, tamanho e rateio de descontos;
`ingest.js` integra esses resultados ao PocketBase. Não há um modelo de IA ou OCR no parser atual.

A obtenção da página na SEFAZ e a extração dos dados são operações distintas. O Android pode continuar lidando
com a WebView e desafios do portal, enviando o HTML salvo para processamento na VPS. Mover o ETL não exige
que a VPS passe a consultar o portal.

## Bibliotecas e reaproveitamento

- **jsoup:** biblioteca Java open source sob licença MIT, utilizável em Kotlin/Android para ler HTML salvo e
  selecionar elementos do DOM. É a principal candidata para uma implementação nativa do parser. Não traz as
  regras da NFC-e prontas: mapeamento, números, datas e conciliação continuam sendo responsabilidade do projeto.
  A documentação atual pede configuração de desugaring no Android; validar no build antes de escolher versão.
- **Reaproveitar o JavaScript:** os módulos puros atuais podem ser avaliados em um motor JS no Android. AndroidX
  JavaScriptEngine é uma opção documentada, com verificação de suporte em execução. Exige adaptar a interface
  CommonJS e validar disponibilidade, conversão dos resultados, inicialização e memória; não é um runtime Node.
- **Servidor/VPS:** o parser já roda nos hooks do PocketBase e nos testes em Node. Manter o ETL remoto permite
  reaproveitar essa implementação; a migração exige cuidar de arquivos, configurações, acesso, backup e operação.

Não há medição de desempenho local ainda. Pelo tipo de operação, a hipótese é que a extração de HTML seja
pequena comparada a tarefas futuras de OCR/IA; isso precisa ser verificado com capturas existentes, sem consultar
a SEFAZ para fazer benchmark.

## Alternativas de arquitetura

| Alternativa | Nova nota sem serviço remoto | Manutenção do parser | Principal custo |
| --- | --- | --- | --- |
| ETL no Android | Pode gerar itens após capturar a página | Atualização do app, ou mecanismo de atualização a definir | Implementar/integrar e manter o parser local |
| ETL na VPS + banco local | Captura fica pendente até processar remotamente | Atualização centralizada | Hospedagem, operação e disponibilidade da rede/serviço |
| ETL básico local + enriquecimento remoto | Dados básicos disponíveis localmente | Divisão explícita de responsabilidades e versões | Maior complexidade de integração |

Em todos os casos, banco local permite consultar o histórico já recebido e manter os fluxos manuais offline.
Se a VPS for necessária para transformar uma nova captura em itens, o aplicativo deixa de ser completamente
autônomo nessa operação. Ele pode continuar independente do PC doméstico e ter uma experiência Android completa.
A decisão posterior escolheu a autonomia de processamento local.

Um modelo híbrido não deve presumir dois parsers concorrentes sobrescrevendo resultados. Se houver processamento
nos dois lugares, definir versões, precedência e preservação das correções manuais.

## Avaliação proposta antes da decisão (histórico)

1. Definir se novas notas precisam produzir itens sem serviço remoto, ou se podem aguardar o ETL na VPS.
2. Manter o desenho do banco local independente do local de execução do parser, com estado de processamento,
   versão/origem dos resultados e correções preservadas.
3. Fazer uma prova pequena com fixtures salvas se a execução local continuar candidata: comparar resultado,
   esforço de integração e comportamento no dispositivo com o parser existente.
4. Escolher local, remoto ou híbrido antes de detalhar a etapa 3 do roadmap. Para VPS, avaliar hospedagem apenas
   depois de definir esse escopo; não há necessidade demonstrada de trocar o PocketBase ou o motor SQLite.

## Fontes consultadas

- [jsoup: recursos e licença](https://jsoup.org/).
- [jsoup: suporte a Android e instalação](https://jsoup.org/download).
- [AndroidX JavaScriptEngine](https://developer.android.com/jetpack/androidx/releases/javascriptengine).
- [PocketBase](https://pocketbase.io/docs/).
