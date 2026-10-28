[简体中文](README.zh-CN.md) | [English](README.md)

# SemQuestTree 问义树

![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg) ![JDK](https://img.shields.io/badge/JDK-8%2B-blue) ![Dependencies](https://img.shields.io/badge/third--party%20deps-0-brightgreen)

**A multi-table semantic retriever driven by decision models: one table is one criteria tree.** No embeddings, no inverted index — domain knowledge is written as a tree of event-level atomic assertions, and a decision model (noul / choice question types) judges layer by layer along the tree: `add` descends a piece of content through the criteria into leaves, `quest` retrieves with a natural-language query.

noul mode is a multi-select branch — one piece of content can hit multiple branches at once; retrieval fans out concurrently and ranks by number of hit leaves. choice mode is a single-select branch — one question per layer, one winning option, descending along a single path. Ships with a visualization server: a start page managing all tables (pick the branch mode when creating), per-table tree expansion, node details on click, right-click to add/edit/delete nodes, hot-swappable backends/models, and a console for placement and retrieval.

![demo](docs/demo.gif)

## Why not vector search

- **Explainable paths**: every hit comes with an explicit decision path and P(yes) trace (which criteria fired, the probability at each layer, tokens spent) — not black-box similarity
- **Editable and steerable by humans**: criteria are configuration; per-subtree rules (ruleAdd / ruleSearch) override global rules, and saved criteria take effect immediately
- **Multi-label for free**: under the noul absolute gate one piece of content lands in multiple leaves — no top-k truncation
- **Zero dependencies, zero infrastructure**: no embedding model, no vector DB, no indexer process — compiles with JDK 8+ and runs

Good fit: FAQ / policy-paragraph lookup, ticket triage, intent routing, and small-to-medium knowledge indexes that a human must be able to read and control.

## Up and running in 60 seconds (no credentials)

```bash
git clone https://github.com/LEERYx/SemQuestTree.git
cd SemQuestTree
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")   # Windows: use Git Bash / WSL
java -cp target/classes org.example.SemQuestServer
```

Open http://localhost:8787 — with no model credentials configured the server automatically falls back to the **mock backend** (deterministic keyword matching; zero tokens, zero network). The demo table 司机意图 (driver intents, 7 utterances) is migrated automatically from files shipped in the repo. Open the table, switch to the right-side 控制台 (console) → 检索 search, and try `找运费价格相关：要求涨价、讨要运费的话术` to see multi-leaf hits, ranked documents and the decision trace. To plug in real models see [Configuration](#configuration) (start by copying `semquest.properties.example`).

## Running the visualization server

From the SemQuestTree directory, compile + start:

```bash
mkdir -p target/classes && javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java") \
     && java -cp target/classes org.example.SemQuestServer
```

Or start directly if already compiled:

```bash
java -cp target/classes org.example.SemQuestServer
```

Stop the server (kills every local SemQuestServer process):

```bash
pkill -f org.example.SemQuestServer
```

Tip: if the upstream decision endpoints are region-blocked, append `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890` (adjust to your local proxy port) to the `java` command.

At http://localhost:8787 :

- The start page lists all tables (cards: name / mode / note / doc count). 新建表 (new table) takes a name + branch mode (noul multi-select / choice single-select — hover the ? for explanations) + note; the mode is fixed at creation. Card 改名 / 备注 renames a table and edits its note (same as the ✎ button inside a table; duplicates get a 400; persisted). Card 删表 deletes the whole tree and its data file
- Inside a table only ROOT shows initially; left-click a node to expand one layer (one query per layer); 全部展开 pulls the whole tree at once
- Click any node → the right panel shows its path, criteria and placed docs (internal nodes also list children and the subtree doc total; nothing shows until you click)
- Right-click a node → 增加判断节点 / 增加选项: for noul fill in name / yes / no criteria (Chinese), for choice fill the option name + optional description. After creation the parent's existing docs are re-judged one by one and migrated into the new leaf
- Right-click a node → 修改节点 / 修改选项: rename and edit criteria with zero model calls; renaming updates placement pointers, edits are persisted in the snapshot and replayed on restart
- The header model bar (global, applies to all tables): pick a backend (jev / openrouter / 百炼 Bailian / mock), then a model list loads (for openrouter it is filtered live by the decisions modality — new official models appear automatically; on failure/timeout it falls back to 32 built-in candidates; a `*.models` manual list, when configured, is used as-is). `⚡` marks decision-endpoint models, `·免费` marks free models. Selecting one hot-swaps instantly (only the credential channel changes; no inference is fired)
- The right-side 控制台 tab has two modes (Enter to run, Shift+Enter for a newline): 落位 add pastes one piece of content and places it into leaves (same ID replaces the old placement); 检索 search takes a natural-language query and returns hit leaves, ranked documents (with text and via path), the decision trace and the token bill
- Right-click a node → 删除节点: after confirmation deletes the whole subtree (including fallback leaves at every level) and cleans up related placements; persistence and growth records are updated in sync (the root and fallback leaves cannot be deleted directly)
- The top-right parameter box adapts to the table mode: noul tables show 门控 (the absolute gate, adjustable at runtime), choice tables show 采纳线 (the acceptance line — a win below it falls to the fallback); changes only affect subsequent decisions, never re-rank placed data, and are persisted per table

Each table is persisted at `tables/<id>.json` (structure, criteria, both gates, placements, growth and edit records); restarts read the snapshot with zero model calls. The legacy single-tree files (`driver-intent-schema.json` + `tree-data.json`) are migrated into the demo table 司机意图 on first start when `tables/` is empty. `--reseed` replays only demo tables (rebuild a clean tree from the schema + re-place Demo.DOCS — calls the model); non-demo tables are untouched; a fresh install starts with an empty table bar and zero model calls:

```bash
java -cp target/classes org.example.SemQuestServer --reseed
```

## Console demo (optional)

```bash
java -cp target/classes org.example.Demo
java -cp target/classes org.example.Demo mock        # pick a backend (mock | jev | openrouter | bailian)
java -cp target/classes org.example.Demo jev 0.7     # backend + gate
```

Prints add placement traces, quest hits and the token bill.

## Configuration

All configuration lives in `semquest.properties` (start by copying [`semquest.properties.example`](semquest.properties.example); the real credentials file is excluded by `.gitignore`). Priority: `-Dsemquest.KEY=...` > config file > code defaults.

| Key | Meaning |
| --- | --- |
| `backend` | mock \| jev \| openrouter \| bailian (jev without a key falls back to openrouter, then to mock; mock always works with no credentials) |
| `noulGate` | noul gate, default 0.7 (initial value for new noul tables; adjustable per table from the header) |
| `choiceGate` | choice acceptance line, default 0.6 (initial value for new choice tables; adjustable per table from the header) |
| `schema` | demo table migration source, default driver-intent-schema.json |
| `timeoutMs` | per-request timeout in milliseconds |
| `server.port` / `server.tables` / `server.data` / `server.viz` | server port / tables dir / legacy data file (migration) / page file (restart required) |
| `jev.*` / `openrouter.*` / `bailian.*` | per-backend url / model / apikey |
| `mock.model` | display id of the mock backend model, default keyword-match |
| `jev.models` / `openrouter.models` / `bailian.models` | manual model lists (comma-separated; when set they take priority; empty = fetch live from upstream) |

Config hot-reload: the server polls `semquest.properties` mtime every 2 s — **saving the file applies it immediately**: `noulGate` applies to all noul tables, `choiceGate` to all choice tables (persisted as they change); timeout, credentials, model lists and per-backend url / model all hot-reload (changed credentials rebuild the current backend with zero inference calls; the log prints a `[热载]` line). Items bound to deployment data need a restart: `server.port` / `server.tables` / `server.data` / `server.viz`, `schema`, and the initial `backend`.

## Conventions

- All-Chinese, four layers deep: rules (ruleAdd / ruleSearch), inputs, options and true-false criteria are all Chinese; criteria are event-level atomic assertions; leave `no` empty to auto-generate the negation side by language (schema nodes may still carry their own `ruleAdd` / `ruleSearch` overriding subtree rules, inheriting the global one by default; an English-criteria backup of the tree lives in `driver-intent-schema.en.json`)
- noul tables: each layer issues one batched noul decision over all real criteria (multi-label absolute gate); every branch with P(yes) ≥ gate descends, so one piece of content can land in multiple leaves; fallback leaves (`X_OTHER`) are excluded from decision requests — if no criterion passes the gate at a layer, the content falls to that layer's fallback; retrieval score = number of hit leaves
- choice tables: one choice decision per layer; options = child nodes (description doubles as criterion, defaulting to the option name), with the fallback option 以上都不符合 (none of the above) as an explicit choice in normalization; a win with confidence < choiceGate falls to the fallback; descent follows a single path; retrieval score = product of winning probabilities along the path — hitting a fallback or dropping below the acceptance line ends the search with no hit
- Fallback grows with the layer: creating the first branch at a layer auto-generates that layer's fallback leaf (visible in the UI immediately; choice-table decision requests always include the fallback option); deleting all branches of a layer (back to leaf form) removes the empty fallback leaf with them (if the fallback still holds placements, deleting the last branch is refused)
- Mock backend: deterministic pure keyword-substring matching (content contains the node name → P = 0.95; choice picks the longest matching option, otherwise the fallback); fully reproducible, for onboarding / demos / tests only — not real semantic judgment
- The Chinese test corpus is in `corpus-zh-test.md` (20 utterances with expected-hit annotations); if upstream endpoints are region-blocked, start the server with proxy flags
- respan-family models (free): state uses the `{input,output}` message protocol and only the noul question type is accepted (the jev-style three-part criteria dialect `instructions + criteria{true,false}`); choice tables on respan fail fast with a clear error — switch to a jev / elephant family model

## License

[MIT](LICENSE)
