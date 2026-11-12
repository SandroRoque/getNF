// What the contribute function accepts (D161), kept apart from the I/O so it can be tested.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Each name the app sends, and the one type it may have. */
export function typeOf(name: string): string | null {
  if (name === "meta.json") return "application/json";
  if (name === "page.html") return "text/html";
  if (/^p[0-9]{2}\.webp$/.test(name)) return "image/webp";
  return null;
}

export const MAX_BYTES = 2 * 1024 * 1024;
/** Per install and day: a busy tester sends a few receipts, each a handful of files. */
export const DAY_FILES = 300;
export const DAY_BYTES = 150 * 1024 * 1024;
/** All installs together, per hour: the publishable key is in every app. */
export const HOUR_FILES = 3000;

export type Request =
  | { kind: "file"; install: string; item: string; name: string; type: string }
  | { kind: "withdraw"; install: string };

/** The request from its query, or why it's refused. */
export function parse(params: URLSearchParams, contentType: string | null, length: number | null): Request | string {
  const install = params.get("install") ?? "";
  if (!UUID.test(install)) return "install must be a uuid";
  if (params.get("withdraw") === "1") return { kind: "withdraw", install };
  const item = params.get("item") ?? "";
  if (!UUID.test(item)) return "item must be a uuid";
  const name = params.get("file") ?? "";
  const type = typeOf(name);
  if (type == null) return "unknown file name";
  if ((contentType ?? "").split(";")[0].trim() !== type) return `${name} must be ${type}`;
  if (length != null && (length < 1 || length > MAX_BYTES)) return "size out of range";
  return { kind: "file", install, item, name, type };
}
