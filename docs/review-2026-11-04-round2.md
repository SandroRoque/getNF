# Segunda rodada — auditoria do Caderneta

**Estado: cenários prioritários da continuação concluídos com resultado ou impedimento documentado (2026-11-06); ver "Continuação 3" ao final.** Textos abaixo desta linha são o checkpoint anterior, preservado.
Base fixa: `749a325f9581cb9d92289d95f79d1cf4744870ed`. Nenhuma alteração de produção, commit, push ou ticket online.
Este documento é atualizado durante a execução para que os achados não dependam do histórico do chat.

## Síntese do checkpoint

**Nove achados novos reproduzidos (R4–R12), além de uma extensão de R2 sem recontagem.** Não recomendo abrir a loja com este commit: retirada incompleta de arquivos, totais divergentes e os problemas anteriores de privacidade/sincronização ainda precisam de correção e reteste. A auditoria não aplicou correções de produção.

A execução do plano **ainda não está integralmente concluída**. A [matriz detalhada](review-repros/round2/coverage.md) registra arquivos, cenários, evidências e lacunas em todas as frentes, inclusive os testes que faltam. Não tratar esta entrega intermediária como certificado de ausência de defeitos.

| Prioridade | Achado | Estado |
|---|---|---|
| P1 | R4 retirada de contribuições deixa objetos órfãos | Reproduzido com função/Storage/REST reais locais |
| P1 | R11 upload em voo persiste depois da retirada concluída | Reproduzido com UI/WorkManager/função/Storage reais e proxy local |
| P1 | R5 acréscimo some dos gastos | Reproduzido JVM |
| P1 | R6 desconto manual muda total indevidamente | Reproduzido JVM |
| P2 | R7 restauração conserva marcações do destino antigo | Reproduzido JVM e arquivo real Android, remoto simulado |
| P2 | R8 resposta LLM inválida conta como concordância | Reproduzido função original, serviços simulados |
| P2 | R9 download 503 não segue retry do worker | Cliente reproduzido por HTTP; alcance no worker confirmado por leitura |
| P2 | R10 backup omite arquivo ausente sem aviso | Reproduzido Android |
| P2 | R12 backup mistura snapshot do banco com página de outro instante | Reproduzido Android por escrita concorrente controlada; interleaving pela UI não demonstrado |

## Achados já reproduzidos

### R4 — P1 — Retirada de contribuições deixa arquivos online e apaga suas referências

- **Local:** `supabase/functions/contribute/index.ts`, bloco `request.kind === "withdraw"`.
- **Cenário:** uma instalação acumulou 1.001 arquivos ao longo do teste. A consulta de `contribution_file` não pagina; o limite REST é 1.000. A função exclui esses 1.000 objetos e depois apaga **todos** os registros da instalação.
- **Resultado:** responde sucesso, mas sobra um objeto no Storage sem registro para a próxima retirada encontrar.
- **Evidência real, local:** função original executada em Deno com rede restrita a localhost, PostgREST e Storage da instância descartável; 1.001 arquivos JSON sintéticos de dois bytes.

```text
withdraw: 200 {"message":"removed 1000"}
remaining Storage folders: 1
remaining database rows: 0
Expected: zero folders and rows
```

Reprodução: `docs/review-repros/round2/contribution_withdraw.py`; saída: `contribution-withdraw.txt` na mesma pasta.

**Correção/aceite:** paginar toda a retirada e excluir registros somente dos objetos cuja remoção foi confirmada. Testar mais de 1.000 arquivos, interrupção entre lotes e repetição: nenhum objeto órfão, nenhuma confirmação antes da exclusão completa. Auditar também `PhotoWithdrawal.withdraw`, que usa o mesmo padrão consulta limitada + DELETE amplo.

### R5 — P1 — Acréscimo lido pelo parser desaparece dos gastos

- **Local:** `data/ReceiptParser.kt`, construção de linhas/cabeçalho; `data/Spending.kt`, `netCents`.
- **Cenário sintético:** fixture com valor líquido de R$ 30,64 recebe acréscimo de R$ 1,00 e valor a pagar de R$ 31,64. O parser reconhece `surchargeCents = 100`, mas o modelo de linhas não incorpora o acréscimo.
- **Resultado:** cabeçalho = 3.164 centavos; soma dos gastos = 3.064 centavos.
- **Reproduzido:** `Round2ArithmeticTest.surchargeMustReachSpending`.

```text
java.lang.AssertionError: expected:<3164> but was:<3064>
```

**Correção/aceite:** representar o acréscimo no modelo e nos agregados sem fabricar desconto negativo; definir sua atribuição às categorias. Total da nota, gastos, exportação e sincronização devem conservar os mesmos centavos.

### R6 — P1 — Desconto manual aceito pela tela altera indevidamente o total da nota

- **Local:** `data/CatalogEditor.kt`, `setDiscount`/`reprorate`; `parse/Derive.kt`, `lineDiscounts`; `ui/receipt/ReceiptScreen.kt`, validação de Salvar.
- **Cenário:** a nota tem desconto total de 50 centavos. O usuário informa 51 centavos numa linha cujo bruto é maior que 51. A tela aceita porque valida só `0..grossCents`. O rateio zera o restante, mas preserva os 51.
- **Resultado:** soma dos descontos = 51, cabeçalho = 50. A edição que deveria redistribuir o desconto reduz o gasto total.
- **Reproduzido:** `CatalogEditorTest.reviewRound2ManualDiscountMustKeepReceiptTotal`.

```text
manual distribution must preserve receipt discount expected:<50> but was:<51>
```

A regra do modelo diz explicitamente que o total não muda, só a distribuição entre categorias (`docs/data-model.md`).

**Correção/aceite:** validar a soma das correções contra o desconto da nota na camada de dados e informar o conflito na tela. Cobrir várias linhas manuais, fotos e reprocessamento; nunca produzir inconsistência silenciosa.

### R7 — P2 — Backup restaurado em outro destino conserva marcações de envio do destino antigo

- **Local:** `data/BackupService.kt` e `data/Backup.kt` copiam/restauram o banco inteiro; `sync/SyncEngine.kt`, seleção de registros pendentes.
- **Cenário:** backup de nota já sincronizada é restaurado em outra caderneta/conta. O manifesto não vincula a origem ao destino; os estados `uploadedVersion`, `dirty`, cursores e bases continuam como estavam.
- **Resultado reproduzido no motor:** a nota existe localmente, mas não é enviada ao destino vazio, mesmo após duas sincronizações.
- **Teste:** `SyncEngineTest.reviewRound2RestoredCleanDatabaseMustUploadToNewLedger`.

```text
restored receipt must exist in destination expected:<1> but was:<0>
```

**Confirmação Android:** exportação e restauração reais no API 36, seguidas de sync com destino simulado vazio, reproduziram `expected:<1> but was:<0>` em `BackupServiceTest.reviewRound2RestoredArchiveMustSendReceiptToNewLedger`. Log: `instrumentation-round2.txt`. O servidor desta reprodução é simulado; a troca de conta pela UI permanece não exercitada.

**Correção/aceite:** identificar o destino no backup e decidir explicitamente entre restaurar o mesmo vínculo ou importar em outro destino reiniciando o estado de sincronização. Testar mesma conta/caderneta, instalação nova e destino diferente; nenhuma falsa confirmação de envio.

### R8 — P2 — Resposta inválida do LLM conta como auditoria concordante

