// Executes original handlers. fetch is intercepted: no network is permitted by Deno.
Deno.env.set('SUPABASE_URL','http://127.0.0.1:1');
Deno.env.set('SUPABASE_SERVICE_ROLE_KEY','synthetic');
Deno.env.set('CATEGORIZE_SECRET','synthetic-secret');
let handler: (req:Request)=>Promise<Response>;
Object.defineProperty(Deno,'serve',{value:(fn:typeof handler)=>{handler=fn;return {};}, configurable:true});
let requests:string[]=[];
globalThis.fetch=async (input:RequestInfo|URL)=>{
 const url=String(input instanceof Request?input.url:input);requests.push(url);
 if(url.includes('/rpc/claim_categorize_jobs'))return Response.json([]);
 throw new Error('unexpected request: '+url);
};
function equal(a:unknown,b:unknown){if(a!==b)throw new Error(`expected ${b}, got ${a}`);}
await import('../../../supabase/functions/categorize/index.ts');
let r=await handler!(new Request('http://local',{method:'POST',body:'{}'}));equal(r.status,403);equal(requests.length,0);console.log('PASS categorize missing secret rejected before DB');
r=await handler!(new Request('http://local',{method:'POST',headers:{'x-categorize-secret':'wrong'},body:'{}'}));equal(r.status,403);equal(requests.length,0);console.log('PASS categorize incorrect secret rejected');
r=await handler!(new Request('http://local',{method:'POST',headers:{'x-categorize-secret':'synthetic-secret'},body:'{}'}));equal(r.status,200);equal((await r.json()).done,0);equal(requests.length,1);console.log('PASS categorize empty queue makes no external model request');
await import('../../../supabase/functions/contribute/index.ts');
requests=[];
for(const [method,path,type] of [['GET','', 'application/json'],['POST','?install=bad','application/json'],['POST','?install=00000000-0000-4000-8000-000000000001&item=00000000-0000-4000-8000-000000000002&file=../secret','image/webp']] ){
 r=await handler!(new Request('http://local'+path,{method,headers:{'content-type':type},...(method==='POST'?{body:'{}'}:{})}));equal(r.status,method==='GET'?405:400);
}
equal(requests.length,0);console.log('PASS contribute method/identifier/path validation before DB');
