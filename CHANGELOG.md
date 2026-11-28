# Changelog

本檔記錄 MJP-Claude-Skills 的重要變更，格式參考 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/)。
技能庫版本以 `Skills/skills-registry.yml` 的 `version` 為準。

## [Unreleased]

### Changed — 平台改由 registry 定義

- registry `platforms` 新增 `label`、`skills_dir`、`category_prefix`、`targets`、`references`、`language`、`forbidden_imports`、`snippet_docs`、`skill_list`（原平台層級的 `mc_versions` 改為 `targets`）；技能層級的 `mc_versions` 不變（也接受 `targets`）
- `sync-skill-references.mjs`、`sync-catalog.mjs`、`extract-skill-java.mjs` 不再寫死 `nms`／`paper` 兩個平台：參考檔、技能資料夾、計數、README／CLAUDE.md 數量句、版本標記（`// @<非預設版本>:`）、禁止的 import 與片段檢查目錄都從 registry 讀取；抽出的範本與重構前逐檔相同
- 網站 `PLATFORMS` 加上 `skillsDir`、`categoryPrefix`，平台判斷改查表；新測試檢查與 registry 一致。`sync-catalog --check` 另檢查技能分類是否以平台的 `category_prefix` 開頭、技能的 `platform` 是否存在
- CLAUDE.md 新增「新增平台」步驟

## [7.6.0] - 2026-11-28

技能庫 registry 版本 `7.4.0` → `7.6.0`（7.5.0 paper-folia-scheduler、7.6.0 paper-display-entities）。共 39 個技能（NMS 16、Paper API 23）。

### Changed — 文件跟上實機檢查與 template.md

- README（8 種語言）：特色新增「實機檢查」（不需玩家在線的技能在真的 Paper／Folia 伺服器上執行，情境在 `verify/runtime/`）；How it works 說明長技能的範本在 `template.md`；目錄結構補上 `template.md`、`verify/runtime/` 與新腳本；貢獻步驟改為加 README 目錄列並執行 `sync-catalog.mjs`（取代已不需手改的 `skills-api.data.test.ts`），新增 `SKILL.md` 約 800 行上限與選用的實機情境步驟
- 網站 FAQ（中英）：版本問答補上實機檢查；貢獻問答補上 `template.md` 與 `sync-catalog.mjs`
- CLAUDE.md：目錄結構註明 Paper API 技能的長範本另有 `template.md`

### Added — 自動化與檢查

- `scripts/sync-catalog.mjs`：依 `Skills/skills-registry.yml` 改寫 README（8 種語言）、CLAUDE.md、網站文案與文件頁中的技能數量與 registry 版本；`--check` 另外檢查技能資料夾、兩份 registry、網站頁（含 frontmatter 版本／分類／`mcVersions`）、README 目錄列、Skills Index 與 paper-api 技能清單，由 `web/tests/catalog.test.ts` 執行；網站測試的預期值改為直接讀 registry
- 片段 import 檢查：抽取腳本列出沒有 `package` 的程式片段（含 `docs/paper-nms/`）的 import，`verify` 的 `checkSnippetImports` 依各版本 classpath 確認類別存在，加入 `skills-compile.yml`
- `skills-canary.yml`：每週一（或手動）讀 Paper 最新 dev bundle，比固定版本新時（新 build 或下一版 beta）先編譯並檢查片段，不擋 PR；`verify` 支援 `-Pbundle=<版本>`
- registry `mc_versions` 正式化：抽取腳本依此跳過不支援的版本，網站技能頁顯示支援的 Minecraft 版本（frontmatter `mcVersions`）
- `scripts/lib/registry.mjs`：不依賴 YAML 套件的 registry 讀取，供上述腳本與網站測試共用
- 實機檢查（`verify/runtime/`、`scripts/runtime-check.mjs`、`skills-runtime.yml`）：從抽出的範本建出插件，在真的 Paper 伺服器上執行並檢查行為；情境可設 `"server": "folia"` 改在 Folia（Fill API 最新 build）上執行，`Probe` 改用 `GlobalRegionScheduler` 以便兩種伺服器共用。14 個情境：26.3 跑 14 個共 207 項檢查、1.21.11 跑 13 個共 199 項（`paper-world-clock` 只有 26.3），涵蓋 `paper-disposable-world`、`paper-data-components`、`paper-registry-events`、`paper-custom-recipes`、`paper-datapack-bundling`、`paper-world-clock`、`paper-service-api`（兩個插件跨 class loader、Wallet 未安裝時的退化）、`paper-sqlite-repository`（`libraries:` 下載驅動、遷移、WAL、寫入延遲、關服最後一次寫入、較新的 schema 拒絕啟動）、`paper-embedded-http`（真的 HTTP 請求：ETag／304、405、404、429 限流、BOM、停用後釋放連接埠）、`paper-brigadier-command`（註冊、別名、reload、錯誤訊息、k/m/b 補全）、`paper-folia-scheduler`（同一個 jar 在 Paper 與 Folia 上跑）、`paper-display-entities`（Paper 與 Folia，兩次啟動）；改到這些技能時與每週一執行，不擋 PR。執行時會接受 Minecraft EULA（`--accept-eula`）