- **Local:** `tools/llm-worker.py`, `audit`: `ok = llm_cat is None or llm_cat == p["category_id"]`.
- **Cenário:** o modelo responde `{}` ou uma categoria fora da lista. `llm_cat` vira `None`.
- **Resultado:** grava `audit_log.agree = true` e atualiza `llm_checked_at`, contando uma resposta sem avaliação válida como concordância e retirando o produto dos nunca revisados.
- **Reproduzido sem rede:** função original com modelo e banco simulados em `docs/review-repros/round2/worker_tests.py`.

```text
audit ok SYNTHETIC | Food -> -
AssertionError: True unexpectedly found in [True]: empty model response is not a validated agreement
```

**Correção/aceite:** resposta inválida deve ser falha/abstenção, sem marcar produto como auditado nem entrar no denominador de concordância. Cobrir JSON vazio, categoria desconhecida e erro de parsing.

## Evidências já preservadas

Todas em `docs/review-repros/round2/`, salvo os testes Kotlin:

| Área | Execução | Resultado salvo |
|---|---|---|
| Build/JVM original | Testes de revisão excluídos | `jvm-baseline.txt`: BUILD SUCCESSFUL |
| Deno existente | Regras de contribuição e leitura | `deno-baseline.txt`: 13 passaram |
| SQL existente | Instância descartável, todas as migrações do commit | `pgtap-baseline.txt`: 100 passaram |
| HTTP/RLS/Storage | 5 usuários sintéticos, papéis, estranho e ex-membro | `http-matrix.txt`: 61 verificações, 0 falhas |
| Integração de sync | `SupabaseLocalTest` com URL/chaves descartáveis explícitas | `supabase-integration.txt`: BUILD SUCCESSFUL |
| Aritmética/novos bugs JVM | 1.000 rateios aleatórios, limite de mês, zeros, R5/R6/R7 | `jvm-round2.txt`: 5 testes, 3 falhas esperadas |
| Retirada >1.000 arquivos | Função original + Storage/PostgREST reais locais | `contribution-withdraw.txt`: R4 reproduzido |
| Worker | Resposta inválida e dry-run | `worker-tests.txt`: 1 falha esperada; dry-run passou |
| Sintaxe e segredos | Python AST, bash -n; 2.220 blobs ancestrais de HEAD | `static-checks.txt`: nenhum candidato nas 3 assinaturas pesquisadas; não é varredura universal |
| Release | Validação local de manifests | `release-check.txt`: 4 manifests, ok |
| Instrumentação inicial API 36 | Migrações existentes, backup, WebP, paridade, transformação de câmera, captura | `instrumentation.txt`: 14 testes, 12 passaram; 2 de captura falharam por seletor obsoleto `Escanear nota`; continuaram falhando após onboarding. Ajuste somente nos testes em execução |
| UX | Primeiro uso no emulador, sem conta, consentimento recusado | Screenshots em `screens/`; navegação ainda em andamento |

## Hipóteses descartadas / relações com primeira rodada

- **503 estacionado permanentemente:** teste inicial simulou `SupabaseException(503)`, mas `SupabaseClient.call` converte 5xx/429 em `IOException`. Não é achado do aplicativo real. Teste inválido removido; log exploratório `jvm-repros.txt` não deve ser citado como defeito confirmado.
- R1–R3 da primeira rodada continuam no relatório anterior; não são contados novamente aqui.
- Nenhum contato com SEFAZ, Supabase online, Play ou serviços externos de categorização.

## Continuação preservada

A lista vigente de pendências está ao final de `docs/review-repros/round2/coverage.md`. As reproduções permanecem no worktree; nenhuma foi cadastrada como ticket online. Não apagar os testes que falham intencionalmente: são asserções do comportamento esperado que expõem os bugs deste commit.

### Ambiente de execução (sem credenciais)

- Worktree: `/home/roque/.codex/worktrees/7007/getNF`.
- Supabase descartável: projeto `getnf-review-round2`, arquivos em `/tmp/getnf-review-round2`, API `127.0.0.1:55321`, DB `127.0.0.1:55322`. Instância original `getnf` intacta.
- Função original contribute: wrapper local em `/tmp/getnf-review-round2/serve-contribute.ts`, porta 55328; processo ainda ativo neste checkpoint.
- Credenciais locais só em `/tmp/getnf-review-round2/status.json`, nunca copiar para o relatório.
- Emulador autorizado: `emulator-5554`, API 36. Nenhuma imagem API 26 instalada. App debug com dados limpos; onboarding concluído e contribuição recusada. Instalar APK explicitamente local antes de interações com conta.
- Helpers temporários: `ui.py`, `start-function.py`, `/tmp/getnf-review-build.py`, `/tmp/getnf-review-integration.py`.

### Checkpoint adicional

- Migração instrumental de cada schema histórico 1–13 para 14 executada: passou, com notas/fotos e correções representativas. Não cobre todas as combinações de dados históricos.
- Instrumentação da segunda execução: 10 testes, 7 passaram, R7 falhou como esperado e dois testes de captura bloquearam no seletor antigo.

### R9 — P2 — Falha transitória no download não agenda repetição automática

**Reproduzido com HTTP real em loopback:** `Round2HttpTest.downloadTransientFailureMustRetryAndKeepExistingFile` devolve 503 ao cliente de Storage. `SupabaseClient.kt:180` lança `SupabaseException`, enquanto o caminho REST converte 429/5xx em `IOException`. `SyncWorker.kt:74` só retorna `Result.retry()` para IOException; a exceção do download termina em `Result.failure()`.

Esperado: preservar arquivo local e agendar retry. Observado: arquivo preservado, mas exceção classificada como recusa definitiva. A sincronização pode ser retomada por novo disparo manual/evento; não é perda permanente de dados. Log `http-client.txt` e XML `http-client-results.xml`. O teste REST dos cinco status 401/403/429/500/503 passou. O cenário de download parou na primeira asserção (503); 429 compartilha o ramo por leitura, sem alegar execução desse segundo caso.

**Correção/aceite:** unificar classificação de erros dos downloads com REST; 429/5xx devem chegar ao worker como erro transitório. Testar retry, conteúdo parcial, arquivo anterior intacto e retomada sem duplicações. Esta evidência substitui a hipótese descartada de estacionamento no upload: são caminhos distintos.

### Outras validações concluídas

- `server-restore.txt`: dump sintético usando a seleção de tabelas do backup operacional, restaurado em `review_restore`, exclusivamente no container descartável. Origem e destino: 11 usuários, 5 notas, 23 linhas, 8.828 centavos. Schema preparado separadamente; não testou baixar/restaurar arquivos do Storage nem recuperar configurações/secrets do serviço.
- `site-tests.txt`: três casos passaram: recuperação limpa fragmento, PUT só ao endpoint configurado; erro de confirmação é texto; senhas diferentes não enviam pedido. DOM/fetch simulados, sem enviar e-mail ou trocar senha real.
- `capture-retry.txt`: após corrigir seletor do teste, recriação da Activity preserva a sessão e reentrada cria nova sessão (passou); outro teste ainda usava texto de instrução antigo, atualizado somente no teste para nova execução.

### Checkpoint de cobertura adicional

