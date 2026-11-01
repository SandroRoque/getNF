# The capture loop as a state machine (as of D150)

How the camera behaves today, from `ui/scan/CameraMode.kt` (`CameraModeDecider`), `ui/scan/IngestSession.kt` (the
hints and completion) and `ui/scan/LiveCapture.kt` (QR, saving, exit). The notes mark the gaps discussed on
2026-10-31: an attempt is never dropped by itself (except for a product), and a worn receipt never completes.

```mermaid
stateDiagram-v2
  direction TB
  [*] --> Nota: Escanear
  [*] --> Produto: Catálogo › Pelo código de barras
  state "Captura de nota" as Nota {
    [*] --> Procurando
    Procurando: Procurando o papel<br/>Aponte para a nota ou um código
    Procurando --> Montando: papel encontrado
    Montando --> Procurando: papel some, a montagem fica
    Montando --> Procurando: antes do QR, sem papel por 2,5 s, descarta (D149)
    state "Montando a nota" as Montando {
      [*] --> Guiando
      Guiando: Guiando<br/>Suba · Desça · Segure firme
      Aproximar: Aproxime · Segure firme
      Voltar: Volte ao trecho já lido
      Guiando --> Aproximar: menos da metade legível
      Aproximar --> Guiando: legível de novo
      Guiando --> Voltar: quadro não se encaixa
      Voltar --> Guiando: encaixou
    }
    note right of Montando
      Depois do QR, sem saída automática:
      travada, Recomeçar (D150) ou sair (R3 a fazer).
      Nota apagada fica aqui para sempre.
    end note
    FaltaQR: Montagem completa<br/>Mostre o QR Code
    Montando --> FaltaQR: completa, QR não lido
    Montando --> Salvando: completa e QR lido
    FaltaQR --> Salvando: QR lido dentro do papel
    Salvando: Salvando<br/>só as fotos necessárias
  }
  note left of Nota
    QR da NFC-e lido trava o modo nota,
    códigos de produto passam a ser ignorados.
    Outra nota no papel só avisa.
  end note
  state "Produto" as Produto {
    [*] --> ProcurandoCodigo
    ProcurandoCodigo: Aponte para o código de barras<br/>sem OCR, quadros mais rápidos
    Folha: Folha do produto<br/>preços nas notas
    ProcurandoCodigo --> Folha: código em 3 de 8 quadros
    Folha --> ProcurandoCodigo: outro código ou fechar
  }
  Nota --> Produto: antes do QR, código no centro, descarta a tentativa
  Produto --> Nota: 2,5 s sem código, recomeça do zero
  Salvando --> Salva
  Salva: Nota salva<br/>ou já estava na caderneta
  Salva --> [*]
  Nota --> Saida: Voltar
  Saida: QR já lido? pergunta
  Saida --> [*]: guardar só o código ou descartar
  Produto --> [*]: Voltar
```

Details the diagram leaves out:

- **Complete** means both paper ends seen, every row that must be captured read in a kept photo (or shown, for rows
  not to be read), and no unread print between required rows (`IngestSession.required`, `completeWith`).
- **Legible** (per frame): row confidence ≥ 0.5, weakest box ≥ 0.6, median of the 3 rows either side ≥ 0.6, at least
  20 px tall, not cut by the frame. A row 3 frames couldn't read while reading its neighbours is taken for a logo and
  no longer required.
- **Over every state:** the torch is on from the start (D117); "Outra nota à vista" when another receipt's QR is
  inside the paper; leaving the app keeps the code (`close()` → keep code); the product camera opened from the Catálogo
  never takes a receipt.
