// deno test supabase/functions/categorize/reads_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { type Ask, answerItem, sizeCandidates, soldByFromUnit } from "./reads.ts";

const sizes = (d: string) => sizeCandidates(d).map((c) => c.each == null ? `${c.count} ×` : `${c.count} × ${c.each} ${c.unit}`);

Deno.test("the unit settles weight and volume; a fractional quantity is weighed", () => {
  assertEquals(soldByFromUnit("KG", 10_000), "weight");
  assertEquals(soldByFromUnit("kg", 10_000), "weight");
  assertEquals(soldByFromUnit("LT", 20_000), "volume");
  assertEquals(soldByFromUnit("UN", 12_340), "weight");
  assertEquals(soldByFromUnit("UN", 20_000), null);
  assertEquals(soldByFromUnit("PCT", 10_000), null);
  assertEquals(soldByFromUnit(null, null), null);
});

Deno.test("sizes the parser reads", () => {
  assertEquals(sizes("CAFE 3 CORACOES 250G"), ["1 × 250000 kg"]);
  assertEquals(sizes("LEITE UHT INT 1L"), ["1 × 1000000 l"]);
  assertEquals(sizes("REFRIG COCA 1,5L"), ["1 × 1500000 l"]);
  assertEquals(sizes("AGUA MIN 12X500ML"), ["12 × 500000 l", "1 × 500000 l"]);
});

Deno.test("sizes the parser misses (status.md): 1K and a decimal comma printed as a space", () => {
  assertEquals(sizes("ARROZ TIO JOAO 1K"), ["1 × 1000000 kg"]);
  assertEquals(sizes("FEIJAO PRETO 1 25KG"), ["1 × 1250000 kg", "1 × 25000000 kg"]);
});

Deno.test("no size where there's none", () => {
  assertEquals(sizes("CAMISETA DET FRONTAL M"), []);
  assertEquals(sizes("PAO FRANCES"), []);
});

Deno.test("packs: a count alone, and a count printed apart from the amount", () => {
  assertEquals(sizes("PILHA AA C/4"), ["4 ×"]);
  assertEquals(sizes("CERV LATA 350ML C/12"), ["1 × 350000 l", "12 ×", "12 × 350000 l"]);
  assertEquals(sizes("OVOS BRANCOS CX 12"), ["12 ×"]);
  assertEquals(sizes("OVOS VERM CARMINAT C 30"), ["30 ×"]);
  assertEquals(sizes("PH F TRIPLHA DUETTO C 12"), ["12 ×"]);
  assertEquals(sizes("VITAMINA C 500MG"), ["1 × 500 kg"]);
  assertEquals(sizes("IOGURTE 6UN 170G"), ["1 × 170000 kg", "6 ×", "6 × 170000 kg"]);
});

Deno.test("a candidate says what it is and how it was written", () => {
  const [c] = sizeCandidates("BISC RECHEADO 140G");
  assertEquals(c.key, "size_0");
  assertEquals(c.text, '140 g (written "140G")');
  assertEquals(sizeCandidates("AGUA MIN 12X500ML")[0].text, 'A pack of 12 × 500 ml, 6 l in all (written "12X500ML")');
  assertEquals(sizeCandidates("PILHA AA C/4")[0].text, 'A pack of 4, with no weight or volume written (written "C/4")');
});

/** A Jev that answers each question with its first option and records what it was asked. */
function fakeJev(): { ask: Ask; asked: { state: string[]; questions: string[] }[] } {
  const asked: { state: string[]; questions: string[] }[] = [];
  const ask: Ask = (state, questions) => {
    asked.push({ state: Object.keys(state).sort(), questions: Object.keys(questions).sort() });
    return Promise.resolve(Object.fromEntries(Object.entries(questions).map(([k, q]) => [k, { choice: Object.keys(q.criteria)[0], confidence: .95 }])));
  };
  return { ask, asked };
}

const categories = [{ key: "beverages__water", text: "Water" }, { key: "food__fruit", text: "Fruit" }];

Deno.test("the category keeps D74's state; the readings get theirs, in a second request", async () => {
  const { ask, asked } = fakeJev();
  const a = await answerItem({ description: "AGUA MIN 12X500ML", unit: "PCT", qty_e4: 10_000 }, categories, "water MIN 12X500ML",
    { "open food facts name": "Água" }, "What kind?", ask);
  assertEquals(asked, [
    { state: ["item", "open food facts name"], questions: ["category"] },
    { state: ["item", "open food facts name", "quantity on the receipt", "receipt text", "unit on the receipt"], questions: ["size", "sold_by"] },
  ]);
  assertEquals(a.category?.chosen.key, "beverages__water");
  assertEquals(a.soldBy, { value: "weight", source: "jev", confidence: .95 });
  assertEquals(a.size, { e6: 6_000_000, unit: "l", count: 12, confidence: .95 });
});

Deno.test("nothing to read and no category wanted: no request", async () => {
  const { ask, asked } = fakeJev();
  const a = await answerItem({ description: "QUEIJO MUSSARELA FAT", unit: "UN", qty_e4: 4_120 }, null, "cheese", {}, "What kind?", ask);
  assertEquals(asked, []);
  assertEquals(a, { soldBy: { value: "weight", source: "parser", confidence: 1 } });
});

Deno.test("a count alone is a pack with no weight or volume", async () => {
  const { ask } = fakeJev();
  const a = await answerItem({ description: "PILHA AA C/4", unit: "UN", qty_e4: 10_000 }, null, "battery AA C/4", {}, "What kind?", ask);
  assertEquals(a.size, { e6: null, unit: null, count: 4, confidence: .95 });
});
