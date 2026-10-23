const assert=require('node:assert/strict');const {readFileSync}=require('node:fs');const path=require('node:path');const vm=require('node:vm')
const root=path.resolve(__dirname,'..');let pageCount=0,handlerCount=0
for(const role of ['customer','owner']){
 const base=path.join(root,`miniapp-${role}`),config=JSON.parse(readFileSync(path.join(base,'app.json'),'utf8'))
 for(const route of config.pages){
  let definition;global.Page=d=>{definition=d};global.wx={};require(path.join(base,route+'.js'))
  const source=readFileSync(path.join(base,route+'.js'),'utf8');new vm.Script(source)
  const wxml=readFileSync(path.join(base,route+'.wxml'),'utf8');assert.ok(wxml.includes('loading'));assert.ok(wxml.includes('error'))
  for(const [,directive]of wxml.matchAll(/wx:(?:if|for)="([^"]*)"/g))assert.ok(directive.startsWith('{{')&&directive.endsWith('}}'),`${route} must bind WXML conditions and lists`)
  for(const [,handler]of wxml.matchAll(/(?:bind|catch)(?:tap|input|change|submit)="([A-Za-z][\w]*)"/g)){assert.equal(typeof definition[handler],'function',`${role}/${route} missing ${handler}`);handlerCount++}
  assert.ok(config.pages.includes(route));pageCount++
 }
 for(const item of config.tabBar.list)assert.ok(config.pages.includes(item.pagePath))
}
const f=require(path.join(root,'miniapp-shared/format'))
assert.equal(f.cents('95.01'),9501);assert.equal(f.cents('0.01'),1);assert.throws(()=>f.cents('1.001'));assert.throws(()=>f.cents('-1'));assert.equal(f.money(5700),'57.00')
async function networkIntent(){
 const store=new Map();let calls=[];let fails=true
 global.wx={getStorageSync:k=>store.get(k),setStorageSync:(k,v)=>store.set(k,v),removeStorageSync:k=>store.delete(k),request:opts=>{calls.push(opts);if(fails){fails=false;opts.fail()}else opts.success({statusCode:200,header:{},data:{id:'same-card'}})}}
 const api=require(path.join(root,'miniapp-shared/api'))
 await assert.rejects(()=>api.mutate('/me/purchases',{packageId:'facial5'}))
 await api.mutate('/me/purchases',{packageId:'facial5'})
 assert.equal(calls[0].header['Idempotency-Key'],calls[1].header['Idempotency-Key']);assert.equal(store.size,0)
 console.log(JSON.stringify({status:'PASS',pages:pageCount,handlers:handlerCount,checks:['routes','handlers','loading/error states','cent precision','same intent after lost response']},null,2))
}
networkIntent().catch(e=>{console.error(e);process.exitCode=1})
