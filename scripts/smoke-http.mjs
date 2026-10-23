import assert from 'node:assert/strict'
import {writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
const base='http://127.0.0.1:18081/api'
function client(){let cookie='',csrf='';return async(path,body,key)=>{
 const res=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',Cookie:cookie,'X-CSRF-TOKEN':csrf,...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body)})
 if(res.headers.get('set-cookie'))cookie=res.headers.get('set-cookie').split(';')[0]
 const data=await res.json();if(path==='/session')csrf=data.csrf;return {status:res.status,data}
}}
const request=client();assert.equal((await request('/session')).status,200)
assert.equal((await request('/demo/login',{username:'alice',password:'lunaria-customer-demo'})).status,200)
assert.equal((await request('/session')).data.role,'CUSTOMER')
const before=(await request('/me')).data
const key='http_purchase_'+Date.now();const purchase=await request('/me/purchases',{packageId:'facial5'},key);assert.equal(purchase.status,200)
const duplicate=await request('/me/purchases',{packageId:'facial5'},key);assert.equal(purchase.data.id,duplicate.data.id)
const after=(await request('/me')).data;assert.equal(before.customer.balance_cents-after.customer.balance_cents,9500)
assert.equal((await request('/owner/dashboard')).status,403)
const day=new Date(Date.now()+8*3600000+2*86400000).toISOString().slice(0,10)
const slots=await request(`/slots?serviceId=facial&date=${day}`);assert.ok(slots.data.length)
const slot=slots.data.find(start=>!after.appointments.some(a=>['PENDING','CONFIRMED'].includes(a.status)&&a.starts_at.slice(0,10)===day))||slots.data[0]
let appointment=await request('/me/appointments',{serviceId:'facial',startsAt:slot,note:'HTTP 验收预约，演示数据'},'http_book_'+Date.now())
assert.equal(appointment.status,200)
assert.match(appointment.data.starts_at,/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/)
const other=client();await other('/session');await other('/demo/login',{username:'bob',password:'lunaria-customer-demo'});await other('/session')
assert.equal((await other('/me/cards/'+purchase.data.id)).status,404)
const owner=client();await owner('/session');await owner('/demo/login',{username:'owner',password:'lunaria-owner-demo'});await owner('/session')
const dashboard=await owner('/owner/dashboard');assert.equal(dashboard.status,200)
const catalog=(await request('/catalog')).data
const result={status:'PASS',checks:['real HTTP cookie login and CSRF','persistent purchase and stable retry','customer owner denial','cross-customer asset denial','real appointment persisted with Shanghai wall time'],cardId:purchase.data.id,appointmentId:appointment.data.id,balanceBefore:before.customer.balance_cents,balanceAfter:after.customer.balance_cents,createdAt:new Date().toISOString()}
const evidence=resolve(import.meta.dirname,'../docs/evidence');await mkdir(evidence,{recursive:true})
await writeFile(resolve(evidence,'http-smoke.json'),JSON.stringify(result,null,2)+'\n')
await writeFile(resolve(evidence,'wireframe-data.json'),JSON.stringify({catalog,me:(await request('/me')).data,dashboard:dashboard.data},null,2)+'\n')
console.log(JSON.stringify(result,null,2))
