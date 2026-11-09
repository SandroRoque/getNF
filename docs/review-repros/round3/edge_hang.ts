// Original categorize handler against the DISPOSABLE local stack; every non-local fetch (Jev, Open Food Facts) hangs
// like a stalled provider. Prints whether the handler finished within the window. No external network is reachable.
const base='http://127.0.0.1:55321';
if(Deno.env.get('SUPABASE_URL')!==base) throw new Error('refusing: not the disposable stack');
Deno.env.set('CATEGORIZE_SECRET','synthetic-secret');
const realFetch=globalThis.fetch.bind(globalThis);
let hung:string[]=[];
globalThis.fetch=((input:RequestInfo|URL,init?:RequestInit)=>{
  const url=String(input instanceof Request?input.url:input);
  if(url.startsWith(base)) return realFetch(input,init);
  hung.push(new URL(url).host); return new Promise(()=>{}); // never settles, no timeout/abort honoured
}) as typeof fetch;
let handler:(r:Request)=>Promise<Response>;
Object.defineProperty(Deno,'serve',{value:(fn:typeof handler)=>{handler=fn;return {};},configurable:true});
await import('../../../supabase/functions/categorize/index.ts');
const windowMs=Number(Deno.args[0]??8000);
const t=Date.now();
const r=await Promise.race([handler!(new Request('http://local',{method:'POST',headers:{'x-categorize-secret':'synthetic-secret'},body:'{}'})).then(x=>'finished '+x.status),
  new Promise<string>(res=>setTimeout(()=>res('STILL RUNNING after '+windowMs+' ms'),windowMs))]);
console.log('handler:',r,'| elapsed',Date.now()-t,'ms | outbound calls that hung:',hung.length,[...new Set(hung)].join(','));
Deno.exit(0);
