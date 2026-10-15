# Modo local, compartilhamento e curadoria

Direção definida pelo usuário em 2026-10-14. Requisitos de produto; funcionalidades ainda não implementadas.

## Base autônoma no Android

O parser e a derivação básica executam no Android. O usuário pode cadastrar, editar, excluir, categorizar,
corrigir volume/unidade/peso e revisar os dados das compras sem serviço externo. Fotos e páginas já capturadas
podem ser processadas localmente. Obter uma página da SEFAZ continua exigindo conexão ao portal.

O modo local oferece análises do próprio histórico, inclusive histórico de preços e IPCI quando houver dados
suficientes e metodologia implementada. Não exige contribuir para uma base coletiva. O acesso ao dashboard no PC
continua sendo um objetivo e deve ter um caminho que não obrigue compartilhar notas com o serviço coletivo.

## Compartilhamento opcional

O usuário pode optar por compartilhar dados das notas para que o responsável pelo serviço classifique, corrija
e confira as informações. A curadoria precisa de uma visão abrangente dos produtos, emissores e dados fiscais e
financeiros disponíveis nas fontes. O objetivo é construir um cadastro compreensivo de produtos e um histórico
de preços a partir das contribuições.

Dados pretendidos incluem, quando disponíveis: identificação e descrição dos produtos, códigos, quantidades,
unidades, embalagens, preços, descontos, acréscimos, totais, informações tributárias e identificação do emissor
(como CNPJ, nome e estabelecimento). Meio de pagamento e valores podem ser úteis; identificadores de cartão ou
do comprador não fazem parte desse objetivo. A lista técnica de campos ainda precisa ser definida.

**Preferência explícita do usuário:** não coletar nome, CPF nem dados de cartão do tomador/comprador. Não confundir
essa exclusão com a identificação do emissor, que é necessária à proposta.

Essa separação deve valer também para evidências, não apenas colunas do banco. HTML, fotos, QR/URLs e chave de
acesso podem conter dados do comprador ou permitir recuperar a nota. Antes de enviar essas fontes, definir o que
é necessário à conferência, o que pode ser removido e quais referências permanecem acessíveis. Não presumir que
retirar nome/CPF dos campos estruturados torna uma captura bruta anônima. O contrato de compartilhamento deve
explicar os dados efetivamente enviados; não adotar automaticamente o upload integral atual como contrato final.

## Análises locais e coletivas

| Recurso | Modo local | Compartilhamento habilitado |
| --- | --- | --- |
| Parser e revisão manual | Disponíveis | Disponíveis |
| Categorias e correções próprias | Disponíveis | Disponíveis |
| Análises do histórico próprio | Disponíveis | Disponíveis |
| IPCI sobre compras próprias | Disponível quando implementado e com cobertura suficiente | Disponível nas mesmas condições |
| Curadoria pelo responsável do serviço | Não depende desse serviço | Disponível conforme fluxo e escopo a definir |
| Análises que dependem da base coletiva | Indisponíveis | Restritas a quem compartilha dados das notas |

A contribuição dá acesso às análises coletivas, não implica acesso irrestrito às notas individuais dos demais
participantes. Exemplos possíveis são comparações de preços por produto, loja ou região; métricas e cobertura
precisam ser definidas antes de prometer resultados.

## Consequências para o modelo

- Distinguir cadastro pessoal, dados extraídos da nota e cadastro curado compartilhado.
- Relacionar produtos e emissores locais aos registros compartilhados sem depender de IDs remotos no modo local.
- Guardar origem, versão e estado de revisão das informações. “Extraído”, “corrigido pelo usuário” e “conferido
  pelo serviço” não são estados equivalentes, nem uma conferência garante ausência absoluta de erros.
- Definir como correções curadas retornam ao Android e como convivem com escolhas manuais do usuário.
- Separar sincronização privada/backup/acesso ao PC de contribuição para a base coletiva.
- Manter regras de unicidade para que o mesmo documento recebido de novo não duplique observações de preço;
  definir como divergências entre contribuições são resolvidas.

## Pontos ainda abertos

1. Campos e evidências enviados, incluindo tratamento de chave de acesso, QR/URLs e dados incidentais do comprador.
2. Identidade e equivalência de produtos entre estabelecimentos, variantes e embalagens.
3. Fluxo de curadoria, aceitação de correções e estados de qualidade visíveis.
4. Granularidade da escolha de compartilhar (conta, nota ou outro escopo) e tratamento do histórico já existente.
5. Comportamento ao parar de compartilhar: dados já contribuídos, exclusão e acesso posterior às análises coletivas.
6. Hospedagem do serviço coletivo, autenticação e controle de acesso. VPS é uma possibilidade, não infraestrutura escolhida.

O compartilhamento de notas também não autoriza automaticamente enviá-las a provedores externos de IA.
As integrações de assistência terão seu próprio escopo de dados definido.
