#!/usr/bin/env node
/**
 * Extracts every complete Java compilation unit (a ```java block with a `package` line)
 * from the skills and writes it as a source file for one Minecraft version, so
 * verify/ can compile the templates against that version's Paper dev bundle.
 *
 *   node scripts/extract-skill-java.mjs 26.3      # → verify/build/extracted/26.3/<group>/<skill>/<set>/...
 *   node scripts/extract-skill-java.mjs 1.21.11
 *
 * Version markers (see Skills/paper-nms/PLATFORM.md):
 *   `code; // @1.21.11: other code;`  → for 1.21.11 the line becomes `other code;`
 *   first line `// @only <version>`   → the block is skipped for other versions
 *
 * Long skills keep their full templates in template.md next to SKILL.md; both files
 * form one compile set.
 *
 * A skill whose registry entry lists `mc_versions` is skipped for every other target
 * (e.g. paper-world-clock is 26.3 only).
 *
 * Partial snippets (no `package` line) are not compiled. Their imports, and those of the
 * docs/paper-nms reference, are written to snippet-imports.tsv; verify's
 * checkSnippetImports task resolves them against the same classpath, so a snippet that
 * names a class removed in a new version is still caught. Exits 1 on conflicting
 * duplicate files or NMS imports in Paper API skills.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ALL_MC_VERSIONS, readRegistry } from './lib/registry.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const TARGETS = ALL_MC_VERSIONS;
const DEFAULT_TARGET = '26.3';
const GROUPS = ['nms', 'paper'];
/** Files whose complete units form a skill's base compile set. */
const TEMPLATE_FILES = ['SKILL.md', 'template.md'];