- `sync-boundaries.txt` / `sync-boundaries-results.xml`: uma nota com página, 23 interrupções, uma após cada chamada remota do envio inicial; servidor aplica a operação antes da IOException. Duas sincronizações seguintes conservam unicidade, todas as linhas e centavos. **Passou.** Não cobre todas as instruções locais entre chamadas nem falhas de energia/fsync; reconciliação de exclusão permanece R1.
- `capture-final.txt`: 2/2 passaram, rotação, controles, recriação de Activity e nova sessão na reentrada. Ajustes somente nos seletores e texto do teste; nenhuma alteração no aplicativo.
- `edge-offline.txt`: handlers originais com fetch interceptado e Deno sem permissão de rede: segredo ausente/incorreto bloqueado, fila vazia sem chamada externa, método/identificador/caminho de contribuição inválidos rejeitados. Passou.
- `script-tests.txt`: 4 testes de funções originais passaram: paginação recursiva de Storage com 1.001 objetos, senha fora do argumento pg_dump, vínculo bundle/manifesto e limite das notas de release. Sem instanciar clientes operacionais ou ler `.env.local`.
- Demonstração sintética abriu com 12 notas no mês; home e Gastos exibiram R$ 798,77. Fonte 150%: screenshot `06-demo-font150.png` mostra “Catálogo” quebrado em duas linhas (última letra isolada), sem bloqueio de navegação observado. Registro de UX de baixa gravidade, fora dos seis achados centrais. Fonte original restaurada após o teste.

### R10 — P2 — Exportação omite foto ausente e ainda declara backup completo

**Reproduzido no Android API36:** criar nota com uma foto sintética, remover apenas o arquivo de origem (simulando estado incompleto), executar exportação. `BackupService.kt:44` usa `files.filterValues { it.isFile }`, mas o manifesto mantém `photos = counts[1]` obtido do banco. A exportação termina com sucesso e o ZIP não contém a foto referenciada. Nenhum aviso distingue backup parcial.

**Esperado:** falhar de forma recuperável ou explicitar o arquivo ausente e não apresentar a cópia como completa. **Observado:** `BackupServiceTest.reviewRound2ExportMustNotPromiseMissingPhoto` falha porque a exportação retorna sucesso. Evidência `backup-redaction.txt`.

A auditoria removeu intencionalmente o arquivo **sintético**; este teste não demonstra que o aplicativo apaga fotos espontaneamente. O defeito é a garantia falsa na exportação de um estado incompleto, que impede perceber o problema antes de depender do backup.

**Correção/aceite:** confrontar referências do snapshot com arquivos exportados, indicar ausências e validar o conjunto também na inspeção. Cobrir foto, página e máscara ausentes, além de desaparecimento durante exportação; nunca declarar cópia integral contendo referências quebradas.

### Extensão de R2, sem novo número

`Round2RedactionTest` constrói um bitmap sintético com região privada conhecida independentemente do OCR. O OCR simulado reconhece só a linha de item, omitindo a região privada. A cópia ainda é produzida; apenas 1.053 de 24.531 pixels da região são escuros (os próprios caracteres), em vez de máscara integral ou rejeição. É o mesmo mecanismo fail-open de R2, agora exercitado com OCR **parcial**, não somente vazio. Não valida o reconhecimento ML Kit real nem todos os padrões de CPF; não é um novo achado independente.

`backup-redaction.txt`: 4 testes, 1 passou (round trip original), 3 falhas esperadas (R7, R10, extensão R2). Login local com conta sintética também executado: teclado/formulário funcionaram e a tela mostrou o usuário autenticado; screenshot `08-local-login.png`. A tela ainda indicava sincronização no instante da captura, portanto essa imagem não prova término do worker.

## Localização precisa no commit auditado

Os caminhos Kotlin abaixo começam em `android/app/src/main/java/io/github/sandroroque/getnf/`.

| Achado | Arquivo/linha principal | Propagação |
|---|---|---|
| R4 | `supabase/functions/contribute/index.ts:30` | select sem paginação; DELETE amplo na linha 37; padrão correlato `sync/PhotoWithdrawal.kt:16`, `sync/LedgerService.kt:98`, `web/apagar-conta/app.js` |
| R5 | `data/ReceiptParser.kt:135` e `:151` | `data/Spending.kt:40` e `:125` |
| R6 | `data/CatalogEditor.kt:316` e `:337` | `parse/Derive.kt:97`; `ui/receipt/ReceiptScreen.kt:207` |
| R7 | `data/BackupService.kt:32` e `data/Backup.kt:125` | `sync/SyncEngine.kt:206` pula a nota já marcada como enviada |
| R8 | `tools/llm-worker.py:214` | `agree` no audit_log e `llm_checked_at` nas linhas seguintes |
| R9 | `sync/SupabaseClient.kt:183` | classificação alternativa em `:203`; catches em `sync/SyncWorker.kt` |
| R10 | `data/BackupService.kt:44` | manifesto usa contagem do banco em `:49` |

## Críticas de desenho e decisões relacionadas

- **D47 (backup):** snapshot físico preserva integridade relacional, mas mistura dados do usuário e estado de transporte. R7 exige separar a identidade do destino da importação; R10 exige conferir também referências aos arquivos, além dos hashes dos arquivos que chegaram ao ZIP.
- **D98/D161 (privacidade):** máscara baseada só no texto reconhecido não oferece garantia de que o restante da imagem não contém dados privados. R2 parcial confirma a mesma fragilidade. Retirada deve preservar trabalho pendente e referências até confirmação completa (R4); DELETE SQL não equivale à exclusão dos objetos.
- **D164 (convergência):** políticas de merge por campo são testadas, mas os cortes após persistência remota e antes da confirmação local precisam permanecer no conjunto de regressão. Os 23 cortes passaram no FakeServer; isso não elimina R1 nem R3, demonstrados em outros limites.
- **D165 (nItem):** não há cenário novo que invalide a premissa. Não reaberto.
- **Processo de release:** checks de manifesto e hash são úteis; o CI atual não roda pgTAP, Deno ou instrumentação, então seu sucesso não cobre os defeitos dessas camadas. Os seletores obsoletos de captura ilustram a lacuna. Recomenda-se gate local reproduzível dessas suítes antes de publicar, sem depender de ambiente de produção.

## Versão curta para copiar ao outro agente

> Auditoria do commit 749a325, sem incluir alterações posteriores e sem correções de produção. Oito novos achados reproduzidos:
>
> - R4/P1: contribute.withdraw consulta no máximo 1.000 arquivos, remove esses objetos e apaga todas as referências. Com 1.001, sobra um objeto privado órfão. Paginar e confirmar exclusão por lote.
> - R5/P1: parser lê acréscimo, mas gastos só somam bruto menos desconto. Nota de 3.164 centavos aparece como 3.064. Conservar acréscimos nos agregados.
> - R6/P1: correção manual de desconto pode ultrapassar o desconto da nota: total 50, linha 51 aceita. Validar soma na camada de dados e manter total.
> - R7/P2: backup preserva uploadedVersion/dirty/cursor/base de outro destino. Nota restaurada não é enviada à nova caderneta vazia. Definir restore do mesmo vínculo versus importação em outro.
> - R8/P2: LLM respondendo {} conta como agree=true e marca llm_checked_at. Resposta inválida deve falhar/abster-se e continuar pendente.
> - R9/P2: download HTTP 503 lança SupabaseException; SyncWorker termina sem retry. Uniformizar 429/5xx com o tratamento transitório de REST.
> - R12/P2: exportação libera o writer antes de copiar os arquivos. Gravação controlada nesse intervalo gera ZIP aceito pela inspeção, com metadados antigos e HTML novo. Criar snapshot coordenado dos arquivos e banco; reprodução na API real, ainda sem medir alcance por interação UI.
> - R11/P1: retirada termina, mas upload antigo ainda em voo persiste depois. App fica enabled=false e withdraw=false com arquivo online. Coordenar revogação e pedidos em voo; cancelamento de worker não basta.
> - R10/P2: exportação filtra arquivo ausente, mantém contagem da foto e declara sucesso. Rejeitar ou informar backup parcial; validar referências.
>
> R1–R3 não foram recontados. OCR parcial foi reproduzido com imagem sintética como extensão de R2. Detalhes, aceite e evidências em docs/review-2026-11-04-round2.md e docs/review-repros/round2/. O plano ainda tem lacunas explícitas na matriz; não declarar a auditoria integral concluída.

