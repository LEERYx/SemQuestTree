# Handoff: SemQuestTree 开源冲刺 — awesome-java 投稿 + GitHub 打磨

## Mission
把问义树/SemQuestTree（/Users/code/other/SemQuestTree，github.com/LEERYx/SemQuestTree）推向公开曝光：投稿 akullpp/awesome-java、补 GitHub topics/About、双语 README 精简。现状：README 改完未 push（用户自己 push）、awesome-java PR 的 commit 已在用户 fork 的 `LEERYx-patch-1` 分支上（commit 94eba51，diff 已核对正确），待用户以正确 base/head 开 PR；topics/About 已交付文案待用户粘贴。

## Context for the next agent
- **工作区/命令**：Bash cwd 是 /Users/code/other/test（不是 repo）。repo 操作用 dir_path=/Users/code/other/SemQuestTree。编译：`cd /Users/code/other/SemQuestTree && javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")`。**只能在 repo 目录跑这个 find**——test 工作区混有 Java21 语法旧原型，javac 8 会炸。
- **项目深度史**：全部历史（双路架构、choice/noul 方言、多表化、mock 后端、每次任务的坑）在项目记忆 `/Users/admin/.qoder/projects/-Users-code-other-test/memory/project-decision-sdk.md`——先读它，别让用户重复讲。
- **硬约束（逐条）**：
  - 绝不花用户 token 测试（“你不要测试调用花我token”）；只做零 token 元数据调用、mock 后端、必 400 探测。判定正确性用户自测。
  - API key 只存 semquest.properties，永不回显；**绝不把真实 semquest.properties 读进上下文**——同步改动用盲 append 或 sed + 计数校验。占位模板是 semquest.properties.example。
  - 用户自己 push GitHub——绝不 commit/push 他的仓库。
  - “去掉/删除”先辨范围（UI 文案 vs 功能实体）——曾在“去掉 jev”上误删后端被纠正。
  - 推荐模型/工具先核实中文支持。
- **环境**：macOS Apple M5；gh CLI 未装（用 `curl -s https://api.github.com/...` + python3 解析，未认证 60 次/小时限速）；github.com / raw.githubusercontent.com 直连被区域封锁，用 `curl -x http://127.0.0.1:7890`；jev 端点同理，java 进程需 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。
- **服务**：viz 服务 :8787（`java -cp target/classes org.example.SemQuestServer`；停止 `pkill -f org.example.SemQuestServer`）。**当前 :8787 进程是旧构建，需用户重启才有任务 #55~#57（节点直取检索、/docs 分页）**。用户已建真表「情绪决策树」等，勿动其数据。

## Completed work
- **awesome-java 侦查实锤**：上游默认分支已切 **main**，README.md 为机器生成（★/license 徽章/健康点/details 折叠）——人工编辑目标是 **README_SOURCE.md**（简单格式 `- [Name](url) - Description.`）；license 徽章自动生成，描述里不要写 MIT。新 Search 节共 6 项（Indexer4j 已被移除），插入点=README_SOURCE.md 的 OpenSearch 与 Viglet Turing ES 之间（约 line 1046）。投稿规则与 PR 文案全文见项目记忆 2026-10-29/30 条目。
- **fork→PR 流程陪走**（用户截图逐步核对）：commit 94eba51 "Add SemQuestTree" 已在 LEERYx-patch-1，diff 正确，"Able to merge"。已交付：PR 标题 `Add SemQuestTree`、正文（What / Why it fits — criteria c/d 范式独特性对照 6 个倒排/向量引擎 / Disclosure "I am the author"）。
- **双语 README 大刀阔斧精简**（用户令“没用的都删了”）：README.md 157→135 行、README.zh-CN.md 135→113 行。删了：整个 "Why not vector search" 节（4 条中 3 条与首屏重复，独特的“人可管”并入首屏 bullet）、重复的编译启动命令块、60 秒段压成 3 bullets、UI 走查 10→7 条单行、热载段 -40%、Conventions 逐条砍半。零信息损失（细节都在 docs/API.md、docs/TROUBLESHOOTING.md、配置表）。改动未 commit，等用户 push。
- **GitHub topics 粘贴清单**（About 齿轮 → Topics，逗号分隔）：`semantic-search, information-retrieval, llm, nlp, java, rag, search-engine, knowledge-base, openrouter, zero-dependency, explainable-ai, retrieval`——`llm`+`java` 是 uhub/awesome-* 按 topic 自动收录的钩子（零 PR 成本的备选曝光）。
- **新 About 描述**（EN）："No embeddings. No vector DB. Your knowledge becomes a criteria tree — an LLM decides its way down, and every hit comes with a readable path. Pure Java 8, zero dependencies."
- 早前：任务 #57（检索节点名直取 + /docs 分页）收尾完成，含测试配方（判别标志：stats 行「节点直取 · N 条 · 零模型调用」、⚡、viz 10 条/页 + 加载更多），记忆已写。

