#!/usr/bin/env node
/**
 * Keeps the skill catalog consistent with Skills/skills-registry.yml.
 *
 *   node scripts/sync-catalog.mjs           # rewrite skill counts and the registry version where they appear
 *   node scripts/sync-catalog.mjs --check   # report anything out of sync (exit 1); used by web/tests/catalog.test.ts
 *
 * Rewritten (counts and version only): READMEs, CLAUDE.md, the site's locale strings and docs page.
 * Checked only (needs a human or agent, e.g. translations): skill folders, both registry copies,
 * web pages and their frontmatter, README catalog rows, the CLAUDE.md index and the paper-api PLATFORM list.
 */
import fs from 'node:fs';
import path from 'node:path';
import matter from '../web/node_modules/gray-matter/index.js';
import { REPO_ROOT, REGISTRY_PATH, ALL_MC_VERSIONS, readRegistry } from './lib/registry.mjs';

const CHECK = process.argv.includes('--check');
const registry = readRegistry();
const counts = {
  total: registry.skills.length,
  nms: registry.skills.filter((s) => s.platform === 'paper-nms').length,
  paper: registry.skills.filter((s) => s.platform === 'paper-api').length,
};
const README_FILES = fs.readdirSync(REPO_ROOT).filter((f) => /^README(\.[\w-]+)?\.md$/.test(f)).sort();

/** A standalone integer: not part of a version such as 1.21.11 or 26.3. */
const INT = /(?<![\d.])\d+(?![\d.])/g;

/** Replaces the first `values.length` standalone integers of `line` with `values`, in order. */
function setInts(line, values) {
  let i = 0;
  return line.replace(INT, (n) => (i < values.length ? String(values[i++]) : n));
}

/** Like setInts, but for the last `values.length` standalone integers of the line. */
function setLastInts(line, values) {
  const skip = (line.match(INT) ?? []).length - values.length;
  let i = 0;
  return line.replace(INT, (n) => {
    const k = i++ - skip;
    return k >= 0 ? String(values[k]) : n;
  });
}

/** Per-file line rewriters. Each returns the corrected line (unchanged when it does not apply). */
const RULES = [
  {
    files: README_FILES,
    rewrite(line) {
      line = line.replace(/badge\/skills-\d+-blue/, `badge/skills-${counts.total}-blue`);
      if (line.startsWith('│') && line.includes('skills-registry.yml')) return setInts(line, [counts.total]);
      if (line.includes('nms/<skill-id>/')) return setInts(line, [counts.nms]);
      if (line.includes('paper/<skill-id>/')) return setInts(line, [counts.paper]);
      if (line.startsWith('### NMS')) return setInts(line, [counts.nms]);
      if (line.startsWith('### Paper API')) return setInts(line, [counts.paper]);
      // the last two integers: some languages start the line with a number (ja: 2 つのトラック)
      if (line.startsWith('- **') && line.includes('NMS') && line.includes('`paper-api`')) return setLastInts(line, [counts.nms, counts.paper]);
      if (line.includes('/docs/skills)') && line.includes('skills-registry.yml') && !line.startsWith('[')) return setInts(line, [counts.total]);
      return line;
    },
  },
  {
    files: ['CLAUDE.md'],
    rewrite(line) {
      line = line.replace(/(?<![\d.])\d+ 個技能/g, `${counts.total} 個技能`);
      line = line.replace(/共 \d+ 個：NMS \d+ 個、Paper API \d+ 個/, `共 ${counts.total} 個：NMS ${counts.nms} 個、Paper API ${counts.paper} 個`);
      if (line.includes('skills-registry.yml')) line = line.replace(/v\d+\.\d+\.\d+/g, `v${registry.version}`);
      return line;
    },
  },
  {
    files: ['web/shared/i18n/locales/en.ts'],
    rewrite: (line) => line.replace(/(?<![\d.])\d+ agent skills/, `${counts.total} agent skills`).replace(/all \d+ skills/, `all ${counts.total} skills`),
  },
  {
    files: ['web/shared/i18n/locales/zh-TW.ts', 'web/app/docs/page.tsx'],
    rewrite: (line) => line.replace(/(?<![\d.])\d+ 個專為/, `${counts.total} 個專為`).replace(/、\d+ 個 Skills/, `、${counts.total} 個 Skills`),
  },
];

const problems = [];
let rewritten = 0;

