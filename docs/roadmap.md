# Etapas de evolução do getNF

Visão de alto nível registrada em 2026-10-14. Este é um plano proposto para dividir o trabalho, não uma indicação
de funcionalidades implementadas. Os requisitos estão em [objectives.md](objectives.md), e a avaliação inicial
da persistência está em [data-model-review.md](data-model-review.md).

## Visão geral

| Etapa | Foco | Entrega esperada |
| --- | --- | --- |
| 1 | Modelo de dados e arquitetura | Esquema e regras definidos para sustentar os novos fluxos |
| 2 | Persistência local e migração | Dados estruturados e cadastros existentes disponíveis no Android |
| 3 | Processamento local das notas | Captura vira itens e produtos no Android sem serviço externo |
| 4 | Cadastro e categorização manual | Usuário cadastra, edita, exclui e corrige pela interface |
| 5 | Análise no Android | Dashboard de gastos e histórico de preços no celular |
| 6 | Continuidade no PC | Dashboard recebe os dados e as correções do Android |
| 7 | IPCI | Índice pessoal com metodologia e cobertura explícitas |
| 8 | Assistência local e por API | Sugestões revisáveis que complementam o fluxo manual |
| 9 | Compartilhamento e curadoria | Contribuições opcionais alimentam produtos, emissores e preços compartilhados |
| 10 | Análises coletivas | Comparações apoiadas no coletivo disponíveis a quem contribui |

## 1. Definir modelo de dados e arquitetura

**Frente adicional de captura:** [automatic-capture.md](automatic-capture.md) detalha a experiência de câmera
contínua, com fotos e conclusão automáticas. A prova de viabilidade pode começar cedo. Incluir sessão, imagens,
qualidade e cobertura no desenho da etapa 1; entregar captura automática simples e depois cobertura de recibos
longos como incrementos próprios. Essa frente fornece as evidências para OCR nas etapas 3–4 e não depende do IPCI
ou do serviço coletivo. O objetivo só está completo quando o usuário não precisa disparar fotos nem decidir
manualmente que já capturou a nota inteira.

Fluxo por segmentos implementado em 2026-10-14: máscara explícita, nitidez restrita à nota, registro de sobreposição,
QR tardio e conclusão automática com cobertura conectada. Substitui o primeiro incremento de documento inteiro
e seu fallback manual. Em 2026-10-15, duas capturas no Motorola concluíram e preservaram as imagens.
A revisão encontrou borrão aceito pelo filtro: a próxima prioridade é qualidade. Retenção generosa durante
o desenvolvimento e futura seleção de imagens ruins/redundantes estão em
[capture-retention-plan.md](capture-retention-plan.md); não há descarte posterior implementado.
A validação física mais ampla continua necessária.
Ver [capture-validation.md](capture-validation.md).

Começar pelas entidades e pelas operações que o usuário precisa executar. Definir identidade de produto,
identificadores externos, categorias, unidades, embalagem, precisão dos valores e separação entre extração e
correções. Estabelecer regras de exclusão e preservação do histórico.

Avaliar e escolher a persistência local. SQLite com Room é a recomendação preliminar, ainda a validar.
Definir o papel do servidor e se o PC será apenas consulta ou também edição; essa escolha afeta as próximas etapas.
O parser no Android foi escolhido pelo usuário após a comparação em [etl-options.md](etl-options.md).
Definir a separação entre cadastro pessoal e compartilhado, conforme [local-and-shared-modes.md](local-and-shared-modes.md).
Desenhar o fluxo manual o suficiente para verificar que o esquema atende às operações, antes de construir as telas.
Incluir fotos como evidência com seus metadados (máscara, faixas, qualidade) e um envio ao servidor que só acrescente,
identificado por conteúdo: o upload atual substitui a lista de fotos do servidor (D35). Proposta de D35: iniciar esta
etapa depois da correção de qualidade da captura, pausando novos incrementos de geometria.

**Conclusão da etapa:** esquema proposto, exemplos dos fluxos principais, política de correções/exclusões e plano
de migração registrados. Escolhas aprovadas devem ser adicionadas ao registro de decisões.

## 2. Implantar persistência local e migrar os dados

Introduzir o banco estruturado no Android e importar as notas, produtos, classificações e correções existentes.
Manter fotos e páginas preservadas, com referências consistentes. Criar exportação e restauração que incluam
também os dados manuais, que não podem ser reconstruídos das capturas.

**Conclusão da etapa:** dados existentes consultáveis no celular, migração conferida e restauração verificada.
O processamento pode continuar no servidor durante esta entrega intermediária.

## 3. Processar notas no Android

Implementar extração e derivação no Android: itens, totais, descontos, identificação inicial dos produtos e
pendências de revisão. Permitir reprocessar capturas preservando correções e informar erros sem perder a nota.
Avaliar OCR local como incremento para recuperar descontos por item das fotos, conforme
[discounts-and-ocr.md](discounts-and-ocr.md), sem bloquear a extração HTML.

**Conclusão da etapa:** uma página capturada produz dados estruturados sem serviço remoto. Capturas salvas podem
ser processadas offline; buscar uma nova página da SEFAZ continua exigindo internet.

## 4. Entregar cadastro e categorização manual

Construir os fluxos de cadastro, edição, exclusão e categorização de produtos, bem como revisão dos itens novos
e correção das associações com o catálogo. Incluir os fluxos de categorias conforme o escopo definido na etapa 1.
Mostrar as consequências da exclusão quando houver referências e permitir resolver duplicatas conforme a política escolhida.

