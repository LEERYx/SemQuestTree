# SemQuestTree HTTP API

可视化服务（`org.example.SemQuestServer`）同时就是一套完整的 REST API：建表、管树、落位、检索全部走 HTTP，任何语言的后端都能直接调用。Java 后端可参考自带客户端示例 `org.example.SemQuestClient`（零依赖，拷走即用）。

## 通用约定

- Base URL：`http://localhost:8787`（`server.port` 可改）；所有请求/响应均为 UTF-8 JSON
- 认证：无，且服务监听全部网卡（局域网内可直接访问）。仅本机自用无所谓；要对外/跨网段暴露，请在前面加网关做鉴权
- 并发模型：**同一张表的所有请求按表级锁串行执行**（不同表并行），客户端无需自己加锁；同表长判定会排队，慢模型下高并发请按表分片
- 错误统一为 `{"error":"原因"}`，状态码：400 参数非法 / 404 资源不存在 / 405 方法不对 / 500·502 判定调用失败
- 花费标注：**[模型]** 会发起决策模型请求（当前后端计费），**[免费]** 零模型调用
- `mode`（noul 多选分支 / choice 单选逐层）建表时定死，之后不可改

---

## 一、表管理 `/api/tables`

### GET /api/tables [免费]

列出全部表。

**出参**：数组，每项

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 表 ID（tables/&lt;id&gt;.json） |
| `name` | string | 表名（全局唯一） |
| `mode` | string | `noul` \| `choice` |
| `note` | string | 备注 |
| `createdAt` | number | 创建时间毫秒 |
| `demo` | boolean | 是否 demo 表（`--reseed` 会重播） |
| `docCount` | number | 落位条数 |

### POST /api/tables [免费]

建表。**入参** `{name, mode, note?}`：`mode` 必填 `noul` 或 `choice`；`note` 可选。

**出参**：同上单条 tableMeta。

**错误**：400 缺 name / mode 非法 / 表名已存在。

### PUT /api/tables?id=X [免费]

改表名与备注（mode 改不了）。**入参** `{name?, note?}`，只发要改的字段。

**出参**：改后 tableMeta。**错误**：404 未知表；400 空名 / 与其他表重名。

### DELETE /api/tables?id=X [免费]

删表：内存移除 + 删除 `tables/<id>.json`（整棵树与落位数据）。

**出参**：`{"ok":true,"id":"..."}`。**错误**：404 未知表。

---

## 二、结构与节点 `/api/t/<id>/…`

节点对象统一形态：`{name, leaf(是否叶), other(是否兜底叶), yes?(正向判据), no?(反向判据), docCount(本节点落位条数)}`。兜底叶（`X_OTHER`）没有判据字段。

### GET /api/t/&lt;id&gt;/meta [免费]

表元信息。**出参**：tableMeta 全字段 + `meta`：`{backend, mode, noulGate, choiceGate, docCount}`。

### GET /api/t/&lt;id&gt;/tree [免费]

全树结构（不含正文，正文走 leaf）。**出参**：`meta` 同上 + `root`（节点对象递归含 `children`，兜底叶排在子节点末位）+ `tableId` / `name` / `note`。

### GET /api/t/&lt;id&gt;/node?name=X [免费]

单层展开（viz 左键一层就是它）。`name` 缺省取 ROOT。

**出参**：`meta` + `node`：节点对象 + `children`（只含直接子节点，不带孙）。

**错误**：404 未知节点。

### GET /api/t/&lt;id&gt;/leaf?name=X [免费]

节点详情（叶与内部节点共用）。

**出参**：`{name, leaf, other, path(根到此的节点名数组), yes?, no?, docs:[{id,content}]}`；内部节点另有 `children` 与 `docTotal`（子树落位去重合计——一 Doc 落多叶只计一次，与各叶直加不同）。

**错误**：404 未知节点 / 缺 name。

### POST /api/t/&lt;id&gt;/node [模型]

增长节点 = 补层挂靠。**入参** `{parent, name, yes, no}`：

- noul 表：`yes`（正向判据）必填，`no` 留空自动生成否定侧
- choice 表：`yes`（选项描述）可空，留空用选项名本身

创建后父节点存量逐条重判回填：过门/胜出迁入新叶并挪出兜底叶。父为内部节点=补层（挂新叶+兜底叶）；父为叶=分化。首个分支创建时自动生成该层兜底叶。

**出参**：`{parent, node(新节点名), moves:[{docId, prob, moved}], judged(重判条数), moved(迁入条数)}`。

**错误**：400 缺字段 / 未知父节点 / 节点名已存在。**花费**：每条存量 1 次判定请求（新节点本身不判）。

