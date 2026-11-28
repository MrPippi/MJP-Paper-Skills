#!/usr/bin/env node
/**
 * Keeps the skill catalog consistent with Skills/skills-registry.yml.
 *
 *   node scripts/sync-catalog.mjs           # rewrite skill counts and the registry version where they appear
 *   node scripts/sync-catalog.mjs --check   # report anything out of sync (exit 1); used by web/tests/catalog.test.ts
 *
 * Rewritten (counts and version only): READMEs, CLAUDE.md, the site's locale strings and docs page.
 * Checked only (needs a human or agent, e.g. translations): skill folders, both registry copies,
 * web pages and their frontmatter, README catalog rows, the CLAUDE.md index, the skill list of every
 * platform with `skill_list: true` and the runtime scenarios (verify/runtime/scenarios.json vs the registry and skills-runtime.yml paths).
 *
 * Platforms (skill folders, labels, counts) come from the registry's `platforms:` section.
 */
import fs from 'node:fs';
import path from 'node:path';
import matter from '../web/node_modules/gray-matter/index.js';
import { REPO_ROOT, REGISTRY_PATH, platformOf, readRegistry, skillsPath } from './lib/registry.mjs';

const CHECK = process.argv.includes('--check');
const registry = readRegistry();
const { platforms } = registry;
const total = registry.skills.length;
/** Skill count per platform, in registry order. */
const platformCounts = platforms.map((p) => registry.skills.filter((s) => s.platform === p.id).length);
const countOf = (platform) => platformCounts[platforms.indexOf(platform)];
const escapeRegex = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
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

/** CLAUDE.md Skills Index summary, e.g. `共 39 個：NMS 16 個、Paper API 23 個` (one part per platform). */
const INDEX_COUNTS = new RegExp(`共 \\d+ 個：${platforms.map((p) => `${escapeRegex(p.label)} \\d+ 個`).join('、')}`);
const INDEX_COUNTS_TEXT = `共 ${total} 個：${platforms.map((p, i) => `${p.label} ${platformCounts[i]} 個`).join('、')}`;

