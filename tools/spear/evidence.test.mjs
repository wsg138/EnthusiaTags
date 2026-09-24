import {test} from 'node:test';
import assert from 'node:assert/strict';
import {isEarsClause, validate} from './ears.mjs';
import {hasTaskEvidence} from './evidence.mjs';

test('supported EARS conditions retain their required response', () => {
  for (const clause of ['THE SYSTEM SHALL work.', 'WHEN ready THE SYSTEM SHALL work.',
    'WHILE ready THE SYSTEM SHALL work.', 'IF broken THEN THE SYSTEM SHALL stop.',
    'IF broken THE SYSTEM SHALL stop.', 'WHERE optional']) assert.equal(isEarsClause(clause), true);
  for (const clause of ['THE SYSTEM SHALL', 'WHEN THE SYSTEM SHALL work.',
    'WHEN ready THE SYSTEM SHALL ', 'IF THEN THE SYSTEM SHALL stop.', 'WHEN ready', 'WHERE'])
    assert.equal(isEarsClause(clause), false);
});
test('very long missing-response clauses do not trigger regex backtracking', () => {
  assert.equal(isEarsClause('WHEN ' + 'word '.repeat(100000)), false);
  assert.equal(validate('- REQ-001: WHEN ' + 'word '.repeat(100000), 'long').ok, false);
});
test('evidence must belong to the selected task rather than a later task', () => {
  const text = '## T-001 [TDD] First\nEvidence: \n## T-002 [TDD] Next\nEvidence: provider source\n';
  assert.equal(hasTaskEvidence(text, 'T-001'), false);
  assert.equal(hasTaskEvidence(text, 'T-002'), true);
  assert.equal(hasTaskEvidence(text, 'T-999'), false);
});
test('blank inline evidence and unrelated metadata cannot satisfy the gate', () => {
  const header = '- [~] **INFRA-003** - Verify\n';
  assert.equal(hasTaskEvidence(header + '  Evidence: \x60 \x60\n  Acceptance:\n  - a goal\n', 'INFRA-003'), false);
  assert.equal(hasTaskEvidence(header + '  Evidence:\n  - inspected source\n', 'INFRA-003'), true);
  assert.equal(hasTaskEvidence(header + '  Evidence: inspected source\n', 'INFRA-003'), true);
});
