// What code reads exactly before System-1 is asked (D108): how an item is sold when its unit settles it, and every
// net size its description could be stating. Jev then answers how it's sold where the unit doesn't settle it, and picks
// one size, or none. The phone keeps the same unit table (Derive.unitKind).

export type SoldBy = "weight" | "volume" | "unit";
export type Size = { e6: number; unit: "kg" | "l" };
/** One way to read the description: [count] units of [each] (millionths of a kg or litre; null when none is written). */
export type Candidate = { key: string; count: number; each: number | null; unit: Size["unit"] | null; text: string };

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
// A count with no amount: "C/4", "C/ 12", "C 30" (not "C 500MG"), "CX12", "CX 6", "6UN", "12 UNID".
const COUNT =
  /(?:\bC\/?\s?(\d+)(?![\d.,]|\s?(?:KGS?|GRS?|G|MG|ML|LTS?|L)\b)|\bCX\s?(\d+)|(?<![\d.,])(\d+)\s?(?:UN|UND|UNID|UNIDADES)\b)/gi;

/**
 * Every way the description could state what one unit of the item is: an amount ("250G"), a pack of amounts
 * ("12X500ML"), or a pack of a count alone ("C/4"); a count printed apart from an amount ("500ML C/12") also gives the
 * pack of both. Distinct, in the order found.
 */
export function sizeCandidates(description: string): Candidate[] {
  const found: Candidate[] = [];
  const add = (count: number, each: number | null, unit: Size["unit"] | null, written: string) => {
    if (!(count >= 1) || (each != null && !(each > 0))) return;
    if (count === 1 && each == null) return;
    if (found.some((c) => c.count === count && c.each === each && c.unit === unit)) return;
    found.push({ key: `size_${found.length}`, count, each, unit, text: describe(count, each, unit, written.trim()) });
  };
  const amount = (n: number, u: string): [number, Size["unit"]] | null => {
    const unit = SIZE_UNITS[u.toUpperCase()];
    return unit ? [Math.round(n * PER_E6[u.toUpperCase()]), unit] : null;
  };
  const num = (s: string) => Number(s.replace(",", "."));
  const plain: [number, Size["unit"], string][] = [];
  for (const m of description.matchAll(PACK)) {
    const a = amount(num(m[2]), m[3]);
    if (a) add(Number(m[1]), a[0], a[1], m[0]);
  }
  for (const m of description.matchAll(SPACED)) {
    const a = amount(Number(`${m[1]}.${m[2]}`), m[3]);
    if (a) { add(1, a[0], a[1], m[0]); plain.push([a[0], a[1], m[0]]); }
  }
  for (const m of description.matchAll(PLAIN)) {
    const a = amount(num(m[1]), m[2]);
    if (a) { add(1, a[0], a[1], m[0]); plain.push([a[0], a[1], m[0]]); }
  }
  for (const m of description.matchAll(COUNT)) {
    const n = Number(m[1] ?? m[2] ?? m[3]);
    add(n, null, null, m[0]);
    for (const [each, unit, written] of plain) add(n, each, unit, `${written} ${m[0].trim()}`);
  }
  return found;
}

function describe(count: number, each: number | null, unit: Size["unit"] | null, written: string): string {
  const w = `(written "${written}")`;
  if (each == null || unit == null) return `A pack of ${count}, with no weight or volume written ${w}`;
  if (count === 1) return `${format(each, unit)} ${w}`;
  return `A pack of ${count} × ${format(each, unit)}, ${format(each * count, unit)} in all ${w}`;
}

function format(e6: number, unit: Size["unit"]): string {
  if (unit === "kg") return e6 < 1_000_000 ? `${trim(e6 / 1_000)} g` : `${trim(e6 / 1_000_000)} kg`;
  return e6 < 1_000_000 ? `${trim(e6 / 1_000)} ml` : `${trim(e6 / 1_000_000)} l`;
}

const trim = (x: number) => String(Math.round(x * 1000) / 1000);

export const SOLD_BY_CRITERIA: Record<SoldBy, string> = {
  weight: "Sold by weight: the quantity on the receipt is in kilograms",
  volume: "Sold by volume: the quantity on the receipt is in litres",
  unit: "Sold by the unit or package: the quantity on the receipt counts items or packs",
};
export const SOLD_BY_INSTRUCTIONS = "How is this item from a Brazilian supermarket receipt sold?";

export const NO_SIZE = "none";
export const SIZE_INSTRUCTIONS =
  "What is one unit of this item, as sold on this receipt line and as written in its receipt text: a net weight or volume, a pack of several, or neither?";
export const NO_SIZE_TEXT =
  "Neither: no net weight, volume or pack count is written (a number that is a model, a percentage or a size of clothing)";

type Question = { type: "choice"; instructions: string; criteria: Record<string, string> };