### Fixed — paper-datapack-bundling 1.0.1

- 實機測試發現：`pack.mcmeta` 的版本範圍不含伺服器時，資料包**不會**被拒絕，仍會載入並自動啟用，log 也沒有任何訊息；`discoverPack` 只有在 `pack.mcmeta` 缺少或無效時才回傳 `null`。修正原本「版本不符會回傳 `null`」的說明與 Fallback 表
- 範本在 `getCompatibility()` 不是 `COMPATIBLE` 時記錄警告，伺服器升級超出 `max_format`（26.4 快照已是 125 以上）時提醒重新測試再調整範圍

### Added — paper-display-entities（registry 7.6.0，共 39 個技能）

- `paper-display-entities`（分類 `paper-display`）：`TextDisplay` 全息字、`ItemDisplay` 插值旋轉、只給單一玩家看的全息字；`Holograms` 以非持久化實體＋插件保存定義確保永遠只有一份：`ChunkLoadEvent` 重建、`EntitiesUnloadEvent` 移除、`EntitiesLoadEvent` 與生成前清掉 PDC 標記的殘留；範例含排行榜全息字與商店展示／任務標記
- 實機驗證（Paper 與 Folia 的 26.3、1.21.11）：區塊卸載後立刻重新載入時，Paper 丟棄了實體而 Folia 把同一個實體帶回來，因此範本在 `EntitiesUnloadEvent` 主動移除；重啟後持久化殘留被移除只剩一份；`setTeleportDuration` 超過 59 丟出 `IllegalArgumentException`

### Added — paper-folia-scheduler（registry 7.5.0，共 38 個技能）

- `paper-folia-scheduler`（新分類 `paper-threading`）：以 `RegionScheduler`、`EntityScheduler`（retired 回呼）、`GlobalRegionScheduler`、`AsyncScheduler` 取代 `BukkitScheduler`，同一份程式在 Paper 與 Folia 執行；`Tasks` 外觀類別把延遲限制在至少 1 tick、停用時取消重複任務；範例含移植對照表與 Folia 上的競技場倒數
- 實機驗證（Paper 與 Folia 的 26.3、1.21.11，同一個 jar）：Folia 上 `BukkitScheduler.runTask` 丟出 `UnsupportedOperationException`、`Entity#teleport` 即使移動 1 格也丟出「Must use teleportAsync while in region threading」；兩者延遲 0 都丟出 `IllegalArgumentException`；實體被移除時待執行任務改跑 `retired`；`teleportAsync` 後實體排程器在新位置的執行緒執行

### Fixed — paper-brigadier-command 1.0.1

- 實機測試發現：其他插件以 `Bukkit.dispatchCommand`／`Player#performCommand` 執行指令時，自訂參數型別 `convert` 丟出的 `CommandSyntaxException` 不會傳給執行者，而是包成 `CommandException` 丟給呼叫端（26.3、1.21.11 皆同）；Fallback 表新增此列。`requirePlayer` 說明補上 26.x 由 `getPlayerOrThrow()` 回覆原版訊息「A player is required to run this command here」

### Changed — 長範本拆到 template.md

