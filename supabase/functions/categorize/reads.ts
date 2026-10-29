// What code reads exactly before System-1 is asked (D108): how an item is sold when its unit settles it, and every
// net size its description could be stating. Jev then answers how it's sold where the unit doesn't settle it, and picks
// one size, or none. The phone keeps the same unit table (Derive.unitKind).

export type SoldBy = "weight" | "volume" | "unit";
export type Size = { e6: number; unit: "kg" | "l" };
export type Candidate = Size & { key: string; text: string };

const WEIGHT_UNITS = new Set(["KG", "KGS", "KILO", "KILOS", "QUILO", "QUILOS"]);
const VOLUME_UNITS = new Set(["L", "LT", "LTS", "LITRO", "LITROS"]);

/** How the line's unit settles the way it's sold: KG and L spellings, or a fractional quantity (weighed). */
export function soldByFromUnit(unit: string | null, qtyE4: number | null): SoldBy | null {
  const u = (unit ?? "").trim().toUpperCase();
  if (WEIGHT_UNITS.has(u)) return "weight";
  if (VOLUME_UNITS.has(u)) return "volume";
  if (qtyE4 != null && qtyE4 % 10_000 !== 0) return "weight";
  return null;
}

// Millionths of a kg or litre per unit written.
const SIZE_UNITS: Record<string, Size["unit"] | undefined> = {};
const PER_E6: Record<string, number> = {};
for (const [names, unit, perE6] of [
  [["KG", "KGS", "K", "KILO", "KILOS"], "kg", 1_000_000],
  [["G", "GR", "GRS", "GRAMAS"], "kg", 1_000],
  [["MG"], "kg", 1],
  [["L", "LT", "LTS", "LITRO", "LITROS"], "l", 1_000_000],
  [["ML"], "l", 1_000],
  [["CL"], "l", 10_000],
] as const) for (const n of names) { SIZE_UNITS[n] = unit; PER_E6[n] = perE6; }

const UNIT = "(KGS?|KILOS?|K|GRAMAS|GRS?|G|MG|LITROS?|LTS?|L|ML|CL)";
// "12X1L", "12 X 200ML", "250G", "1,5L", "1.25KG", and "1 25KG" (a decimal comma printed as a space).
const PACK = new RegExp(`(?<![\\d.,])(\\d+)\\s?X\\s?(\\d+(?:[.,]\\d+)?)\\s?${UNIT}(?![A-Z])`, "gi");
const SPACED = new RegExp(`(?<![\\d.,])(\\d+) (\\d{1,3})\\s?${UNIT}(?![A-Z])`, "gi");
const PLAIN = new RegExp(`(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s?${UNIT}(?![A-Z])`, "gi");

/** Every net size the description could be stating, distinct, in the order found. */
export function sizeCandidates(description: string): Candidate[] {
  const found: Candidate[] = [];
  const add = (amount: number, u: string, written: string) => {
    const unit = SIZE_UNITS[u.toUpperCase()];
    if (!unit || !(amount > 0)) return;
    const e6 = Math.round(amount * PER_E6[u.toUpperCase()]);
    if (found.some((c) => c.e6 === e6 && c.unit === unit)) return;
    found.push({ key: `size_${found.length}`, e6, unit, text: `${format(e6, unit)} (written "${written.trim()}")` });
  };
  const num = (s: string) => Number(s.replace(",", "."));
  for (const m of description.matchAll(PACK)) add(Number(m[1]) * num(m[2]), m[3], m[0]);
  for (const m of description.matchAll(SPACED)) add(Number(`${m[1]}.${m[2]}`), m[3], m[0]);
  for (const m of description.matchAll(PLAIN)) add(num(m[1]), m[2], m[0]);
  return found;
}

function format(e6: number, unit: Size["unit"]): string {
  if (unit === "kg") return e6 < 1_000_000 ? `${trim(e6 / 1_000)} g` : `${trim(e6 / 1_000_000)} kg`;
  return e6 < 1_000_000 ? `${trim(e6 / 1_000)} ml` : `${trim(e6 / 1_000_000)} l`;
}

const trim = (x: number) => String(Math.round(x * 1000) / 1000);

export const SOLD_BY_CRITERIA: Record<SoldBy, string> = {
  weight: "Sold by weight: the quantity on the receipt is in kilograms (produce, meat, cheese or bread by the kilo)",
  volume: "Sold by volume: the quantity on the receipt is in litres (bulk or draft liquids)",
  unit: "Sold by the unit or package: the quantity on the receipt counts items or packs",
};
export const SOLD_BY_INSTRUCTIONS = "How is this item from a Brazilian supermarket receipt sold?";

export const NO_SIZE = "none";
export const SIZE_INSTRUCTIONS =
  "What is the net weight or volume of one unit of this item, as written in its receipt text? A pack's total counts as one unit.";
export const NO_SIZE_TEXT = "No net weight or volume is written (a number that is a count, a model, a percentage or a size of clothing)";
