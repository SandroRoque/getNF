// deno test supabase/functions/categorize/reads_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { sizeCandidates, soldByFromUnit } from "./reads.ts";

const sizes = (d: string) => sizeCandidates(d).map((c) => `${c.e6} ${c.unit}`);

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
  assertEquals(sizes("CAFE 3 CORACOES 250G"), ["250000 kg"]);
  assertEquals(sizes("LEITE UHT INT 1L"), ["1000000 l"]);
  assertEquals(sizes("REFRIG COCA 1,5L"), ["1500000 l"]);
  assertEquals(sizes("AGUA MIN 12X500ML"), ["6000000 l", "500000 l"]);
});

Deno.test("sizes the parser misses (status.md): 1K and a decimal comma printed as a space", () => {
  assertEquals(sizes("ARROZ TIO JOAO 1K"), ["1000000 kg"]);
  assertEquals(sizes("FEIJAO PRETO 1 25KG"), ["1250000 kg", "25000000 kg"]);
});

Deno.test("no size where there's none", () => {
  assertEquals(sizes("CAMISETA DET FRONTAL M"), []);
  assertEquals(sizes("PAO FRANCES"), []);
  assertEquals(sizes("PILHA AA C/4"), []);
});

Deno.test("a candidate keeps how it was written", () => {
  const [c] = sizeCandidates("BISC RECHEADO 140G");
  assertEquals(c.key, "size_0");
  assertEquals(c.text, '140 g (written "140G")');
});
