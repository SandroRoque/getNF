# Modelo de dados local (etapa 1)

2026-10-17. Desenho proposto para aprovação. Fecha a etapa 1 do roadmap e orienta a etapa 2, que implanta o banco
no Android e migra os dados. Já decidido pelo usuário (D43):

- **O PC só consulta.** O celular é a fonte da verdade.
- **Os relatórios usam a categoria atual do produto.**

Este documento substitui a parte de proposta de [data-model-review.md](data-model-review.md).

## Princípio

Uma NFC-e identificada pela chave é um documento fiscal imutável. O que a extração leu da página (ou, no futuro, das
fotos) é evidência e nunca é editado pelo usuário. As correções ficam em dois lugares:

- **no catálogo:** nome exibido, categoria, tags e tamanho do produto;
- **na linha da nota:** a associação com o produto e o desconto, como marcações explícitas com origem.

Reprocessar uma nota (parser novo, OCR novo) regrava apenas os campos extraídos e nunca toca nesses dois lugares.

## O que os dados reais mostraram

Conferência somente leitura no PocketBase local em 2026-10-17: 9 notas, 135 linhas, 84 produtos e 75 categorias.

| Pergunta | Resultado | Consequência |
| --- | --- | --- |
| Casas decimais da quantidade | até 3 (itens em KG) | quantidade em décimos de milésimo (escala 4, como o `qCom` da NFC-e) |
| Casas decimais do preço unitário | até 2 na página | preço unitário em escala 4, com folga para páginas com mais casas |
| `qtd × preço unitário` confere com o total da linha | 135 de 135 | o total em centavos continua sendo o valor de referência |
| Reprocessar a mesma página dá o mesmo (linha, código) | 9 de 9 notas | casamento por número da linha é estável; ele vem do `nItem` da nota |
| Notas com o mesmo código em duas linhas | 4 de 9 | o código sozinho não identifica uma linha; o número da linha é necessário |
| Códigos de item | 97 GTIN válidos, 38 internos da loja, nenhum vazio | os dois tipos de código são necessários desde já |
| Trabalho manual a migrar | 82 produtos categorizados (33 categorias distintas), 4 com tags (2 distintas), 3 tamanhos manuais | exportação única do servidor na migração |

As unidades encontradas foram UN, KG e PC.

## Esquema local (Room)

Room funcionou no build atual (AGP 9.0.1, Kotlin 2.3.20, Room 2.8.5 e KSP 2.3.12), com o teste de gravação e leitura
passando no emulador. O APK cresceu 16 KB, porque o runtime já vinha pelo WorkManager.

Dinheiro é sempre `Long` em centavos. Quantidade e preço unitário são `Long` em escala 4, e o tamanho do produto é
`Long` em milionésimos de kg ou de litro. Não há `Double` em valores persistidos.

### Nota e evidência

**`receipt`**

| Coluna | Tipo | Notas |
| --- | --- | --- |
| `chave` | text PK | 44 dígitos |
| `issuer_cnpj` | text FK → `issuer` | nulo até extrair |
| `issued_at` | text ISO-8601 com fuso | da página, não da leitura |
| `gross_cents`, `discount_cents`, `paid_cents` | long | totais extraídos |
| `page_file` | text | HTML salvo, relativo ao diretório da nota |
| `parser_version`, `parse_error` | int, text | reprocessa quando a versão sobe |
| `captured_at`, `version`, `uploaded_version` | | como no manifesto atual (`ReceiptStore`) |

**`receipt_photo`**

| Coluna | Tipo | Notas |
| --- | --- | --- |
| `receipt` | FK → `receipt` | apagada junto com a nota |
| `local_name` | text | único dentro da nota |
| `session_id`, `segment` (JSON), `mask_file` | | metadados da captura (D42) |
| `captured_at` | | |
| `uploaded` | bool | substitui `uploadedPhotos` do manifesto |

**`issuer`**: `cnpj` PK e `name` (o último nome lido).

### Linha da nota

**`receipt_line`**, com PK (`receipt`, `line_no`):

| Grupo | Colunas | Quem escreve |
| --- | --- | --- |
| Extraído | `description`, `code`, `qty_e4`, `unit`, `unit_price_e4`, `gross_cents` | só a extração |
| Associação | `product_id` FK → `product`; `assignment` = `auto` \| `manual` | a extração (se `auto`) ou o usuário |
| Desconto | `discount_cents`; `discount_source` = `html` \| `photo` \| `manual` \| `prorated`; `discount_review` = `unreviewed` \| `confirmed` | extração, OCR, usuário ou rateio |
| Estado | `orphaned` bool | o reprocessamento (ver abaixo) |

O valor líquido não é guardado; é sempre calculado como `gross_cents − discount_cents`. O rateio só escreve linhas com
`discount_source = prorated`, e só distribui o desconto total da nota menos os descontos já observados ou manuais.

