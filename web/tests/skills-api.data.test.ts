/**
 * Tests for features/skills/api/skills.ts against the real web/data/skills/ content,
 * checked against Skills/skills-registry.yml (ids, versions, categories).
 *
 * Must run with cwd = web/ (npm test does this).
 */
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {
  getAllSkills,
  getCategories,
  getFeaturedSkills,
  getSearchIndex,
  getSkillBySlug,
  getSkillsByCategory,
} from '../features/skills/api/skills';
import { readRegistry } from './helpers/registry';

const DATA_DIR = path.join(__dirname, '..', 'data', 'skills');

/** Expectations come from Skills/skills-registry.yml, so adding a skill needs no test edit. */
const REGISTRY = readRegistry();
const EXPECTED_SLUGS = REGISTRY.skills.map((s) => s.id).sort();

describe('getAllSkills (real data)', () => {
  it('parses every .md file in data/skills', () => {
    const mdCount = fs.readdirSync(DATA_DIR).filter((f) => f.endsWith('.md')).length;
    assert.equal(getAllSkills().length, mdCount);
  });

  it('contains exactly the registry skills, in title order', () => {
    const skills = getAllSkills();
    assert.deepEqual(skills.map((s) => s.slug).sort(), EXPECTED_SLUGS);
    const titles = skills.map((s) => s.title);
    assert.deepEqual(titles, [...titles].sort((a, b) => a.localeCompare(b)));
  });

  it('uses frontmatter id equal to filename slug for every skill', () => {
    for (const s of getAllSkills()) assert.equal(s.id, s.slug);
  });

  it('marks every skill active at its registry version with string updatedAt', () => {
    const versions = new Map(REGISTRY.skills.map((s) => [s.id, s.version]));
    for (const s of getAllSkills()) {
      assert.equal(s.status, 'active', s.slug);
      assert.equal(s.version, versions.get(s.slug), s.slug);
      assert.match(s.updatedAt, /^\d{4}-\d{2}-\d{2}$/, s.slug);
    }
  });
});

describe('getCategories (real data)', () => {
  it('counts every registry category, sorted by count desc, with labels', () => {
    const expected = new Map<string, number>();
    for (const s of REGISTRY.skills) expected.set(s.category, (expected.get(s.category) ?? 0) + 1);
    const categories = getCategories();
    assert.deepEqual(new Map(categories.map((c) => [c.id, c.count])), expected);
    const counts = categories.map((c) => c.count);
    assert.deepEqual(counts, [...counts].sort((a, b) => b - a));
    for (const c of categories) assert.ok(c.label && c.labelEn, c.id);
  });

  it('getSkillsByCategory returns title-ordered members', () => {
    assert.deepEqual(getSkillsByCategory('nms-world').map((s) => s.slug), [
      'nms-block-entity',
      'nms-chunk-access',
      'nms-particle-effect',
    ]);
  });
});

describe('getFeaturedSkills (real data)', () => {
  it('returns the 7 featured skills', () => {
    assert.deepEqual(getFeaturedSkills().map((s) => s.slug), [
      'nms-custom-entity',
      'nms-custom-menu',
      'nms-fake-player',
      'nms-nbt-manipulation',
      'nms-packet-interceptor',
      'nms-packet-sender',
      'nms-scoreboard',
    ]);
  });
});

describe('getSearchIndex (real data)', () => {
  it('has one entry per skill with the projected keys only', () => {
    const index = getSearchIndex();
    assert.equal(index.length, EXPECTED_SLUGS.length);
    for (const entry of index) {
      assert.deepEqual(Object.keys(entry), [
        'id', 'slug', 'title', 'titleZh', 'description', 'descriptionZh', 'tags', 'category', 'status',
      ]);
    }
  });
});

describe('getSkillBySlug (real data)', () => {
  it('renders nms-packet-sender starting with h1 then 目的 h2', async () => {
    const skill = await getSkillBySlug('nms-packet-sender');
    assert.ok(skill);
    assert.ok(skill.contentHtml.startsWith('<h1 id="nms-packet-sender">NMS Packet Sender</h1>\n<h2 id="目的">目的</h2>'));
    assert.equal(skill.headings[0].text, '目的');
  });

  it('renders every skill to non-empty HTML', async () => {
    for (const slug of EXPECTED_SLUGS) {
      const skill = await getSkillBySlug(slug);
      assert.ok(skill && skill.contentHtml.length > 0, slug);
    }
  });
});
