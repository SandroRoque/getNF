// deno test supabase/functions/contribute/rules_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { MAX_BYTES, parse } from "./rules.ts";

const install = "0b6a3c1e-8f2d-4c55-9a7e-2d1f0e9b7a61";
const item = "5f0c2b9a-1e3d-4a7b-8c6d-9e0f1a2b3c4d";
const q = (s: string) => new URLSearchParams(s);

Deno.test("a photo, a page and the meta file are taken with their own type", () => {
  assertEquals(parse(q(`install=${install}&item=${item}&file=p00.webp`), "image/webp", 1000),
    { kind: "file", install, item, name: "p00.webp", type: "image/webp" });
  assertEquals(typeof parse(q(`install=${install}&item=${item}&file=page.html`), "text/html; charset=utf-8", 10), "object");
  assertEquals(typeof parse(q(`install=${install}&item=${item}&file=meta.json`), "application/json", 10), "object");
});

Deno.test("anything else is refused", () => {
  assertEquals(parse(q(`install=x&item=${item}&file=p00.webp`), "image/webp", 1), "install must be a uuid");
  assertEquals(parse(q(`install=${install}&item=${item}&file=../p00.webp`), "image/webp", 1), "unknown file name");
  assertEquals(parse(q(`install=${install}&item=${item}&file=p00.jpg`), "image/jpeg", 1), "unknown file name");
  assertEquals(parse(q(`install=${install}&item=${item}&file=p00.webp`), "text/html", 1), "p00.webp must be image/webp");
  assertEquals(parse(q(`install=${install}&item=${item}&file=p00.webp`), "image/webp", MAX_BYTES + 1), "size out of range");
  assertEquals(parse(q(`install=${install}&item=${item}&file=p00.webp`), "image/webp", 0), "size out of range");
});

Deno.test("withdrawing needs only the install", () => {
  assertEquals(parse(q(`install=${install}&withdraw=1`), null, null), { kind: "withdraw", install });
});