const JAVA_BLOCK = /```java\r?\n([\s\S]*?)```/g;
const PACKAGE = /^package\s+([\w.]+)\s*;/m;
const TOP_LEVEL_TYPE =
  /^(public\s+)?(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*(?:class|interface|record|enum|@interface)\s+(\w+)/gm;
const ONLY = /^\s*\/\/\s*@only\s+(\S+)/;
const VERSION_ALT = /^(\s*).*?\/\/\s*@1\.21\.11:\s?(.*)$/;
const NMS_IMPORT = /^import\s+(?:static\s+)?(?:net\.minecraft|org\.bukkit\.craftbukkit)\./m;
const SNIPPET_IMPORT = /^\s*import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;/gm;
/**
 * Not checked: JDK packages (not on the Gradle classpath; javac checks them when templates compile)
 * and com.example, the namespace every template uses for its own classes.
 */
const UNCHECKED_PREFIX = /^(?:java|javax|jdk|sun|com\.sun|com\.example)\./;
/** Reference docs whose snippets are checked as well (they belong to no skill). */
const SNIPPET_DOC_DIRS = ['docs/paper-nms'];

/** Applies the version markers; returns null when the block does not apply to `target`. */
export function applyTarget(code, target) {
  const firstLine = code.split('\n').find((l) => l.trim() !== '') ?? '';
  const only = firstLine.match(ONLY);
  if (only && only[1] !== target) return null;
  if (target !== '1.21.11') return code;
  return code
    .split('\n')
    .map((line) => {
      const alt = line.match(VERSION_ALT);
      return alt ? `${alt[1]}${alt[2]}` : line;
    })
    .join('\n');
}

/** File name of a compilation unit: its public top-level type, else the first top-level type. */
export function primaryTypeName(code) {
  const types = [...code.matchAll(TOP_LEVEL_TYPE)];
  const publicType = types.find((m) => m[1]);
  return (publicType ?? types[0])?.[2] ?? null;
}

function collectUnits(group, id, files, target, problems) {
  const units = new Map();
  let skippedSnippets = 0;
  for (const file of files) {
    skippedSnippets += collectFile(group, id, file, target, units, problems);
  }
  return { units, skippedSnippets };
}

/** Adds the complete units of one markdown file to `units`; returns its partial-snippet count. */
function collectFile(group, id, file, target, units, problems) {
  let skippedSnippets = 0;
  const full = path.join(REPO_ROOT, 'Skills', group, id, file);
  if (!fs.existsSync(full)) return 0;
  const where = `Skills/${group}/${id}/${file}`;
  const markdown = fs.readFileSync(full, 'utf8').replace(/\r\n/g, '\n');
  for (const [, block] of markdown.matchAll(JAVA_BLOCK)) {
    const pkg = block.match(PACKAGE);
    if (!pkg) {
      skippedSnippets += 1;
      continue;
    }
    const code = applyTarget(block, target);
    if (code === null) continue;
    if (group === 'paper' && NMS_IMPORT.test(code)) problems.push(`${where}: Paper API skill imports NMS/CraftBukkit`);
    const type = primaryTypeName(code);
    if (!type) {
      problems.push(`${where}: no top-level type in package ${pkg[1]}`);
      continue;
    }
    const relPath = `${pkg[1].replace(/\./g, '/')}/${type}.java`;
    const existing = units.get(relPath);
    if (existing === undefined) units.set(relPath, code);
    else if (existing !== code) problems.push(`${where}: conflicting definitions of ${relPath}`);
  }
  return skippedSnippets;
}

/**
 * Two compile sets per skill: `skill` (SKILL.md + template.md) and, when examples.md has
 * complete files, `examples` (the templates with the example files layered on top,
 * since examples may extend or replace a template class).
 */
function extractSkill(group, id, target) {
  const problems = [];
  const template = collectUnits(group, id, TEMPLATE_FILES, target, problems);
  const examples = collectUnits(group, id, ['examples.md'], target, problems);
  const sets = { skill: template.units };
  if (examples.units.size) sets.examples = new Map([...template.units, ...examples.units]);
  return { sets, problems, skippedSnippets: template.skippedSnippets + examples.skippedSnippets };
}

/** Imports of every partial snippet in `files` (repo-relative) as `where<TAB>static|class<TAB>name` rows. */
export function snippetImports(files, target) {
  const rows = [];
  for (const rel of files) {
    const full = path.join(REPO_ROOT, rel);
    if (!fs.existsSync(full)) continue;
    const markdown = fs.readFileSync(full, 'utf8').replace(/\r\n/g, '\n');
    for (const [, block] of markdown.matchAll(JAVA_BLOCK)) {
      if (PACKAGE.test(block)) continue;                       // complete units are compiled instead
      const code = applyTarget(block, target);
      if (code === null) continue;
      for (const [, isStatic, name] of code.matchAll(SNIPPET_IMPORT)) {
        if (!UNCHECKED_PREFIX.test(name)) rows.push(`${rel}\t${isStatic ? 'static' : 'class'}\t${name}`);
      }
    }
  }
  return rows;
}

/** Markdown files whose snippets are checked for `target`: every built skill plus SNIPPET_DOC_DIRS. */
function snippetFiles(target, versionsById) {
  const files = [];
  for (const group of GROUPS) {
    const groupDir = path.join(REPO_ROOT, 'Skills', group);
    for (const id of fs.readdirSync(groupDir).sort()) {
      if (!(versionsById.get(id) ?? TARGETS).includes(target)) continue;
      for (const file of [...TEMPLATE_FILES, 'examples.md']) files.push(`Skills/${group}/${id}/${file}`);
    }
  }
  for (const dir of SNIPPET_DOC_DIRS) {
    for (const file of fs.readdirSync(path.join(REPO_ROOT, dir)).filter((f) => f.endsWith('.md')).sort()) {
      files.push(`${dir}/${file}`);
    }
  }
  return files;
}

function main() {
  const target = process.argv[2] ?? DEFAULT_TARGET;
  if (!TARGETS.includes(target)) {
    console.error(`Unknown target "${target}". Use one of: ${TARGETS.join(', ')}`);
    process.exit(2);
  }
  const outRoot = path.join(REPO_ROOT, 'verify', 'build', 'extracted', target);
  fs.rmSync(outRoot, { recursive: true, force: true });

  let files = 0;
  let snippets = 0;
  let skippedSkills = 0;
  const problems = [];
  const versionsById = new Map(readRegistry().skills.map((skill) => [skill.id, skill.mcVersions]));
  for (const group of GROUPS) {
    const groupDir = path.join(REPO_ROOT, 'Skills', group);
    for (const id of fs.readdirSync(groupDir).sort()) {
      if (!fs.statSync(path.join(groupDir, id)).isDirectory()) continue;
      if (!(versionsById.get(id) ?? TARGETS).includes(target)) {
        skippedSkills += 1;                     // registry mc_versions excludes this target
        continue;
      }
      const result = extractSkill(group, id, target);
      problems.push(...result.problems);
      snippets += result.skippedSnippets;
      for (const [set, units] of Object.entries(result.sets)) {
        for (const [relPath, code] of units) {
          const out = path.join(outRoot, group, id, set, relPath);
          fs.mkdirSync(path.dirname(out), { recursive: true });
          fs.writeFileSync(out, code);
          files += 1;
        }
      }
    }
  }

  const imports = snippetImports(snippetFiles(target, versionsById), target);
  fs.mkdirSync(outRoot, { recursive: true });
  fs.writeFileSync(path.join(outRoot, 'snippet-imports.tsv'), imports.join('\n') + '\n');

  for (const problem of problems) console.error(`error: ${problem}`);
  console.log(`[${target}] extracted ${files} files; skipped ${snippets} partial snippets and ${skippedSkills} skills not built for this version; listed ${imports.length} snippet imports`);
  if (problems.length) process.exit(1);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