- `paper-discord-bridge`：`SKILL.md` 由 2136 行縮為 176 行，完整範本（11 個類別、2 個 JUnit 測試、`config.yml`／`plugin.yml`）移到同目錄 `template.md`；`SKILL.md` 的 Code Template 改為「先讀 `template.md`」指示與檔案索引表。技能 ID、registry、網站頁面不變
- `scripts/extract-skill-java.mjs`：`SKILL.md` 與 `template.md` 合為同一組編譯；`scripts/sync-skill-references.mjs` 與 `skill-references` 測試也會掃描 `template.md` 的 `references/` 連結
- 其餘 7 個超過 800 行的技能同樣拆分（`SKILL.md` 行數 → 現在）：`paper-economy-ledger` 1458 → 176、`paper-combat-tag` 1295 → 155、`paper-disposable-world` 1293 → 162、`paper-packetevents-filter` 1058 → 159、`paper-softdepend-hook` 960 → 173、`paper-client-side-effects` 844 → 135、`paper-chest-gui` 814 → 163。規則類小節（Core Rules、The Listener Trap 等）留在 `SKILL.md`；examples.md 中「類別在 SKILL.md」的說明改指 `template.md`
- CLAUDE.md：新增長範本拆檔規則

### Changed — 說明技能更新方式

- README（8 種語言）與網站「開始使用」：補上 `npx skills check`、從 Release tag 安裝（`@v7.6.0`），並說明技能不會自動更新，`check` 比對的是 Git commit 而非版本號

## [7.4.0] - 2026-11-23

技能庫 registry 版本 `7.0.0` → `7.4.0`（7.1.0 paper-discord-bridge、7.2.0 目標版本改為 Paper 26.3 + 1.21.11、7.3.0 與 7.4.0 新增 26.x 技能）。共 37 個技能（NMS 16、Paper API 21）。

### Fixed — 文件與網站跟上 26.3 更新

- `nms-version-adapter` 範例：`adapter-v26_2` 模組誤用 26.3 dev bundle（26.3 更新時的批次替換造成），改回 `26.2.build.135-stable`；`adapter-v26_3` 由 beta 改為 `26.3.build.258-stable`
- `docs/paper-nms`：`bukkit-nms-bridge` 版本表改為 1.21.11／26.3；`entities` 補上 26.2 起的史萊姆系階層（`cubemob`、`AbstractCubeMob`）與 26.2 新增 Attribute、`KNOCKBACK_RESISTANCE` 新範圍；`packets` 新增「26.3 新增」封包表，並註明 26.3 的 `ClientboundAnimatePacket` 已沒有揮手動作（`PLATFORM.md` 同步）
- README（8 種語言）：Gradle 需求改為 9.7.1+；相容性表新增 Spigot 列與 `paper-world-clock` 僅 26.3 的例外
- 網站：頁尾版本改為 1.21.11 · 26.3；FAQ 新增「可以用在 Spigot 嗎？」並註明 world-clock 例外；`web/README.md` 依賴表改為實際套件（Next.js 16.4.6、unified／rehype／Shiki 管線），`npm audit` 0 個漏洞；新增技能步驟補上英文內文、平台數與像素字型；移除已無作用的 `postcss-selector-parser` override

### Added — paper-custom-recipes、paper-menu-type-gui、paper-datapack-bundling、paper-world-clock（registry 7.4.0，共 37 個技能）

- `paper-custom-recipes`（paper-gameplay）：有形狀／無形狀合成、`RecipeChoice.itemType`、`TransmuteRecipe` 升級保留附魔、26.3 `BrewingRecipe`（1.21.11 `PotionMix`）；26.3 改用 `addRecipes`／`removeRecipes` 與 `RecipeChoice.exactChoice`（舊寫法在 26.3 淘汰）。實機發現 `/minecraft:reload` 會清掉插件配方，範本在 `ServerResourcesReloadedEvent` 重新註冊
- `paper-menu-type-gui`（paper-ui）：MenuType 可攜式工作台、鐵砧（花費上限）、切石機等與自訂交易；依 Paper 原始碼說明方塊實體類介面沒有 `location` 時是不運作、不存檔的暫存方塊實體（需要玩家客戶端，未實機）
- `paper-datapack-bundling`（paper-data）：bootstrap 以 `DATAPACK_DISCOVERY` 從 jar 載入資料包並自動啟用（預設不啟用）、`pack.mcmeta` 範圍 1.21.11 = 94.1、26.3 = 121.0、依版本分資料夾
- `paper-world-clock`（paper-world，**僅 26.3**）：每個世界暫停、流速、固定時間與 `TimeSkipEvent` 時間鎖；實機確認時鐘狀態每個世界獨立且重啟後保留、地獄沒有時鐘
- 三個可實機的技能在 Paper 26.3 build 258 驗證 27 項（含真實釀造台、重啟、reload）；四個技能皆通過 1.21.11／26.3 編譯（`paper-world-clock` 只編譯 26.3）
- registry、`.claude/skills/`、網站頁（繁中＋英文）、README 目錄（8 種語言）、`PLATFORM.md` 技能清單、CLAUDE.md、測試、像素字型子集同步更新

