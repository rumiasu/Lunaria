// A static layout adapter for reviewing real WXML + tokens. This is not a WeChat runtime or a second product frontend.
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {createRequire} from 'node:module'
import vm from 'node:vm'
const root=resolve(import.meta.dirname,'..'),out=resolve(root,'docs/evidence')
const require=createRequire(import.meta.url),f=require('../miniapp-shared/format.js')
const fixture=JSON.parse(await readFile(resolve(out,'wireframe-data.json'),'utf8'))
function parse(text){const root={children:[]},stack=[root];for(const token of text.match(/<[^>]+>|[^<]+/g)||[]){
 if(token.startsWith('</')){stack.pop();continue}if(token.startsWith('<!--'))continue
 if(token.startsWith('<')){const name=token.match(/^<([\w-]+)/)?.[1];if(!name)continue;const attrs={};for(const [,key,value]of token.matchAll(/([\w:-]+)="([^"]*)"/g))attrs[key]=value;const node={name,attrs,children:[]};stack.at(-1).children.push(node);if(!token.endsWith('/>'))stack.push(node)}else stack.at(-1).children.push({text:token})
 }return root.children}
const escape=value=>String(value??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;')
function evaluate(expression,ctx){try{return vm.runInNewContext(expression,ctx,{timeout:1000})}catch(error){throw new Error(`WXML expression ${expression}: ${error.message}`)}}
function template(value,ctx){return value.replace(/\{\{([\s\S]*?)\}\}/g,(_,expression)=>escape(evaluate(expression,ctx)))}
function render(node,ctx){if(node.text!==undefined)return template(node.text,ctx);const a=node.attrs
 if(a['wx:for']){const list=evaluate(a['wx:for'].slice(2,-2),ctx)||[];return Array.from(list).map((item,index)=>render({...node,attrs:{...a,'wx:for':null}},{...ctx,item,index})).join('')}
 if(a['wx:if']&&!evaluate(a['wx:if'].slice(2,-2),ctx))return ''
 const tag={view:'div',text:'span',picker:'div',switch:'input'}[node.name]||node.name
 const attrs=Object.entries(a).filter(([k])=>['class','placeholder','type','disabled','value'].includes(k)).map(([k,v])=>{const value=template(v,ctx);return k==='disabled'?(value==='true'?' disabled':''):` ${k}="${value}"`}).join('')
 return `<${tag}${attrs}>${node.children.map(child=>render(child,ctx)).join('')}</${tag}>`
}
const css=(await readFile(resolve(root,'miniapp-shared/tokens.wxss'),'utf8')).replace(/(^|\n)page\{/g,'$1.phone{').replace(/([\d.]+)rpx/g,'$1rem')
const me=fixture.me,catalog=fixture.catalog
const customer={ready:true,loading:false,busy:false,error:'',name:me.customer.name,balance:f.money(me.customer.balance_cents),packages:catalog.packages.map(f.decorate),services:catalog.services}
const appointments=fixture.dashboard.appointments.filter(a=>['PENDING','CONFIRMED'].includes(a.status)).map(f.decorate)
const owner={ready:true,loading:false,busy:false,error:'',today:f.today(),appointments,count:appointments.filter(a=>a.timeText.startsWith(f.today())).length}
for(const [name,route,data]of [['customer','miniapp-customer/pages/home/index.wxml',customer],['owner','miniapp-owner/pages/today/index.wxml',owner]]){
 const wxml=await readFile(resolve(root,route),'utf8');const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lunaria ${name} WXML 布局检查</title><style>html{font-size:calc(min(100vw,390px)/750)}body{margin:0;background:#dfe3de;font:16px system-ui}*{box-sizing:border-box}.phone{max-width:390px;margin:0 auto;min-height:100vh}button{width:100%;border:0;font-family:inherit;cursor:default}input,textarea{font:inherit}text{display:inline} .qa{background:#252f2b;color:white;font-size:12px;padding:10px;text-align:center}${css}</style><body><main class="phone"><aside class="qa">WXML 静态布局检查 · 非微信工具验收 · 美术未定稿</aside>${parse(wxml).map(node=>render(node,data)).join('')}</main></body></html>`
 await writeFile(resolve(out,`${name}-wireframe.html`),html)
}
console.log('Rendered 2 representative screens from real WXML, shared tokens and HTTP demo data.')
