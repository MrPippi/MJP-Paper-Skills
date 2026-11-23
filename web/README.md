# MJP-Paper-Skills Web — Minecraft Paper Agent Skills 網站

MJP-Paper-Skills 官方網站，提供 Skills 瀏覽、搜尋與詳細頁功能。以 Next.js static export 產出 `out/`，部署於 GitHub Pages。

## 需求

- Node.js 24（與 CI 相同；最低需 Node 20.6+ 才能執行 `node --import tsx` 測試）
- npm 10+

## 依賴

| 套件 | 版本（lockfile） | 用途 |
|------|-----------------|------|
| `next` | 16.4.6 | App Router、static export |
| `react` / `react-dom` | 19.3.0 | UI |
| `gray-matter` | 4.0.3 | 解析 `data/skills/*.md` frontmatter |
| `unified` / `remark-parse` / `remark-gfm` / `remark-rehype` | 11.0.5 / 11.0.0 / 4.0.1 / 11.1.2 | Markdown → HAST |
| `rehype-sanitize` / `rehype-slug` / `rehype-stringify` | 6.0.0 / 6.0.0 / 10.0.1 | 清理、標題錨點、輸出 HTML |
| `shiki` / `@shikijs/rehype` | 4.5.0 | 建置時程式碼高亮（淺色／深色雙主題） |
| `hast-util-to-string` / `unist-util-visit` | 3.0.1 / 5.1.0 | 擷取標題、走訪語法樹 |
| `fuse.js` | 7.5.0 | 客戶端模糊搜尋 |
| `tailwindcss` / `@tailwindcss/postcss` | 4.3.3 | 樣式（dev） |
| `typescript` | 6.0.3 | 型別檢查（dev） |
| `tsx` | 4.23.15 | 執行 TypeScript 測試（dev） |
| `@types/node` / `@types/react` / `@types/react-dom` / `@types/hast` | 24.19.1 / 19.3.0 / 19.3.0 / 3.0.5 | 型別（dev） |

`npm audit`：0 個漏洞（2026-11-23）。

## 本機開發

```bash
cd web
npm ci
npm run dev
```

開啟 [http://localhost:3000](http://localhost:3000)

## 指令

| 指令 | 說明 |
|------|------|
| `npm run dev` | 開發伺服器 |
| `npx tsc --noEmit` | 型別檢查 |
| `npm test` | characterization tests（`tests/*.test.ts`，`node:test` + `tsx`） |
| `npm run build` | 靜態匯出至 `out/`；`robots.txt` / `sitemap.xml` 由 `app/robots.ts`、`app/sitemap.ts` 產生 |

`output: 'export'` 不支援 `next start`；要預覽建置結果請以任一靜態伺服器服務 `out/`（例如 `npx serve out`）。

## 環境變數

| 變數 | 說明 | 預設值 |
|------|------|--------|
| `NEXT_PUBLIC_SITE_URL` | 網站正式 URL（SEO、sitemap 用） | `https://mrpippi.github.io/MJP-Paper-Skills`（見 `config/site.ts`） |

本機需要時可建立 `.env.local` 設定上述變數。

## 新增 Skill

1. 在 `data/skills/` 新增 `{skill-id}.md`，填入 YAML frontmatter（參考現有檔案）
2. 在 `data/skills/en/{skill-id}.md` 新增英文內文（只含內文、不得有中文）
3. 在儲存庫根目錄執行 `node scripts/sync-catalog.mjs` 更新各處技能數量（測試的預期值直接讀 `Skills/skills-registry.yml`，不必手改；`tests/catalog.test.ts` 會檢查網站頁、README 目錄與數量是否與 registry 一致）
4. 若 `tests/pixel-font.test.ts` 回報缺字，執行 `python scripts/pixel-font.py <Cubic_11.ttf>` 重新產生像素字型子集
5. `npm test && npm run build`

## 部署

- **GitHub Pages（主要）**：推送至 `main` 後由 `.github/workflows/nextjs.yml` 執行型別檢查、測試、建置並部署；basePath 由 `GITHUB_REPOSITORY` 自動推得。
- **PR 檢查**：`.github/workflows/ci.yml` 對每個 PR 執行型別檢查、測試與建置（不部署）。
