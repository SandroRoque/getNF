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
| Associação | `store_item_id` FK → `store_item`, pelo código | só a extração |
| Correção | `product_override_id` FK → `product`, nulo | o usuário, só para esta linha |
| Desconto | `discount_cents`; `discount_source` = `html` \| `photo` \| `manual` \| `prorated`; `discount_review` = `unreviewed` \| `confirmed` | extração, OCR, usuário ou rateio |
| Estado | `orphaned` bool | o reprocessamento (ver abaixo) |

O produto efetivo da linha é `product_override_id`, se houver, senão o produto do item da loja. O valor líquido não é guardado; é sempre calculado como `gross_cents − discount_cents`. O rateio só escreve linhas com
`discount_source = prorated`, e só distribui o desconto total da nota menos os descontos já observados ou manuais.

### Catálogo

Dois níveis: o **item da loja** é o cadastro como a nota mostra; o **produto** é o que a coisa é. Um produto pode
reunir itens de várias lojas ("Banana caturra" no mercado A e no mercado B), e cada mercado continua com seu cadastro.

**`store_item`**, com chave única (`code_kind`, `code`, `store_cnpj`):

| Coluna | Tipo | Notas |
| --- | --- | --- |
| `id` | text PK (UUID) | |
| `code_kind` | text | `gtin` (código de barras, vale em qualquer loja) ou `store` (código interno da loja) |
| `code` | text | o código lido na nota |
| `store_cnpj` | text | loja dona do código interno; vazio para `gtin` |
| `description` | text | a última descrição lida ("BANANA CATURRA KG") |
| `parsed_size_e6`, `parsed_size_unit` | | tamanho deduzido da descrição, quando houver |
| `product_id` | FK → `product` | para qual produto este item conta |

A regra atual de `derive.productKey` decide o tipo: GTIN válido e fora da faixa restrita vira `gtin`, e qualquer outro
código vira `store`, valendo só junto com o CNPJ da loja (o "243" de uma loja não é o "243" de outra). Um código
desconhecido cria um item da loja e um produto novo só dele.

**`product`**

| Coluna | Tipo | Notas |
| --- | --- | --- |
| `id` | text PK (UUID) | o identificador que unifica os itens das lojas |
| `name` | text, nulo | nome dado pelo usuário; nulo exibe a descrição do item da loja mais recente |
| `category_id` | FK → `category`, nulo | nulo = não categorizado |
| `size_e6`, `size_unit` (`kg` \| `l`), `size_source` (`parsed` \| `manual`) | | `manual` nunca é sobrescrito; `parsed` vem do item da loja |
| `archived` | bool | some das listas; o histórico continua |
| `created_at` | | |

**Vincular e desvincular.** O usuário pode dizer "este item do mercado B também é Banana caturra": o item passa a
apontar para o produto existente, e o produto que tinha nascido só para ele é descartado se não tiver nada manual
(nome, categoria, tamanho ou tags); se tiver, o app pergunta. Desvincular devolve ao item um produto próprio. As linhas
apontam para o item da loja, não para o produto, então vincular e desvincular nunca alteram uma compra.

Regra: vincule só o que é o mesmo item, com a mesma variedade e a mesma forma de venda. Banana caturra e banana prata,
ou vinhos diferentes, ficam em produtos separados; agrupá-los é papel da categoria e, no futuro, da comparação de
preços. O app avisa ao vincular itens de unidades incompatíveis (kg com unidade), porque o preço por kg deixaria de ser
comparável. O preço continua visível por loja, porque cada linha sabe de qual nota e loja veio.

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

- **Mesmo `line_no` e mesmo `code`:** regrava os campos extraídos. Correção de produto da linha, desconto manual ou confirmado e
  qualquer dado do catálogo ficam intactos. O item da loja é recalculado pelo código.
- **Mesmo `line_no` e outro `code`, ou linha que sumiu:** se a linha tinha algo manual, ela não é apagada. Fica
  `orphaned = true` e aparece como pendência de revisão. Sem nada manual, é substituída ou removida.
- **Linha nova:** associada ao item da loja pelo código, como numa nota nova.

Os dados reais indicam que o caso órfão não deve ocorrer com a página da SEFAZ, porque o número da linha é o `nItem` do
documento. A regra existe para OCR e para formatos futuros.

## Fluxos

Os exemplos são fictícios. Ids encurtados.

### 1. Nota nova com produto conhecido

Antes: `store_item(S1, gtin, <gtin do café>) → P1`, com `P1.category = bebidas.cafe`.

A nota `N1` traz a linha 3 com esse GTIN. Resultado:
`receipt_line(N1, 3, store_item=S1, discount_source=prorated)`, com produto efetivo `P1`.

A linha já entra na categoria Café sem nenhuma ação.

### 2. Banana caturra em dois mercados

O mercado A vende `BANANA CATURRA KG` com código interno `243`: `store_item(S2, store, 243, A) → P2`, e o usuário
chamou `P2` de "Banana caturra", categoria Frutas. A primeira compra no mercado B traz `BAN CATURRA KG`, código `1050`,
e cria `store_item(S3, store, 1050, B) → P3`, sem nada manual.

O usuário vincula `S3` a `P2`. Resultado: `S3 → P2` e `P3` é descartado. As linhas de B não mudam; seu produto
efetivo passa a ser `P2`. O histórico de preço de "Banana caturra" junta as duas lojas e pode ser separado por loja.
As próximas compras em B já caem em `P2`. Desvincular `S3` cria de novo um produto só dele.