### PUT /api/t/&lt;id&gt;/node [免费]

修改节点（改名/改判据），零模型调用、不重判已落位。**入参** `{name, newName?, yes, no}`（`newName` 不发=不改名；改 yes/no 传新文本）。

**出参**：`meta` + `{oldName, newName, parent, yes, no}`。改名连带更新落位指针与兜底叶名，修改记录随快照持久化、重启自动回放。

**错误**：400 未知节点 / 重名 / 根节点改名等。

### DELETE /api/t/&lt;id&gt;/node?name=X [免费]

删除节点整个子树（含各级兜底叶），清理相关落位指针，孤儿正文从索引移除；增长/修改记录同步过滤。

**出参**：`meta` + `{parent, removed(被删节点名数组), docsRemoved(随之移除的正文 ID)}`。

**错误**：400 根节点与兜底叶不可直接删 / 末位分支受兜底落位护栏保护。

---

## 三、判定 `/api/t/<id>/…`

### POST /api/t/&lt;id&gt;/add [模型]

落位：一条语料沿判据树下行落进叶。**入参** `{content, id?}`：`id` 缺省自动生成 `U-<36进制时间戳>`；**同 id 重落 = 替换旧落位**（先摘旧指针再落位）。

**出参**：`meta` + `{id, leaves(落进的叶名), steps, stats}`。

**错误**：400 缺 content / JSON 非法；502 判定调用失败（超时、上游报错）。

### POST /api/t/&lt;id&gt;/quest [模型]

检索：自然语言查询沿判据树语义下行，命中叶上的文档按分排序返回。

**入参** `{query}`。

**出参**：

| 字段 | 说明 |
| --- | --- |
| `query` | 原查询 |
| `matchedLeaves` | 命中叶名（noul：过门分支；choice：单路径末叶） |
| `ranked` | `[{docId, score, viaLeaves(该文档命中的叶), content}]`；score：noul=命中叶数，choice=路径各层胜出概率连乘 |
| `steps` | 判定轨迹 |
| `stats` | 本次会话累计账单文本行 |

**错误**：400 缺 query；500 判定调用失败。

### `steps` 判定轨迹（add/quest 共用）

每层一个元素：`{node(层节点名), probs:{节点名: P(yes) 或各项概率}, latencyMs, inputTokens, outputTokens}`；choice 模式额外带 `choice(胜出选项)` 与 `confidence(置信度)`。

### POST /api/t/&lt;id&gt;/gate [免费]

调门控，随表落盘，只影响其后的判定、不重排已落位。**入参**：noul 表 `{noulGate: 0.5~1}`；choice 表 `{choiceGate: 0~1}`（发错键 400）。

**出参**：`meta`（新门控值在内）。

---

## 四、模型后端（全局，跨表生效）

### GET /api/models?provider=X [免费]

决策模型列表。`provider` 缺省=当前后端。

**出参**：`{provider, source, current, models:[...]}`。`source`：`config`（properties 手动清单优先）> `upstream`（上游实时拉取，openrouter 按决策模态过滤）> `fallback`（内置 32 款快照）。models 条目：`{id, name?, free?, decisions?}`（`decisions:true` 表示决策端点模型）。

> 注意：**「目录在列 ≠ 端点活着」**，免费款会上下线，切换后真正调用才验证。

### GET /api/model [免费]

当前后端：`{backend, provider, model}`。

### POST /api/model [免费]

热切换 `{provider, model}`：仅校验凭据非空并替换通道，不发推理。provider 可选 `mock | jev | openrouter | bailian`（mock 零凭据关键词匹配，体验/测试用）。

**出参**：`{backend, provider, model}`。**错误**：400 未知 provider / 缺凭据（key 是否有效在真正判定时才暴露，401=key 错、403=无额度）。

---

## 调用示例（curl）

```bash
# 建表 → 加分支 → 落位 → 检索
curl -s http://localhost:8787/api/tables -d '{"name":"售后问题","mode":"noul","note":"演示"}'
curl -s http://localhost:8787/api/t/<id>/node -d '{"parent":"ROOT","name":"退款","yes":"用户明确要求退钱退款"}'
curl -s http://localhost:8787/api/t/<id>/add  -d '{"id":"Z-001","content":"我要退钱，不然投诉你们"}'
curl -s http://localhost:8787/api/t/<id>/quest -d '{"query":"有没有人要求退款的"}'
```

零依赖 Java 调用见 [`org.example.SemQuestClient`](../src/main/java/org/example/SemQuestClient.java)（连同 `Json.java` 两个文件拷进你的后端即可），`main()` 里有从建表到检索的完整演示。