- Ampliação HTTP concluída: `http-matrix-expanded.txt`, **81 verificações, zero falhas**, acrescentando overwrite/delete de Storage e alteração de ledger_id/exclusão de issuer pelos cinco papéis; conferidos os efeitos persistidos, não só status HTTP. Não é ainda CRUD completo de todas as tabelas.

### Histórico da hipótese de retirada concorrente (agora confirmada como R11)

`consent-race.txt` / `consent-race-results.xml`: teste com barreira no transporte de `TestContribution.send` deixa um arquivo após simular retirada concluída antes do POST antigo terminar. Neste checkpoint inicial era **hipótese pendente**: o teste impunha a ordem dos dois workers sem demonstrar o agendamento real. A integração abaixo confirmou o cenário. A chamada HTTP síncrona não recebe cancelamento explícito em `ContributionWorker`; verificar agendamento/cancelamento antes de promover a achado. A evidência inicial isolada não bastava; a reprodução integrada abaixo é a confirmação vigente.

Login + sync local: tela passou a mostrar “Sincronizado há 1 min”, screenshot `09-local-sync-complete.png`.

### Ambiente adicional da captura sintética

AVD descartável criado em `/tmp/getnf-review-round2/avd`, nome `review_round2`, serial **emulator-5556**, API36 já instalada. Câmera usa `docs/review-repros/round2/fixtures/camera-synthetic.png`, QR com URL `10.0.2.2:55329` e chave sintética; nenhum host SEFAZ. Tentativa inicial de segundo processo do mesmo AVD foi recusada pelo emulator e não alterou o original. O novo AVD independente está ativo; a instância `emulator-5554` permanece preservada. Ao terminar, encerrar apenas emulator-5556.

### Contagem auditável da baseline

Nova execução separada das reproduções: `jvm-baseline-final.txt` e `jvm-baseline-counts.json`: **219 testes, 208 passaram, 11 pulados, zero falhas**. Pulados: ExportReparseTest (backup externo), DemoCaderneta.write (gerador), PhotoDiscountsEvalTest, PhotoReaderBenchTest, SensitiveTextTest.realFrames/realFramesLeaks, CaptureBenchTest (corpus real não usado) e quatro SupabaseLocalTest (dependem de ambiente). Os quatro de Supabase foram executados separadamente com a instância descartável (`supabase-integration.txt`); não contar os outros sete como validação. Nenhum benchmark de mídia real foi usado como evidência de desempenho.

## R11 — P1 — Upload em voo persiste após a retirada de consentimento terminar

**Estado: reproduzido integrado, com UI e WorkManager reais.** Arquivos: `sync/ContributionWorker.kt:35` e `:66`, `data/TestContribution.kt:43` e `:147`; `supabase/functions/contribute/index.ts:29` e trecho de upload. Caminhos Kotlin relativos à raiz indicada acima.

1. App debug no emulator-5554, endpoint explicitamente local, consentimento ligado e fila com página HTML de 33 bytes inventados. Preferências anteriores salvas em `/tmp`; nenhuma nota pessoal usada.
2. WorkManager real inicia o POST. `consent_proxy.py` recebe seu corpo e segura o encaminhamento ao backend, simulando pedido lento/em voo.
3. Pela tela Ajustes, desligar “Enviar fotos durante o teste”. O worker de retirada substitui o anterior e a função original conclui a exclusão no Supabase descartável.
4. O proxy libera o POST antigo à mesma função original. O servidor aceita e persiste o objeto **depois** da retirada.
5. Consultar Storage/PostgREST e preferências do app. Não sobra nova retirada pendente.

```text
UPLOAD_BODY_RECEIVED page.html
WITHDRAW_BACKEND_200
UPLOAD_BACKEND_200
Persisted contribution rows AFTER completed withdrawal: 1 ['page.html']
Storage object after withdrawal: 200 synthetic bytes: 33
Consent flags: {'enabled': 'false', 'withdraw': 'false'}
```

**Esperado:** concluída a retirada, nenhum pedido anterior ainda pode deixar contribuição persistida sem nova limpeza. **Observado:** objeto e linha voltam após sucesso, com consentimento desativado. A ordenação foi imposta por um proxy de teste; não se mediu a frequência dessa corrida em produção. Transporte, app, agendamento, função e armazenamento deste cenário foram reais locais.

**Causa/alcance:** trocar/cancelar work não constitui barreira contra efeitos remotos de uma requisição já transmitida. A retirada limpa o estado naquele instante, mas não invalida uploads antigos; o flag é limpo antes de o pedido antigo terminar. É independente de R4: este caso usa **um** arquivo, então paginar a retirada não o corrige.

**Correção/aceite:** estabelecer uma geração/estado de revogação verificável pelo servidor ou outra coordenação que impeça pedidos anteriores de persistirem depois da retirada; não tratar cancelamento local como confirmação remota. Repetir a ordem acima, timeout/resposta perdida, reenvio e consentimento ligado novamente. Depois da retirada concluída, não pode haver arquivo residual nem fila antiga autorizada.

**Evidências:** `consent-proxy.txt`, `consent-race-integration.txt`, `consent_proxy.py`, `prepare_consent_device.py`, `check_consent_race.py`. Teste determinístico menor: `TestContributionTest.reviewRound2WithdrawalDuringAnInflightSendMustNotLeaveAnUpload`; XML `consent-race-results.xml`.

- Limpeza da reprodução R11 concluída: objeto sintético removido e preferências originais de servidor/conta/consentimento restauradas no emulator-5554; `consent-cleanup.txt`. Proxy local encerrado após a reprodução.

## Checkpoint de execução — 2026-11-06

- **Storage:** `storage_restore.py` executou o `mirror` original contra o Supabase descartável, restaurou os **14 objetos** em bucket separado e comparou SHA-256 de todos os bytes. Segunda execução não baixou nenhum objeto inalterado. Bucket de destino e fixture de origem removidos ao terminar; evidência `storage-restore.txt`. Isso complementa o dump sintético já restaurado; não prova consistência temporal entre dump e arquivos durante escritas concorrentes.
- **Worker:** suíte ampliada em `worker-expanded.txt`: cinco testes, quatro passaram e um reproduziu R8. Timeout simulado não grava decisões; dry-run, parsing de JSON e exclusão de categoria manual passaram. Não é integração com provedor nem teste de dois processos concorrentes.
- **Captura sintética:** emulador descartável `emulator-5556`, API36, câmera alimentada apenas pela fixture inventada. Quatro frames salvos em `capture-diagnostic/`, com OCR de sete linhas e um QR detectado. A captura permaneceu FIND_RECEIPT. Replay pelo benchmark original reproduziu exatamente as decisões: lado esquerdo encontrado em 21 bandas; lado direito rejeitado em todas. A fixture tem margem direita maior que metade da largura do bloco de texto, rejeitada pelo limite de `PaperFinder.kt`. **Não contado como bug novo**: falta distinguir adequação da fixture de alcance indevido da heurística. O benchmark concluiu sua execução, mas **a captura não foi concluída**. Logs em `capture-replay.txt` e `capture-diagnostic/ingest/log.txt`; não equivale a teste de câmera física ou de recuperação offline.

