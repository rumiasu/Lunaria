import {createServer} from 'node:http'
import {readFile} from 'node:fs/promises'
import {resolve} from 'node:path'
const files={'/customer':resolve(import.meta.dirname,'../docs/evidence/customer-wireframe.html'),'/owner':resolve(import.meta.dirname,'../docs/evidence/owner-wireframe.html')}
createServer(async(req,res)=>{const file=files[req.url];if(!file){res.writeHead(404);res.end('Not found');return}res.setHeader('content-type','text/html;charset=utf-8');res.end(await readFile(file))}).listen(18082,'127.0.0.1',()=>console.log('Static WXML layout checks: http://127.0.0.1:18082/customer and /owner'))
