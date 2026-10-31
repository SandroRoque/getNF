# The capture loop, idealised: superseded by four rules (D149)

On 2026-10-31 the capture was drawn as an idealised state machine (an outer machine deciding the object, an inner one
assembling the receipt, a per-frame sequence of questions), redrawn per frame with a single initial state, and then
critiqued: the hints were outputs posing as states, the candidate/committed phases repeated per object, the logic
lived in the guards, and the same behaviour would replay identically in the benchmark. It wasn't implemented. What it
left is four rules, applied to the current code ([decisions.md](decisions.md), D149):

1. **R1.** A provisional attempt that has started and loses the paper for 2.5 s drops and starts afresh.
2. **R2.** An attempt is provisional until its NFC-e QR code is read inside the paper; then nothing drops without the
   person deciding.
3. **R3.** Stuck after the QR code: an offer, part worn ("Salvar assim", with its cost) or no progress ("Recomeçar").
4. **R4.** "Recomeçar" always available.

Two lessons kept for later designs: put a whole process (gathering proof, picking photos, closing the ends) in a state
of its own rather than a flag, and let each state ask only its own questions of a frame.

How the capture behaves today: [capture-fsm.md](capture-fsm.md).
