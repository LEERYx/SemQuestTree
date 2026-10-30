# Troubleshooting · 常见问题排查

按症状定位。判定类错误均以 `HTTP <状态码>: <上游响应摘要>` 形式出现在响应 `error` 字段 / 服务端日志里。

## 凭据与花费

**HTTP 401** — API key 错误或过期。检查 `semquest.properties` 里对应 provider 的 `*.apikey`（也可用环境变量 `OPENROUTER_API_KEY` 等）；注意 key 前后不要带引号和空格。

**HTTP 402 / 403（insufficient_quota / credits）** — key 有效但额度用尽。jev 换新 key；OpenRouter 账户充值或改用 `:free` 结尾的免费款；百炼免费额度耗尽后需在控制台续购。

**451 / 连接被重置（jev、OpenRouter 区域封锁）** — 启动时加 JVM 代理参数：

```bash
java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -cp target/classes org.example.SemQuestServer
```

**免费款今天调不通，昨天还好好的** — 免费款上下线是常态，**目录在列 ≠ 端点活着**。模型列表能拉到只代表目录里有它；换另一款 `:free` 模型再试。

**模型切了但没生效** — 必须在下拉里**显式选中一次模型**（触发 change 才发 `POST /api/model`）；只切 provider 停在占位「— 选择模型 —」时后端不会变。程序赋值 select 不触发 change，这是刻意设计，防「看着选了、实际没切」的假象。

**模型下拉提示「静态候选」** — 上游实时列表不可达或缺凭据，列表来自内置快照/手动清单。仍可手选，真正调用才验证端点是否活着。

## 题型与模型方言

**报错「仅支持 noul 题型（上游不收 choice）」** — respan 族模型只收 noul（上游 400 实测）。choice 表请切换 jev 族或 `baseten/elephant-*` 模型。

**mock 后端结果「不对」** — mock 是**关键词子串匹配，不是语义判定**：内容里逐层包含分支名才过门（大小写敏感）。演示表和自测语料的分支名必须含正文/查询里的关键词，否则全落兜底。体验判定语义请接真实模型。

## 检索行为

**noul 查询 P(yes) 低得离谱（明明该命中）** — 模型判定的唯一语义依据是分支的 yes 判据文本，分支名只是标签。名字与判据矛盾时（如分支叫「悲伤」、判据写的却是愤怒），模型信判据——改判据（右键修改节点），改名字没用。判据是机制描述型文案（如「通过强调不可逆的失去……」）时，具体句子能过门，而「所有悲伤的话」这类泛查询本身不满足该描述，属预期语义：检索时引擎默认把判据包上检索框架再下发（`prompt.noul.search.criterion`，默认「该查询的结果应包含满足以下描述的内容：{yes}」），落位侧恒等不动（`prompt.noul.add.criterion`）。泛查询仍不命中就调 search 包裹模板，保存热载。

**同款查询没走缓存（fastPath=false）** — 缓存直出需同时满足：`questCacheSize` > 0（默认 256，0=关闭）、查询原文与上次完全一致（差一个空格标点都算新查询）、上次查询之后表没有变更（落位 / 加改删节点 / 调门控 / 提示词热载都会清空该表缓存）、条目没被 LFU 淘汰（容量满时命中次数最少的先出局）。缓存仅内存，重启从零攒。

**改了判据，已落位的文档没动** — 修改节点零模型调用、不重判存量（设计如此）。落位由后续 add/quest 按新判据自然收正；demo 表可 `--reseed` 整体重播（只重播 demo 表，自建表不受影响）。

**删除节点被拒绝「最后一个实子节点且兜底叶仍有落位」** — 快照回放护栏：先清掉该层兜底数据（删文档或 `--reseed`），或先删其他分支。

## 服务与端口

**启动报 `BindException: Address already in use`** — 8787 被占。配置文件加 `server.port=8788` 换端口，或先停旧进程：`pkill -f org.example.SemQuestServer`（按启动参数匹配，只杀 SemQuestServer 进程）。

**编译报错 / 中文判据乱码** — 用仓库标准命令编译（JDK 8+，零依赖）：

```bash
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")
```

缺 `-encoding UTF-8` 时 Windows/Linux 默认编码会把中文判据编坏；`-d target/classes` 缺了会导致运行 `java -cp target/classes …` 时加载到旧 class。

**服务在局域网可访问** — 服务监听全部网卡且无鉴权（设计如此）。跨网段暴露前请在前面加网关做鉴权。

## 数据与并发

**表文件会写坏吗** — 不会。落盘统一走「写临时文件 → 原子 rename」，进程崩溃最多丢最后一次写盘，不会留半截 JSON。

**并发 add/quest 会互相踩吗** — 同一张表的所有请求按表级锁串行（不同表并行），包括判定和落盘；客户端无需自己加锁。慢模型下同表高并发会排队，可按表分片。

**批量导入有上限吗** — `addBatch` 单次 ≤ 500 条，超出返回 400；条目级失败不牵连整批，失败明细在响应 `failed` 数组里。