### Catálogo

**`product`**

| Coluna | Tipo | Notas |
| --- | --- | --- |
| `id` | text PK (UUID) | id interno, para as referências não dependerem do código |
| `code_kind` | text | `gtin` (código de barras, vale em qualquer loja) ou `store` (código interno da loja) |
| `code` | text | o código lido na nota |
| `store_cnpj` | text | loja dona do código interno; vazio para `gtin` |
| `name` | text, nulo | nome dado pelo usuário; nulo exibe a última descrição extraída |
| `category_id` | FK → `category`, nulo | nulo = não categorizado |
| `size_e6`, `size_unit` (`kg` \| `l`), `size_source` (`parsed` \| `manual`) | | `manual` nunca é sobrescrito |
| `archived` | bool | some das listas; o histórico continua |
| `created_at` | | |

Chave única (`code_kind`, `code`, `store_cnpj`): **um código, um produto**, como hoje no servidor. A regra atual de
`derive.productKey` continua decidindo o tipo: GTIN válido e fora da faixa restrita vira `gtin`, e qualquer outro
código vira `store`, valendo só junto com o CNPJ da loja (o "243" de uma loja não é o "243" de outra). Uma linha com
código desconhecido cria um produto novo.

Produtos não são juntados. Itens parecidos (vinhos diferentes, a banana de duas lojas, cafés de marcas diferentes)
continuam produtos distintos, cada um com seu histórico de preço. Agrupá-los é papel da categoria e, no futuro, da
comparação de preços (ver "Fora deste desenho"). Se aparecer na prática uma loja que troca o código interno de um item,
isso terá uma decisão própria.

**`category`**

| Coluna | Notas |
| --- | --- |
| `id` | UUID estável; renomear ou mover não muda o id |
| `seed_path` | caminho em `taxonomy/categories.txt` quando veio de lá (`food.produce.fruit`); nulo se criada pelo usuário |
| `label`, `parent_id`, `sort` | |
| `user_modified` | bool; a taxonomia não sobrescreve rótulo, pai nem ordem |
| `deleted` | bool (tombstone); a taxonomia não recria |

**`tag`** (`id`, `slug`, `description`, `seed`, `deleted`) e **`product_tag`** (`product_id`, `tag_id`).

## Reprocessamento

Uma **linha** é cada item impresso na nota, ou seja, uma compra de um produto naquela nota. O mesmo produto pode aparecer
em duas linhas da mesma nota (o caixa passou duas unidades separadas), com o mesmo código. Por isso a linha é
reconhecida pelo seu número na nota, não pelo código.


Uma nota é reprocessada quando a página muda ou quando `parser_version` sobe. As linhas novas são casadas com as
existentes por (`chave`, `line_no`).

- **Mesmo `line_no` e mesmo `code`:** regrava os campos extraídos. Associação manual, desconto manual ou confirmado e
  qualquer dado do catálogo ficam intactos. Com `assignment = auto`, a associação é recalculada pelo código.
- **Mesmo `line_no` e outro `code`, ou linha que sumiu:** se a linha tinha algo manual, ela não é apagada. Fica
  `orphaned = true` e aparece como pendência de revisão. Sem nada manual, é substituída ou removida.
- **Linha nova:** associada pelo código, como numa nota nova.

Os dados reais indicam que o caso órfão não deve ocorrer com a página da SEFAZ, porque o número da linha é o `nItem` do
documento. A regra existe para OCR e para formatos futuros.

## Fluxos

Os exemplos são fictícios. Ids encurtados.

### 1. Nota nova com produto conhecido

Antes: `product(P1, gtin, <gtin do café>)`, com `P1.category = bebidas.cafe`.

A nota `N1` traz a linha 3 com esse GTIN. Resultado:
`receipt_line(N1, 3, product=P1, assignment=auto, discount_source=prorated)`.

A linha já entra na categoria Café sem nenhuma ação.

### 2. Tamanho corrigido e depois um parser novo

A descrição é `CAFE TORRADO 5OOG` (com a letra O no lugar do zero), e `parseSize` não reconhece o tamanho. O usuário
informa 500 g: `P1.size_e6 = 500000, size_unit = kg, size_source = manual`.

O parser sobe de versão e a nota é reprocessada. As linhas são regravadas e `P1` não muda.

### 3. Associação corrigida e depois reprocessamento

A linha `N1/5` usa o código interno `77`, que a loja reutilizou para outro produto. Ela caiu em `P4` (Detergente), mas
é Esponja. O usuário reassocia a linha a `P5`: `assignment = manual`.

No reprocessamento, a linha 5 continua com o código `77` e a associação manual é preservada. Só as linhas com
`assignment = auto` seguem o código.

### 4. Desconto manual

