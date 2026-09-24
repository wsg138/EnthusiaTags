// Project-local locked state machine. Bash delegates here; retain UPSTREAM-NOTICE.md.
import fs from 'node:fs'; import path from 'node:path'; import {randomUUID} from 'node:crypto';
import {hasTaskEvidence} from './evidence.mjs';
const file=path.resolve(process.env.SPEAR_STATE_FILE || '.claude/spear-state.json');
const transitions={idle:['spec'],spec:['spec-done'],'spec-done':['prove','arch'],prove:['prove-done'],'prove-done':['engine'],engine:['engine-done'],'engine-done':['arch'],arch:['arch-done'],'arch-done':['refine'],refine:['idle']};
const [operation,...args]=process.argv.slice(2);
const lock=file+'.lock'; let locked=false;
function load() {
  if(!fs.existsSync(file)) return {version:1,phase:'idle'};
  let state; try {state=JSON.parse(fs.readFileSync(file,'utf8'));} catch(e) {throw Error('Malformed SPEAR state: '+e.message);}
  if(!state || Array.isArray(state) || typeof state!=='object' || !Object.hasOwn(transitions,state.phase)) throw Error('SPEAR state must be an object with a valid phase');
  return state;
}
function acquire() {
  fs.mkdirSync(path.dirname(file),{recursive:true});
  const timeout=Number(process.env.SPEAR_LOCK_TIMEOUT_MS || 5000);
  if(!Number.isFinite(timeout) || timeout<0 || timeout>60000) throw Error('Invalid SPEAR lock timeout');
  const deadline=Date.now()+timeout;
  while(!locked) {
    try {fs.mkdirSync(lock);locked=true;return;} catch(e) {if(e.code!=='EEXIST') throw e;}
    if(Date.now()>=deadline) throw Error('SPEAR state is locked; stop the owning process before recovering a stale lock: '+lock);
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,25);
  }
}
function save(state) {
  const next={...state,lastUpdated:new Date().toISOString()};
  const temp=file+'.tmp-'+process.pid+'-'+randomUUID();
  try {
    const fd=fs.openSync(temp,'wx');
    try {fs.writeFileSync(fd,JSON.stringify(next,null,2)+'\n');fs.fsyncSync(fd);} finally {fs.closeSync(fd);}
    fs.renameSync(temp,file);
  } finally {fs.rmSync(temp,{force:true});}
  try {fs.appendFileSync(path.join(path.dirname(file),'spear-history.jsonl'),JSON.stringify(next)+'\n');}
  catch(e) {console.error('State saved, but history append failed: '+e.message);}
}
function requireEvidence(state) {
  if(!state.currentTaskId) throw Error('Select a task before leaving spec-done');
  const text=fs.readFileSync(process.env.SPEAR_TASKS_FILE || 'docs/tasks.md','utf8');
  if(!hasTaskEvidence(text,state.currentTaskId))
    throw Error('Task '+state.currentTaskId+' requires non-empty Evidence before prove/arch');
}

try {
  acquire(); const state=load();
  switch(operation) {
    case 'state_assert_phase':
      if(!args.length || !args.includes(state.phase)) throw Error('spear requires phase='+args.join('|')+'; current phase='+state.phase);
      break;
    case 'state_set_phase':
      if(!(transitions[state.phase] || []).includes(args[0])) throw Error('Invalid SPEAR transition: '+state.phase+' -> '+args[0]);
      if(state.phase==='spec-done') requireEvidence(state);
      if(args[0]==='prove-done' && state.testStatus!=='red') throw Error('A recorded red test is required');
      if(args[0]==='engine-done' && state.testStatus!=='green') throw Error('A recorded green test is required');
      save({...state,phase:args[0]}); break;
    case 'state_record_test':
      if(!['prove','engine'].includes(state.phase)) throw Error('Tests can only be recorded in prove/engine');
      if(args.length!==3 || !args[0].trim() || !args[1].trim() || !['red','green'].includes(args[2])) throw Error('Record a test file, name, and red|green status');
      save({...state,testFile:args[0],testName:args[1],testStatus:args[2]}); break;
    case 'state_task':
      if(state.phase!=='idle') throw Error('Select a task only while idle');
      if(args.length!==2 || !args[0].trim() || !args[1].trim()) throw Error('Task ID and requirement ID are required');
      save({version:1,phase:'idle',currentTaskId:args[0],reqId:args[1]}); break;
    case 'state_clear':
      if(state.phase!=='refine') throw Error('Clear only after refine');
      save({version:1,phase:'idle'}); break;
    default: throw Error('Unknown state operation: '+operation);
  }
} catch(e) {console.error(e.message);process.exitCode=1;}
finally {if(locked) fs.rmdirSync(lock);}
