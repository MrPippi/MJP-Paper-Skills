<div align="center">

# MJP Skills — Minecraft Java Plugin Skills

**コンパイル検証済みの、Minecraft Paper プラグイン開発向け Agent Skills**

Mojang 公式名称を使う低レベルな NMS と純粋な Paper API に対応。`SKILL.md` を読み込むあらゆる AI コーディングツールで利用できます

[![Paper](https://img.shields.io/badge/Paper-1.21.11%20%7C%2026.3-2ea44f)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21%20%7C%2025-orange)](https://adoptium.net)
[![Skills](https://img.shields.io/badge/skills-39-blue)](https://mrpippi.github.io/MJP-Paper-Skills/docs/skills)
[![Agent Skills](https://img.shields.io/badge/format-Agent%20Skills-8a63d2)](https://agentskills.io)
[![Skills compile](https://github.com/MrPippi/MJP-Paper-Skills/actions/workflows/skills-compile.yml/badge.svg)](https://github.com/MrPippi/MJP-Paper-Skills/actions/workflows/skills-compile.yml)
[![License: MIT](https://img.shields.io/github/license/MrPippi/MJP-Paper-Skills)](LICENSE)

[**ドキュメント**](https://mrpippi.github.io/MJP-Paper-Skills) · [**スキルカタログ**](https://mrpippi.github.io/MJP-Paper-Skills/docs/skills) · [**変更履歴**](CHANGELOG.md)

[English](README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md) · 日本語 · [한국어](README.ko.md) · [Español](README.es.md) · [Português (BR)](README.pt-BR.md) · [Русский](README.ru.md)

</div>

---

AI コーディングツールは、Paper プラグインで細かな間違いをしがちです。古い、あるいは難読化された NMS 名称、Netty スレッドや非同期スレッドからの Bukkit 呼び出し、バージョン間で変更された API などです。MJP-Paper-Skills は、検証済みのプレイブックをエージェントに提供します。各スキルは `SKILL.md` であり、コードテンプレート、ビルド設定、スレッドのルール、フォールバックを含み、エージェントはコードを書く前にそれを読み込みます。

## 特長

- **コンパイル検証済み**：CI が変更のたびに、完成したすべてのテンプレートを Paper **1.21.11** と **26.3** の両方でコンパイルします。バージョン固有の行はインラインで明示されています。
- **実機チェック済み**：プレイヤーがいなくても動作を確認できるスキル（ワールド、レシピ、アイテムコンポーネント、レジストリ、コマンド、ストレージ、Folia スケジューラー）は、実際の Paper サーバーでも実行し、必要に応じて Folia でも実行します。シナリオは [`verify/runtime/`](verify/runtime) にあります。
- **Mojang 公式名称**：NMS コードは Paperweight userdev と、26.1 以降 Minecraft が難読化せずに提供している名称を使います。
- **スレッドセーフな設計**：各スキルは、すべての呼び出しがどのスレッド（メイン、Netty IO、非同期）で実行されるかを明記しています。
- **ツールに依存しない**：オープンな [Agent Skills](https://agentskills.io) 形式は、Claude Code、OpenAI Codex、Cursor、GitHub Copilot、Gemini CLI などで動作します。
- **2 つのトラック**：低レベルな作業向けの NMS スキル 16 個と、`paper-api` だけで動作する Paper API スキル 23 個があります。
- **読みやすいドキュメント**：すべてのスキルは[ドキュメントサイト](https://mrpippi.github.io/MJP-Paper-Skills)でも公開されており、英語と繁体字中国語で読めます。

## 目次

- [クイックスタート](#クイックスタート)
- [スキルカタログ](#スキルカタログ)
- [仕組み](#仕組み)
- [互換性](#互換性)
- [リポジトリ構成](#リポジトリ構成)
- [コントリビュート](#コントリビュート)
- [ライセンス](#ライセンス)

---

## クイックスタート

### 1. スキルをインストールする

[skills CLI](https://github.com/vercel-labs/skills) で、このリポジトリから直接インストールできます。必要なのは `npx` 用の [Node.js](https://nodejs.org) だけで、アカウントや登録は不要です。

1. プラグインプロジェクトのルートフォルダで次を実行します。

   ```bash
   npx skills add MrPippi/MJP-Paper-Skills
   ```

2. インストールするスキルを選びます。CLI は AI ツール（Claude Code、Codex、Cursor など）を検出し、どのツール向けにインストールするか、およびシンボリックリンク（推奨）とコピーのどちらにするかを尋ねます。
3. `npx skills list` で結果を確認します。

各スキルは、必要なビルド設定とスレッドのルールを自身の `references/` フォルダに同梱しているため、スキルを 1 つだけインストールしても動作します。

| 目的 | コマンド |
|------|---------|
| 利用可能なスキルを一覧表示 | `npx skills add MrPippi/MJP-Paper-Skills --list` |
| スキルを 1 つインストール | `npx skills add MrPippi/MJP-Paper-Skills --skill paper-dialog-ui` |
| 指定したツール向けにすべてをプロンプトなしでインストール | `npx skills add MrPippi/MJP-Paper-Skills --skill '*' -a claude-code codex -y` |
| すべてのプロジェクト向けにインストール（例: `~/.claude/skills/`） | 任意の `add` コマンドに `-g` を付ける |
| 更新の有無を確認 | `npx skills check` |
| インストール済みのスキルを更新 | `npx skills update` |
| リリース版をインストール | `npx skills add MrPippi/MJP-Paper-Skills@v7.6.0 --skill paper-dialog-ui` |
| スキルを削除 | `npx skills remove paper-dialog-ui` |

スキルは自動では更新されません。`npx skills check` は `skills-lock.json` に記録された Git コミットとこのリポジトリの最新コミット（バージョン番号ではありません）を比較するため、`main` への新しいコミットはすべて更新として表示されます。検証済みのバージョンを使うには、[リリース](https://github.com/MrPippi/MJP-Paper-Skills/releases)のタグからインストールしてください。

<details>
<summary>手動インストール</summary>

`MJP-Paper-Skills/.claude/skills/` を、お使いの AI ツールがスキルを読み込むフォルダにコピーします。

| ツール | 一般的なプロジェクトパス |
|------|---------------------|
| Claude Code | `.claude/skills/` |
| OpenAI Codex、Gemini CLI | `.agents/skills/` |
| Cursor | `.cursor/skills/` |
| GitHub Copilot | `.github/skills/` |

```bash
git clone https://github.com/MrPippi/MJP-Paper-Skills.git
cp -r MJP-Paper-Skills/.claude/skills .agents/skills   # adjust the target for your tool
```

</details>

> [!NOTE]
> スキルのパスはツールやバージョンによって異なります。お使いのツールのドキュメントを確認してください。多くのツールは、共有の場所として `.agents/skills/` も読み込みます。

**Agent Skills に対応していないツールの場合は、** 指示ファイル（`AGENTS.md`、`.cursorrules`、`.github/copilot-instructions.md` など）からスキルを参照するようにします。

```markdown
Before writing Paper plugin code, find the matching skill in <skills-folder>/skills-registry.yml
(by trigger_keywords) and follow its SKILL.md, plus the files in its references/ folder.
```

### 2. 必要なことを依頼する

作りたい機能を普通の言葉で説明してください。ツールが、依頼内容を各スキルの説明とトリガーキーワードに照らして照合します。

```text
"パケットでプレイヤーにアクションバーのメッセージを送りたい"
"ServerboundChatPacket を傍受して、特定の単語をフィルターしたい"
"独自の追跡 AI を持つカスタム Zombie エンティティを作りたい"
"このプレイヤーだけに見えるワールドボーダーを表示したい"
```

---

## スキルカタログ

39 個のスキルはすべて、Paper 1.21.11 と 26.3 に対してコンパイル検証済みです。フィルターや完全なテンプレートは[ドキュメントサイト](https://mrpippi.github.io/MJP-Paper-Skills/docs/skills)で確認できます。機械可読なインデックスは [`Skills/skills-registry.yml`](Skills/skills-registry.yml) です。

### NMS（16 スキル、Paperweight userdev が必要）

| カテゴリ | スキル | 内容 |
|----------|-------|--------------|
| パケット | [`nms-packet-sender`](Skills/nms/nms-packet-sender/SKILL.md) | カスタムの Clientbound パケットを、1 人、グループ、または全員に送信 |
| パケット | [`nms-packet-interceptor`](Skills/nms/nms-packet-interceptor/SKILL.md) | Netty パイプラインでパケットを傍受・変更 |
| エンティティ | [`nms-custom-entity`](Skills/nms/nms-custom-entity/SKILL.md) | PathfinderGoal AI を持つカスタム NMS エンティティ |
| エンティティ | [`nms-attribute-modifier`](Skills/nms/nms-attribute-modifier/SKILL.md) | AttributeMap と AttributeModifier による動的な属性 |
| プレイヤー | [`nms-player-profile`](Skills/nms/nms-player-profile/SKILL.md) | NPC の外見のための GameProfile スキン注入 |
| プレイヤー | [`nms-fake-player`](Skills/nms/nms-fake-player/SKILL.md) | クライアントなしの ServerPlayer によるフェイクプレイヤー（ボット） |
| データ | [`nms-nbt-manipulation`](Skills/nms/nms-nbt-manipulation/SKILL.md) | アイテム、エンティティ、ブロックエンティティの CompoundTag NBT の読み書き |
| データ | [`nms-data-component`](Skills/nms/nms-data-component/SKILL.md) | DataComponentType によるアイテムコンポーネント |
| ワールド | [`nms-block-entity`](Skills/nms/nms-block-entity/SKILL.md) | NBT、ティック処理、クライアント同期を備えたカスタムブロックエンティティ |
| ワールド | [`nms-chunk-access`](Skills/nms/nms-chunk-access/SKILL.md) | LevelChunk と ChunkSection への直接的なブロックアクセス |
| ワールド | [`nms-particle-effect`](Skills/nms/nms-particle-effect/SKILL.md) | ClientboundLevelParticlesPacket によるパーティクル効果 |
| 表示 | [`nms-scoreboard`](Skills/nms/nms-scoreboard/SKILL.md) | スコアボード、オブジェクティブ、チーム |
| 表示 | [`nms-boss-event`](Skills/nms/nms-boss-event/SKILL.md) | ServerBossEvent によるプレイヤーごとのボスバー |
| UI | [`nms-custom-menu`](Skills/nms/nms-custom-menu/SKILL.md) | AbstractContainerMenu をベースにしたコンテナ GUI |
| ブリッジ | [`nms-reflection-bridge`](Skills/nms/nms-reflection-bridge/SKILL.md) | Paperweight を使わない、リフレクションベースの NMS アクセス |
| ブリッジ | [`nms-version-adapter`](Skills/nms/nms-version-adapter/SKILL.md) | マルチバージョン NMS 対応のためのアダプターパターン |

### Paper API（23 スキル、`paper-api` のみ必要）

| カテゴリ | スキル | 内容 |
|----------|-------|--------------|
| UI | [`paper-dialog-ui`](Skills/paper/paper-dialog-ui/SKILL.md) | コールバックをメインスレッドで処理する Dialog API 画面 |
| UI | [`paper-chest-gui`](Skills/paper/paper-chest-gui/SKILL.md) | ページ送りとクリック保護を備えた InventoryHolder チェスト GUI |
| UI | [`paper-menu-type-gui`](Skills/paper/paper-menu-type-gui/SKILL.md) | MenuType で持ち運べるバニラの作業台や取引画面を開く |
| データ | [`paper-sqlite-repository`](Skills/paper/paper-sqlite-repository/SKILL.md) | `user_version` マイグレーションと単一の書き込みスレッドを備えた SQLite リポジトリ |
| データ | [`paper-config-lang`](Skills/paper/paper-config-lang/SKILL.md) | イミュータブルな設定オブジェクト、設定のバージョン管理、MiniMessage 言語ファイル |
| データ | [`paper-datapack-bundling`](Skills/paper/paper-datapack-bundling/SKILL.md) | プラグイン jar 内のデータパックを bootstrapper から有効化 |
| 連携 | [`paper-service-api`](Skills/paper/paper-service-api/SKILL.md) | ServicesManager によるプラグイン間 API |
| 連携 | [`paper-softdepend-hook`](Skills/paper/paper-softdepend-hook/SKILL.md) | Vault と PlaceholderAPI のソフト依存関係フック |
| 連携 | [`paper-embedded-http`](Skills/paper/paper-embedded-http/SKILL.md) | localhost にバインドし、レート制限を備えた組み込み JSON HTTP API |
| 連携 | [`paper-discord-bridge`](Skills/paper/paper-discord-bridge/SKILL.md) | JDK のみで実装する Discord 双方向チャットブリッジ：webhook で送信、Gateway で受信 |
| ネットワーク | [`paper-packetevents-filter`](Skills/paper/paper-packetevents-filter/SKILL.md) | PacketEvents または ProtocolLib によるパケットフィルタリング |
| ネットワーク | [`paper-client-side-effects`](Skills/paper/paper-client-side-effects/SKILL.md) | プレイヤーごとのワールドボーダー、時間、天候、非表示プレイヤー |
| ゲームプレイ | [`paper-combat-tag`](Skills/paper/paper-combat-tag/SKILL.md) | ダメージの帰属と戦闘中ログアウトの処理を備えた PvP 戦闘タグ |
| ゲームプレイ | [`paper-safe-teleport`](Skills/paper/paper-safe-teleport/SKILL.md) | 安全な地点の探索、ランダムテレポート、非同期テレポート、クールダウン |
| ゲームプレイ | [`paper-economy-ledger`](Skills/paper/paper-economy-ledger/SKILL.md) | 多通貨の帳簿、エスクロー、Vault エコノミープロバイダー |
| ゲームプレイ | [`paper-registry-events`](Skills/paper/paper-registry-events/SKILL.md) | ブートストラップでカスタムエンチャントとダメージタイプを追加し、バニラの項目やタグを変更 |
| ゲームプレイ | [`paper-data-components`](Skills/paper/paper-data-components/SKILL.md) | NMS なしでアイテムコンポーネントを操作：食べ物、武器、滑空、ツールチップ、PDC による識別 |
| ゲームプレイ | [`paper-custom-recipes`](Skills/paper/paper-custom-recipes/SKILL.md) | クラフト・変換・醸造レシピを一括同期し、リロードにも対応 |
| コマンド | [`paper-brigadier-command`](Skills/paper/paper-brigadier-command/SKILL.md) | `LifecycleEvents.COMMANDS` で登録する Brigadier コマンド |
| ワールド | [`paper-disposable-world`](Skills/paper/paper-disposable-world/SKILL.md) | 使い捨てワールドとアリーナのリセット |
| ワールド | [`paper-world-clock`](Skills/paper/paper-world-clock/SKILL.md) | ワールドごとの時計の一時停止・速度・時間ロック（26.3 のみ） |
| スレッド | [`paper-folia-scheduler`](Skills/paper/paper-folia-scheduler/SKILL.md) | Paper と Folia で共通のスケジューリング：リージョン・エンティティ・グローバル・非同期スケジューラ |
| 表示 | [`paper-display-entities`](Skills/paper/paper-display-entities/SKILL.md) | TextDisplay と ItemDisplay によるホログラムとアニメーション表示（常に 1 体だけ） |

---

## 仕組み

```text
Your request ──▶ skill description ──▶ SKILL.md ──▶ references/ ──▶ Generated code
                 (trigger keywords)     (template,     (build.gradle,
                                        inputs,        paper-plugin.yml,
                                        fallbacks)     threading, naming)
```

1. エージェントは、説明とトリガーキーワードを手がかりに、依頼内容に合うスキルを選びます。
2. そのスキルの `SKILL.md`（入力、出力、スレッドセーフティに関する注意、フォールバック）とコードテンプレートを読み込みます。テンプレートは `SKILL.md` 内にあるか、長いスキルでは同じフォルダの `template.md` にあり、`SKILL.md` がその索引と「先に読む」指示を載せています。`examples.md` は追加のシナリオを扱います。
3. `references/` に同梱されたプラットフォーム別ビルド設定を適用します。これは [`Skills/paper-nms/PLATFORM.md`](Skills/paper-nms/PLATFORM.md) または [`Skills/paper-api/PLATFORM.md`](Skills/paper-api/PLATFORM.md) から生成されます。
4. `references/` に同梱されたスレッドと Mojang 名称のルールに従います。これは [`Skills/_shared/`](Skills/_shared) から生成されます。

テンプレートで扱っていない API については、[NMS クイックリファレンス](docs/paper-nms)がパケット、エンティティ、Netty パイプライン、Bukkit ↔ NMS ブリッジを網羅しています。

---

## 互換性

| 項目 | 対応状況 |
|------|-----------|
| Minecraft / Paper | 1.21.11 と 26.3（テンプレートは 26.3 が既定。1.21.11 との差異は `// @1.21.11:` で明示）（`paper-world-clock` は 26.3 のみ） |
| Spigot | 対象外。各 `SKILL.md` に Paper 専用・コア移植可・Spigot で動作のいずれかを明記（Spigot-API 26.3 で確認） |
| Paper dev bundle | `1.21.11-R0.1-SNAPSHOT` / `26.3.build.258-stable` |
| Java | 21 (1.21.11) / 25 (26.3) |
| ビルド | Gradle 9.7.1+（9.8.1 で検証済み）、Groovy DSL |
| Paperweight userdev | `2.0.0-beta.24`（NMS スキルのみ。`reobfJar` は不要） |
| Shadow（任意） | `com.gradleup.shadow` `9.6.1`（マルチモジュールビルド用、`nms-version-adapter`） |
| ソフト依存関係 | VaultAPI 1.7.1、PlaceholderAPI 2.11.6、packetevents 2.13.0、ProtocolLib 5.3.0、sqlite-jdbc 3.49.1.0 |

標準の `build.gradle` と `paper-plugin.yml` のテンプレートは、2 つの `PLATFORM.md` にあります。1.21.x テンプレートからアップグレードしますか？ [CHANGELOG.md](CHANGELOG.md) と、[`Skills/paper-nms/PLATFORM.md`](Skills/paper-nms/PLATFORM.md) のセクション 5 を参照してください。

---

## リポジトリ構成

```text
MJP-Paper-Skills/
├── .claude/skills/           # コピーしてすぐ使えるスキルフォルダ（PLATFORM フォルダを除き Skills/ のミラー）
├── Skills/                   # 正規のスキルソース
│   ├── skills-registry.yml   # 全 39 スキルのインデックス（paper-nms + paper-api）
│   ├── _shared/              # すべてのスキルで共通のスレッドと命名のルール
│   ├── paper-nms/PLATFORM.md # NMS 用 build.gradle / paper-plugin.yml テンプレート、バージョン表
│   ├── paper-api/PLATFORM.md # Paper API のビルド設定、ソフト依存関係の座標
│   ├── nms/<skill-id>/       # SKILL.md + examples.md + references/（NMS スキル 16 個）
│   └── paper/<skill-id>/     # SKILL.md（長いテンプレートは template.md）+ examples.md + references/（Paper API スキル 23 個）
├── scripts/                  # references/ の同期、テンプレート抽出、カタログ同期、実機チェック、dev bundle canary
├── verify/                   # 抽出したテンプレートをバージョンごとにコンパイルする Gradle プロジェクト
│   └── runtime/              # 実機シナリオ：スキルごとのプローブプラグインと期待するチェック
├── docs/paper-nms/           # NMS API クイックリファレンス
├── web/                      # Next.js ドキュメントサイト（GitHub Pages への静的エクスポート）
├── CHANGELOG.md
└── CLAUDE.md                 # このリポジトリで作業する AI エージェント向けのメンテナー用指示
```

---

## コントリビュート

コントリビューションを歓迎します。スキルを追加するには、次の手順に従ってください。

1. `Skills/nms/<slug>/` または `Skills/paper/<slug>/` に `SKILL.md` と `examples.md`（例は 2 つ以上）を作成する。`SKILL.md` は約 800 行以内に収め、長いテンプレートは同じフォルダの `template.md` に置く。
2. `.claude/skills/` 配下の同じパスにミラーリングし、`node scripts/sync-skill-references.mjs` を実行して `references/` フォルダを生成する（`PLATFORM.md` や `_shared/` のファイルを変更するたびに再実行する）。
3. 両方の `skills-registry.yml` にエントリを追加する。
4. サイトページ `web/data/skills/<slug>.md` と、その英語本文 `web/data/skills/en/<slug>.md` を追加し、8 つの README のスキル表、`CLAUDE.md` の Skills Index、（Paper API スキルの場合）`Skills/paper-api/PLATFORM.md` の一覧にそれぞれ 1 行追加する。その後 `node scripts/sync-catalog.mjs` を実行してスキル数を更新する。`npm test` は `--check` でカタログを検査する。
5. テンプレートが Paper 1.21.11 と 26.3 でコンパイルできることを確認する。`Skills/` に触れるすべてのプルリクエストで CI がこれを実行する。ローカルで実行する場合は（26.3 は JDK 25、1.21.11 は JDK 21）：

   ```bash
   node scripts/extract-skill-java.mjs 26.3
   cd verify && ./gradlew compileSkills -Pmc=26.3
   ```

6. 任意：プレイヤーがいないサーバーで動作を確認できる場合は、`verify/runtime/` に実機シナリオを追加する（`CLAUDE.md` を参照）。CI は実際の Paper サーバーで、シナリオが指定する場合は Folia で実行する。ローカルで実行するには（Minecraft EULA に同意することになります）：

   ```bash
   cd verify && ./gradlew buildRuntimePlugins -Pmc=26.3 && cd ..
   node scripts/runtime-check.mjs 26.3 --accept-eula
   ```

[`CLAUDE.md`](CLAUDE.md) に、完全な手順とリポジトリの不変条件が記載されています。ドキュメントサイトの開発は次のとおりです。

```bash
cd web
npm ci
npx tsc --noEmit   # type check
npm test           # tests (node:test + tsx)
npm run build      # static export to web/out/
```

CI は、すべてのプルリクエストで同じチェックを実行します。サイトのスタックとスクリプトについては [`web/README.md`](web/README.md) を参照してください。

---

## ライセンス

[MIT](LICENSE) © MrPippi

Minecraft の公式製品ではありません。Mojang および Microsoft による承認や関連はありません。
