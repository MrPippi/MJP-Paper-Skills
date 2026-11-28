import type { PixelIconName } from '@/shared/ui/pixel-icons';

/**
 * Mirrors the `platforms:` section of Skills/skills-registry.yml (id, label, skills_dir,
 * category_prefix, in registry order; tests/platform.test.ts checks it). The icon and docs page
 * are site-only. Adding a platform: add it to the registry, then here.
 */
export const PLATFORMS = [
  { id: 'paper-nms', label: 'NMS', icon: 'redstone', docSlug: 'paper-nms', skillsDir: 'nms', categoryPrefix: 'nms-' },
  { id: 'paper-api', label: 'Paper API', icon: 'emerald', docSlug: 'paper-api', skillsDir: 'paper', categoryPrefix: 'paper-' },
] as const satisfies readonly PlatformDefinition[];

export type PlatformId = (typeof PLATFORMS)[number]['id'];

interface PlatformDefinition {
  id: string;
  label: string;
  icon: PixelIconName;
  /** Docs page describing this platform's build setup. */
  docSlug: string;
  /** Folder under Skills/ that holds the platform's skills. */
  skillsDir: string;
  /** Prefix of every category id on this platform. */
  categoryPrefix: string;
}

/** Platform of a skill whose path and category match no platform. */
const FALLBACK_PLATFORM: PlatformId = 'paper-api';

interface PlatformSource {
  githubPath: string;
  category: string;
}

export function getPlatform(skill: PlatformSource): PlatformId {
  const byPath = PLATFORMS.find((p) => skill.githubPath.startsWith(`Skills/${p.skillsDir}/`));
  if (byPath) return byPath.id;
  return PLATFORMS.find((p) => skill.category.startsWith(p.categoryPrefix))?.id ?? FALLBACK_PLATFORM;
}

export function isPlatformId(value: string | null | undefined): value is PlatformId {
  return PLATFORMS.some((p) => p.id === value);
}

const ICON_BY_SUFFIX: Record<string, PixelIconName> = {
  packet: 'pearl',
  network: 'redstone',
  entity: 'egg',
  bridge: 'pickaxe',
  integration: 'emerald',
  data: 'book',
  ui: 'chest',
  display: 'sign',
  player: 'head',
  world: 'grass',
  gameplay: 'sword',
  command: 'command',
  threading: 'redstone',
};

export function categoryIconFor(category: string): PixelIconName {
  const prefix = PLATFORMS.find((p) => category.startsWith(p.categoryPrefix))?.categoryPrefix ?? '';
  const suffix = category.slice(prefix.length);
  return ICON_BY_SUFFIX[suffix] ?? 'pickaxe';
}

export interface CategoryGroup<T> {
  id: string;
  skills: T[];
}

export interface PlatformGroup<T> {
  platform: PlatformId;
  skills: T[];
  categories: CategoryGroup<T>[];
}

/** Platforms in PLATFORMS order; categories in first-seen order. Input is not mutated. */
export function groupSkillsByPlatform<T extends PlatformSource>(skills: readonly T[]): PlatformGroup<T>[] {
  return PLATFORMS.map(({ id }) => {
    const own = skills.filter((s) => getPlatform(s) === id);
    const categoryIds = [...new Set(own.map((s) => s.category))];
    return {
      platform: id,
      skills: own,
      categories: categoryIds.map((cat) => ({ id: cat, skills: own.filter((s) => s.category === cat) })),
    };
  });
}
