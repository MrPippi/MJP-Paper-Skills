/**
 * Downloads, prepares and runs a Paper server for scripts/runtime-check.mjs.
 *
 * The server jar comes from PaperMC's download API (fill.papermc.io) and is checked against the
 * SHA-256 the API publishes. Paperclip patches it once into a base folder (libraries/, versions/,
 * cache/); every scenario then gets a fresh copy of that folder, so no world or plugin data leaks
 * from one scenario to the next.
 */
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { REPO_ROOT } from './registry.mjs';

const FILL_API = 'https://fill.papermc.io/v3/projects/paper/versions';
/** PaperMC asks API clients to identify themselves; no personal data. */
const USER_AGENT = 'mjp-paper-skills-runtime-check/1.0 (+https://github.com/MrPippi/MJP-Paper-Skills)';
const PREPARED_DIRS = ['libraries', 'versions', 'cache'];

/** The dev bundle verify/build.gradle pins for `mc`, e.g. 26.3.build.258-stable. */
export function pinnedBundle(mc) {
  const gradle = fs.readFileSync(path.join(REPO_ROOT, 'verify', 'build.gradle'), 'utf8');
  const escaped = mc.replace(/\./g, '\\.');
  const match = gradle.match(new RegExp(`'${escaped}'\\s*:\\s*\\[bundle:\\s*'([^']+)'`));
  if (!match) throw new Error(`verify/build.gradle has no target for Minecraft ${mc}`);
  return match[1];
}

/** The server build matching a dev bundle: 26.3.build.258-stable → build 258; a SNAPSHOT → the latest build. */
export function serverBuildFor(mc, bundle) {
  const build = bundle.match(/\.build\.(\d+)-/)?.[1];
  return { version: mc, build: build ?? 'latest' };
}

async function fetchJson(url) {
  const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!response.ok) throw new Error(`${url}: HTTP ${response.status}`);
  return response.json();
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

/** Downloads the server jar into `dir` (reused when already there and the checksum matches). */
export async function downloadPaper({ version, build }, dir) {
  const info = await fetchJson(`${FILL_API}/${encodeURIComponent(version)}/builds/${encodeURIComponent(build)}`);
  const download = info?.downloads?.['server:default'];
  const expected = download?.checksums?.sha256;
  if (!download?.url?.startsWith('https://') || !/^[0-9a-f]{64}$/.test(expected ?? '') || !/^[\w.-]+\.jar$/.test(download.name ?? '')) {
    throw new Error(`Unexpected download info for Paper ${version} build ${build}`);
  }
  fs.mkdirSync(dir, { recursive: true });
  const jar = path.join(dir, download.name);
  if (fs.existsSync(jar) && sha256(jar) === expected) return { jar, build: info.id ?? build, channel: info.channel };

  const response = await fetch(download.url, { headers: { 'User-Agent': USER_AGENT } });
  if (!response.ok) throw new Error(`${download.url}: HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const actual = crypto.createHash('sha256').update(bytes).digest('hex');
  if (actual !== expected) throw new Error(`${download.name}: SHA-256 ${actual} does not match ${expected}`);
  fs.writeFileSync(jar, bytes);
  return { jar, build: info.id ?? build, channel: info.channel };
}

/** Lets Paperclip download the vanilla server and libraries once, without starting the server. */
export function preparePaper(jar, baseDir, java) {
  if (fs.existsSync(path.join(baseDir, 'versions'))) return;
  fs.mkdirSync(baseDir, { recursive: true });
  const result = spawnSync(java, ['-Dpaperclip.patchonly=true', '-jar', path.resolve(jar)], { cwd: baseDir, encoding: 'utf8' });
  if (result.status !== 0 || !fs.existsSync(path.join(baseDir, 'versions'))) {
    throw new Error(`Paperclip could not prepare ${jar}:\n${result.stdout}\n${result.stderr}`);
  }
}

const SERVER_PROPERTIES = {
  'online-mode': 'false',
  'server-ip': '127.0.0.1',
  'server-port': '25599',
  'level-type': 'minecraft\\:flat',
  // spelled out: an empty preset makes the server log "No key layers" and fall back to the default
  'generator-settings': JSON.stringify({
    layers: [
      { block: 'minecraft:bedrock', height: 1 },
      { block: 'minecraft:dirt', height: 2 },
      { block: 'minecraft:grass_block', height: 1 },
    ],
    biome: 'minecraft:plains',
  }),
  'level-seed': 'mjp-runtime-check',
  'generate-structures': 'false',
  'spawn-protection': '0',
  'view-distance': '3',
  'simulation-distance': '3',
  'max-players': '1',
  'enable-query': 'false',
  'enable-rcon': 'false',
};

/**
 * Creates a fresh server folder from the prepared base. Writing eula.txt accepts the Minecraft EULA
 * (https://aka.ms/MinecraftEULA); callers only do this after the user opted in with --accept-eula.
 */
export function newServerDir(baseDir, jar, dir, pluginJars) {
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'plugins'), { recursive: true });
  for (const name of PREPARED_DIRS) {
    const from = path.join(baseDir, name);
    if (fs.existsSync(from)) fs.cpSync(from, path.join(dir, name), { recursive: true });
  }
  fs.copyFileSync(jar, path.join(dir, 'paper.jar'));
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), Object.entries(SERVER_PROPERTIES).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
  for (const plugin of pluginJars) fs.copyFileSync(plugin, path.join(dir, 'plugins', path.basename(plugin)));
}

/**
 * Starts the server in `dir` and resolves when it exits (or is killed after `timeoutMs`).
 * The console output is written to `logFile` and returned as lines.
 */
export function runServer(dir, java, logFile, timeoutMs) {
  return new Promise((resolve, reject) => {
    // IReallyKnowWhatIAmDoingISwear skips the 20 s pause Paper adds when a build is a few weeks old
    const args = ['-Xms512M', '-Xmx2G', '-DIReallyKnowWhatIAmDoingISwear=true', '-jar', 'paper.jar', '--nogui'];
    const child = spawn(java, args, { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
    const log = fs.createWriteStream(logFile);
    let output = '';
    const collect = (chunk) => {
      const text = chunk.toString('utf8');
      output += text;
      log.write(text);
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, timeoutMs);
    child.on('error', (error) => {
      clearTimeout(timer);
      log.end();
      reject(error);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      log.end(() => resolve({ code, timedOut, lines: output.split(/\r?\n/) }));
    });
  });
}