**Conclusão da etapa:** o usuário mantém seu catálogo pelo Android sem agente nem administração do PocketBase;
as edições sobrevivem ao reprocessamento. O fluxo manual deve estar completo antes de depender de assistência automática.

Incluir correção manual dos descontos e distinção entre valores observados e rateados. OCR pode sugerir valores,
mas origem e estado de revisão precisam permanecer visíveis.

## 5. Entregar análise de primeira classe no Android

Disponibilizar resumo de gastos, filtros por período e categoria, detalhes de notas e produtos e evolução dos
preços. Integrar captura, pendências e análise à navegação do aplicativo. Expor descontos estimados e ausência
de dados de forma compreensível.

**Conclusão da etapa:** o usuário consulta e investiga suas compras registradas no celular sem servidor disponível;
os totais conciliam com as notas, respeitando os problemas de extração identificados.

## 6. Manter o dashboard no PC com dados consistentes

Implementar o mecanismo de compartilhamento escolhido na etapa 1, cobrindo dados estruturados, classificações,
correções e exclusões. Adaptar o dashboard para a nova origem. Se houver edição em mais de um dispositivo,
implementar as regras de conflito definidas para esse caso.
Este é acesso privado aos próprios dados, distinto da contribuição coletiva; o modo local também deve ter
um caminho para consultar o dashboard no PC sem enviar notas ao serviço coletivo.

**Conclusão da etapa:** o PC mostra dados e correções do Android, com estado de atualização visível; desconexão
e novas tentativas não duplicam compras nem ressuscitam cadastros excluídos.

O dashboard atual deve continuar disponível durante a transição. A conferência da etapa 3 deve comparar o novo
processamento com o existente antes de retirar responsabilidades do servidor. Ao introduzir edições locais na
etapa 4, tornar explícitas eventuais diferenças de atualização no PC até que esta etapa esteja concluída.

## 7. Definir e implementar o IPCI

Escolher metodologia, cesta de referência, pesos, períodos, tratamento de preços ausentes, substituições e
mudanças de embalagem. Implementar o índice e explicar a contribuição dos produtos/categorias, distinguindo
variação de preços de mudança de consumo. Mostrar cobertura e insuficiência de histórico.

**Conclusão da etapa:** cálculo verificável com exemplos conhecidos, metodologia registrada e apresentação
no Android e no dashboard do PC. Não apresentar uma leitura numérica quando a cobertura não sustentar o cálculo.

## 8. Experimentar assistência local e depois APIs

Identificar o que o usuário chamou de “system one” e “laya” antes de escolher ferramentas. Experimentar primeiro
a assistência local para cadastro/categorização, com sugestões que o usuário aceita, rejeita ou corrige.
Depois avaliar outras APIs, considerando qualidade, custo, dados enviados e funcionamento sem conexão.

**Conclusão da etapa:** assistência opcional com origem das sugestões identificada, correções manuais preservadas
e fluxo de cadastro funcional quando a assistência estiver indisponível.

## 9. Entregar compartilhamento opcional e curadoria

Definir o contrato de contribuição: dados de produtos, emissores e dados fiscais/financeiros disponíveis;
preferência por excluir nome, CPF e dados de cartão do comprador, inclusive nas evidências enviadas. Tratar
referências de consulta da nota conforme esse contrato. Implementar envio opcional, revisão pelo responsável,
identificação dos produtos/emissores compartilhados e retorno das correções com origem e estado de revisão.

Definir escopo da opção de compartilhar, exclusões, interrupção da contribuição e hospedagem do serviço.

**Conclusão da etapa:** contribuições alimentam um cadastro abrangente e histórico de preços sem duplicar notas;
correções curadas convivem com edições pessoais segundo regras explícitas. Desabilitar ou ficar sem o serviço
não impede parser, revisão manual e análises locais.

## 10. Entregar análises baseadas no coletivo

Definir comparações e métricas suportadas pela base compartilhada, com cobertura e critérios de comparabilidade
dos produtos. Disponibilizá-las somente para quem compartilha dados das notas, com controle de acesso no serviço.
Participar não implica ter acesso às notas individuais dos demais usuários.

**Conclusão da etapa:** participantes acessam análises coletivas com cobertura visível; usuários no modo local
continuam recebendo suas análises pessoais, inclusive IPCI quando sustentado pelo próprio histórico.

## Dependências e divisão das entregas

- A etapa 1 vem primeiro. As escolhas de esquema e edição entre dispositivos orientam o restante.
- A etapa 2 sustenta processamento, cadastro e análise locais. As etapas 3 e 4 podem avançar em incrementos
  separados após essa base, mantendo o processamento atual enquanto necessário.
- A etapa 6 pode ser antecipada após a persistência local se a atualização do dashboard no PC tiver prioridade.
  Seu contrato deve acompanhar cada novo tipo de dado editável introduzido.
- O IPCI depende do histórico de preços confiável da etapa 5; seu desenho metodológico pode começar antes.
- A assistência depende do fluxo manual e das regras de correção, mas não depende do IPCI. As etapas 7 e 8
  podem trocar de ordem conforme a prioridade do usuário.
- A etapa 9 depende da base local e das regras de identidade/correção; pode ser antecipada depois da etapa 4.
  A etapa 10 depende da contribuição e de cobertura suficiente. Ambas são opcionais para o usuário e não
  condicionam a autonomia ou as análises locais.

Cada etapa deve ser dividida em mudanças pequenas com conferência proporcional: preservação dos dados na
migração, manutenção das correções no reprocessamento, conciliação nas análises e consistência no compartilhamento.
Ao concluir uma entrega, atualizar o status real do projeto. Prazos e estimativas ficam para depois de detalhar
o escopo de cada etapa.