/**
 * The questions one item needs. `categories` (key → description) is null when its product needs no category. The way
 * it's sold is asked only when its unit doesn't settle it (`settled`); its size only when it isn't sold by weight or
 * volume and its description has a candidate.
 */
export function buildQuestions(
  item: { description: string; unit?: string | null; qty_e4?: number | null },
  categories: Record<string, string> | null, categoryInstructions: string,
): { questions: Record<string, Question>; settled: SoldBy | null; candidates: Candidate[] } {
  const questions: Record<string, Question> = {};
  if (categories) questions.category = { type: "choice", instructions: categoryInstructions, criteria: categories };
  const settled = soldByFromUnit(item.unit ?? null, item.qty_e4 ?? null);
  if (!settled) questions.sold_by = { type: "choice", instructions: SOLD_BY_INSTRUCTIONS, criteria: SOLD_BY_CRITERIA };
  // Sold by weight or volume, the line's quantity is already the measure; a size in the name doesn't price it.
  const candidates = settled === "weight" || settled === "volume" ? [] : sizeCandidates(item.description);
  if (candidates.length) {
    questions.size = {
      type: "choice", instructions: SIZE_INSTRUCTIONS,
      criteria: { ...Object.fromEntries(candidates.map((c) => [c.key, c.text])), [NO_SIZE]: NO_SIZE_TEXT },
    };
  }
  return { questions, settled, candidates };
}

/** What every question sees: the description translated by the lexicon and as printed, the unit and the quantity. */
export function buildState(
  item: { description: string; unit?: string | null; qty_e4?: number | null }, translated: string,
): Record<string, string> {
  const state: Record<string, string> = { item: translated, "receipt text": item.description };
  if (item.unit) state["unit on the receipt"] = item.unit;
  if (item.qty_e4 != null) state["quantity on the receipt"] = String(item.qty_e4 / 10_000);
  return state;
}

export type JevAnswer = { choice: string; confidence: number };
export type Ask = (state: Record<string, string>, questions: Record<string, Question>) => Promise<Record<string, JevAnswer>>;
export type Answers<O> = {
  category?: { chosen: O; confidence: number };
  soldBy?: { value: SoldBy; source: "parser" | "jev"; confidence: number };
  /** [e6]: the whole unit's weight or volume (count × each); [count]: units in a pack, 1 for a single one. Both null: none. */
  size?: { e6: number | null; unit: Size["unit"] | null; count: number | null; confidence: number };
};

type Readable = { description: string; unit?: string | null; qty_e4?: number | null };

/** Whether the item has anything for Jev to read (how it's sold, or a size candidate). */
export function needsReading(item: Readable): boolean {
  return Object.keys(buildQuestions(item, null, "").questions).length > 0;
}

/**
 * Two requests at most, side by side (D108): the category, only when `choices` is given (its product has none), on
 * exactly the state measured in D74 (the translated description and Open Food Facts' fields, `off`); and the item's
 * readings (how it's sold, its size) on their own state, which adds the printed text, unit and quantity. Sharing one
 * state moved two categories to confident mistakes in the 82-item check.
 */
export async function answerItem<O extends { key: string; text: string }>(
  item: Readable, choices: O[] | null, translated: string, off: Record<string, string>, categoryInstructions: string, ask: Ask,
): Promise<Answers<O>> {
  const out: Answers<O> = {};
  const { questions, settled, candidates } = buildQuestions(item, null, categoryInstructions);
  if (settled) out.soldBy = { value: settled, source: "parser", confidence: 1 };
  const [c, r] = await Promise.all([
    choices
      ? ask({ item: translated, ...off }, {
        category: { type: "choice", instructions: categoryInstructions, criteria: Object.fromEntries(choices.map((o) => [o.key, o.text])) },
      })
      : {},
    Object.keys(questions).length ? ask({ ...buildState(item, translated), ...off }, questions) : {},
  ]);
  const a: Record<string, JevAnswer> = { ...c, ...r };

  if (a.category) {
    const chosen = choices!.find((o) => o.key === a.category.choice);
    if (!chosen) throw new Error(`Jev chose an unknown category: ${a.category.choice}`);
    out.category = { chosen, confidence: a.category.confidence };
  }
  if (a.sold_by) {
    if (!(a.sold_by.choice in SOLD_BY_CRITERIA)) throw new Error(`Jev chose an unknown way of selling: ${a.sold_by.choice}`);
    out.soldBy = { value: a.sold_by.choice as SoldBy, source: "jev", confidence: a.sold_by.confidence };
  }
  if (a.size) {
    const k = candidates.find((x) => x.key === a.size.choice);
    if (!k && a.size.choice !== NO_SIZE) throw new Error(`Jev chose an unknown size: ${a.size.choice}`);
    out.size = { e6: k?.each != null ? k.each * k.count : null, unit: k?.unit ?? null, count: k?.count ?? null, confidence: a.size.confidence };
  }
  return out;
}
