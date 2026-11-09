// Runs the ORIGINAL contribute function on 127.0.0.1:55338. SUPABASE_URL must point at the local delay proxy.
const serve = Deno.serve;
Deno.serve = ((handler: Deno.ServeHandler) => serve({ hostname: '127.0.0.1', port: 55338 }, handler)) as typeof Deno.serve;
await import('../../../supabase/functions/contribute/index.ts');
