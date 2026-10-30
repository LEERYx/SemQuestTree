[简体中文](README.zh-CN.md) | [English](README.md)

# 问义树 SemQuestTree

![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg) ![JDK](https://img.shields.io/badge/JDK-8%2B-blue) ![Dependencies](https://img.shields.io/badge/third--party%20deps-0-brightgreen)

**不用向量的检索。** 把领域知识写成判据树，决策模型带着内容逐层下行——`add` 把话落进叶子，`quest` 用自然语言取回。每条命中都带完整判定路径：哪些判据点火、概率多少、花了几分钱。

- **可解释**——每次命中都是一条可读路径 + 逐层 P(yes)，不是黑盒相似度
- **多标签**——一句话同时落多个叶（noul 模式）；单路径下行也有（choice 模式）
- **人可管**——判据即配置：界面直接改，子树规则可覆盖，保存即生效
- **零基建**——不要向量模型、不要向量库、不要索引进程；JDK 8+ 跑起来就是
- **可视化**——内置服务：展开树、点节点、改判据、换模型、查询控制台

适合：FAQ / 政策条款定位、工单分流、意图路由——一切要人看得懂管得住的小中规模索引。

![demo](docs/demo.gif)

## 60 秒开箱（零凭据）

```bash
git clone https://github.com/LEERYx/SemQuestTree.git
cd SemQuestTree
javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")   # Windows 请在 Git Bash / WSL 下执行
java -cp target/classes org.example.SemQuestServer
```

- 未配任何模型凭据 → 自动落到内置 **mock 后端**（零 token、零网络）。打开 http://localhost:8787 ——demo 表「司机意图」随仓库自动迁移，进表点右侧「控制台」→「检索」，输入 `找运费价格相关：要求涨价、讨要运费的话术`
- 英文示例表（GIF 里的 Driver Intents）：`java -cp target/classes org.example.SemQuestClient seed`
- 接真实模型：复制 `semquest.properties.example` → `semquest.properties`，见[配置](#配置)

## 启动可视化服务

在 SemQuestTree 目录下，编译 + 启动：

```bash
mkdir -p target/classes && javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java") \
     && java -cp target/classes org.example.SemQuestServer
```

已编译过则直接启动：

```bash
java -cp target/classes org.example.SemQuestServer
```

停止服务（终止本机全部进程）：

```bash
pkill -f org.example.SemQuestServer
```

上游决策端点被区域封锁时，给 `java` 追加 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`（按本机代理端口改）；其余见 **[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)**。

打开 http://localhost:8787 ：

- 开始页：全部表以卡片呈现——新建表（命名 + 分支方式 noul / choice + 备注，mode 建表定死）、改名 / 备注 / 删表
- 左键点节点展开一层，「全部展开」一次拉全树；点任意节点 → 右侧显示路径、判据与落位（内部节点另含子树内容，每次 10 条）
- 右键 →「增加判断节点 / 增加选项」：建分支自动把父节点存量逐条重判回填；「修改节点」改名称与判据零模型调用；「删除节点」删整个子树并清相关落位（根节点与兜底叶受保护）
- 头部模型栏（全局）：选后端 jev / openrouter / 百炼 / mock，再选模型即热切换；⚡ 为决策端点模型，·免费 为免费模型
- 右侧「控制台」双模式（Enter 触发，Shift+Enter 换行）：`落位 add` 粘一条内容落位（粘多行即批量），`检索 search` 返回命中叶、排序文档、判定轨迹与 token 账单；同一条查询再次检索即缓存直出（⚡，零模型调用）
- 右上角参数框：noul 表「门控」/ choice 表「采纳线」——运行时可调、随表落盘，不重排已落位数据
- 「EN / 中文」切换界面语言

每张表持久化在 `tables/<id>.json`（结构、判据、双门控、落位、增长与修改记录），重启零模型调用直读快照。`--reseed` 只按 schema 重建 demo 表（调模型）：

```bash
java -cp target/classes org.example.SemQuestServer --reseed
```

## 跑控制台 Demo（可选）

```bash
java -cp target/classes org.example.Demo
java -cp target/classes org.example.Demo mock        # 指定后端（mock | jev | openrouter | bailian）
java -cp target/classes org.example.Demo jev 0.7     # 指定后端 + 门控
```

打印 add 落位轨迹、quest 检索命中与 token 账单。

## HTTP API 与 Java 客户端

可视化服务本身就是一套 REST API，全部端点见 **[docs/API.md](docs/API.md)**。仓库自带零依赖 Java 客户端 [`org.example.SemQuestClient`](src/main/java/org/example/SemQuestClient.java)：连同 `Json.java` 两个文件拷进你的 Java 后端即可调用全部接口，凭据只需存在于服务端：

```bash
java -cp target/classes org.example.SemQuestClient                    # 演示：建表 → 加分支 → 落位 → 检索 → 删表；约 6 次真实判定，零成本先切 mock
java -cp target/classes org.example.SemQuestClient http://localhost:8790   # 指定地址
```

## 配置

全部配置集中在 `semquest.properties`（从 [`semquest.properties.example`](semquest.properties.example) 复制起步；真实凭据文件已被 `.gitignore` 排除），优先级：`-Dsemquest.KEY=...` > 配置文件 > 代码默认。

**决策模型 API key 目前支持两家：jev 与 OpenRouter**，需自行到对应平台申请并在配置文件填写；百炼暂未开放决策模型，待其上线后再适配（配置项先保留备用）。零凭据体验用 `backend=mock`。

| 配置 | 说明 |
| --- | --- |
| `backend` | mock \| jev \| openrouter \| bailian（jev 缺 key 自动回退 openrouter，再缺落到 mock；mock 恒可用零凭据） |
| `noulGate` | noul 门控，默认 0.7（新建 noul 表的初始值；表内右上角可按表调整） |
| `choiceGate` | choice 采纳线，默认 0.6（新建 choice 表的初始值；表内右上角可按表调整） |
| `prompt.*` | 判定提示词（系统侧组装文案），add（落位）与 search（检索）各一套：`prompt.rule.add` / `prompt.rule.search`（state 规则；新表默认值——热载只置换规则仍等于上次默认文本的表，已自定义的不动）、`prompt.noul.add.instructions`（{name}=分支名）、`prompt.noul.search.criterion`（{yes}=判据原文、{name}=分支名；判定前对判据文本套检索框架，让「所有悲伤的话」这类泛查询能过机制描述型判据——落位侧恒等 `{yes}` 写死，无配置键）、`prompt.noul.search.query`（{query}=查询原文；检索请求也包上角色框架，给判据框架里的「该查询」提供指代源，元描述查询不被当成内容本身判定，默认「用户想检索：{query}」，配 `{query}` 恒等退回）、`prompt.choice.add.root` / `prompt.choice.add.node`（{node}=层节点名，ROOT 层用 root 键）、`prompt.choice.other`（兜底选项描述）；可选分野键 `prompt.noul.search.instructions` / `prompt.choice.search.root` / `prompt.choice.search.node` 缺省或留空即继承 add 侧同键；删行或留空回内置默认 |
| `questCacheSize` | 查询结果缓存容量（条），默认 256；0=关闭；同款查询零模型直返，保存配置热载 |
| `schema` | demo 表迁移来源，默认 driver-intent-schema.json |
| `timeoutMs` | 单请求超时（毫秒） |
| `server.port` / `server.tables` / `server.data` / `server.viz` | 服务端口 / 表目录 / 旧版数据文件（迁移用） / 页面文件（重启生效） |
| `jev.*` / `openrouter.*` | 决策模型两家的 url / model / apikey（key 自行申请） |
| `bailian.*` | 百炼 url / model / apikey（暂未开放决策模型，上线后适配） |
| `mock.model` | mock 后端模型 id 展示名，默认 keyword-match |
| `jev.models` / `openrouter.models` / `bailian.models` | 手动模型清单（逗号分隔；配置即清单优先生效，留空回上游实时拉取） |

配置热载：服务内 2s 轮询 `semquest.properties`，**保存文件即生效**——`noulGate` / `choiceGate` / `questCacheSize` / `prompt.*`（提示词热载同时清空各表查询缓存；`prompt.rule.*` 只置换规则仍等于上次默认文本的表，自定义过的不动），超时、凭据、模型清单、各后端 url / model 同样热载（改凭据自动重建当前后端，零推理调用）。需重启：`server.*`、`schema`、`backend` 首选。

## 约定

- 四层全中文：规则（ruleAdd / ruleSearch）、输入、选项、true-false 判据；判据用事件级原子断言，`no` 留空自动生成否定侧；子树可自带规则覆盖；英文判据版备份 `driver-intent-schema.en.json`
- noul 表：每层对全部真实判据做一次批量判定，P(yes) ≥ gate 的分支全下行（多标签）；兜底叶（`X_OTHER`）不进判定请求，某层零分支过门即落该层兜底；检索同条件镜像（命中节点且有数据就不返回空）；分数 = 命中叶数
- 查询缓存：同一条查询（原文完全一致）零模型直返（⚡）；按命中次数淘汰（LFU）；容量 `questCacheSize` 默认 256（0=关闭）；任何表变更即清空该表缓存；仅内存不落盘
- choice 表：每层一次单选判定，选项 = 子节点（描述即判据，留空用名）+ 显式兜底「以上都不符合」；胜出置信度 < choiceGate 落兜底；单路径下行；分数 = 路径各层胜出概率连乘，落兜底/低于采纳线即无命中
- 兜底随层而生：某层建第一个分支时自动生成兜底叶；删光某层分支时空兜底连带撤掉（兜底仍有落位则拒删）
- mock 后端：纯关键词包含匹配（内容含节点名 → P=0.95），确定性可复现，仅供体验 / 演示 / 测试
- 中文实测语料：`corpus-zh-test.md`（20 条含预期命中标注）
- respan 族模型（免费）：state 走 `{input,output}` 消息协议，题型仅收 noul（jev 同款三段判据方言）；choice 表选它会明确报错，请换 jev / elephant 族模型

## License

[MIT](LICENSE)