### Added — paper-registry-events、paper-data-components（registry 7.3.0，共 33 個技能）

- 新技能 `paper-registry-events`（paper-gameplay）：在 `PluginBootstrap` 以 `RegistryEvents` 新增自訂附魔（吸血）與傷害類型（流血）、用 `entryAdd` + `filter` 修改原版項目（鋒利上限 6）、用 `LifecycleEvents.TAGS.postFlatten` 加入附魔台／交易／`bypasses_armor` 標籤，執行期以 `RegistryAccess` 取回；附 1.21.11／26.3 可用註冊表對照表。已在 Paper 26.3 實機驗證 18 項（含 `/minecraft:reload` 後標籤仍在、流血對全套鑽石裝造成完整傷害）
- 新技能 `paper-data-components`（paper-gameplay）：Paper Data Component API 自訂物品（食物＋可食用效果、武器屬性、滑翔、提示框、冷卻），`setData`／`unsetData`／`resetData` 與原型值、PDC id 識別、`matchesWithoutData` 忽略耐久比對；附 1.21.11 → 26.3 元件增刪表。已在 Paper 26.3 實機驗證 27 項
- `nms-data-component`、`nms-custom-menu`、`nms-reflection-bridge` 調整觸發關鍵字，一般需求改由 Paper API 技能（`paper-data-components`、`paper-chest-gui`）命中；`nms-reflection-bridge` 說明改為以 Spigot／26.1 以前伺服器相容為主要用途
- registry、`.claude/skills/`、網站頁（繁中＋英文）、README 目錄（8 種語言）、`PLATFORM.md` 技能清單與技能數量同步更新；像素字型子集重新產生

### Security — 升級 Next.js 至 16.4.6

- Web 依賴 Next.js 16.4.0 → 16.4.6（`npm audit`：16.0.0–16.4.4 受影響，含 GHSA-2464-fhr4-w9xw 圖片最佳化 RCE、GHSA-p3m5-59mp-jv5r、GHSA-673q-mjm6-r5xr 等）；升級前 `next build` 會因 SecurityFatalError 中止，升級後 tsc、測試、靜態匯出皆通過

### Changed — 目標版本改為 Paper 26.3 + 1.21.11（registry 7.2.0）

- 驗證目標由 26.2／1.21.11 改為 **26.3／1.21.11**：dev bundle／paper-api `26.3.build.258-stable`、`api-version: '26.3'`、Javadoc 連結、CI matrix、`verify/`、抽取腳本預設版本同步更新；26.2 不再驗證（`PLATFORM.md` 列出 26.2 → 26.3 的差異）
- 建置基準改為 Gradle 9.7.1+（paperweight userdev 2.0.0-beta.24 的最低需求；原寫 8.11.2+ 有誤）
- `verify/` 編譯改開 `-Xlint:deprecation,removal`；26.3 與 1.21.11 皆零錯誤、零淘汰警告
- 每個 `SKILL.md` 與網站頁新增 **Spigot** 相容性（僅 Paper／核心可移植／Spigot 可用），`PLATFORM.md` 新增 Spigot 相容段落（對照 Spigot-API 26.3 Javadoc）

### Fixed — 26.3 不相容與淘汰 API

