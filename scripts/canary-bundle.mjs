#!/usr/bin/env node
/**
 * Finds the newest Paper dev bundle on repo.papermc.io and compares it with the 26.x bundle
 * pinned in verify/build.gradle. Used by .github/workflows/skills-canary.yml to compile the
 * templates against a newer build (or a new Minecraft version's beta) before the repo moves to it.
 *
 *   node scripts/canary-bundle.mjs            # prints the result
 *   GITHUB_OUTPUT=... node scripts/...        # also writes bundle=, pinned=, newer= for the workflow
 */
import fs from 'node:fs';
import path from 'node:path';
import { REPO_ROOT } from './lib/registry.mjs';

const METADATA_URL = 'https://repo.papermc.io/repository/maven-public/io/papermc/paper/dev-bundle/maven-metadata.xml';
/** The canary compiles the default (26.x) code path, so it only follows 26.x and later bundles. */
const MIN_MAJOR = 26;

function pinnedBundle() {
  const gradle = fs.readFileSync(path.join(REPO_ROOT, 'verify', 'build.gradle'), 'utf8');
  const match = gradle.match(/'26\.\d+'\s*:\s*\[bundle:\s*'([^']+)'/);
  if (!match) throw new Error('No 26.x bundle found in verify/build.gradle targets');
  return match[1];
}

/** Paper's newest published dev bundle (the metadata's <latest>, which includes betas). */
async function latestBundle() {
  const response = await fetch(METADATA_URL);
  if (!response.ok) throw new Error(`${METADATA_URL}: HTTP ${response.status}`);
  const xml = await response.text();
  const latest = xml.match(/<latest>([^<]+)<\/latest>/)?.[1];
  if (!latest) throw new Error('maven-metadata.xml has no <latest> element');
  return latest;
}

const pinned = pinnedBundle();
const latest = await latestBundle();
const major = Number.parseInt(latest, 10);
const newer = latest !== pinned && major >= MIN_MAJOR;

console.log(`pinned: ${pinned}`);
console.log(`latest: ${latest}`);
console.log(newer ? 'A newer bundle is available; the canary will compile against it.' : 'No newer 26.x bundle; nothing to check.');

if (process.env.GITHUB_OUTPUT) {
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `bundle=${latest}\npinned=${pinned}\nnewer=${newer}\n`);
}
