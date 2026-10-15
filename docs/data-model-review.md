# Revisão do modelo de dados e da persistência

2026-10-14. Proposta para discussão; não é um esquema aprovado nem uma migração implementada.

**Atualização da discussão:** após avaliar ETL em VPS, o usuário escolheu parser no Android. O modelo também deve
acomodar compartilhamento opcional e curadoria sem comprometer o modo local; ver
[local-and-shared-modes.md](local-and-shared-modes.md) para as fronteiras entre cadastros e os dados a compartilhar.

## Conclusão preliminar

É necessário evoluir o modelo e acrescentar persistência estruturada no Android. Não foi identificada uma
necessidade de substituir o motor relacional ou remover o PocketBase imediatamente. A recomendação é SQLite no
Android, acessado por Room, preservando arquivos de captura separadamente. PocketBase pode continuar atendendo
o dashboard no PC durante a transição. A forma de compartilhar dados entre os dispositivos permanece aberta.

Modelo de dados, motor do banco, biblioteca de acesso e servidor são escolhas distintas:

| Camada | Hoje | Proposta |
| --- | --- | --- |
| Modelo | Notas, linhas, produtos, categorias e tags | Evoluir identidade, correções, exclusão e histórico |
| Persistência no Android | Arquivos e manifesto JSON por nota | SQLite para dados estruturados; arquivos para fotos/HTML |
| Acesso ao banco no Android | Não existe para os dados estruturados | Room, sujeito à validação da integração com o build |
| Servidor no PC | PocketBase, que usa SQLite | Manter inicialmente; reavaliar seu papel após definir compartilhamento |

Room oferece acesso a SQLite, verificação de consultas e suporte a migrações; não é outro motor de banco nem
um mecanismo automático de sincronização. Não selecionar versão ou alterar dependências antes de validar a
compatibilidade com AGP/Kotlin/KSP usados pelo projeto.

## Evidência no código atual

- `android/.../data/ReceiptStore.kt`: guarda apenas metadados da captura e estado de upload em JSON, junto dos arquivos.
- `server/pb_migrations/1791600000_derived_data.js`: já separa `receipts`, `receipt_items`, `products`, `categories` e `tags`.
  Produtos exigem uma chave GTIN ou CNPJ/código de loja; o cadastro manual sem esses identificadores não tem desenho próprio.
- `server/pb_hooks/lib/ingest.js`: atualiza a descrição do produto em cada extração. Preserva tamanho marcado como
  manual e descontos de foto por número da linha, mas recria os itens; não há preservação geral de correções manuais.
- Categorias são sincronizadas dos arquivos de taxonomia no início do servidor. Edição pelo usuário precisa de
  uma política para conviver com essa taxonomia.
- Valores monetários persistidos são números em reais; os cálculos de rateio já usam centavos inteiros.

## Evolução proposta do modelo

### Produto e seus identificadores

Dar ao produto um ID estável criado localmente, independente do nome, GTIN e código de loja. Tratar esses códigos
como identificadores associados ao produto, com regras de unicidade. Isso permite cadastrar sem código, corrigir
identificadores e associar códigos diferentes ao mesmo produto quando a equivalência for confirmada.

Não juntar automaticamente produtos diferentes apenas por descrição ou unidade. Equivalência para comparação
de preços é uma decisão adicional à identidade cadastral.

### Evidência, extração e correções

Manter a descrição original, quantidade, unidade e valores extraídos da nota separados dos campos apresentados
no catálogo e das correções do usuário. A implementação pode usar campos de sobreposição ou registros de correção;
a escolha deve considerar as operações reais do fluxo manual, sem exigir um sistema completo de eventos.

Uma nova extração não deve apagar nome manual, associação corrigida de produto ou embalagem corrigida.
A correspondência entre linhas antigas e novas precisa ser definida; IDs recriados e número de linha isolado
não devem ser presumidos suficientes para todos os reprocessamentos.

### Exclusão e categorias

Proposta: permitir remoção física de um produto sem referências; para produtos com compras, oferecer arquivamento
ou reassociação antes da remoção. A política final deve distinguir excluir cadastro, excluir uma nota e ocultar
um produto. Arquivar não satisfaz por si só todas as formas de exclusão pedidas pelo usuário.

Categorias devem ter identidade estável independente do caminho/nome editável. Definir o que acontece com produtos
e subcategorias ao excluir ou mover uma categoria, e como as categorias iniciais da taxonomia são atualizadas
sem sobrescrever alterações pessoais.

### Dinheiro, quantidade e histórico

Proposta: totais e descontos em centavos inteiros. Quantidades e preços unitários precisam de precisão própria:
um preço unitário pode exigir mais de duas casas. Definir representação decimal/escala e arredondamento explícitos.
Para descontos, separar origem (extração HTML, OCR de foto, manual ou rateio), estado de revisão e evidência,
conforme a proposta em [discounts-and-ocr.md](discounts-and-ocr.md).

Preservar a embalagem/unidade aplicável à compra para que uma edição no cadastro não altere silenciosamente o
preço histórico por kg/litro. Distinguir uma correção de dado histórico de uma mudança real de embalagem.
Também definir se relatórios por categoria refletem o cadastro atual ou a classificação da época.

### Assistência e IPCI

Sugestões automáticas devem ter origem e estado de revisão separados dos valores confirmados. O primeiro modelo
não precisa depender de um provedor de IA específico.

As linhas de compra já são a base das observações de preço. Não é necessário criar imediatamente um segundo
histórico de preços duplicado. Cestas, pesos e versões metodológicas do IPCI serão definidos quando a metodologia
for discutida; por enquanto, preservar identidade, datas, unidades, preços e origem das correções.

## Ordem proposta para o trabalho

1. Definir entidades e operações com exemplos concretos de cadastro, correção, reprocessamento e exclusão.
2. Desenhar o esquema local e a migração, incluindo classificações/correções que hoje só existem no servidor.
3. Validar Room no build e migrar um fluxo pequeno, preservando capturas e comportamento existente.
4. Definir como o PC recebe os dados e quais dispositivos podem editar. Só então fechar regras de sincronização,
   conflitos e propagação de exclusões. Upload idempotente de capturas não equivale a sincronização de catálogo.

## Referências

- [Room: persistência local sobre SQLite](https://developer.android.com/training/data-storage/room).
- [PocketBase: servidor com banco SQLite embutido](https://pocketbase.io/docs/).
- [Arquitetura atual](architecture.md) e [objetivos do produto](objectives.md).
