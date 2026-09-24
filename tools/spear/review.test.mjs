import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
import {spawnSync,spawn} from 'node:child_process';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {validate} from './ears.mjs';
const helper=fileURLToPath(new URL('./state.mjs',import.meta.url));
function fixture(t,state) {
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'spear-review-')); t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const file=path.join(dir,'state.json'); if(state!==undefined) fs.writeFileSync(file,JSON.stringify(state));
 const tasks=path.join(dir,'tasks.md'); fs.writeFileSync(tasks,'## T-001 [TDD] Test task\nEvidence:\n');
 const env={...process.env,SPEAR_STATE_FILE:file,SPEAR_TASKS_FILE:tasks,SPEAR_LOCK_TIMEOUT_MS:'100'};
 return {dir,file,tasks,env,run:(...args)=>spawnSync(process.execPath,[helper,...args],{env,encoding:'utf8'})};
}
test('requirements reject missing clauses at next header and EOF',()=>{
 assert.equal(validate('### REQ-001 title\n### REQ-002 title\nTHE SYSTEM SHALL work.','req').ok,false);
 assert.equal(validate('### REQ-001 title\n','req').ok,false);
});
test('inline requirements are actually validated',()=>{
 assert.equal(validate('- REQ-001: not an EARS clause','req').ok,false);
 assert.equal(validate('- REQ-001: THE SYSTEM SHALL work.','req').ok,true);
});
test('non-object and malformed state never count as idle',t=>{
 for(const value of [null,[],0,'bad']) {const f=fixture(t,value); assert.notEqual(f.run('state_assert_phase','idle').status,0);}
 const f=fixture(t,{}); fs.writeFileSync(f.file,'{broken'); assert.notEqual(f.run('state_assert_phase','idle').status,0);
});
test('an occupied shared lock prevents writes',t=>{
 const f=fixture(t,{version:1,phase:'idle'}); fs.mkdirSync(f.file+'.lock');
 assert.notEqual(f.run('state_task','T-001','REQ-001').status,0);
 assert.equal(JSON.parse(fs.readFileSync(f.file,'utf8')).currentTaskId,undefined);
});
test('evidence is required before prove or architecture',t=>{
 const f=fixture(t,{version:1,phase:'spec-done',currentTaskId:'T-001'});
 assert.notEqual(f.run('state_set_phase','prove').status,0);
 fs.writeFileSync(f.tasks,'## T-001 [TDD] Test task\nEvidence: node:fs official API\n');
 assert.equal(f.run('state_set_phase','prove').status,0);
});
test('failed atomic replacement preserves the previous bytes',t=>{
 const f=fixture(t,{version:1,phase:'idle'}); const before=fs.readFileSync(f.file,'utf8');
 const preload=path.join(f.dir,'deny-rename.mjs');
 fs.writeFileSync(preload,"import fs from 'node:fs'; fs.renameSync=()=>{throw new Error('injected rename failure')};");
 const result=spawnSync(process.execPath,['--import',pathToFileURL(preload).href,helper,'state_task','T-001','REQ-001'],{env:f.env,encoding:'utf8'});
 assert.notEqual(result.status,0); assert.equal(fs.readFileSync(f.file,'utf8'),before);
 assert.equal(fs.readdirSync(f.dir).some(n=>n.includes('.tmp-')),false);
});
test('parallel phase changes cannot both claim the same predecessor',async t=>{
 const f=fixture(t,{version:1,phase:'idle'}); f.env.SPEAR_LOCK_TIMEOUT_MS='5000';
 const run=()=>new Promise(resolve=>{ const child=spawn(process.execPath,[helper,'state_set_phase','spec'],{env:f.env,stdio:'ignore'}); child.on('exit',code=>resolve(code)); });
 const codes=await Promise.all([run(),run()]); assert.equal(codes.filter(code=>code===0).length,1);
 assert.equal(JSON.parse(fs.readFileSync(f.file,'utf8')).phase,'spec');
});
test('shell entry point delegates to the same locked state machine',()=>{
 const shell=fs.readFileSync(new URL('./hooks/lib/state.sh',import.meta.url),'utf8');
 assert.match(shell,/exec .*node|exec .*NODE/); assert.match(shell,/state[.]mjs/);
});
