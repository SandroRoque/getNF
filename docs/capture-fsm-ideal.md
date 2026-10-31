# The capture loop, idealised (draft, 2026-10-31)

A first definition to compare with today's ([capture-fsm.md](capture-fsm.md)); critique and compatibility come later.
Three pieces: the outer machine decides the object, the inner one assembles a receipt, and every frame answers a fixed
sequence of questions whose answers update the control variables that fire both machines' transitions.

## Control variables

| variable | meaning |
|---|---|
| `prova` | evidence of a receipt: the NFC-e QR, 2 receipt markers ("Documento Auxiliar", "Qtd. total de itens", "Valor total", "Consulte pela chave", CNPJ), or an item row whose arithmetic holds |
| `chave` | the receipt's access key, from a QR inside the paper |
| `guardado` | at least one photo saved for this attempt (a commitment: nothing is dropped by itself after it) |
| `buffer` | frames seen while undetermined, handed to the assembly once there's proof |
| `t_visto` / `t_ganho` | last time the object was in view / last time a new row was read |
| `pontas` | the paper's top and bottom seen at the rows |
| `fracas[row]` | frames that saw a missing row and read it below the legibility bar |
| timers | `T_perda` (object lost, ~2.5 s), `T_travada` (no gain, ~6 s), `T_escuro`; `K` frames for "worn" |

## Outer machine

```mermaid
stateDiagram-v2
  direction TB
  [*] --> Determinando: Escanear
  [*] --> ComCodigo: atalho do Catálogo
  Determinando: Determinando objeto<br/>quadros em buffer, sem compromisso
  Determinando --> Nota: prova de nota
  Determinando --> ComCodigo: prova de produto com código
  Determinando --> SemCodigo: prova de produto sem código
  note right of Determinando
    Prova de nota: QR da NFC-e, ou 2 marcadores de cupom,
    ou uma linha de item que fecha a conta.
    Prova de produto: código de barras confirmado no centro.
    Sem código: a pessoa indica (por enquanto).
  end note
  Nota: Nota<br/>Montando a nota (FSM própria)
  ComCodigo: Produto com código<br/>folha com os preços pagos
  SemCodigo: Produto sem código<br/>procurar no Catálogo
  Nota --> Determinando: objeto perdido por T_perda e nada guardado
  ComCodigo --> Determinando: sem código por T_perda
  SemCodigo --> Determinando: objeto perdido por T_perda
  ComCodigo --> Nota: prova de nota
  Nota --> Nota: QR de outra nota e nada guardado, troca
  Nota --> Salva: completa
  Nota --> SoCodigo: aceita salvar sem os itens
  Salva: Nota salva
  SoCodigo: Nota salva só com o código<br/>itens pela página do SEFAZ
  Salva --> [*]
  SoCodigo --> [*]
  Determinando --> [*]: Voltar
  Nota --> Saida: Voltar
  Saida: Saída<br/>com QR, guardar código ou descartar
  Saida --> [*]
  ComCodigo --> [*]: Voltar
  SemCodigo --> [*]: Voltar
```

## Inner machine: Montando a nota

```mermaid
stateDiagram-v2
  direction TB
  [*] --> Ancorando: prova de nota, buffer aproveitado
  Ancorando: Ancorando<br/>primeiras linhas na montagem
  Ancorando --> Lendo: linhas encaixadas
  state "Lendo" as Lendo {
    [*] --> Parado
    Parado: Lendo o trecho<br/>Segure firme
    Subir: Suba pela nota
    Descer: Desça pela nota
    Ajustar: Aproxime ou firme<br/>menos da metade legível
    Parado --> Subir: falta em cima
    Parado --> Descer: falta embaixo
    Subir --> Parado: trecho novo à vista
    Descer --> Parado: trecho novo à vista
    Parado --> Ajustar: ilegível no quadro
    Subir --> Ajustar: ilegível no quadro
    Descer --> Ajustar: ilegível no quadro
    Ajustar --> Parado: legível de novo
  }
  Lendo --> Desencaixado: quadro não encaixa
  Desencaixado: Desencaixado<br/>Volte ao trecho já lido
  Desencaixado --> Lendo: encaixou
  Lendo --> Travada: sem ganho por T_travada
  Travada: Travada<br/>classifica a causa
  Travada --> Apagada: faltantes vistas K vezes, sempre fracas
  Travada --> SemCausa: outra causa
  Apagada: Oferta, parte apagada<br/>Salvar assim · Continuar
  SemCausa: Oferta, não avança<br/>Recomeçar · Continuar
  Apagada --> Lendo: Continuar
  SemCausa --> Lendo: Continuar
  SemCausa --> [*]: Recomeçar, volta a Determinando
  Apagada --> [*]: Salvar assim, só com o código
  Lendo --> CompletaSemQR: pontas fechadas e tudo lido, sem QR
  CompletaSemQR: Completa sem QR<br/>Mostre o QR Code
  CompletaSemQR --> Salvando: QR lido no papel
  Lendo --> Salvando: pontas fechadas, tudo lido, QR lido
  Salvando: Salvando<br/>menor conjunto de fotos
  Salvando --> [*]: Nota salva
```

## Every frame: the questions, in order

```mermaid
flowchart TB
  Q0["Quadro novo"] --> Q1{"Escuro há T_escuro?"}
  Q1 -->|sim| A1["Avisa luz, segue"] --> Q2
  Q1 -->|não| Q2{"QR da NFC-e no papel?"}
  Q2 -->|"sim, chave nova"| A2["chave = lida, prova de nota"] --> Q4
  Q2 -->|"sim, outra chave"| A3["outra nota: troca se nada guardado, senão avisa"] --> Q4
  Q2 -->|não| Q3{"Sem prova de nota e código de produto confirmado no centro?"}
  Q3 -->|sim| A4["Produto com código, OCR desligado"] --> FIM
  Q3 -->|não| Q4{"Papel encontrado?"}
  Q4 -->|não| Q5{"Perdido há T_perda e nada guardado?"}
  Q5 -->|sim| A5["Descarta tentativa, Determinando"] --> FIM
  Q5 -->|não| A6["Pede para voltar ao papel"] --> FIM
  Q4 -->|sim| Q6{"Linhas trazem prova de nota?"}
  Q6 -->|sim| A7["prova = verdadeiro"] --> Q7
  Q6 -->|não| Q7{"Há prova de nota?"}
  Q7 -->|não| A8["Guarda no buffer, Determinando"] --> FIM
  Q7 -->|sim| Q8{"Linhas encaixam na montagem?"}
  Q8 -->|não| A9["Desencaixado, Volte ao trecho"] --> Q12
  Q8 -->|sim| Q9{"Metade ou mais legível?"}
  Q9 -->|não| A10["Ajustar, Aproxime ou firme"] --> Q12
  Q9 -->|sim| Q10{"Ganho, linhas novas lidas?"}
  Q10 -->|sim| A11["Segura ou guarda o quadro, t_ganho = agora"] --> Q11
  Q10 -->|não| Q11{"Pontas fechadas e tudo lido?"}
  Q11 -->|"sim, com chave"| A12["Salvando"] --> FIM
  Q11 -->|"sim, sem chave"| A13["Completa sem QR"] --> FIM
  Q11 -->|não| Q12{"Sem ganho há T_travada?"}
  Q12 -->|sim| A14["Travada, classifica a causa"] --> FIM
  Q12 -->|não| A15["Direção, suba, desça ou segure"] --> FIM
  FIM(["Próximo quadro"])
```
