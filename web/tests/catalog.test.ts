/**
 * The skill catalog (counts in READMEs / CLAUDE.md / site strings, web pages, README rows,
 * the CLAUDE.md index, both registry copies) must match Skills/skills-registry.yml.
 * Counts are fixed with `node scripts/sync-catalog.mjs`; everything else it reports needs an edit.
 *
 * Must run with cwd = web/ (npm test does this).
 */
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const SCRIPT = path.resolve(process.cwd(), '..', 'scripts', 'sync-catalog.mjs');

describe('skill catalog', () => {
  it('matches the registry (sync-catalog --check)', () => {
    const result = spawnSync(process.execPath, [SCRIPT, '--check'], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr || result.stdout);
  });
});
