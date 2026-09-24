// Pure task-evidence parsing shared with the fail-closed state machine.
function taskHeading(line) {
  return line.match(/^(?:#{2,4}\s+|\s*-\s+\[[ x~!]\]\s+\*\*)([A-Za-z]+-\d+)\b/);
}
function taskLines(text, taskId) {
  const result = [];
  let active = false;
  for (const line of text.split(/\r?\n/)) {
    const heading = taskHeading(line);
    if (heading) {
      if (active) break;
      active = heading[1] === taskId;
    } else if (active) result.push(line);
  }
  return result;
}
function hasEvidenceBullet(lines) {
  for (const line of lines) {
    if (/^\s*[A-Z][A-Za-z ]+:/.test(line)) return false;
    if (/^\s*-\s+\S/.test(line)) return true;
  }
  return false;
}
export function hasTaskEvidence(text, taskId) {
  const lines = taskLines(text, taskId);
  const index = lines.findIndex(line => /^\s*Evidence:/.test(line));
  if (index < 0) return false;
  const inline = lines[index].replace(/^\s*Evidence:\s*/, '');
  if (inline.replace(/[\x60\s]/g, '')) return true;
  return hasEvidenceBullet(lines.slice(index + 1));
}
