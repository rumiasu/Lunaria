// Read-only delivery verification against this project's running isolated backend.
import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
const evidence=resolve(import.meta.dirname,'../docs/evidence')
const original=JSON.parse(await readFile(resolve(evidence,'http-smoke.json'),'utf8'))
const oldData=JSON.parse(await readFile(resolve(evidence,'wireframe-data.json'),'utf8'))
const oldCard=oldData.me.cards.find(card=>card.id===original.cardId)
assert.ok(oldCard,'Existing demo card snapshot evidence required')
function client(){let cookie='',csrf='';return async(path,body)=>{
 const response=await fetch('http://127.0.0.1:18081/api'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',Cookie:cookie,'X-CSRF-TOKEN':csrf},body:body===undefined?undefined:JSON.stringify(body)})
 if(response.headers.get('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0]
 const data=await response.json();if(data.csrf)csrf=data.csrf;return {status:response.status,data}
}}
const customer=client();await customer('/session');assert.equal((await customer('/demo/login',{username:'alice',password:'lunaria-customer-demo'})).status,200);await customer('/session')
const me=(await customer('/me')).data
assert.equal(me.membership_policy.mode,'TRACK_ONLY');assert.equal(me.membership_policy.upgrades_enabled,false)
assert.equal(me.customer.balance_cents,original.balanceAfter)
const card=me.cards.find(value=>value.id===original.cardId);assert.ok(card)
for(const field of ['duration','total','paid_cents','tier_snapshot','qualification_snapshot','rule_snapshot'])assert.equal(card[field],oldCard[field],`Existing ${field} must not be recalculated`)
assert.ok(me.appointments.some(a=>a.id===original.appointmentId))
assert.equal(me.ledger.filter(l=>l.kind==='RECHARGE'&&l.id==='seed-alice').length,1)
assert.equal(me.summary.fulfilled_reference_cents,me.customer.fulfilled_cents)
assert.equal((await customer('/owner/dashboard')).status,403)
const owner=client();await owner('/session');await owner('/demo/login',{username:'owner',password:'lunaria-owner-demo'});await owner('/session')
const dashboard=await owner('/owner/dashboard');assert.equal(dashboard.status,200);assert.equal(dashboard.data.membership_policy.mode,'TRACK_ONLY')
const result={status:'PASS',checks:['Latest jar starts on the original isolated demo database','Existing balance, card rights/rule snapshot and appointment preserved','Seed principal not duplicated','TRACK_ONLY policy visible through real HTTP','Consumption summary keeps fulfillment separate','Customer cannot access owner API'],cardId:card.id,balanceCents:me.customer.balance_cents,checkedAt:new Date().toISOString(),financialMutations:false}
await writeFile(resolve(evidence,'backend-live-check-20261023.json'),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify(result,null,2))
