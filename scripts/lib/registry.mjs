/**
 * Minimal reader for Skills/skills-registry.yml, shared by the repo scripts (no YAML dependency).
 *
 * Only the fields the scripts need are read: the top-level `version` and, for each entry under
 * `skills:`, `id`, `version`, `platform`, `category`, `mc_versions` and `trigger_keywords`.
 * The registry layout is fixed (two-space list items, four-space fields), which this parser relies on.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const REGISTRY_PATH = path.join(REPO_ROOT, 'Skills', 'skills-registry.yml');

/** Every MC version the repository verifies; a skill without `mc_versions` supports all of them. */
export const ALL_MC_VERSIONS = ['26.3', '1.21.11'];

const unquote = (value) => value.trim().replace(/^"(.*)"$/, '$1');

function parseInlineList(value) {
  const inner = value.trim().replace(/^\[/, '').replace(/\]$/, '');
  return inner.split(',').map((item) => unquote(item)).filter((item) => item !== '');
}

/** @returns {{ version: string, skills: Array<{ id: string, version: string, platform: string, category: string, mcVersions: string[], triggerKeywords: string[] }> }} */
export function readRegistry(file = REGISTRY_PATH) {
  const lines = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n');
  const version = unquote((lines.find((l) => l.startsWith('version:')) ?? 'version: ""').slice('version:'.length));
  const skills = [];
  let inSkills = false;
  let current = null;
  let inTriggers = false;
  for (const line of lines) {
    if (/^\S/.test(line)) {
      inSkills = line.startsWith('skills:');
      current = null;
      continue;
    }
    if (!inSkills) continue;
    const item = line.match(/^ {2}- id: (.+)$/);
    if (item) {
      current = { id: unquote(item[1]), version: '', platform: '', category: '', mcVersions: [...ALL_MC_VERSIONS], triggerKeywords: [] };
      skills.push(current);
      inTriggers = false;
      continue;
    }
    if (!current) continue;
    const field = line.match(/^ {4}([a-z_]+):\s*(.*)$/);
    if (field) {
      inTriggers = field[1] === 'trigger_keywords';
      if (field[1] === 'version') current.version = unquote(field[2]);
      if (field[1] === 'platform') current.platform = unquote(field[2]);
      if (field[1] === 'category') current.category = unquote(field[2]);
      if (field[1] === 'mc_versions') current.mcVersions = parseInlineList(field[2]);
      continue;
    }
    const listItem = line.match(/^ {6}- (.+)$/);
    if (inTriggers && listItem) current.triggerKeywords.push(unquote(listItem[1]));
  }
  return { version, skills };
}