### Captura e recuperação local concluídas neste checkpoint

A segunda fixture (`make_camera_fixture.sh`) reduziu a margem direita: captura completa, uma foto salva em um frame; screenshot 14. Isso descarta falha geral do OCR/câmera nesse ambiente, sem validar toda a heurística do papel. O replay dos dois cenários coincide com as decisões reais (`capture-replay-final.txt`); esse corpus não possui oráculo de itens no benchmark, portanto `items=0` não é cobertura de legibilidade.

Com servidor loopback desligado, Buscar a página mostrou `ERR_CONNECTION_REFUSED` (screenshot 15). Pular manteve a nota pendente. Depois de encerrar o processo e abrir novamente, a foto permanecia acessível. Com `page_fixture_server.py` ativo, nova busca salvou a fixture SC e exibiu oito itens e total R$30,64 (R$31,14−R$0,50), mantendo uma foto; screenshot 16 e `page-recovery-ui.txt`. Nenhuma requisição à SEFAZ: QR usa `10.0.2.2:55329`, redirecionamento apenas local, HTML sem recursos externos. A foto e o HTML têm conteúdos diferentes deliberadamente; não se alega correspondência OCR/HTML desse teste. A mensagem de falha aparece em inglês no WebView, sem ação de tentar novamente na mesma tela: observação de UX, não novo bloqueio de uso, pois voltar e repetir funcionou.

## R12 — P2 — Backup aceita banco e página de instantes diferentes

**Classificação:** reproduzido em Android com Room/ZIP reais e escrita controlada pela API de produção. Não foi reproduzida a sequência concorrente exclusivamente por gestos na UI; não se afirma frequência nem que toda sincronização provoca o caso.

**Arquivo/linha:** `data/BackupService.kt:29–45`, `data/Backup.kt:54–60`, `data/ReceiptStore.kt:119–121`. A conexão writer protege checkpoint, cópia do banco e lista de arquivos; é liberada antes de os bytes desses arquivos entrarem no ZIP. `savePage` substitui o mesmo `page.html`.

**Entrada/execução:** `BackupServiceTest.reviewRound2ConcurrentPageWriteMustProduceCoherentBackup` cria uma nota com URL `/before` e HTML `synthetic before 10.00`. No callback de progresso após o primeiro arquivo arquivado, chama `ReceiptStore.savePage` com URL `/after` e HTML `synthetic after 20.00`. Exporta, verifica todos os hashes, extrai e abre o banco restaurado. Nenhuma correção de produção ou arquivo pessoal envolvido.

**Esperado:** o par URL/conteúdo é inteiramente anterior ou posterior à gravação, ou a exportação recusa explicitamente o conflito. **Observado:** exportação e inspeção sucedem; banco contém URL `/before`, página contém `synthetic after 20.00`. Asserção de consistência falha, conforme `backup-concurrent.txt`. A ordem é imposta no callback para reproduzir o intervalo, sem depender de sorte do escalonador.

**Alcance e decisão:** a garantia declarada de consistência em BackupService/D47 não inclui snapshot dos arquivos. R10 trata arquivo ausente antes da exportação; aqui todos os arquivos existem e hashes conferem, portanto não é outra manifestação de arquivo omitido. O teste não demonstra alteração espontânea de centavos nem perda definitiva; demonstra backup logicamente misturado, que a verificação de integridade aceita. R1–R3 e R7 não explicam essa mistura temporal.

**Direção de correção:** coordenar snapshot do banco e conteúdo imutável dos arquivos com todos os escritores relevantes, ou detectar a mudança e repetir/recusar o backup. Bloquear apenas escrita SQL não estabiliza um arquivo substituído fora da transação.

**Aceite:** repetir o teste e cenários de foto/página durante exportação; todo backup aceito deve representar uma versão coerente e restaurável. Uma inspeção bem-sucedida não pode esconder referências ou conteúdos de estados incompatíveis. Validar também a concorrência efetivamente alcançável por UI e workers antes de dimensionar a prioridade operacional.

### Persistência e encerramento dos recursos desta continuação

- `Round2CopyTest`: dois testes JVM passaram com duas bases locais independentes. Copiar e repetir não duplica nota/foto; destino fica pendente de upload sem remoteId herdado; editar sua página não altera a origem; página já existente no destino é preservada. Evidências `copy-local.txt` e `copy-local-results.xml`. Não é teste de autorização remota nem do fluxo completo ReceiptCopier/UI.
- Emulador descartável `emulator-5556` e servidor de fixture `55329` encerrados após salvar as evidências. Emulador original `5554` e instância Supabase original não foram apagados. Supabase descartável permanece para as próximas frentes.
- `git diff --check` passou; alterações limitadas a testes, documentação e utilitários de reprodução. Nenhum commit, push ou ticket. Varredura das evidências não encontrou JWT nem chave `sb_secret_` em conteúdo salvo. Isso não substitui revisão universal de segredos.
- **Estado continua em andamento:** faltam matriz completa de mutações RLS, troca de conta/caderneta com worker ativo, concorrência do worker/Edge e semântica dos scripts operacionais restantes. API26 indisponível. O novo R12 não encerra a rodada.

### Continuação delegada — 540 verificações CRUD

Matriz completa de verbos básicos para as **27 tabelas públicas e cinco papéis**, com registros sintéticos válidos e conferência de efeitos: **540/540 passaram**, evidência `http-crud.txt`, reprodução `http_crud.py`. SELECT usa linha existente; INSERT recusado é validado com o mesmo payload por service role; UPDATE/DELETE são conferidos no banco, não apenas pelo status HTTP. Isso fecha a lacuna de verbos básicos da matriz, sem substituir ataques adicionais de referências entre cadernetas, corrida ou a avaliação de funções RPC.

---

# Continuação 3 — 2026-11-06

Base: `749a325` (worktree em HEAD destacado, só arquivos de teste/documentação adicionados, nenhum commit/push/ticket). Evidências novas em [`docs/review-repros/round3/`](review-repros/round3/); as de R1–R12 continuam em `round2/`. Apenas dados sintéticos; servidor: a pilha descartável `getnf-review-round2` (127.0.0.1:55321); Android: **somente `emulator-5556`** (AVD descartável, app apontado para um proxy de loopback `10.0.2.2:55340` → pilha descartável). `emulator-5554` e a instância `getnf` não foram usados. Nada de produção, restore drill original, `.env.local` ou rede externa.

## Resultado por cenário prioritário

