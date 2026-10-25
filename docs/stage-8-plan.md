# Etapa 8: o LLM como fonte de sugestões

Plano de 2026-10-25 (D80). Escolhido pelo usuário: as quatro frentes, com o LLM local (LM Studio, modelo Swift) no PC.
Base: D51 (sugestões com origem e estado de revisão), D57 (o LLM revisa, o Jev atribui), D66 (itens genéricos), D69
(rotina de limpeza), D74 (Jev no Supabase), D75 (descrições das categorias).

Tudo o que o LLM produz é sugestão: o usuário confirma no celular, como hoje. Nada muda sozinho, exceto entradas do
léxico que passam pela trava de regressão (D69).

## 8a. Esquema

- **Produto:** tamanho sugerido (quantidade, unidade, modelo, data) e a data da última auditoria. A discordância do
  LLM com uma categoria automática usa as colunas de sugestão que já existem.
- **Categoria:** descrição sugerida.
- **`link_suggestion`** (sincronizada com o celular): "este item da loja é o mesmo produto que aquele", com
  confiança, motivo e estado (pendente, aceita, recusada).
- **Só do servidor:** `audit_log` (cada conferência do LLM, para medir o erro de verdade), `category_rule` (regras
  aprendidas das correções do usuário, usadas pelo LLM) e entradas do léxico com origem `llm`.
- **`worker_report`:** o relatório de saúde de cada execução, legível pelo usuário.

## 8b. O worker no PC (`tools/llm-worker.py`)

Roda uma vez por noite (timer do systemd, depois do backup), carrega o modelo no LM Studio, faz as tarefas e
descarrega. Fala com o Supabase com a chave secreta e com o Jev pelo modo de verificação da função (D74).

1. **Auditoria (D69):** confere categorias automáticas não confirmadas, maior gasto primeiro, depois as nunca
   conferidas, mais uma amostra aleatória que mede o erro. Discordou: grava a sugestão do LLM, e o produto vai para
   "Para escolher".
2. **Aprender com as correções:** para cada correção nova, o LLM propõe a regra ou a entrada de léxico implícita.
   Regras vão para `category_rule`; entradas de léxico só entram se a verificação do Jev nos itens confirmados não
   piorar e a correção passar a sair certa.
3. **Mesmo produto em outra loja:** candidatos sem código de barras (e os com código, se a descrição bate) entre
   lojas diferentes, mesma unidade de venda; o LLM decide se é o mesmo item (variedade e forma de venda, D44/D66).
4. **Tamanhos:** onde o parser não leu o tamanho e o item não é vendido por peso, o LLM propõe (ou diz "sem
   tamanho": a mala de "10KG" é capacidade).
5. **Descrições:** categorias criadas pelo usuário sem descrição ganham um rascunho.
6. **Relatório:** aceitas automaticamente, fila de revisão, erro medido na amostra, idade da auditoria mais antiga,
   idade do backup (D65).

## 8c. Celular

- "Para escolher" inclui as discordâncias: "Jev: X · o LLM acha: Y", com Manter, Usar Y e Outra categoria.
- Um filtro novo, **Sugestões**: mesmos produtos e tamanhos, com Aceitar e Recusar.
- Categorias mostra a descrição sugerida com Aceitar.
- Ajustes mostra o último relatório.

## 8d. Agendamento

Timer do systemd às 04:00 (o backup roda às 03:30). Sem o PC ligado, nada roda e nada se perde: o worker retoma na
próxima noite.