A nota `N2` tem desconto total de R$ 3,00, e o usuário vê na foto R$ 2,00 de desconto na linha 2. Resultado:
- a linha 2 fica com `discount_cents = 200, source = manual, review = confirmed`;
- o rateio distribui os 100 centavos restantes entre as demais linhas, proporcionalmente ao bruto (com
  `source = prorated`).

O total da nota não muda; só muda a distribuição entre as categorias.

### 5. Exclusões

- **Nota:** "Apagar a nota de 12/03 no Mercado X? Ela tem 23 linhas e 14 fotos, que serão apagadas também." O usuário
  confirma. A nota, as linhas, as fotos e a página são apagadas no celular e, pela cópia, no servidor. Produtos que
  ficaram sem compras continuam no catálogo.
- **Produto sem compras:** é apagado direto.
- **Produto com compras:** a tela mostra quantas compras ele tem e oferece arquivar: o produto some das listas e as
  compras continuam nos relatórios. Não existe apagar um produto e perder suas compras. Se ele aparecer de novo numa
  nota, volta a ser listado.
- **Categoria com produtos:** "8 produtos e 2 subcategorias estão em Frutas. Mover para: [Hortifrúti]". A categoria
  pai é sugerida. A categoria fica com `deleted = true` para a taxonomia não recriá-la.
- **Tag:** "Remover a tag 'orgânico' de 5 produtos?" Tags não somam valores, então nada muda nos totais.

## Taxonomia

`taxonomy/categories.txt` e `tags.txt` passam a ser semente, embarcada no APK:

- **Primeira instalação:** cria as categorias com `seed_path`.
- **Atualização da semente:**
  - acrescenta categorias novas;
  - atualiza rótulo e ordem só onde `user_modified = false`;
  - nunca recria categorias com `deleted = true`;
  - nunca apaga categorias, nem as que saíram do arquivo.

  Isso substitui a sincronização que o servidor faz hoje (`syncTaxonomy`).

## Papel do servidor

O celular envia ao servidor, num só sentido, as notas, as linhas com seus valores efetivos (produto, desconto e
origem) e o catálogo (produtos, categorias e tags). As fotos continuam em `receipt_photos` (D42).

- **Sem conflitos de sincronização:** o servidor nunca edita.
- **Fim do parse no servidor:** depois da troca, o PocketBase deixa de parsear e as regras de edição do admin são
  fechadas. O parser em JS fica como referência de teste até a versão Kotlin cobrir as mesmas fixtures.
- **Dashboard inalterado:** a view `spending_lines` mantém as colunas, e o dashboard não muda.
- **Exclusões:** chegam ao servidor como remoções na próxima cópia.
- **Fora do escopo:** sincronização em dois sentidos e edição pelo PC. Podem virar uma etapa própria.

## Plano de migração (etapa 2)

1. **Backup:** do servidor (`POST /api/backups`, cópia em `~/getnf-backups/`) e dos diretórios das notas no celular.
2. **Exportação única do servidor** para um JSON:
   - para cada chave de produto (`gtin:…` ou `store:cnpj:code`): o caminho da categoria, as tags e o tamanho manual;
   - as notas que só existem no servidor, com a página. Hoje o servidor tem 9 notas e o celular 8.
3. **No celular:**
   - semear a taxonomia;
   - importar os manifestos atuais em `receipt` e `receipt_photo`, preservando o estado de envio;
   - parsear as páginas localmente;
   - aplicar a exportação, casando pelas chaves de produto.
4. **Conferir** antes de mudar o envio:
   - mesmas contagens de notas, linhas e produtos;
   - mesma categoria em cada produto;
   - totais por mês iguais aos do dashboard atual.
5. **Mudar o envio para o formato novo** e desligar o parse no servidor, nessa ordem e só depois da conferência.

A exportação é um JSON local, entregue ao app pelo adb ou por um endpoint autenticado do servidor. A escolha fica
para a etapa 2. O JSON tem descrições de compras reais e não entra no repositório.

## Fora deste desenho

- **Agrupar produtos parecidos para comparar preços** (bananas de lojas diferentes, vinhos, café 500 g de marcas
  diferentes): é uma decisão de análise (IPCI), não de identidade. Os produtos continuam distintos; pode virar uma
  tabela de grupos de comparação depois. Para os totais de gasto, a categoria já agrupa.
- **Vínculo com o cadastro compartilhado e curadoria:** o id local e o código do produto permitem associar depois,
  sem depender de ids remotos ([local-and-shared-modes.md](local-and-shared-modes.md)).
- **Classificação histórica por época:** foi recusada por agora (D43).
- **OCR de descontos:** o esquema já tem `source = photo` e o estado de revisão.
- **Retenção de fotos e limpeza das duplicadas antigas:** ver [capture-retention-plan.md](capture-retention-plan.md).