- `nms-fake-player`：26.3 移除 `LivingEntity#swing(InteractionHand)`，改用 `swing(hand, SwingAnimation.DEFAULT, false)`（26.3 原本編譯失敗）
- `nms-packet-interceptor`：26.3 以 `ServerboundPunchPacket` 取代 `ServerboundSwingPacket`
- `paper-combat-tag`：`PotionEffectTypeCategory`（26.3 標記 for removal）改用 `PotionEffectType#getEffectCategory()`
- `paper-chest-gui`：移除 `InventoryAction.HOTBAR_MOVE_AND_READD`；`ItemFlag.HIDE_ADDITIONAL_TOOLTIP` 改用 `TOOLTIP_DISPLAY` 資料元件
- `paper-disposable-world`：移除無作用的 `keepSpawnLoaded`、`shouldGenerateBedrock`；`Block#getBlockKey` 改為自行打包座標
- `paper-disposable-world`：修正 26.x 存檔路徑。26.x 把 `WorldCreator` 建立的世界存在 `<主世界>/dimensions/minecraft/<名稱>/`，沒有 `level.dat`，世界 UUID 改存在 `data/paper/metadata.dat`；原範本照舊版結構去世界容器找資料，導致崩潰殘留永遠清不掉、從 26.x 世界做的範本一律被拒。改為 `WorldFolders.dataRoot` 依版本決定路徑，範本以 `region/` 判斷，複製時略過 UUID 檔，並改用 `.copying` / `.deleting` 原子改名取代「`level.dat` 最後寫／最後刪」。已在 Paper 26.3（build 258）實機驗證 18 項檢查
- `paper-client-side-effects`：`WorldBorder#setSize(double, long)` 改用 `changeSize(double, long)`
- `docs/paper-nms/packets.md`：`ClientboundExplodePacket` 補上 26.3 新增的 `playSound` 參數；揮手封包改寫為 26.3／1.21.11 兩版

### Added — 26.3 新內容

- `paper-client-side-effects` 範例 4：26.3 `Player#postEffects()` 畫面後製效果（會存進玩家資料，需自行移除）
- `nms-data-component`：26.2 → 26.3 元件增刪表（由兩版 dev bundle 比對），並說明優先使用 Paper Data Component API

### Added — paper-discord-bridge（registry 7.1.0，共 31 個技能）

- 新技能 `paper-discord-bridge`（paper-integration）：只用 JDK `HttpClient`／`WebSocket` 的 Discord 雙向聊天橋接，不需 JDA 或 DiscordSRV。遊戲 → Discord 以 webhook 佇列送出（daemon 執行緒、429 照 `Retry-After` 重送一次、`allowed_mentions` 為空、Markdown／提及跳脫）；Discord → 遊戲以純邏輯 Gateway 狀態機（HELLO、抖動心跳、IDENTIFY／RESUME、op 7／9、致命關閉碼、退避）在單一執行緒運作，入站回主執行緒、經 `AccountLinks` 檢查後送進聊天；token 與 webhook 網址不進 log
- 兩種用法：只送出（webhook，無 bot）與雙向（bot token + Message Content Intent）；examples 含死亡／成就公告、僅限已連結帳號發言、管理員頻道
- 改寫自 Bydsmp 的 DiscordBridge 插件，Bydsmp 專屬依賴改為可替換介面
- registry、`.claude/skills/`、網站頁（繁中＋英文）、README 目錄（8 種語言）與技能數量同步更新；像素字型子集重新產生

### Changed — 技能內文改為英文

- 30 個技能的 `SKILL.md`、`examples.md` 內文、程式碼註解與字串改為英文；frontmatter `description` 與觸發關鍵字（Triggers 段落）維持中英並陳，中文提問仍可觸發。逐檔比對確認程式碼未變動（只改註解與字串），並通過 26.2／1.21.11 編譯
- `Skills/paper-*/PLATFORM.md` 與 `Skills/_shared/*.md` 改為英文（`references/` 隨之更新）；原中文版移至 `web/data/docs/zh/`，網站繁中頁面不變
- 網站文件載入改為依 `registry.ts` 的 `sourceLang` 選擇來源或翻譯（`docs/paper-nms/` 仍以中文為來源）

### Added — 範本編譯 CI

- `scripts/extract-skill-java.mjs`：從 SKILL.md／examples.md 抽出完整 Java 檔（含 `package`），依目標版本套用 `// @1.21.11:` 與 `// @only`；Paper API 技能若 import NMS 直接失敗
- `verify/`：Gradle 9.8.1 + Paperweight 專案，每個技能（與其 examples）各自一個 source set，對 26.2（JDK 25）與 1.21.11（JDK 21）dev bundle 加上軟依賴、JUnit API 編譯
- `.github/workflows/skills-compile.yml`：改到 `Skills/`、`verify/` 時於 PR 與 main 執行雙版本編譯；README 加上狀態徽章
- 提交 `web/AGENTS.md`（`next dev` 產生的 Next.js 16 代理提示）

### Removed — 歷史殘留

