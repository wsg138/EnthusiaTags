// Compatibility entry point. Keep a single validated implementation.
export {validate} from '../../ears.mjs';
import {validate} from '../../ears.mjs';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
if(process.argv[1] && import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    if(!process.argv[2]) throw Error('Usage: node ears.mjs PATH');
    const result=validate(fs.readFileSync(process.argv[2],'utf8'),process.argv[2]);
    for(const e of result.errors) console.error(e.file+':'+e.line+': '+e.id+': '+e.reason);
    process.exitCode=result.ok?0:1;
  } catch(e) {console.error(e.message);process.exitCode=1;}
}