### 3. Tamanho corrigido e depois um parser novo

A descrição é `CAFE TORRADO 5OOG` (com a letra O no lugar do zero), e `parseSize` não reconhece o tamanho. O usuário
informa 500 g: `P1.size_e6 = 500000, size_unit = kg, size_source = manual`.

O parser sobe de versão e a nota é reprocessada. As linhas são regravadas e `P1` não muda.

### 4. Correção do produto de uma linha e depois reprocessamento

A linha `N1/5` usa o código interno `77`, que a loja reutilizou para outro produto. Ela caiu em `P4` (Detergente), mas
é Esponja. O usuário corrige só essa linha: `product_override_id = P5`.

No reprocessamento, a linha 5 continua com o código `77` e a correção é preservada. As outras compras desse código continuam em `P4`. Se a loja
trocou de vez o que o código significa, o certo é desvincular o item da loja, não corrigir linha a linha.

### 5. Desconto manual

A nota `N2` tem desconto total de R$ 3,00, e o usuário vê na foto R$ 2,00 de desconto na linha 2. Resultado:
- a linha 2 fica com `discount_cents = 200, source = manual, review = confirmed`;
- o rateio distribui os 100 centavos restantes entre as demais linhas, proporcionalmente ao bruto (com
  `source = prorated`).

O total da nota não muda; só muda a distribuição entre as categorias.

### 6. Exclusões

- **Nota:** "Apagar a nota de 12/03 no Mercado X? Ela tem 23 linhas e 14 fotos, que serão apagadas também." O usuário
  confirma. A nota, as linhas, as fotos e a página são apagadas no celular e, pela cópia, no servidor. Produtos que
  ficaram sem compras continuam no catálogo.
- **Produto sem compras:** é apagado direto, com os itens da loja sem compras que apontavam para ele.
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
origem) e o catálogo (itens da loja, produtos, categorias e tags). As fotos continuam em `receipt_photos` (D42).

- **Sem conflitos de sincronização:** o servidor nunca edita.
- **Fim do parse no servidor:** depois da troca, o PocketBase deixa de parsear e as regras de edição do admin são
  fechadas. O parser em JS fica como referência de teste até a versão Kotlin cobrir as mesmas fixtures.
- **Dashboard inalterado:** a view `spending_lines` mantém as colunas, e o dashboard não muda.
- **Exclusões:** chegam ao servidor como remoções na próxima cópia.
- **Fora do escopo:** sincronização em dois sentidos e edição pelo PC. Podem virar uma etapa própria.

## Plano de migração

Correção de 2026-10-17: no roadmap, ler as notas no celular é a etapa 3. Na etapa 2 o servidor continua lendo as
notas e continua sendo onde se categoriza; o celular ganha o banco e uma cópia consultável. A inversão (o celular como
fonte da verdade, envio num só sentido) acontece nas etapas 3 e 4.

**Etapa 2:**
- **2a (D45):** banco no celular para notas, fotos e sessões de captura. Os `receipt.json` são importados uma vez e
  renomeados para `receipt.json.migrated`, preservando versões e estado de envio. Nada muda na tela nem no envio.
- **2b:** as demais tabelas, e uma cópia somente leitura, vinda do servidor, das linhas e do catálogo:
  - categorias, tags e tamanhos manuais;
  - cada produto do servidor vira um item da loja e um produto (hoje 1 para 1), casando pelas chaves de produto;
  - a nota que só existe no servidor (hoje o servidor tem 9 e o celular 8).

  Conferir contagens, a categoria de cada produto e os totais por mês contra o dashboard.
- **2c:** exportar e restaurar o banco, as fotos e as páginas, com uma restauração real verificada.

**Etapas 3 e 4:**
1. Backup do servidor (`POST /api/backups`, cópia em `~/getnf-backups/`) e do celular.
2. O parser em Kotlin cobre as mesmas fixtures que o de JS, e as notas são lidas no celular.
3. O cadastro passa a ser editado no celular. Nesse momento, conferir de novo contagens, categorias e totais por mês.
4. Mudar o envio para a cópia num só sentido e desligar o parse e a edição no servidor, nessa ordem e só depois da
   conferência.

Dados de compras reais que passarem pelo PC durante a migração (exportações em JSON) não entram no repositório.

## Fora deste desenho

- **Agrupar produtos diferentes para comparar preços** (vinhos, café 500 g de marcas diferentes, banana caturra com
  prata): é uma decisão de análise (IPCI), não de identidade. Os produtos continuam distintos; pode virar uma tabela de
  grupos de comparação depois. Para os totais de gasto, a categoria já agrupa. O mesmo item em lojas diferentes não é
  esse caso: ele é unificado no produto (fluxo 2).
- **Vínculo com o cadastro compartilhado e curadoria:** o id local do produto e os códigos dos itens da loja permitem associar depois,
  sem depender de ids remotos ([local-and-shared-modes.md](local-and-shared-modes.md)).
- **Classificação histórica por época:** foi recusada por agora (D43).
- **OCR de descontos:** o esquema já tem `source = photo` e o estado de revisão.
- **Retenção de fotos e limpeza das duplicadas antigas:** ver [capture-retention-plan.md](capture-retention-plan.md).