- 刪除不再維護的 `.cursor/`（舊版 Cursor 規則與 8 個早期技能）
- 刪除 `docs/` 下舊的四平台文件（`paper/`、`purpur/`、`velocity/`、`waterfall/`、`CONVENTIONS.md`、`README.md`）；內容過時（如已棄用的 `com.github.johnrengelman.shadow`），且未被網站或技能引用。NMS 速查表 `docs/paper-nms/` 保留

### Added — `npx skills add` 安裝

- 支援 [skills CLI](https://github.com/vercel-labs/skills)：`npx skills add MrPippi/MJP-Paper-Skills`（可用 `--skill <id>` 只裝單一技能）
- 每個技能新增 `references/`，內附該技能需要的 `PLATFORM.md` 與執行緒／命名規則副本，單獨安裝也完整可用；由 `scripts/sync-skill-references.mjs` 產生，`web/tests/skill-references.test.ts` 檢查是否同步
- `SKILL.md`／`examples.md` 中指向技能資料夾外的連結（`../../_shared/…`、`Skills/paper-*/PLATFORM.md`）改為 `references/<檔名>`
- README（8 種語言）與網站「開始使用」、首頁終端機改以 `npx skills add` 為主要安裝方式，手動複製收合為備選

### Changed — 專案改名與定位

- 專案更名 **MJP-Claude-Skills → MJP-Paper-Skills**；目標使用者擴大到任何支援 Agent Skills（`SKILL.md`）的 AI 編碼工具（Claude Code、OpenAI Codex、Cursor、GitHub Copilot、Gemini CLI…），不再限定 Claude Code
- README（8 種語言）：快速開始改為各工具的 Skills 目錄對照，並提供不支援 Agent Skills 時的 `AGENTS.md` 寫法；30 列 Skills 表格改為摘要加網站目錄連結
- 網站文案、標題、站名同步更新
- 移除不再使用的 `web/vercel.json`（改由 GitHub Pages 部署）


### Web（網站重新設計）

#### Changed
- 改為文件中心架構：`/docs`（總覽、開始使用、平台、核心概念、Skills、NMS 速查表），三欄版面（側欄導覽／內文／本頁目錄）
- 視覺改為 Claude 風格：淺色象牙白 + 陶土橘、深色暖炭，跟隨系統並可手動切換；Source Serif 標題、Silkscreen 像素字點綴
- Minecraft 點綴：SVG 像素圖示（分類、平台）、首頁像素地景、像素按鈕陰影與分隔線；可在 `web/config/mc-assets.ts` 換成遊戲截圖與物品圖
- 程式碼區塊改為建置時 Shiki 雙主題高亮（零執行時高亮 JS），附語言標籤與複製鈕
- 搜尋同時涵蓋 Skills 與文件頁；技能列表支援 `?platform=`、`?category=` 篩選

#### Fixed
- 切換語言後首頁「三步驟」永遠透明：步驟用標題當 React key，換語言時元素被替換而沒被捲動進場監看；改用穩定 key，並以 MutationObserver 監看之後新增的進場元素
- 捲動進場提早 20% 觸發、已捲過的元素直接顯示，快速捲動與整頁截圖不再拍到空白；列印時強制顯示全部內容
- 首頁終端機最後一行被打字動畫的 `clip-path` 裁掉（`generatec`）：改為可換行，動畫結束不再裁切

#### Added
- 動態效果：首頁終端機逐行打字、雲朵飄移、太陽／月亮浮動、樹葉搖曳、花粉／螢火蟲；捲動進場；卡片 hover 物品彈跳與格子高亮；複製成功彈跳；主要按鈕經驗值球粒子；主題切換圓形日夜過場。全部遵守 `prefers-reduced-motion`
- 像素字型改為俐方體 11 號（Cubic 11，OFL）子集（約 630 字、28 KB），中英文點綴文字一致像素化；取代只有拉丁字母的 Silkscreen；產生腳本 `web/scripts/pixel-font.py`
- 原版材質（26.3 資源包）：13 個物品圖示、首頁方塊地景改用真實貼圖，圖示外框改為物品欄格子；擷取腳本 `web/scripts/mc-art.py`
- `Skills/*/PLATFORM.md`、`Skills/_shared/*.md`、`docs/paper-nms/*.md` 直接渲染為網站頁面（單一來源，不複製）

#### Deprecated
- 舊網址 `/skills/*`、`/categories/*`、`/guide` 改為轉址頁（noindex），導向新路由

## [7.0.0] - 2026-10-09

技能庫 registry 版本 `6.0.0` → `7.0.0`（major：目標平台改為 Paper 1.21.11／26.2、範本 API 不相容變更、新增 `paper-api` 平台）。共 30 個技能。

### Paper API 技能（PR #16）

依 BlockoSMP 與 Bydsmp 插件集的實際模式新增技能；全部範本、examples 中的完整類別與 JUnit 測試，都對 Paper **1.21.11** 與 **26.2** 實際編譯，測試實際執行。

#### Added

- **Paper API 技能平台**：`Skills/paper/`、`Skills/paper-api/PLATFORM.md`（paper-api compileOnly、軟依賴座標）、`Skills/_shared/paper-threading.md`、registry `paper-api` 平台
- 14 個 Paper API 技能：`paper-dialog-ui`、`paper-chest-gui`、`paper-sqlite-repository`、`paper-config-lang`、`paper-service-api`、`paper-softdepend-hook`、`paper-embedded-http`、`paper-packetevents-filter`、`paper-client-side-effects`、`paper-combat-tag`、`paper-safe-teleport`、`paper-economy-ledger`、`paper-brigadier-command`、`paper-disposable-world`
- NMS 技能 `nms-fake-player`：沒有客戶端的 `ServerPlayer` 假玩家（機器人），NMS 限定單一套件、版本不符時只停用該功能
- 網站新增 15 個技能頁、NMS／Paper 分類圖示

#### Changed

- `nms-packet-interceptor`、`nms-packet-sender`、`nms-custom-menu`、`nms-player-profile`、`nms-custom-entity` 新增「替代方案／相關技能」段落（優先使用 Paper API、PacketEvents、Dialog）
- `Skills/paper-nms/PLATFORM.md` 新增「NMS 隔離與版本守門」規範
- CLAUDE.md、README（8 種語言）、網站文案改為「NMS + Paper API」兩大類，共 30 個技能
- Web 測試改為依資料動態計算數量；搜尋測試只鎖定必含結果

### 依賴升級、範本修正與 1.21.11／26.2 雙版本（PR #14、#15、#17）

涵蓋 PR [#14](https://github.com/MrPippi/MJP-Claude-Skills/pull/14)（依賴升級、安全修補、範本修正）與
PR [#15](https://github.com/MrPippi/MJP-Claude-Skills/pull/15)（移植至 Paper 26.2）。

#### ⚠️ Breaking

- **目標平台由 Minecraft 1.21 – 1.21.3 改為 Paper 1.21.11 與 26.2 雙版本**（#15）
  - 所有範本同時對 `1.21.11-R0.1-SNAPSHOT`（Java 21）與 `26.2.build.132-stable`（Java 25）編譯驗證；預設寫 26.2 API，1.21.11 不同處以行尾 `// @1.21.11:` 或區塊 `// @only <版本>` 標註
  - 26.x dev bundle 版本格式為 `<mc>.build.<n>-<channel>`
  - Minecraft 26.1 起原版不再混淆；文件改稱「Mojang 官方名稱」，Spigot 命名的外掛在 26.x 無法執行
  - 1.21.x 版範本不再維護，保留於 git 歷史
- **範本 API 跟隨 26.x 變更**（#15），既有依 1.21 範本產生的程式碼需比對調整：
  - `ResourceLocation` → `Identifier`；`EntityType.XXX` → `EntityTypes.XXX`；`Entity.moveTo()` → `snapTo()`；`Level.isClientSide` → `isClientSide()`
  - BlockEntity／Entity 序列化改用 `ValueOutput` / `ValueInput`；`CompoundTag.getXxx()` 回傳 `Optional`
  - `Component.Serializer` 移除，改用 `PaperAdventure.asVanilla()` / `asAdventure()`
  - authlib `GameProfile` 改為 record（`id()` / `name()` / `properties()`）；profile 查詢改用 `MinecraftServer.services()`
  - `ServerBossEvent` 建構子需 UUID；粒子封包新增 `alwaysShow`；`DiscardedPayload(Identifier, byte[])`
  - `DataComponents.UNBREAKABLE` 為 `Unit`（提示顯示改由 `TOOLTIP_DISPLAY` 控制）；`CustomModelData` 改為清單，`ItemComponentUtil.getCustomModelData()` 回傳 `Optional<Float>`
  - `nms-version-adapter` 範例改為 `V26_2` / `V26_3` adapter，版本偵測改比對 `major.minor`
- `AttributeUtil.removeModifier(..., UUID)` 已移除，modifier id 一律為 `Identifier`（#14）
- `ScoreboardManager.apply()` / `setLine()` / `removeLine()` / `remove()` 現在會實際送出計分板封包（原本 `apply()` 不會讓 sidebar 顯示）（#14）

#### Added

- `CustomMenuHolder` 範本：作為 container owner，讓 `InventoryClickEvent` 能辨識自訂 GUI；`CustomMenu` 新增 `(syncId, inventory, CustomMenuHolder)` 建構子（#14）
- Web：以 `node:test` + `tsx` 撰寫的 characterization tests（`npm test`），CI 於 push 與 PR 執行（新增 `.github/workflows/ci.yml`）（#14）
- `CHANGELOG.md`；README 新增依賴說明
- 多語 README：简体中文、日本語、한국어、Español、Português (BR)、Русский，各 README 頂端提供語言切換列

#### Changed

- 所有 SKILL.md 範本 class 經 dev bundle 實際編譯驗證（26.2：42 個 class 0 錯誤）；docs／examples 片段經自動包裝編譯檢查（#14、#15）
- `Skills/paper-nms/PLATFORM.md`：版本對照表、`api-version: '26.2'`、Javadoc 26.2、1.21 → 26.x 遷移重點（#15）
- Paperweight userdev 1.7.2 → `2.0.0-beta.24`（需 Gradle 8.11.2+）（#14）
- 10 個先前僅存在 `Skills/` 的技能同步至 `.claude/skills/`；CLAUDE.md 移除不存在的 `AGENTS.md` / `.agents/` 描述（#14）
- Web 依賴：Next.js 16.1.6 → 16.4.0、React 19.2.3 → 19.3.0、TypeScript 5.9.3 → 6.0.3、Tailwind 4.2 → 4.3.3、fuse.js 7.1 → 7.5、`@types/node` ^20 → ^24（#14）
- CI：Node 20 → 24；GitHub Actions 升至 checkout@v7、setup-node@v7、configure-pages@v6、cache@v6、upload-pages-artifact@v5、deploy-pages@v5（#14）
- robots.txt / sitemap.xml 統一由 `app/robots.ts`、`app/sitemap.ts` 產生，sitemap 新增 `/guide`（#14）
- Web 技能頁的範例程式碼對齊範本實際 API（#14、#15）

#### Fixed

- 範本 import 不存在的 `org.bukkit.craftbukkit.v1_21_R1`（Paper 1.20.5+ 已無版本號 relocation）（#14）
- 物品 NBT 改用 `DataComponents.CUSTOM_DATA`；玩家頭顱改用 `DataComponents.PROFILE`（#14）
- `nms-reflection-bridge`：`MethodHandleCache` 會往父類查找方法／欄位（原本 `send()` 在執行期找不到）（#14）
- PLATFORM.md 範本無條件 `dependsOn 'shadowJar'` 導致未套用 shadow 時 `assemble` 失敗（#14）
- `vercel.json` 的 `outputDirectory` 改為 `out`（#14）
- 文件中錯誤的類名與 API：`ClientboundExplodePacket`、`ClientboundDisconnectPacket` 套件、`CraftItemStack.unwrap()`、`ServerboundInteractPacket` 等（#14、#15）

#### Security

- 修補 Next.js critical（request smuggling 等）與 sharp、postcss、js-yaml、nanoid、source-map-js 等 high／moderate 漏洞；`npm audit` 由 12 個（1 critical）降至 4 個 moderate（`sprintf-js`，上游無修補）（#14）
- 技能 Markdown 改用 remark-html 預設 sanitize；`getSkillBySlug` 只接受 kebab-case slug，防止路徑穿越（#14）

#### Removed

- 未使用的依賴：`rehype-autolink-headings`、`rehype-highlight`、`rehype-slug`、`reading-time`（#14）
- `web/scripts/generate-static-metadata.ts` 與提交在 repo 中的 `public/robots.txt`、`public/sitemap.xml`（#14）

## [6.0.0] - 2026-05-01

### Added

- 新增 10 個 NMS 技能（nbt-manipulation、custom-menu、scoreboard、player-profile、particle-effect、attribute-modifier、block-entity、data-component、chunk-access、boss-event），共 15 個（PR #12）
- Web：zh-TW / EN 語言切換、英文標題翻譯與樣式更新（PR #13，2026-05-23，未變更 registry 版號）