| Cenário | Resultado | Evidência |
|---|---|---|
| Matriz CRUD/RLS por papel | **Passou.** 540 verificações (round2) + 33 de ataques entre cadernetas (round3): 21 chamadas de RPC executáveis por usuário autenticado (rename_ledger, set_role, create_invite, remove_member, delete_ledger, leave_ledger, set_display_name, sync_taxonomy, claim_fetch, release_fetch; estranho, membro e gestor) sem efeito indevido (estado comparado antes/depois; gestor renomear a caderneta é permitido pelo desenho); 8 referências cruzadas por FK recusadas (409/400); mover categoria para caderneta onde só é membro recusado; Storage de outra caderneta recusado. Observação: membro de duas cadernetas **pode mover** recibo/loja sem filhos de uma para outra (equivale a copiar+apagar; com fotos o FK bloqueia, 409) | `crossledger.txt`, `http_crossledger.py` |
| Troca de conta/caderneta com workers ativos | **R13 e R22 reproduzidos** (emulador, WorkManager real) | `switch-tests.txt`, `proxy-requests.log` |
| Restauração entre contas | **R7 confirmado contra servidor real** (antes: servidor simulado) + consequência nova (extensão de R7) | `restore-jvm.txt`, `restore-jvm-results.xml` |
| Concorrência e falhas de workers/Edge | **R15, R16, R18, R19, R21 reproduzidos**; fila SQL do categorizador sem dupla reivindicação (passou) | ver achados |
| Consistência banco+Storage em gravações | **R14, R15, R17 reproduzidos** | ver achados |
| Scripts operacionais | **R16, R17, R20, R21** + leitura integral dos demais, sem outros achados (ver "Cobertura dos scripts") | ver achados |

## Achados novos

### R13 — P2 — "Sair" não é respeitado por um sync em andamento

- **Local:** `sync/SyncWorker.kt:38` (`client.refresh(...).also { accountSettings.signedIn(it) }`), `data/AccountSettings.kt:63` e `ui/settings/AccountScreen.kt:84` (`signOut()` só apaga as preferências; não cancela o trabalho nem invalida nada).
- **(a) Conta ressuscitada.** O worker lê o refresh token, a renovação demora; a pessoa toca "Sair"; a resposta chega e `signedIn()` regrava usuário e token. Teste instrumentado `reviewRound3SignOutWhileTheWorkerRefreshesMustStaySignedOut` (proxy atrasa `/auth/v1/token` 3 s, "Sair" após 1,2 s): `assertNull(settings.account())` passa logo após o `signOut()` e **falha ao fim do worker** com `Account(userId=…, refreshToken=…)`. A pessoa fica logada de novo sem ter feito nada.
- **(b) Dados continuam saindo.** `reviewRound3SignOutMidSyncMustStopSendingTheirData` (cada chamada REST atrasada 1,2 s; "Sair" ≈3,5 s após o início): o POST `/rest/v1/receipt` chega ao servidor ≈3,4 s **depois** do "Sair" (ver `proxy-requests.log`: `sync_taxonomy` → `receipt`), e a nota local aparece na caderneta (`expected:<0> but was:<1>`). O worker segue com a sessão antiga até o fim e só então termina.
- **Alcance:** janela real é o tempo de um sync (renovação ou rodada de envio, segundos; mais com fotos). Perda de dados não há; o risco é confiança (sair não sai) e envio de dados de quem acabou de sair num aparelho compartilhado.
- **Aceite:** `signOut` cancela o trabalho único e o worker confere, antes de gravar sessão e entre fases, que ainda é a mesma conta (geração/identidade); repetir os dois testes.

### R14 — P1 — "Retirar fotos" e "apagar conta" deixam objetos quando há mais de 1.000 fotos (mesma raiz de R4)

- **Local:** `sync/PhotoWithdrawal.kt:16–20` e `sync/LedgerService.kt:94–103` (`deleteMe`): `select` único (PostgREST devolve no máximo 1.000 linhas), objetos dessas linhas apagados, depois `DELETE` de **todas** as linhas (ou `delete_me`, que apaga todas) — o objeto 1.001 perde a única referência. O comentário "Storage first, so a run that stops halfway still finds the paths next time" não vale além do limite.
- **Reproduzido** (JVM contra Supabase local real, Storage real, 1.001 fotos de 1 byte): `Round3WithdrawalTest`
  - retirada: `removed=1000; objects left=1`;
  - `deleteMe` numa caderneta compartilhada (réplica fiel das linhas de `LedgerService`, que exigem `Context`): `paths the client knew=1000; objects of the departed person still in the shared caderneta=1`.
- **Efeito:** foto arquivada (já com regiões cobertas, mas ainda privada) permanece online depois da retirada de consentimento/exclusão de conta, sem linha que a aponte; a limpeza por prefixo só ocorre ao apagar a caderneta. Contradiz D103/D105 e a página de exclusão.
- **Aceite:** o de R4 (paginar toda a retirada; apagar linhas só dos objetos confirmados), mais teste com 1.001 e interrupção entre lotes em `PhotoWithdrawal` **e** `deleteMe` (também `web/apagar-conta`, mesma leitura de código, não executado no navegador). Evidência: `withdrawal-jvm.txt`, `withdrawal-jvm-results.xml`.

### R15 — P2 — `contribute` (retirada): arquivo recebido entre a leitura e o DELETE vira objeto órfão

- **Local:** `supabase/functions/contribute/index.ts:29–38`. A retirada lê as linhas da instalação, remove esses objetos, e então `DELETE ... eq(install_id)` apaga **todas** as linhas da instalação, inclusive as de arquivos que chegaram nesse intervalo.
- **Reproduzido de forma determinística** (função original, Storage/PostgREST locais; proxy de loopback segura só o `DELETE` do Storage): retirada lê só A; upload de B conclui (200); retirada termina `removed 1`; **0 linhas, 1 objeto**; uma segunda retirada responde `removed 0` e o objeto fica. Independe de >1.000 (R4) e do cliente (R11 é a variante com o app real). `contribute-withdraw-race.txt`.
- **Aceite:** retirada atômica por instalação (marcar/gerar revogação no servidor e recusar uploads posteriores; listar o Storage por prefixo em vez de confiar nas linhas; apagar linhas só dos caminhos confirmados). Repetir este teste e o de R11.

### R16 — P2 — `llm-worker.py` pagina sem `ORDER BY` e perde/duplica linhas quando o telefone sincroniza durante a leitura

- **Local:** `tools/llm-worker.py:66–73` (`Supabase.select`, `limit`/`offset` sem ordenação) usado para `category`, `product`, `store_item`, `receipt_line`, `issuer`… de cada caderneta (linhas 135–141).
- **Reproduzido** com a classe original: 2.500 linhas; entre a 1ª e a 2ª página 800 são atualizadas (como um sync do app) → o worker recebeu 2.500 linhas, **1.729 distintas: 771 duplicadas e 771 ausentes**. Totais gastos por produto (`u.spent`), ordem de revisão e regras aprendidas deixam de refletir o banco. `worker-pagination.txt`.
- **Aceite:** paginação por chave (`order=` único + keyset) como o `SyncEngine` já faz (D164), ou `Prefer: count`/snapshot; teste com escrita concorrente. O worker "sugere, não decide", daí P2.

### R17 — P2 — Backup operacional: arquivos apagados online ficam para sempre no PC; a política diz 14 dias

