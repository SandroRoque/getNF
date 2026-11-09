# Reproduções da rodada 2

Commit auditado: `749a325f9581cb9d92289d95f79d1cf4744870ed`. Apenas dados inventados. Relatório principal: `../../review-2026-11-04-round2.md`; escopo e lacunas: `coverage.md`.

## Regras para repetir

- Não executar scripts operacionais apontados a `.env.local` nem `tools/supabase-restore-drill.sh`.
- Os scripts HTTP desta pasta exigem o projeto **descartável** `getnf-review-round2`, API `127.0.0.1:55321`, e leem suas credenciais de `/tmp/getnf-review-round2/status.json`. Não copiar esse arquivo para o repositório. Os scripts conferem a URL quando usam essas credenciais.
- `server_restore.sh` recria **somente** o banco `review_restore` dentro de `supabase_db_getnf-review-round2`. Não trocar o nome do container pelo da instância habitual. Necessita Docker e schemas locais já migrados.
- Instrumentação usa sempre `adb -s emulator-5554` (ou o AVD descartável `emulator-5556`). Nunca chamar `connectedAndroidTest` com dispositivos não selecionados.
- APKs devem ser compilados com `-Pgetnf.supabaseUrl=http://10.0.2.2:55321` e a publishable key local via argumento gerado sem imprimir. O debug padrão pode apontar a produção.

## Testes offline

```sh
node docs/review-repros/round2/site_tests.cjs
python3 docs/review-repros/round2/script_tests.py
python3 docs/review-repros/round2/worker_tests.py
. tools/env.sh
deno run --cached-only --allow-env --allow-read docs/review-repros/round2/edge_offline.ts
```

`worker_tests.py` deve falhar em R8 neste commit. O handler Deno intercepta fetch e não recebe permissão de rede.

No diretório `android`, com Java/Gradle locais disponíveis:

```sh
./gradlew testDebugUnitTest --tests '*Round2ArithmeticTest' --tests '*Round2HttpTest' --tests '*CatalogEditorTest.reviewRound2*' --tests '*SyncEngineTest.reviewRound2*'
```

As falhas de R5/R6/R7/R9 são o resultado esperado **da reprodução do defeito**, não testes verdes. O teste de 23 interrupções deve passar. Não executar a suíte inteira esperando sucesso enquanto as reproduções dos bugs permanecerem sem correção.

Instrumentação, após instalar ambos os APKs locais no emulador selecionado:

```sh
adb -s emulator-5554 shell am instrument -w -e class io.github.sandroroque.getnf.data.BackupServiceTest,io.github.sandroroque.getnf.sync.Round2RedactionTest br.com.roqlab.caderneta.debug.test/androidx.test.runner.AndroidJUnitRunner
```

A baseline de backup passa; R7/R10 e a extensão de R2 falham. `Round2RedactionTest` usa raster sintético independente com OCR simulado; não mede acurácia do ML Kit.

## Evidências que não devem ser confundidas com achados

- `jvm-repros.txt`: exploração antiga contém um falso positivo de 503 no upload; a classe real converte esse erro para IOException. O teste inválido foi removido.
- `instrumentation.txt` e `capture-retry.txt`: seletores/textos obsoletos dos testes. `capture-final.txt` contém o resultado após atualizar somente os testes: 2/2 passam.
- `consent-race.txt`: reprodução inicial com transporte simulado; posteriormente confirmada como R11 em `consent-race-integration.txt`, com UI/worker/backend reais locais.
- `security-inventory.txt`: configuração/grants, não prova completa de autorização.
- `static-checks.txt`: scanner limitado a três assinaturas. Zero candidatos não significa zero segredos de qualquer tipo.

A pasta conserva logs separados para baseline, reproduções e testes de cobertura. O relatório principal indica qual resultado é a evidência vigente.

### Continuação de 2026-11-06

- `storage_restore.py`: usa exclusivamente API `127.0.0.1:55321`, executa mirror original, restaura em bucket temporário e compara hashes; limpa seus objetos. Não usa `.env.local` nem o restore drill operacional.
- `make_camera_fixture.sh`: gera imagem e QR sintéticos para câmera imagefile do emulador descartável; requer ImageMagick e qrencode.
- `page_fixture_server.py`: servidor `127.0.0.1:55329` sem upstream, entrega fixture SC existente. Rodar apenas enquanto necessário e encerrar depois.
- Replay sintético: de `android/`, `GETNF_BENCH="$PWD/../docs/review-repros/round2/bench" ./gradlew :app:testDebugUnitTest --tests '*CaptureBenchTest' --offline`. Não aponta para corpus real de test-media. Os dois cenários não possuem oráculo de itens; consultar `ingest/log.txt` para decisões e limites.

R12: `BackupServiceTest#reviewRound2ConcurrentPageWriteMustProduceCoherentBackup` falha intencionalmente contra o commit auditado; `backup-concurrent.txt` registra o banco anterior combinado com HTML posterior, embora os hashes do ZIP passem. Executado exclusivamente no emulator-5556.

`Round2CopyTest` passa em dois cenários com bancos locais independentes; `copy-local-results.xml` preserva a contagem. Servidor da fixture e emulator-5556 foram encerrados ao terminar a continuação de 2026-11-06.

### Rodada 3

Arquivos em `../round3/`. Mesmas regras: só a pilha descartável `getnf-review-round2`, só `emulator-5556`, nenhum `.env.local`, nada de `supabase-restore-drill.sh`, nenhuma execução de `blank-buyers.py`/`load-lexicon.py`.

```sh
. tools/env.sh
python3 docs/review-repros/round3/categorize_queue.py          # SQL: reivindicação concorrente, retomada sem teto
python3 docs/review-repros/round3/edge_hang.py                 # handler Edge original, provedor parado (Deno --cached-only)
python3 docs/review-repros/round3/contribute_withdraw_race.py  # R15 (sobe a função original em :55338 e um proxy em :55339)
python3 docs/review-repros/round3/contribute_quota_race.py     # R19 (precisa da função em :55328, como na rodada 2)
python3 docs/review-repros/round3/http_crossledger.py          # 33 verificações entre cadernetas
python3 docs/review-repros/round3/worker_pagination.py         # R16
python3 docs/review-repros/round3/worker_model_lifecycle.py    # R21 (lms falso)
python3 docs/review-repros/round3/backup_mirror.py             # R17 (mirror original)
python3 docs/review-repros/round3/dump_mirror_gap.py
python3 docs/review-repros/round3/blank_buyers_limits.py       # requisições do script reproduzidas; o script não é executado
python3 docs/review-repros/round3/refresh_rotation.py          # ~25 s
```

JVM com Supabase local (falham de propósito: R7 estendido e R14): `docs/review-repros/round3/run_restore_jvm.py` roda `*Round3RestoreTest`; troque o filtro e o arquivo de saída para `*Round3WithdrawalTest`.

Instrumentação (emulador descartável, APK com `-Pgetnf.supabaseUrl=http://10.0.2.2:55340`): `python3 docs/review-repros/round3/delay_proxy.py proxy-requests.log &` e depois `python3 docs/review-repros/round3/run_switch_tests.py <método> ...` (limpa os dados do app entre métodos; usa `adb -s emulator-5556`).
