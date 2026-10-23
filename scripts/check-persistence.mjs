import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
const evidence=resolve(import.meta.dirname,'../docs/evidence'),before=JSON.parse(await readFile(resolve(evidence,'http-smoke.json'),'utf8'))
let cookie='',csrf=''
async function call(path,body){const res=await fetch('http://127.0.0.1:18081/api'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',Cookie:cookie,'X-CSRF-TOKEN':csrf},body:body?JSON.stringify(body):undefined});if(res.headers.get('set-cookie'))cookie=res.headers.get('set-cookie').split(';')[0];assert.equal(res.status,200);const data=await res.json();if(data.csrf)csrf=data.csrf;return data}
await call('/session');await call('/demo/login',{username:'alice',password:'lunaria-customer-demo'});await call('/session');const me=await call('/me')
assert.equal(me.customer.balance_cents,before.balanceAfter)
assert.ok(me.cards.some(c=>c.id===before.cardId));assert.ok(me.appointments.some(a=>a.id===before.appointmentId))
assert.equal(me.ledger.filter(l=>l.kind==='RECHARGE'&&l.id==='seed-alice').length,1)
const result={status:'PASS',checks:['same card after process restart','same appointment after restart','balance preserved','seed recharge not duplicated'],cardId:before.cardId,appointmentId:before.appointmentId,balanceCents:me.customer.balance_cents,checkedAt:new Date().toISOString()}
await writeFile(resolve(evidence,'restart-persistence.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2))