- **Local/decisão:** `tools/supabase-backup.py` (docstring: "Files deleted online stay here"); `docs/privacy-policy.md:110` ("cópia de segurança diária … guardada por 14 dias") e `docs/play-data-safety.md:28` ("backups expire in 14 days").
- **Reproduzido** (mirror original, Storage local): objeto apagado online (`c.txt`) → nova execução do mirror (`(0, 18)`) → **cópia continua no PC**. Só os dumps do banco expiram (KEEP=14); fotos, páginas (mesmo as que o usuário apagou ou cuja conta foi excluída) e máscaras no diretório `files/` não. Isso torna a declaração da política/Data Safety incorreta para os arquivos e faz a exclusão de conta/retirada (R4/R14) valer só para o servidor.
- **Relacionados (P3, mesmo script):** (i) um objeto que sai entre a listagem e o download faz o Storage responder **400 NoSuchKey**; `mirror()` não trata e **aborta**, sem gravar `.index.json` (os arquivos já baixados são rebaixados na próxima execução); (ii) `check()` só olha a idade do **dump**: com zero arquivos espelhados ou mirror falhando toda noite ele retorna 0 ("ok"); (iii) dump e mirror não são atômicos: foto removida entre os dois deixa linha no dump sem arquivo no espelho (`dump-mirror-gap.txt`). Tentativas de nome com `..` pelo Storage são normalizadas/recusadas antes de chegar ao disco (resultado negativo; ver `backup-mirror.txt`).
- **Aceite:** decidir e documentar a retenção real (expirar `files/` também, ou ajustar a política); tratar 4xx por objeto no mirror, gravar o índice incrementalmente e fazer `--check` cobrir a idade do último mirror e a contagem.

### R18 — P3 — Fila do categorizador: sem limite para execuções que morrem, sem timeout nos provedores, falha tardia reabre trabalho concluído

- `supabase/functions/categorize/index.ts` não usa `AbortSignal`/timeout em `fetch` (Jev e Open Food Facts). Com o provedor parado, o handler **não terminou** em 8 s e deixou 5 jobs `running attempts=1 error=-` (`edge-hang.txt`; handler original em Deno, fetch externo suspenso; runtime Edge real não exercitado).
- `claim_categorize_jobs` retoma `running` após 10 min **sem olhar `attempts`**: 12 rodadas de reivindicar+morrer deixam `status=running attempts=12`, nunca `failed` (o limite 6 só vale pelo caminho `fail_categorize_job`). `categorize-queue.txt`.
- Uma resposta tardia de `fail_categorize_job` de uma execução antiga põe de volta em `pending` um job que outra execução já deixou `done`.
- **Passou:** duas reivindicações simultâneas (lote 40, 60 jobs) retornam conjuntos disjuntos (40 + 20, interseção 0): `FOR UPDATE SKIP LOCKED` funciona.
- **Aceite:** timeout por chamada, teto de tentativas também no caminho de retomada, `fail_` condicionado ao estado `running` da mesma tentativa.

### R19 — P3 — `contribute`: cota diária é aproximada sob concorrência

`DAY_FILES=300`, mas 360 pedidos de uma instalação aceitam 300 (1 conexão), 307 (8) e **331 (32)** (`contribute-quota-race.txt`): verificação e gravação não são atômicas. O teto global por hora tem o mesmo desenho. Baixo impacto (cota anti-abuso), mas o limite não é garantia.

### R20 — P3 — `tools/blank-buyers.py`: lista incompleta e aborta em página ausente

Não foi executado (lê credenciais de produção). As duas requisições, reproduzidas verbatim na pilha descartável (`blank-buyers-limits.txt`): a listagem `receipt?select=page_path…` devolve **1.000 de 1.100** linhas (sem paginação) — o relatório "N pages, M with a buyer" é parcial; um `page_path` cujo objeto não existe gera **HTTP 400** que o script não trata (só repete ≥500), encerrando a execução. Além disso só olha `receipt.page_path`: cópias já espelhadas em `~/getnf-backups/.../files` (R17) e páginas do bucket `contributions` não são tocadas.

### R21 — P3 — `llm-worker.py`: a primeira execução a terminar descarrega o modelo da outra

`Llm.__exit__` descarrega se *aquela* instância carregou. Com duas execuções sobrepostas (manual durante a noturna; `TimeoutStartSec=2h`) a que terminou primeiro descarrega o modelo sob a outra (`worker-model-lifecycle.txt`, `lms` falso; sem LM Studio). O timer systemd não inicia duas instâncias da mesma unidade.

### R22 — P3 (latente) — Abrir outra caderneta confia na morte do processo

`AccountSettings.openLedger` grava `LEDGER` (commit) e `RestartActivity.restart` mata o processo; mas o banco do processo (`GetNFApp.database`) segue ligado à caderneta antiga. Reproduzido chamando `openLedger(outra)` e rodando o `SyncWorker` real sem reiniciar: a nota do banco antigo **foi enviada para a caderneta recém-aberta** (`expected:<0> but was:<1>`, `switch-tests.txt`). Na prática só um worker que *comece* entre o commit e `exitProcess` (milissegundos) seria afetado; documentado porque a defesa de D104 ("nunca enviar para outra caderneta") depende desse tempo, não de uma checagem (`SyncWorker` poderia comparar `ledgerId()` com a caderneta a que `app.database` foi aberto).

### Extensão de R7 (sem recontagem) — contra servidor real

`Round3RestoreTest` (Supabase local, duas contas): banco já sincronizado com a caderneta A, agora sincronizando como B em B (estado que o restore reproduz): `receiptsInB=0` (R7 confirmado fora do `FakeServer`); em seguida a pessoa edita o nome de **um** produto: `pushed=1`, **um produto-fantasma** é criado em B (`category_id=null`, 0 lojas, 0 notas; 3 dos 4 produtos locais não foram) e nada fica "parked". A caderneta B passa a ter dados parciais que parecem corretos. `restore-jvm.txt`.

## Resultados negativos (passaram) — não são cobertura exaustiva

- 33 ataques entre cadernetas (tabela acima); 540 verbos CRUD (round2).
- Dupla reivindicação da fila do categorizador: nenhuma.
- Rotação de refresh token (GoTrue local): reuso paralelo imediato devolve o mesmo filho; token **uma** geração atrás ainda é aceito; **duas** gerações atrás dá 400 `refresh_token_already_used` e a família não é revogada (`refresh-rotation.txt`). O `SyncWorker` trata qualquer 400/401 como "sair" — só alcançável com token duas gerações atrás (não demonstrado no app).
- Traversal por nome de objeto no Storage: normalizado/recusado (`backup-mirror.txt`).
- `seed_new_user()` é executável por `anon`/`authenticated` (grant padrão de PUBLIC) mas é função de gatilho: não chamável via RPC (observação, não achado).

## Cobertura dos scripts (`tools/`)

Lidos integralmente nesta rodada (semântica, não só sintaxe): `supabase-backup.py`, `supabase-restore-drill.sh` (**não executado**: faz `db reset` do Supabase local de `getnf` sem confirmação e compara contagens com o banco online ao vivo — falso "FAILED" se houve escrita depois do dump), `llm-worker.py`, `contributions.py`, `tickets.py`, `load-lexicon.py` (**padrão grava no online**; senha na URL do `psql`, ao contrário do cuidado de `supabase-backup.py`), `blank-buyers.py`, `build-web.py`, `check.sh`, `emulator.sh`, `harden-local-stack.sh`, `setup-system.sh`, `setup-play-api.sh`, `make-upload-key.sh`, `pull-capture-dumps.sh`, `device-bench.sh`, `capture-bench.sh`, `webview-eval.mjs`, unidades systemd e hook pre-push. Executados contra a pilha descartável: mirror e `Llm`/`Supabase.select` originais. Round2 já cobria `play.py`/`release.py`. **Não** auditados a fundo: `categorize-eval.py`, `taxonomy-sql.py`, `make-icons.py`, `make-nfce-fixture.py`, `capture-timing.py`, `dashboard.html`, `doctor.sh`, `local-stack-check.sh` (apenas lidos por alto, sem achados).