for (const rule of RULES) {
  for (const rel of rule.files) {
    const file = path.join(REPO_ROOT, rel);
    const text = fs.readFileSync(file, 'utf8');
    const nl = text.includes('\r\n') ? '\r\n' : '\n';
    const lines = text.split(nl);
    const next = lines.map((line) => rule.rewrite(line));
    const changed = next.filter((line, i) => line !== lines[i]).length;
    if (!changed) continue;
    if (CHECK) {
      problems.push(`${rel}: ${changed} line(s) with outdated counts or version (run node scripts/sync-catalog.mjs)`);
    } else {
      fs.writeFileSync(file, next.join(nl));
      rewritten += changed;
      console.log(`updated ${rel} (${changed} line(s))`);
    }
  }
}

// ---- Checks that cannot be fixed automatically ----

const ids = registry.skills.map((s) => s.id);
const groupOf = (skill) => (skill.platform === 'paper-nms' ? 'nms' : 'paper');

for (const group of ['nms', 'paper']) {
  for (const id of fs.readdirSync(path.join(REPO_ROOT, 'Skills', group))) {
    if (!ids.includes(id)) problems.push(`Skills/${group}/${id}: folder has no registry entry`);
  }
}
const claudeCopy = path.join(REPO_ROOT, '.claude', 'skills', 'skills-registry.yml');
if (fs.readFileSync(claudeCopy, 'utf8').replace(/\r\n/g, '\n') !== fs.readFileSync(REGISTRY_PATH, 'utf8').replace(/\r\n/g, '\n')) {
  problems.push('.claude/skills/skills-registry.yml differs from Skills/skills-registry.yml');
}

const readmes = Object.fromEntries(README_FILES.map((f) => [f, fs.readFileSync(path.join(REPO_ROOT, f), 'utf8')]));
const claudeMd = fs.readFileSync(path.join(REPO_ROOT, 'CLAUDE.md'), 'utf8');
const paperPlatform = fs.readFileSync(path.join(REPO_ROOT, 'Skills', 'paper-api', 'PLATFORM.md'), 'utf8');

for (const skill of registry.skills) {
  const group = groupOf(skill);
  if (!fs.existsSync(path.join(REPO_ROOT, 'Skills', group, skill.id, 'SKILL.md'))) {
    problems.push(`${skill.id}: Skills/${group}/${skill.id}/SKILL.md is missing`);
  }
  const page = path.join(REPO_ROOT, 'web', 'data', 'skills', `${skill.id}.md`);
  if (!fs.existsSync(page)) {
    problems.push(`${skill.id}: web/data/skills/${skill.id}.md is missing`);
  } else {
    const { data } = matter(fs.readFileSync(page, 'utf8'));
    for (const [key, expected] of [['id', skill.id], ['version', skill.version], ['category', skill.category]]) {
      if (String(data[key] ?? '') !== expected) problems.push(`${skill.id}: web frontmatter ${key} "${data[key]}" != registry "${expected}"`);
    }
    const pageVersions = Array.isArray(data.mcVersions) ? data.mcVersions.map(String) : ALL_MC_VERSIONS;
    if ([...pageVersions].sort().join() !== [...skill.mcVersions].sort().join()) {
      problems.push(`${skill.id}: web mcVersions [${pageVersions}] != registry mc_versions [${skill.mcVersions}]`);
    }
  }
  if (!fs.existsSync(path.join(REPO_ROOT, 'web', 'data', 'skills', 'en', `${skill.id}.md`))) {
    problems.push(`${skill.id}: web/data/skills/en/${skill.id}.md is missing`);
  }
  for (const [file, text] of Object.entries(readmes)) {
    if (!text.includes(`(Skills/${group}/${skill.id}/SKILL.md)`)) problems.push(`${file}: no catalog row for ${skill.id}`);
  }
  if (!claudeMd.includes(`| \`${skill.id}\` |`)) problems.push(`CLAUDE.md: no Skills Index row for ${skill.id}`);
  if (group === 'paper' && !paperPlatform.includes(`| \`${skill.id}\` |`)) problems.push(`Skills/paper-api/PLATFORM.md: no skill list row for ${skill.id}`);
}

if (CHECK) {
  for (const problem of problems) console.error(`out of sync: ${problem}`);
  if (problems.length) process.exit(1);
  console.log(`catalog in sync (${counts.total} skills: ${counts.nms} NMS, ${counts.paper} Paper API; registry ${registry.version})`);
} else {
  for (const problem of problems) console.error(`needs manual update: ${problem}`);
  console.log(rewritten ? `rewrote ${rewritten} line(s)` : 'counts and version already in sync');
  if (problems.length) process.exit(1);
}
