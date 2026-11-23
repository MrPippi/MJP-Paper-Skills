/**
 * Reads Skills/skills-registry.yml through scripts/lib/registry.mjs (the same parser the repo
 * scripts use), so tests derive their expectations from the registry instead of hard-coding them.
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export interface RegistrySkill {
  id: string;
  version: string;
  platform: string;
  category: string;
  mcVersions: string[];
  triggerKeywords: string[];
}

let cache: { version: string; skills: RegistrySkill[] } | null = null;

export function readRegistry(): { version: string; skills: RegistrySkill[] } {
  if (cache) return cache;
  const lib = pathToFileURL(path.resolve(process.cwd(), '..', 'scripts', 'lib', 'registry.mjs')).href;
  const result = spawnSync(
    process.execPath,
    ['--input-type=module', '-e', `import { readRegistry } from ${JSON.stringify(lib)}; process.stdout.write(JSON.stringify(readRegistry()));`],
    { encoding: 'utf8' },
  );
  if (result.status !== 0) throw new Error(`could not read the registry: ${result.stderr}`);
  cache = JSON.parse(result.stdout);
  return cache!;
}