/** Per-file line rewriters. Each returns the corrected line (unchanged when it does not apply). */
const RULES = [
  {
    files: README_FILES,
    rewrite(line) {
      line = line.replace(/badge\/skills-\d+-blue/, `badge/skills-${total}-blue`);
      if (line.startsWith('│') && line.includes('skills-registry.yml')) return setInts(line, [total]);
      for (const p of platforms) {
        if (line.includes(`${p.skillsDir}/<skill-id>/`)) return setInts(line, [countOf(p)]);   // layout tree
        if (line.startsWith(`### ${p.label}`)) return setInts(line, [countOf(p)]);             // catalog heading
      }
      // the summary bullet naming every platform: its last integers, one per platform in registry order
      // (some languages start the line with a number, e.g. ja: 2 つのトラック)
      if (line.startsWith('- **') && platforms.every((p) => line.includes(p.label)) && line.includes('`paper-api`')) {
        return setLastInts(line, platformCounts);
      }
      if (line.includes('/docs/skills)') && line.includes('skills-registry.yml') && !line.startsWith('[')) return setInts(line, [total]);
      return line;
    },
  },
  {
    files: ['CLAUDE.md'],
    rewrite(line) {
      line = line.replace(/(?<![\d.])\d+ 個技能/g, `${total} 個技能`);
      line = line.replace(INDEX_COUNTS, INDEX_COUNTS_TEXT);
      if (line.includes('skills-registry.yml')) line = line.replace(/v\d+\.\d+\.\d+/g, `v${registry.version}`);
      return line;
    },
  },
  {
    files: ['web/shared/i18n/locales/en.ts'],
    rewrite: (line) => line.replace(/(?<![\d.])\d+ agent skills/, `${total} agent skills`).replace(/all \d+ skills/, `all ${total} skills`),
  },
  {
    files: ['web/shared/i18n/locales/zh-TW.ts', 'web/app/docs/page.tsx'],
    rewrite: (line) => line.replace(/(?<![\d.])\d+ 個專為/, `${total} 個專為`).replace(/、\d+ 個 Skills/, `、${total} 個 Skills`),
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
const knownPlatform = (skill) => platforms.some((p) => p.id === skill.platform);
/** Folder under Skills/ that holds a skill (its platform's skills_dir). */
const groupOf = (skill) => platformOf(registry, skill).skillsDir;

for (const { skillsDir: group } of platforms) {
  for (const id of fs.readdirSync(path.join(REPO_ROOT, 'Skills', group))) {
    if (!ids.includes(id)) problems.push(`Skills/${group}/${id}: folder has no registry entry`);
  }
}
for (const skill of registry.skills.filter((s) => !knownPlatform(s))) {
  problems.push(`${skill.id}: unknown platform "${skill.platform}" (not under platforms: in the registry)`);
}
const claudeCopy = path.join(REPO_ROOT, '.claude', 'skills', 'skills-registry.yml');
if (fs.readFileSync(claudeCopy, 'utf8').replace(/\r\n/g, '\n') !== fs.readFileSync(REGISTRY_PATH, 'utf8').replace(/\r\n/g, '\n')) {
  problems.push('.claude/skills/skills-registry.yml differs from Skills/skills-registry.yml');
}

const readmes = Object.fromEntries(README_FILES.map((f) => [f, fs.readFileSync(path.join(REPO_ROOT, f), 'utf8')]));
const claudeMd = fs.readFileSync(path.join(REPO_ROOT, 'CLAUDE.md'), 'utf8');
/** platform_file text of every platform that lists its skills (skill_list: true). */
const skillLists = new Map(
  platforms.filter((p) => p.skillList).map((p) => [p.id, fs.readFileSync(path.join(REPO_ROOT, skillsPath(p.platformFile)), 'utf8')]),
);

for (const skill of registry.skills.filter(knownPlatform)) {
  const platform = platformOf(registry, skill);
  const group = platform.skillsDir;
  if (!skill.category.startsWith(platform.categoryPrefix)) {
    problems.push(`${skill.id}: category "${skill.category}" does not start with ${platform.id}'s category_prefix "${platform.categoryPrefix}"`);
  }
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
    const pageVersions = Array.isArray(data.mcVersions) ? data.mcVersions.map(String) : platform.targets;
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
  const skillList = skillLists.get(platform.id);
  if (skillList !== undefined && !skillList.includes(`| \`${skill.id}\` |`)) problems.push(`${skillsPath(platform.platformFile)}: no skill list row for ${skill.id}`);
}

// Runtime scenarios (verify/runtime/scenarios.json) name real skills on versions they support,
// build from existing template sets, and trigger skills-runtime.yml when those skills change.
const scenarioConfig = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'verify', 'runtime', 'scenarios.json'), 'utf8'));
const runtimeWorkflow = fs.readFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'skills-runtime.yml'), 'utf8');
for (const scenario of scenarioConfig.scenarios) {
  for (const id of scenario.skills) {
    const skill = registry.skills.find((s) => s.id === id);
    if (!skill) {
      problems.push(`verify/runtime/scenarios.json: ${scenario.id} names unknown skill ${id}`);
      continue;
    }
    const unsupported = scenario.mc.filter((v) => !skill.mcVersions.includes(v));
    if (unsupported.length) problems.push(`verify/runtime/scenarios.json: ${scenario.id} runs ${id} on ${unsupported} (not in its mc_versions)`);
    const pathFilter = `"Skills/${groupOf(skill)}/${id}/**"`;
    if (runtimeWorkflow.split(pathFilter).length - 1 < 2) problems.push(`.github/workflows/skills-runtime.yml: add ${pathFilter} to the pull_request and push paths`);
  }
  for (const name of scenario.plugins) {
    const plugin = scenarioConfig.plugins[name];
    if (!plugin) {
      problems.push(`verify/runtime/scenarios.json: ${scenario.id} uses undefined plugin ${name}`);
      continue;
    }
    const [group, id] = plugin.templates.split('/');
    if (!fs.existsSync(path.join(REPO_ROOT, 'Skills', group, id, 'SKILL.md'))) problems.push(`verify/runtime/scenarios.json: plugin ${name} builds from missing skill ${plugin.templates}`);
  }
}

if (CHECK) {
  for (const problem of problems) console.error(`out of sync: ${problem}`);
  if (problems.length) process.exit(1);
  console.log(`catalog in sync (${total} skills: ${platforms.map((p, i) => `${platformCounts[i]} ${p.label}`).join(', ')}; registry ${registry.version})`);
} else {
  for (const problem of problems) console.error(`needs manual update: ${problem}`);
  console.log(rewritten ? `rewrote ${rewritten} line(s)` : 'counts and version already in sync');
  if (problems.length) process.exit(1);
}
