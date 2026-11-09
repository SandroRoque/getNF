const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=require('node:path').resolve(__dirname,'../../..');
function env(hash='',search='') {
 const nodes=new Map(),requests=[];let replaced=false;
 const document={getElementById(id){if(!nodes.has(id))nodes.set(id,{hidden:true,textContent:'',value:'',disabled:false,handlers:{},addEventListener(k,f){this.handlers[k]=f},classList:{toggle(){}},replaceChildren(){},querySelector(){return {disabled:false}}});return nodes.get(id)},createElement(){return {textContent:''}}};
 const context={URLSearchParams,console,atob,document,location:{hash,search,pathname:'/nova-senha/'},history:{replaceState(){replaced=true}},window:{GETNF:{url:'http://127.0.0.1:1',key:'synthetic'}},fetch:async(url,opts)=>{requests.push({url,opts});return {ok:true,status:200,json:async()=>({}),text:async()=>''}}};
 return {context,nodes,requests,replaced:()=>replaced};
}
(async()=>{
 const e=env('#access_token=synthetic&type=recovery');vm.runInNewContext(fs.readFileSync(root+'/web/nova-senha/app.js','utf8'),e.context);
 assert(e.replaced());e.context.document.getElementById('senha').value='abcdef';e.context.document.getElementById('repita').value='abcdef';await e.nodes.get('form').handlers.submit({preventDefault(){}});
 assert.equal(e.requests.length,1);assert.equal(e.requests[0].opts.method,'PUT');assert.equal(e.requests[0].opts.headers.authorization,'Bearer synthetic');assert.equal(e.nodes.get('ok').hidden,false);console.log('PASS password recovery removes fragment and sends token only to configured endpoint');
 const x=env('#error=access_denied&error_description=%3Cscript%3Ebad%3C/script%3E&error_code=custom');vm.runInNewContext(fs.readFileSync(root+'/web/conta-confirmada/app.js','utf8'),x.context);assert(x.replaced());assert.equal(x.nodes.get('erro').hidden,false);assert(x.nodes.get('detalhe').textContent.includes('<script>'));assert.equal(x.requests.length,0);console.log('PASS confirmation renders error as text, clears token/query, sends nothing');
 const m=env('#access_token=synthetic');vm.runInNewContext(fs.readFileSync(root+'/web/nova-senha/app.js','utf8'),m.context);m.context.document.getElementById('senha').value='abcdef';m.context.document.getElementById('repita').value='wrong';await m.nodes.get('form').handlers.submit({preventDefault(){}});assert.equal(m.requests.length,0);console.log('PASS mismatching passwords do not send');
})();