## Limitações e impedimentos concretos

- **Sem iOS/API 26**; um único AVD API 36. Captura física em aparelho real não executada.
- **Reinício e WorkManager (hipótese não reproduzida):** `GetNFApp.onCreate` aplica o restore pendente *antes* de abrir o banco, mas o WorkManager inicializa por `androidx.startup` antes do `onCreate`; um `SyncWorker` pendente poderia abrir `app.database` antes da troca de arquivos. Provar exige uma barreira dentro de `GetNFApp.onCreate` (código de produção), o que a regra desta rodada proíbe.
- **Sobreposição de `SyncWorker` com `REPLACE`** (o antigo cancelado só ao próximo ponto de suspensão, o novo já iniciado, `OtherLedgers` com `SyncEngine` por caderneta): não executado; os 23 cortes da rodada 2 cobrem a idempotência do envio, não a concorrência de dois motores no mesmo arquivo.
- `contribute`: falha do `upsert` da linha depois do upload (objeto sem linha) apenas por leitura; o runtime Edge real (limites de tempo/memória) não faz parte da pilha local.
- `delete_me`/`leave_ledger` por RPC com >1.000 fotos: o cliente só passa os caminhos que conhece; a parte do servidor (retorno de caminhos) não foi medida com 1.001 linhas.
- As provas de R13/R22 usam um proxy de loopback para impor ordem (atraso), não a frequência real do cenário.
- **Higiene do ambiente:** a pilha descartável publica `0.0.0.0:55321` (API) e `:55322` (Postgres, senha padrão `postgres`) e a função de apoio escuta em 127.0.0.1. `tools/harden-local-stack.sh` só protege **54320–54329**; as portas 55321+ ficam fora da regra. Dados são sintéticos, mas convém **parar a pilha descartável** quando terminar (`docker stop $(docker ps -q --filter name=getnf-review-round2)`; os dados são preservados). Não testei o alcance a partir de outro dispositivo. O proxy 55340 e o `emulator-5556` foram encerrados.
- Tudo o que falha de propósito (testes Kotlin `reviewRound3*`) é o comportamento esperado: não são testes verdes.

## Resumo copiável (continuação 3, commit 749a325)

> Segunda continuação da auditoria do 749a325 (sem correções de produção, commits ou tickets). Novos achados reproduzidos:
>
> - R14/P1: retirar fotos (`PhotoWithdrawal`) e apagar conta (`deleteMe`) leem no máximo 1.000 linhas, removem esses objetos e apagam todas as linhas; com 1.001 fotos sobra 1 objeto sem referência (mesma raiz de R4). Reproduzido com Supabase/Storage locais reais.
> - R15/P2: `contribute` retirada: um arquivo recebido entre o SELECT e o DELETE amplo vira objeto órfão (0 linhas, 1 objeto), independente de >1.000. Determinístico.
> - R13/P2: "Sair" durante um sync: (a) a renovação em voo regrava a conta (usuário volta logado); (b) o run continua enviando a nota ~3,4 s depois do "Sair". Emulador, WorkManager real.
> - R17/P2: `supabase-backup.py` mantém indefinidamente arquivos apagados online; privacidade/Data Safety dizem 14 dias. Mirror aborta em 400 e perde o índice; `--check` olha só o dump.
> - R16/P2: `llm-worker.py` pagina sem ORDER BY: com 800 atualizações entre páginas, 771 linhas duplicadas e 771 perdidas de 2.500.
> - P3: R18 (categorizador sem timeout, `running` sem teto de tentativas, falha tardia reabre job concluído; fila sem dupla reivindicação), R19 (cota de `contribute` aproximada: 331 > 300 com 32 paralelos), R20 (`blank-buyers.py` lista 1.000 de 1.100 e aborta em página ausente; não executado), R21 (duas execuções do worker: a primeira a sair descarrega o modelo), R22 (abrir outra caderneta depende de matar o processo: worker sem reinício envia o banco antigo à nova).
> - R7 estendido (servidor real): após restaurar em outra conta, editar um produto cria um produto-fantasma na caderneta nova, sem notas.
> - Passaram: 540 verbos CRUD + 33 ataques entre cadernetas (RPCs, FKs, Storage); reivindicação de jobs sem duplicidade; rotação de refresh token; traversal no Storage.
> - Impedimentos: restore×WorkManager no boot e sobreposição de `SyncWorker` não reproduzidos (exigem código de produção/instrumentação); runtime Edge real fora da pilha. A pilha descartável escuta em 0.0.0.0:55321/55322 fora da regra do `harden-local-stack.sh`; pará-la ao fim.
> Detalhes e evidências: `docs/review-2026-11-04-round2.md` (Continuação 3) e `docs/review-repros/round3/`.

## Reconfirmação independente de R4–R12 — 2026-11-06

Reexecução das reproduções do Codex neste worktree (749a325), pilha descartável reiniciada, `emulator-5556`. Saídas em `docs/review-repros/round3/reconfirm/`.

| Achado | Reexecução | Resultado |
|---|---|---|
| R4 | `contribution_withdraw.py` (função original, 1.001 arquivos) | **Confirmado:** `removed 1000`, 1 pasta no Storage, 0 linhas |
| R5 | `Round2ArithmeticTest.surchargeMustReachSpending` | **Confirmado:** `expected:<3164> but was:<3064>` |
| R6 | `CatalogEditorTest.reviewRound2ManualDiscountMustKeepReceiptTotal` | **Confirmado:** `expected:<50> but was:<51>` |
| R7 | `SyncEngineTest` (JVM) e `BackupServiceTest` (Android real); antes também contra servidor real (`Round3RestoreTest`) | **Confirmado** nos três níveis: `expected:<1> but was:<0>` |
| R8 | `worker_tests.py` | **Confirmado:** resposta vazia conta como concordância; os outros 4 casos passam |
| R9 | `Round2HttpTest.downloadTransientFailureMustRetryAndKeepExistingFile` | **Confirmado:** 503 chega como `SupabaseException`, não `IOException` |
| R10 | `BackupServiceTest.reviewRound2ExportMustNotPromiseMissingPhoto` (Android) | **Confirmado** |
| R11 | `TestContributionTest.reviewRound2WithdrawalDuringAnInflightSendMustNotLeaveAnUpload` (JVM, ordem imposta) | **Confirmado no nível determinístico** (1 arquivo persiste após a retirada). **Não reexecutada** a integração com UI/WorkManager/proxy: os scripts do Codex fixam `emulator-5554` e alteram as preferências do app desse aparelho (o existente); mantive a evidência original de `consent-race-integration.txt` |
| R12 | `BackupServiceTest.reviewRound2ConcurrentPageWriteMustProduceCoherentBackup` (Android) | **Confirmado:** banco `/before`, página `after 20.00` |
| Extensão de R2 | `Round2RedactionTest` (Android) | **Confirmado:** `masked fraction=1053/24531` |

Controles: o round trip de backup original passa (5 testes Android, 4 falhas esperadas); no JVM, 9 testes, 5 falhas esperadas (R11, R6, R9, R5, R7) e 4 passam, entre eles os 23 cortes. Os objetos órfãos sintéticos da reprodução de R4 foram removidos do bucket descartável. `emulator-5556` encerrado.
