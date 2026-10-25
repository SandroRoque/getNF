# Etapa 4: catálogo no celular sobre o Supabase

Plano de 2026-10-25 (D70). Substitui a etapa 4 do [roadmap.md](roadmap.md), que era só manual e com o PocketBase como
dono do catálogo. Base: D51 (sugestões com origem e estado de revisão), D55 (dados online, sem banco no PC), D61
(fotos pequenas), D62–D63 (Supabase em São Paulo, plano grátis, fotos desde o início), D65 (saúde do banco), D67 (Jev)
e D69 (rotina de limpeza).

## Por que nessa ordem

Hoje o PocketBase decide categoria, produto e tamanho, e cada atualização do celular sobrescreve a cópia local
(`ServerMirror`, D46). Uma edição no celular sumiria na próxima atualização. Por isso o dono dos dados muda primeiro,
direto para o Supabase, sem gastar trabalho de sincronização com o PocketBase, que vai ser aposentado.

## 4a. Fundação no Supabase

- **Projeto** em São Paulo (sa-east-1), plano grátis. Desenvolvimento com o Supabase CLI local (Docker); o esquema só
  muda por migrações em `supabase/migrations/` (D65).
- **Esquema:** o de [data-model.md](data-model.md) (nota, página, linha, emissor, item da loja, produto, categoria,
  tag, foto), com `user_id` e Row Level Security em toda tabela. Categorias e catálogo são por usuário; a árvore
  de cada um nasce de `taxonomy/categories.txt`.
- **Proveniência (D51, D69)** nos campos interpretados (categoria, tamanho, vínculo de produto): valor, origem (regex,
  Jev, LLM, usuário), modelo e versão, confiança, data e estado (sugerido, automático, confirmado, corrigido).
- **Conta:** e-mail e senha, uma conta (a do usuário).
- **Sincronização no Android:** o Room continua como cache e base do modo local. Edições vão para o Room e para uma
  fila de saída enviada ao Supabase; o celular puxa o que mudou desde a última vez. Regra: um valor confirmado pelo
  usuário nunca é trocado por um de modelo; entre dois do usuário, vale o mais recente.
- **Fotos (D61):** recorte da nota pela máscara, tons de cinza, WebP; bucket privado por usuário. Os descontos
  continuam lidos no original, no celular. O original é apagado do celular depois da confirmação.
- **Migração dos dados reais:** backup do PocketBase e do celular antes. Notas, páginas, linhas e fotos saem do
  celular; categorias, vínculos e tamanhos manuais, do PocketBase (só ele os tem). Ninguém fica de fora: contagens
  e totais conferidos dos dois lados. As 55 fotos duplicadas não vão.
- **Backup (D65):** desde o primeiro dia de dados reais, `pg_dump` noturno e cópia das fotos para
  `~/getnf-backups/` no PC; uma restauração num Supabase local antes de confiar nele.
- **Conclusão:** os dados reais estão no Supabase, o celular sincroniza nos dois sentidos, totais batem com as notas,
  um backup foi restaurado. O app para de enviar ao PocketBase.

## 4b. Sugestões com o Jev

- **Edge Function** chamada quando entra um item da loja novo (fila no banco, para não perder nada se a função
  falhar). Pergunta única com todas as categorias folha (D67), com o léxico de tradução e, se houver código de barras,
  o nome do Open Food Facts (D68, consulta uma vez por código, guardada numa tabela).
- **Limiar:** confiança ≥ 0,9 vira "automático, não confirmado"; abaixo, vai para a lista de revisão. Ajustável.
- **Chave do Jev** como segredo do Supabase. Saem para a TypeSafe: descrição do item, nome do Open Food Facts e,
  se usado, loja, preço e outros itens da nota.
- **Conclusão:** uma nota nova ganha categorias em menos de um minuto com o PC desligado; o conjunto de 82 itens
  passado pela função dá o mesmo resultado do D67.

## 4c. Revisão e edição no celular (interface em português)

- **Lista de revisão:** primeiro os sugeridos (confiança baixa), depois, opcionalmente, os automáticos. Confirmar com
  um toque ou escolher outra categoria (seletor com busca na árvore).
- **Produto:** renomear, categoria, tamanho (com a origem visível), tags; vincular um item da loja a um produto
  existente (banana em dois mercados) e desvincular, com aviso de unidades incompatíveis (kg com unidade).
- **Linha:** corrigir o desconto, voltar ao rateado, ver a origem (página rateada, foto, manual): o que faltou do D54.
- **Árvore de categorias:** criar, renomear, mover, excluir, juntar e dividir. Excluir com produtos pede para onde
  eles vão, sugerindo a categoria-mãe. Cada edição grava um evento num registro só de inclusão (D75), que é
  construído primeiro, antes do editor:
  - **Juntar:** os produtos confirmados seguem para a categoria resultante e continuam confirmados.
  - **Dividir:** os produtos da categoria antiga vão para a lista de revisão ("escolha uma das novas"), e as
    confirmações antigas saem do conjunto de teste do D69 até a escolha.
  - **Qualquer mudança na árvore** dispara a nova verificação das atribuições automáticas (D69).
  - **Descrição:** cada categoria tem uma descrição de uma linha, separada do rótulo; ao criar uma, o LLM propõe a
    descrição e o usuário aceita ou edita. O Jev lê a descrição em vez do rótulo.
- **Toda ação do usuário** grava origem "usuário" e não é sobrescrita por reprocessamento nem por modelo.
- **Conclusão:** o usuário mantém o catálogo só pelo celular, sem administração de servidor; as edições sobrevivem a
  reprocessamento e sincronização; o fluxo manual funciona sem o Jev.

4b e 4c podem andar juntas depois da 4a.

## Fora desta etapa

Relatórios no celular (etapa 5), app web, IPCI (etapa 7, D64), a auditoria semanal pelo LLM e a trava de regressão
do D69 (worker no PC), itens genéricos para produtos sem código (D66), beta com amigos.

## O que precisa do usuário

1. Criar a conta e o projeto no Supabase, região São Paulo, e pôr URL, chaves e senha do banco em `server/.env.local`.
2. Confirmar antes de cada passo que envia dados reais para fora (migração para o Supabase, chamadas ao Jev).