## In progress / not done
- **用户侧待办**（我无法代做）：①完成 PR——base repository 必须= akullpp/awesome-java、base=main、head= LEERYx:LEERYx-patch-1（fork 内 compare 页会开出自指 PR 的坑，见 Pitfalls）；②粘贴 topics 与 About；③push 本地 README 改动；④重启 :8787。
- **PR 后跟进**：回复 maintainer 评论，改动推同分支 PR 自动更新；审稿数天到数周，被拒可改后再投或转投。
- **备选清单**（awesome-java 拒了再动）：IR-LLM/Awesome-Information-Retrieval-in-the-Age-of-LLM、WangRongsheng/Awesome-LLM-with-RAG、hannibal046/Awesome-LLM、uhub 自动收录。
- **任务 #5（L1 定义级判据截枝 + add 过火排序污染）**：长期挂起等用户拍板，勿自行开工。候选修法已列（L1 原子化/分层门控 0.5-0.7/DEADHEAD 挪枝）。
- **NL 检索方向**：用户暂停中；判据包裹代码与声明式措辞保留在真实 properties（`prompt.noul.search.criterion`）。若恢复：热载即生效，可调 knob 就这一个。

## Pitfalls & lessons
- **fork 只拷 main**：GitHub fork 默认勾"copy the main branch only"，fork 里没有 master——`/edit/master/` 404。编辑地址是 `https://github.com/LEERYx/awesome-java/edit/main/README_SOURCE.md`。
- **自指 PR 陷阱**：fork 内 compare 页（面包屑 LEERYx/awesome-java）base:main ← compare:LEERYx-patch-1 两边都是自己，点 Create 开出无用自 PR。正路：页内 "compare across forks" 链接改 4 个下拉，或 fork 代码页横幅 "1 commit ahead of akullpp:main" → Contribute → Open pull request（自动指向上游）。
- **GitHub 新版 Preview 页**：折叠 `<details>` 内容 Ctrl+F 搜不到——搜"Indexer4j没搜到"的第一层原因；第二层原因是新布局里 Indexer4j 根本没了。Code tab 才能全页搜索。
- **api.github.com 未认证限速 60/h**：连续查几个 repo 后会 403，等一小时或换代理+带 token（用户 key 不动）。验证 PR 是否真开在 upstream：`curl -s "https://api.github.com/repos/akullpp/awesome-java/pulls?head=LEERYx:LEERYx-patch-1"`。
- **对外文档精简的执行方式**：每处删除在报告里逐一列出（让用户可恢复）；删节前先确认内容在别处有家（docs/、配置表），保证零信息损失——用户验证过这套，后续照做。
- **pkill 模式必须匹配真实启动参数**（曾因 -D 启动参数不匹配导致杀不掉、假阴性"SERVER_STOPPED"）。
- **一次性测试实例**必须先手造 `{"docs":[],"grown":[],"edits":[]}` 数据文件再启动——数据文件不存在会走 reseed 路径调模型。多表回放：replayTableData 已有判重守卫，但别依赖它当垃圾场。

## Suggested first steps
1. 核实 PR 是否真开在 upstream（上面那条 curl）；若没开，给用户三条开法中最省事的一条（代码页横幅 Contribute）。
2. 确认 topics/About/push 三件用户侧待办是否完成。
3. 提醒 :8787 重启换新构建（带任务 #55~#57）。
4. 若 PR 有 maintainer 回复，按“补 criteria 论据/补英文材料”方向协助回应；被拒则走备选清单。
5. 任务 #5 与 NL 检索：仅等用户拍板，不主动碰。
