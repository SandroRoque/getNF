# Onde hospedar o serviço online

Comparação de 2026-10-24, depois de D55 (serviço online primeiro, sem banco no computador do usuário). Decidido em
D62: Supabase. Preços e limites conferidos na data, em
fontes públicas listadas no fim; mudam com frequência.

## O que o serviço precisa fazer

- **Contas e isolamento por usuário** desde o início (D55: o usuário primeiro, depois amigos e um beta).
- **Sincronizar com o Android:** notas, linhas, catálogo e correções. O celular continua buscando a página na SEFAZ
  (limite de ~5 consultas por hora por IP) e lendo a nota; o servidor recebe o resultado.
- **Guardar fotos e máscaras.** Hoje 9 notas ocupam ~124 MB com fotos, máscaras e páginas (~14 MB por nota). Com ~15
  notas por mês são ~200 MB por mês por usuário.
  Com D61 (planejado: o celular lê os descontos na foto original e envia só uma cópia recortada, em cinza, WebP),
  isso cai para menos de ~1 MB por nota, ~10 MB por mês.
- **Relatórios em SQL:** totais por mês e categoria, preços ao longo do tempo (IPCI).
- **Categorização (D57–D59):** o Laya atribui (CPU basta: 200–460 ms por pergunta, ~3 perguntas por item, só para itens
  novos), o LLM revisa em lote e mantém léxico e regras. Precisa de um processo que rode modelos, e as funções
  serverless típicas não rodam PyTorch.
- **Consultas por código de barras** (D60), uma vez por item e com cache.
- **Pouca operação, custo baixo, dados no Brasil** de preferência (LGPD, latência).

## Opções

| | **Supabase** | **PocketBase em VPS** | **Firebase** |
|---|---|---|---|
| Banco | Postgres completo | SQLite (um servidor) | Firestore (documentos, NoSQL) |
| Contas e isolamento | Auth pronto + Row Level Security no banco | Auth e regras de API prontos | Auth + regras de segurança |
| Fotos | Storage pronto | Arquivos prontos | Cloud Storage (exige plano pago desde 02/2026) |
| Relatórios SQL | Sim | Sim (views, como hoje) | Não; agregações viram código ou exportação |
| Rodar Laya/LLM | Não: precisa de um worker à parte | Sim, na mesma máquina, se tiver RAM | Não: worker à parte |
| Região no Brasil | São Paulo (sa-east-1) | Depende do VPS: Hetzner não tem; Vultr, Linode, AWS Lightsail e provedores locais têm | São Paulo (southamerica-east1) |
| Operação | Quase nenhuma (backups, atualizações por eles) | Nossa: atualizações, backups, segurança, disco | Quase nenhuma |
| Reaproveita o que existe | Pouco: esquema novo em Postgres, cliente novo no Android | Muito: migrações, hooks e cliente atuais | Pouco |
| Preso ao fornecedor | Baixo: é Postgres, exporta com `pg_dump`; Supabase é open source | Nenhum | Alto |
| Custo para começar | Grátis: 500 MB de banco, 1 GB de arquivos; pausa após 1 semana sem uso | VPS de 4 GB: ~€8,50/mês (Hetzner, fora do Brasil) a ~US$24/mês (Linode em SP) | Grátis até as cotas diárias; Storage pede cartão |
| Custo com uso real | Pro US$25/mês: 8 GB de banco, 100 GB de arquivos, sem pausa, teto de gasto | O mesmo VPS até encher o disco | Paga por leitura/escrita; difícil de prever |

Opções descartadas: PocketHost (PocketBase gerenciado, ~US$5–10/mês) não roda o Laya e não tem região no Brasil;
AWS ou Google Cloud "crus" exigiriam montar banco, auth e storage por conta própria.

## O worker de categorização

Em qualquer opção sem modelos no servidor, um processo à parte busca itens novos, chama o Laya e o LLM, e grava as
sugestões. Isso combina com o momento atual:

- **Agora (desenvolvimento):** o worker roda no computador do usuário, onde já estão o Laya, a GPU e o LM Studio (D57:
  modelos locais por enquanto). Ele só lê e escreve no serviço online; o banco não fica no computador.
- **No lançamento:** o worker vai para um VPS pequeno (CPU basta para o Laya) e o LLM passa a um provedor de modelos
  open-weight na nuvem.

## Recomendação

**Supabase na região de São Paulo, com o worker de categorização à parte.**

- Contas, isolamento por usuário, fotos e SQL vêm prontos, e o beta com amigos não exige trabalho de segurança extra.
- Pouca operação: backups e atualizações ficam com eles. O ganho é tempo, não preço.
- Sem prisão: o Supabase é open source e pode ser auto-hospedado. Se a conta crescer, o mesmo banco vai para uma
  máquina própria ou um VPS.
- O plano grátis basta para começar. O Pro (US$25/mês) passa a valer quando os amigos entrarem, ou antes, se a pausa
  por inatividade ou o 1 GB de arquivos (~5 meses de fotos de um usuário) atrapalharem.

**PocketBase em VPS** é a alternativa se o custo ou o controle pesarem mais: reaproveita o servidor atual e roda o Laya
na mesma máquina, mas backups, disponibilidade e o isolamento entre usuários ficam conosco. **Firebase** não combina com relatórios em
SQL nem com preços ao longo do tempo.

## Busca por semelhança não pesa

Os exemplos de itens parecidos levaram o LLM de 87% a 95% (D58), mas pelo D57 o LLM só revisa em lote. O worker acha
os itens parecidos em memória (milhares de itens se varrem em instantes); o banco não precisa fazer isso.

## Custo se o app crescer

Estimativa grosseira, de memória, não reconferida: 1.000 usuários ativos com as fotos pequenas do D61 (~10 GB de fotos
por mês).

- **Supabase:** US$25/mês, mais ~US$0,02/GB/mês de arquivos acima de 100 GB e ~US$0,09/GB de saída acima de 250 GB;
  servidor de banco maior por ~US$15–110/mês. Provavelmente abaixo de US$100/mês.
- **VPS:** ~US$20–40/mês para a mesma carga. Mais barato, mas o tempo de operação é nosso.
- **Firebase:** cobra por leitura e escrita; o mais difícil de prever e o que mais cresce com relatórios.

Se as fotos virarem o grosso da conta, só elas vão para um armazenamento barato e sem custo de saída (Cloudflare R2).

## O que decidir

1. ~~Supabase ou PocketBase em VPS~~: Supabase (D62).
2. Fotos no serviço online desde o início, ou só os dados primeiro e as fotos depois.
3. Começar no plano grátis ou já no Pro.

## Fontes (2026-10-24)

- Supabase: [preços e limites](https://makerkit.dev/blog/saas/supabase-pricing), [regiões](https://supabase.com/docs/guides/platform/regions)
- Firebase: [preços 2026 e mudança do Storage](https://magora-systems.com/firebase-pricing/)
- PocketBase/PocketHost: [preços](https://pockethost.io/pricing)
- VPS: [Hetzner, localizações](https://docs.hetzner.com/cloud/general/locations/), [comparação de VPS](https://gautamkhorana.com/blog/best-vps-hosting-2026/)
- Laya em CPU: README do projeto ([NandhaKishorM/laya](https://github.com/NandhaKishorM/laya))
