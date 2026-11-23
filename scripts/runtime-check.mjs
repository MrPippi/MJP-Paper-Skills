#!/usr/bin/env node
/**
 * Runs the skill templates on a real Paper server and checks what they do (verify/runtime/).
 *
 *   node scripts/extract-skill-java.mjs 26.3
 *   (cd verify && ./gradlew buildRuntimePlugins -Pmc=26.3)
 *   node scripts/runtime-check.mjs 26.3 --accept-eula [scenario ...]
 *
 * --accept-eula is required: the script writes eula=true for each test server, which accepts the
 * Minecraft EULA (https://aka.ms/MinecraftEULA). The server build matches the dev bundle pinned in
 * verify/build.gradle. Each scenario in verify/runtime/scenarios.json gets a fresh server, started
 * `starts` times; it passes when every start reaches DONE, no CHECK fails, no probe or plugin
 * error is logged, and the passed checks total `checks`. Logs: verify/build/runtime/<mc>/<scenario>/.
 * Set JAVA (or JAVA_HOME) to pick the JDK: 25 for 26.3, 21 for 1.21.11.
 */
import fs from 'node:fs';
import path from 'node:path';
import { REPO_ROOT } from './lib/registry.mjs';
import { downloadPaper, newServerDir, pinnedBundle, preparePaper, runServer, serverBuildFor } from './lib/paper-server.mjs';

const START_TIMEOUT_MS = 5 * 60 * 1000;
const CHECK_LINE = /\[PROBE\] CHECK (OK|FAIL)\s+(.*)$/;
const DONE_LINE = /\[PROBE\] DONE\b/;
/** Probe exceptions and plugins that fail to load, bootstrap or enable. */
const ERROR_LINE = /\[PROBE\] FAILED|Error occurred while (?:enabling|loading)|Could not load plugin|Failed to (?:load|run) (?:plugin|bootstrap)/i;

function parseArgs(argv) {
  const positional = argv.filter((a) => !a.startsWith('--'));
  const [mc, ...only] = positional;
  if (!mc) throw new Error('Usage: node scripts/runtime-check.mjs <mc> --accept-eula [scenario ...]');
  return { mc, only, acceptEula: argv.includes('--accept-eula') };
}

function javaCommand() {
  if (process.env.JAVA) return process.env.JAVA;
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', 'java');
  return 'java';
}

function loadScenarios(mc, only) {
  const config = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'verify', 'runtime', 'scenarios.json'), 'utf8'));
  const scenarios = config.scenarios.filter((s) => s.mc.includes(mc) && (only.length === 0 || only.includes(s.id)));
  const unknown = only.filter((id) => !config.scenarios.some((s) => s.id === id));
  if (unknown.length) throw new Error(`Unknown scenario(s): ${unknown.join(', ')}`);
  if (!scenarios.length) throw new Error(`No runtime scenario targets Minecraft ${mc}`);
  return scenarios;
}

function pluginJars(mc, scenario) {
  return scenario.plugins.map((name) => {
    const jar = path.join(REPO_ROOT, 'verify', 'build', 'runtime-plugins', mc, `${name}.jar`);
    if (!fs.existsSync(jar)) throw new Error(`${jar} is missing. Run: (cd verify && ./gradlew buildRuntimePlugins -Pmc=${mc})`);
    return jar;
  });
}

/** Reads one start's console output. */
function evaluateStart(run) {
  const checks = [];
  const errors = [];
  let done = false;
  for (const line of run.lines) {
    const check = line.match(CHECK_LINE);
    if (check) checks.push({ ok: check[1] === 'OK', what: check[2].trim() });
    if (DONE_LINE.test(line)) done = true;
    if (ERROR_LINE.test(line)) errors.push(line.trim());
  }
  if (run.timedOut) errors.push(`server did not stop within ${START_TIMEOUT_MS / 1000} s`);
  else if (!done) errors.push(`server exited (code ${run.code}) before the probe logged DONE`);
  return { checks, errors, finished: done && !run.timedOut };
}

async function runScenario(mc, scenario, server, java) {
  const dir = path.join(REPO_ROOT, 'verify', 'build', 'runtime', mc, scenario.id);
  newServerDir(server.baseDir, server.jar, dir, pluginJars(mc, scenario));
  const checks = [];
  const errors = [];
  for (let start = 1; start <= scenario.starts; start++) {
    const log = path.join(dir, `start-${start}.log`);
    const result = evaluateStart(await runServer(dir, java, log, START_TIMEOUT_MS));
    checks.push(...result.checks);
    errors.push(...result.errors.map((e) => `start ${start}: ${e}`));
    if (!result.finished) break;   // later starts build on this one
  }
  const passed = checks.filter((c) => c.ok).length;
  const failed = checks.filter((c) => !c.ok).map((c) => c.what);
  if (passed !== scenario.checks) errors.push(`${passed} checks passed, expected ${scenario.checks}`);
  return { scenario, passed, failed, errors, ok: failed.length === 0 && errors.length === 0, dir };
}

function report(mc, server, results) {
  const lines = [`Paper ${mc} build ${server.build}`];
  for (const r of results) {
    lines.push(`${r.ok ? 'PASS' : 'FAIL'}  ${r.scenario.id} (${r.scenario.skills.join(', ')}): ${r.passed}/${r.scenario.checks} checks`);
    for (const f of r.failed) lines.push(`      failed check: ${f}`);
    for (const e of r.errors) lines.push(`      ${e}`);
    if (!r.ok) lines.push(`      logs: ${path.relative(REPO_ROOT, r.dir)}`);
  }
  console.log(lines.join('\n'));

  if (process.env.GITHUB_STEP_SUMMARY) {
    const md = [`### Runtime checks: Paper ${mc} build ${server.build}`, '', '| Scenario | Skills | Checks | Result |', '|---|---|---|---|'];
    for (const r of results) {
      md.push(`| \`${r.scenario.id}\` | ${r.scenario.skills.map((s) => `\`${s}\``).join(', ')} | ${r.passed}/${r.scenario.checks} | ${r.ok ? '✅' : '❌'} |`);
    }
    const problems = results.flatMap((r) => [...r.failed.map((f) => `failed check: ${f}`), ...r.errors].map((p) => `- \`${r.scenario.id}\`: ${p}`));
    if (problems.length) md.push('', ...problems);
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md.join('\n') + '\n');
  }
}

async function main() {
  const { mc, only, acceptEula } = parseArgs(process.argv.slice(2));
  if (!acceptEula) {
    throw new Error('The test servers need the Minecraft EULA (https://aka.ms/MinecraftEULA). Pass --accept-eula to accept it and run.');
  }
  const scenarios = loadScenarios(mc, only);
  const java = javaCommand();
  const root = path.join(REPO_ROOT, 'verify', 'build', 'runtime', mc);
  const download = await downloadPaper(serverBuildFor(mc, pinnedBundle(mc)), path.join(root, 'downloads'));
  const server = { ...download, baseDir: path.join(root, 'base') };
  console.log(`Paper ${mc} build ${server.build} (${path.basename(server.jar)})`);
  preparePaper(server.jar, server.baseDir, java);

  const results = [];
  for (const scenario of scenarios) {
    console.log(`running ${scenario.id} ...`);
    results.push(await runScenario(mc, scenario, server, java));
  }
  report(mc, server, results);
  if (results.some((r) => !r.ok)) process.exit(1);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
