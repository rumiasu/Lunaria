import { mkdir,copyFile,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const root=resolve(import.meta.dirname,'..')
for(const role of ['customer','owner']){
  const dir=resolve(root,`miniapp-${role}`);await mkdir(resolve(dir,'utils'),{recursive:true})
  for(const file of ['api.js','format.js','page.js'])await copyFile(resolve(root,'miniapp-shared',file),resolve(dir,'utils',file))
  await copyFile(resolve(root,'miniapp-shared/tokens.wxss'),resolve(dir,'app.wxss'))
  await writeFile(resolve(dir,'project.config.json'),JSON.stringify({description:'Lunaria 独立开发骨架；美术待讨论',compileType:'miniprogram',appid:'touristappid',projectname:`Lunaria-${role}`,miniprogramRoot:'./',setting:{urlCheck:false,es6:true,minified:false}},null,2)+'\n')
}
console.log('Synced native miniapp shared files; no external dependencies.')
