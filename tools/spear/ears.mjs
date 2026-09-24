// Project-local adaptation of the MIT-licensed SPEAR EARS validator; see UPSTREAM-NOTICE.md.
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
// String splitting keeps malformed long conditions linear-time instead of regex backtracking.
export function isEarsClause(clause) {
  const clean = clause.replace(/\s+/g, ' ').trim();
  const response = 'THE SYSTEM SHALL ';
  if (clean.startsWith(response)) return clean.length > response.length;
  if (clean.startsWith('WHERE ')) return clean.length > 'WHERE '.length;
  const trigger = ['WHEN ', 'WHILE ', 'IF '].find(prefix => clean.startsWith(prefix));
  if (!trigger) return false;
  const remainder = clean.slice(trigger.length);
  const separator = ' ' + response;
  const split = remainder.indexOf(separator);
  if (split < 1) return false;
  const condition = remainder.slice(0, split).replace(/(?:^| )THEN$/, '').trim();
  const result = remainder.slice(split + separator.length).trim();
  return condition.length > 0 && result.length > 0;
}

function parseInlineRequirement(line) {
  const clean = line.trim();
  if (!clean.startsWith('-')) return null;
  const body = clean.slice(1).trimStart();
  const separator = body.indexOf(':');
  if (separator < 0) return null;
  const id = body.slice(0, separator).trim();
  if (!/^REQ-\d+$/.test(id)) return null;
  return {id, clause: body.slice(separator + 1).trimStart()};
}

export function validate(text, filename) {
  const errors=[]; let pending=null;
  const fail=(entry,reason)=>errors.push({id:entry.id,file:filename,line:entry.line,reason});
  const check=(entry,clause)=>{
    const clean=clause.replace(/^\*\*(Ubiquitous|Event-driven|State-driven|Unwanted|Feature)\.\*\*\s*/,'').trim();
    if(!isEarsClause(clean))
      fail(entry,'Clause does not match a supported EARS pattern or has an empty response');
  };
  text.split(/\r?\n/).forEach((line,index)=>{
    const header=line.match(/^###\s+(REQ-\d+)\b/);
    const inline=parseInlineRequirement(line);
    if(header || inline) {
      if(pending) fail(pending,'Requirement is missing its EARS clause');
      const entry={id:header ? header[1] : inline.id,line:index+1};
      if(inline) {check(entry,inline.clause); pending=null;} else pending=entry;
      return;
    }
    if(pending && line.trim() && !line.startsWith('#')) {check(pending,line.trim());pending=null;}
  });
  if(pending) fail(pending,'Requirement is missing its EARS clause');
  return {ok:errors.length===0,errors};
}
if(process.argv[1] && import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    if(!process.argv[2]) throw Error('Usage: node ears.mjs PATH');
    const result=validate(fs.readFileSync(process.argv[2],'utf8'),process.argv[2]);
    for(const e of result.errors) console.error(e.file+':'+e.line+': '+e.id+': '+e.reason);
    process.exitCode=result.ok?0:1;
  } catch(e) {console.error(e.message);process.exitCode=1;}
}
