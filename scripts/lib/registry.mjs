/**
 * Minimal reader for Skills/skills-registry.yml, shared by the repo scripts (no YAML dependency).
 *
 * Only the fields the scripts need are read: the top-level `version`; for each entry under
 * `skills:`, `id`, `version`, `platform`, `category`, `mc_versions` (or `targets`) and
 * `trigger_keywords`; for each entry under `platforms:`, the fields documented at the top of
 * that section. The registry layout is fixed (two-space list items, four-space fields,
 * scalar or inline-list values), which this parser relies on.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const REGISTRY_PATH = path.join(REPO_ROOT, 'Skills', 'skills-registry.yml');

const unquote = (value) => value.trim().replace(/^"(.*)"$/, '$1');

function parseInlineList(value) {
  const inner = value.trim().replace(/^\[/, '').replace(/\]$/, '');
  return inner.split(',').map((item) => unquote(item)).filter((item) => item !== '');
}

/** Platform field (snake_case in the registry) → property name and parser. */
const PLATFORM_FIELDS = {
  name: ['name', unquote],
  label: ['label', unquote],
  platform_file: ['platformFile', unquote],
  skills_dir: ['skillsDir', unquote],
  category_prefix: ['categoryPrefix', unquote],
  targets: ['targets', parseInlineList],
  references: ['references', parseInlineList],
  language: ['language', unquote],
  forbidden_imports: ['forbiddenImports', parseInlineList],
  snippet_docs: ['snippetDocs', parseInlineList],
  skill_list: ['skillList', (v) => unquote(v) === 'true'],
};

const newPlatform = (id) => ({
  id,
  name: '',
  label: '',
  platformFile: '',
  skillsDir: '',
  categoryPrefix: '',
  targets: [],
  references: [],
  language: '',
  forbiddenImports: [],
  snippetDocs: [],
  skillList: false,
});

/**
 * @typedef {{ id: string, name: string, label: string, platformFile: string, skillsDir: string, categoryPrefix: string,
 *   targets: string[], references: string[], language: string, forbiddenImports: string[],
 *   snippetDocs: string[], skillList: boolean }} Platform
 * @typedef {{ id: string, version: string, platform: string, category: string, mcVersions: string[],
 *   triggerKeywords: string[] }} Skill
 */

/**
 * A skill without `mc_versions` gets its platform's `targets`.
 * @returns {{ version: string, skills: Skill[], platforms: Platform[] }}
 */
export function readRegistry(file = REGISTRY_PATH) {
  const lines = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n');
  const version = unquote((lines.find((l) => l.startsWith('version:')) ?? 'version: ""').slice('version:'.length));
  const skills = [];
  const platforms = [];
  let section = '';
  let current = null;
  let inTriggers = false;
  for (const line of lines) {
    if (/^\S/.test(line)) {
      section = line.replace(/:.*$/, '');
      current = null;
      continue;
    }
    const item = line.match(/^ {2}- id: (.+)$/);
    if (item && section === 'skills') {
      current = { id: unquote(item[1]), version: '', platform: '', category: '', mcVersions: null, triggerKeywords: [] };
      skills.push(current);
      inTriggers = false;
      continue;
    }
    if (item && section === 'platforms') {
      current = newPlatform(unquote(item[1]));
      platforms.push(current);
      continue;
    }
    if (!current) continue;
    const field = line.match(/^ {4}([a-z_]+):\s*(.*)$/);
    if (field && section === 'platforms') {
      const known = PLATFORM_FIELDS[field[1]];
      if (known) current[known[0]] = known[1](field[2]);
      continue;
    }
    if (field && section === 'skills') {
      inTriggers = field[1] === 'trigger_keywords';
      if (field[1] === 'version') current.version = unquote(field[2]);
      if (field[1] === 'platform') current.platform = unquote(field[2]);
      if (field[1] === 'category') current.category = unquote(field[2]);
      if (field[1] === 'mc_versions' || field[1] === 'targets') current.mcVersions = parseInlineList(field[2]);
      continue;
    }
    const listItem = line.match(/^ {6}- (.+)$/);
    if (section === 'skills' && inTriggers && listItem) current.triggerKeywords.push(unquote(listItem[1]));
  }
  for (const skill of skills) {
    skill.mcVersions ??= [...(platforms.find((p) => p.id === skill.platform)?.targets ?? [])];
  }
  return { version, skills, platforms };
}

/** The platform a skill belongs to; throws for an unknown platform id. */
export function platformOf(registry, skill) {
  const platform = registry.platforms.find((p) => p.id === skill.platform);
  if (!platform) throw new Error(`${skill.id}: unknown platform "${skill.platform}"`);
  return platform;
}

/** Every target any platform verifies, in registry order (first = the default target). */
export function allTargets(registry) {
  return [...new Set(registry.platforms.flatMap((p) => p.targets))];
}

/** Repo-relative path of a Skills/-relative registry path (platform_file, references). */
export const skillsPath = (rel) => `Skills/${rel}`;
