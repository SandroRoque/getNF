# Objetivos e evolução do getNF

Registrado em 2026-10-14, a partir da discussão com o usuário. Este documento registra a direção do produto;
não descreve funcionalidades já implementadas nem exige executar todas as mudanças simultaneamente.

## Objetivos definidos

### 1. Aplicativo autocontido, com Android em primeira classe

- O usuário deve conseguir usar o aplicativo e seus recursos essenciais no Android sem depender de um PC ou
  servidor ligado: preservar notas, processar os dados capturados, gerenciar o catálogo e consultar suas análises.
- A experiência de análise e dashboard deve fazer parte do aplicativo Android.
- O dashboard no PC deve continuar disponível. O mecanismo de acesso aos dados no PC ainda precisa ser definido.
- A consulta à SEFAZ continua dependendo de acesso à internet; autonomia local não implica consultar o portal offline.
- A arquitetura atual, com extração e categorização no PocketBase, é o ponto de partida para uma migração gradual.

**Decisão após avaliar ETL em VPS, em 2026-10-14:** o parser executará no Android. O modo local inclui revisão
manual completa e análises próprias sem dependência do serviço coletivo. Compartilhamento é opcional para
curadoria e acesso a análises coletivas; ver [local-and-shared-modes.md](local-and-shared-modes.md).

### 2. Cadastro e categorização sob controle do usuário

Até aqui, o cadastro e a categorização têm dependido de agente e da administração do servidor. Falta definir
um fluxo manual no produto. O usuário precisa conseguir cadastrar, editar e excluir itens, além de categorizá-los,
pela interface do Android e sem assistência de agente.

O desenho do fluxo deve contemplar:

- Cadastro manual de um produto e identificação de um produto novo encontrado em uma nota.
- Revisão de itens pendentes de identificação ou categorização.
- Edição de nome, identificação, categoria, unidade e tamanho de embalagem, conforme os campos aplicáveis.
- Correção de uma associação entre item da nota e produto do catálogo.
- Exclusão de cadastros, com comportamento explícito quando já existem compras vinculadas.
- Preservação das correções manuais quando uma nota for reprocessada.

**Ainda a definir:** a distinção na interface entre produto do catálogo e linha de uma nota; o alcance da exclusão
de cada entidade; criação, edição e exclusão de categorias; tratamento de duplicatas e equivalências entre produtos.
O requisito de exclusão está definido, mas a política para referências e histórico ainda não está.

### 3. IPCI — Índice de Preços ao Consumidor Individualíssimo

Criar uma medida de inflação individual a partir do histórico de compras do usuário. O nome proposto pelo usuário
é IPCI — Índice de Preços ao Consumidor Individualíssimo.

O indicador deve permitir investigar a evolução dos preços da cesta pessoal, distinguindo-a da variação do gasto
causada por quantidades ou escolhas de consumo diferentes.

**Propostas para discussão, ainda não decisões metodológicas:**

- Cesta pessoal de referência com quantidades fixas por um período.
- Comparação inicial dos mesmos produtos, com normalização por kg/litro quando aplicável.
- Exibição da cobertura: quanto da cesta tem preços comparáveis no período.
- Histórico por produto e contribuição de produtos/categorias para a variação do índice.
- Identificação das limitações decorrentes de compras não capturadas, promoções, troca de loja, embalagens e
  descontos rateados. Ausência de observação não deve ser interpretada automaticamente como preço constante.

A fórmula, o período de referência, as regras para preços ausentes, as substituições e a atualização da cesta
ainda precisam ser definidos. A cobertura inicial vem das compras registradas; não pressupõe representar todas
as despesas pessoais, como aluguel e serviços.

### 4. Avaliar assistência automática local e por API

Considerar uma API descrita pelo usuário como **“system one”**, começando com **“laya” localmente** e refinando
depois com outras APIs novas. Esses termos foram preservados como mencionados; a ferramenta, o modelo ou o
provedor exatos ainda precisam ser identificados antes de qualquer escolha técnica.

Avaliar essa possibilidade para auxiliar o cadastro e a categorização, mantendo o fluxo manual completo.
Ainda precisam ser definidos:

- O que a assistência propõe: identificação, categoria, unidade, embalagem ou outros campos.
- Como o usuário revisa, aceita e corrige sugestões.
- Como preservar a origem das sugestões e a prioridade das correções manuais.
- Como executar a etapa local e quando recorrer a uma API externa.
- Quais dados seriam enviados, os custos e o comportamento sem conexão ou sem API configurada.

A hipótese de trabalho é permitir evoluir a assistência sem prender o cadastro a um único provedor.
Nenhuma integração, envio de dados ou contratação de API está decidido por este registro.

### 5. Cadastro compartilhado e análises coletivas

Permitir compartilhar dados das notas para classificação, correção e conferência pelo responsável do serviço,
construindo um cadastro abrangente de produtos e emissores e histórico de preços. Pretende-se coletar os dados
fiscais, financeiros e de produtos disponíveis, preferindo excluir nome, CPF e dados de cartão do comprador.
O contrato de campos e evidências ainda precisa ser desenhado.

Análises que dependam do coletivo ficam disponíveis somente para quem compartilha dados das notas. Quem optar
pelo modo local continua com suas análises próprias. Regras e questões abertas estão em
[local-and-shared-modes.md](local-and-shared-modes.md).

### 6. Captura contínua e automática

Substituir a sequência QR → fotos manuais → conclusão manual por uma sessão contínua: o usuário aponta/percorre
a nota e o app coleta imagens suficientes, orienta ajustes e fecha a câmera automaticamente. Não exigir disparos
de fotos pelo usuário. Qualidade, identificação e cobertura integral devem orientar o término, inclusive em
recibos longos. Detecção de bordas e seleção de frames são sugestões de implementação, não escolhas fechadas.
É requisito obrigatório localizar a área visível da nota e usar somente seu interior para avaliar nitidez,
excluindo o fundo e a borda papel/fundo. A localização deve admitir trechos parciais do recibo.
Durante o desenvolvimento, preservar generosamente as imagens de cada NF. Planejar heurísticas para
rejeitar capturas ruins e reduzir redundância, preservando cobertura e detalhes úteis; não implementar limpeza
silenciosa nesta etapa. Ver [capture-retention-plan.md](capture-retention-plan.md) e D34.
Ver [automatic-capture.md](automatic-capture.md) para fluxo, alternativas e entregas incrementais.

## Etapas propostas

A divisão do trabalho está em [roadmap.md](roadmap.md), com entregas e dependências. A primeira etapa é revisar
o modelo de dados e a persistência, conforme solicitado pelo usuário; a avaliação preliminar está em
[data-model-review.md](data-model-review.md). As recomendações técnicas ainda são propostas.

As etapas podem ser divididas ou reordenadas conforme a discussão. Não é necessário implementar todos os
objetivos de uma vez.
