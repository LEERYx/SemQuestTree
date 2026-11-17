package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 问义树可视化服务（零依赖，JDK 内置 com.sun.net.httpserver）。
 *
 * <p>多表路由（一张表 = 一棵树）：
 * <pre>
 *   GET  /                    → viz.html（可视化页面；磁盘实时读，改完刷新即生效）
 *   GET  /api/tables          → 表清单 [{id,name,mode,note,createdAt,docCount}]
 *   POST /api/tables          → 新建表 {name, mode:"noul"|"choice", note?}：noul 多选分支并发命中，
 *                               choice 单选逐层下行；mode 建表时定死，新建为仅 ROOT 空树
 *   DELETE /api/tables?id=X   → 删除表（内存 + tables/&lt;id&gt;.json）
 *   PUT  /api/tables?id=X     → 改表名/备注 {name?, note?}，重名校验，零模型调用
 *   GET  /api/t/&lt;id&gt;          → 表详情（元信息 + metaMap）
 *   GET  /api/t/&lt;id&gt;/tree     → 全树结构 JSON（附表名/备注）
 *   GET  /api/t/&lt;id&gt;/leaf?name=X → 节点详情 JSON（路径 + 判据 + 落位正文；叶与内部节点共用视图）
 *   GET  /api/t/&lt;id&gt;/docs?node=X → 节点直取 JSON（子树全部叶的落位内容，零模型调用；limit 缺省 500）
 *   POST /api/t/&lt;id&gt;/node     → 增长判断节点 {parent,name,yes,no}：补层挂靠，父节点兜底存量逐条
 *                               重判过门迁入（choice 模式即新增选项）；报告回 JSON
 *   PUT  /api/t/&lt;id&gt;/node     → 修改节点 {name, newName?, yes, no}：改名/改判据，零模型调用
 *   DELETE /api/t/&lt;id&gt;/node?name=X → 删除节点及其整个子树（含兜底叶），同步清理 grown/edits
 *   POST /api/t/&lt;id&gt;/gate     → 调参 {gate}（noul 门控）/ {choiceGate}（choice 采纳线），落盘
 *   POST /api/t/&lt;id&gt;/add      → 落位 {content, id?}：一条语料按判据下行落叶，随快照落盘
 *   GET  /api/t/&lt;id&gt;/extend   → 节点延伸状态（viz 进度条轮询；phase: idle/running/done/error）
 *   POST /api/t/&lt;id&gt;/extend   → 手动触发 {node}：后台串行拆细该节点（与水位 growth.splitAt×
 *                               growth.splitFactor 自动触发同链路），不阻塞 add/quest
 *   GET  /api/t/&lt;id&gt;/maint    → 维护任务状态 + 各层兜底水位 + 失败记忆快照（viz 维护面板轮询）
 *   POST /api/t/&lt;id&gt;/maint    → 手动触发一轮维护（回收/体检/重写/兜底批量生长，后台执行；
 *                               兜底积压达阈值的自动触发同链路），不阻塞 add/quest
 *   POST /api/t/&lt;id&gt;/quest    → 查询 {query}：与节点名完全一致时直接返回该节点子树内容（零模型）；
 *                                  否则自然语言检索，返回命中叶 + 排序文档 + 判定轨迹
 *   GET  /api/models          → 决策模型列表 ?provider=mock|jev|openrouter|bailian（上游零 token 元数据；
 *                               失败/缺凭据回静态候选；openrouter 置顶补入配置里的决策模型）
 *   GET/POST /api/model       → 当前 provider/model / 热切换 {provider,model}（仅凭据校验，零推理）
 *   GET/POST /api/growth      → 自我生长开关/生成模型有效值与 openrouter chat 模型清单 /
 *                               运行时热改 {enabled?, model?}（内存覆盖，零推理，重启回 growth.* 配置值）
 * </pre>
 *
 * <p>多表持久化在 tables/（server.tables，每表一个 &lt;id&gt;.json：{id, name, mode, note, createdAt,
 * demo, schema{mode,gate,choiceGate,ruleAdd,ruleSearch,root}, docs, grown, edits}）。重启零模型
 * 调用：先回放 grown 增长结构、edits 修改记录再挂正文。旧版单树（driver-intent-schema.json +
 * tree-data.json）在 tables/ 为空时首启自动迁移为 demo 表「司机意图」；--reseed 只重播 demo 表
 * （schema 重建干净树 + Demo.DOCS 重新下行落位，调模型），非 demo 表不动。全新安装（无 tables/
 * 且无旧版文件）启动为空表栏，零模型调用。
 *
 * <p>jev 上游被区域封锁时启动加：-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
 */
public final class SemQuestServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 延伸与维护共用全局单工线程池（daemon）：都是重整理（分钟级），全局串行排队足够——
     *  同表延伸/维护绝不并发动树，多表任务不互抢生成模型资源。 */
    private static final ExecutorService MAINT_POOL = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread th = new Thread(r, "semquest-maint");
            th.setDaemon(true);
            return th;
        }
    });

    public static void main(String[] args) throws Exception {
        boolean reseed = false;
        for (String a : args) {
            if ("--reseed".equals(a)) reseed = true;
        }

        Config cfg = Config.load();
        int port = cfg.getInt("server.port", 8787);
        int timeoutMs = cfg.getInt("timeoutMs", 30000);
        double gate = cfg.getDouble("noulGate", 0.7);
        double choiceGate = cfg.getDouble("choiceGate", 0.6);
        Path schema = Paths.get(cfg.get("schema", "driver-intent-schema.json"));
        Path dataFile = Paths.get(cfg.get("server.data", "tree-data.json"));
        Path vizFile = Paths.get(cfg.get("server.viz", "viz.html"));
        Path tablesDir = Paths.get(cfg.get("server.tables", "tables"));

        String provider = normalizeProvider(cfg.get("backend", "jev"));
        SwitchableBackend backend = new SwitchableBackend(Demo.createBackend(cfg, provider, timeoutMs));
        Selection sel = new Selection();
        sel.provider = provider;
        sel.model = currentModelOf(cfg, provider);
        final GrowthState gstate = new GrowthState();   // 生长运行时覆盖（viz 🌱 热改；null 回退 growth.* 配置值）

        // 判定提示词装载（最早时机 fail-fast）：全部来自 prompt.* 键指向的提示词文件（PromptLoader 严格装载——
        // 路径未配置/文件不可读/内容空白即启动失败，代码零内置文本零默认回退），热载见 watcher；
        // 先于表装载——提示词不完备就不该发生任何表迁移/回放副作用
        final SemQuestTree.Prompts promptsInit = promptsOrFail(cfg);
        final String ruleAddInit = ruleOrFail(cfg, "prompt.rule.add");       // 树级规则建表默认值（提示词文件内容）
        final String ruleSearchInit = ruleOrFail(cfg, "prompt.rule.search");

        // 多表装载：tables/*.json 全部零模型调用回放；tables/ 为空且有旧版单树时自动迁移
        ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<String, Table>();
        loadTables(tables, tablesDir, backend);
        if (tables.isEmpty() && Files.exists(schema)) {
            Table t = migrateLegacy(schema, dataFile, backend, gate, tablesDir);
            tables.put(t.id, t);
            System.out.println("旧版单树已迁移为 demo 表「" + t.name + "」→ " + t.file.getFileName());
        }
        if (reseed) {
            boolean anyDemo = false;
            for (Table t : tables.values()) {
                if (!t.demo) continue;
                anyDemo = true;
                reseedTable(t, backend);
            }
            if (!anyDemo && Files.exists(schema)) {
                Table t = migrateLegacy(schema, dataFile, backend, gate, tablesDir);
                tables.put(t.id, t);
                reseedTable(t, backend);
            } else if (!anyDemo) {
                System.out.println("[reseed] 没有 demo 表可重播（tables/ 与旧版 schema 均为空）");
            }
        }
        for (Table t : tables.values()) {
            System.out.println("表「" + t.name + "」(" + (t.tree.choiceMode() ? "choice" : "noul")
                    + ") 就绪：" + t.tree.docCount() + " 条落位 · 增长 " + t.grown.size()
                    + " · 修改 " + t.edits.size() + " · " + t.file.getFileName());
        }
        // 查询缓存容量按配置应用到全部表（热载 watcher 只在配置变化时触发，初始值这里铺一次）
        final int qcInit = Math.max(0, cfg.getInt("questCacheSize", 256));
        for (Table t : tables.values()) {
            synchronized (t) {
                t.tree.setQuestCacheCap(qcInit);
            }
        }
        // 判定提示词铺到全部表（装载已在上文 fail-fast 完成）
        for (Table t : tables.values()) {
            synchronized (t) {
                t.tree.setPrompts(promptsInit);
            }
        }
        // 启动补跑：重启前未消化的兜底积压继续消化（各层积压达阈值 → 提交后台维护任务，全局单工排队）
        for (Table t : tables.values()) {
            synchronized (t) {
                maybeSubmitMaint(t, cfg, backend, gstate);
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new StaticHandler(vizFile));
        server.createContext("/api/tables", new TablesHandler(tables, cfg, backend, tablesDir));
        server.createContext("/api/t/", new TableRouter(tables, cfg, backend, gstate));
        server.createContext("/api/models", new ModelsHandler(cfg, sel, timeoutMs));
        server.createContext("/api/model", new ModelHandler(cfg, backend, sel));
        server.createContext("/api/growth", new GrowthHandler(cfg, gstate, timeoutMs));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("问义树可视化服务已启动: http://localhost:" + port
                + "   （后端 " + backend.name() + " · 模型 " + sel.model + " · 门控 " + gate
                + " · 采纳线 " + choiceGate + " · 表 " + tables.size() + " 张）");

        // 配置热载：2s 轮询 properties mtime，保存即生效（双门控/查询缓存容量/判定提示词/树级规则/超时/凭据/模型清单/各后端 url·model）。
        // gate 应用到全部 noul 表，choiceGate 应用到全部 choice 表，questCacheSize 与 prompt.* 应用到全部表
        // （提示词热载同时清空各表查询缓存；prompt.rule.* 仅置换规则仍等于上次应用文本的表，自定义文本不动，置换落盘）。
        // 不热载（重启生效）：server.port / server.data / server.viz / server.tables、schema、backend 首选。
        final Path cfgPath = Paths.get(System.getProperty("semquest.config", Config.DEFAULT_FILE));
        final long[] lastMtime = { cfgPath.toFile().lastModified() };
        final SemQuestTree.Prompts[] lastPrompts = { promptsInit };   // 上次应用的判定提示词（热载变化检测）
        final String[] lastRules = { ruleAddInit, ruleSearchInit };   // 上次应用的 prompt.rule.* 提示词文件内容
        Thread watcher = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                        long m = cfgPath.toFile().lastModified();
                        if (m == 0 || m == lastMtime[0]) continue;
                        lastMtime[0] = m;
                        if (!cfg.reload()) {
                            System.out.println("[热载] 配置读取失败，保留旧配置");
                            continue;
                        }
                        double g = cfg.getDouble("noulGate", 0.7);
                        if (g > 0 && g <= 1) {
                            for (Table t : tables.values()) {
                                if (t.tree.choiceMode() || g == t.tree.gate()) continue;
                                synchronized (t) {       // 与 /api/t 请求共用表锁，别绕开树
                                    t.tree.setGate(g);
                                    persistTable(t);
                                }
                                System.out.println("[热载] 表「" + t.name + "」门控 → " + g);
                            }
                        }
                        double cg = cfg.getDouble("choiceGate", 0.6);
                        if (cg > 0 && cg <= 1) {
                            for (Table t : tables.values()) {
                                if (!t.tree.choiceMode() || cg == t.tree.choiceGate()) continue;
                                synchronized (t) {
                                    t.tree.setChoiceGate(cg);
                                    persistTable(t);
                                }
                                System.out.println("[热载] 表「" + t.name + "」采纳线 → " + cg);
                            }
                        }
                        int qc = Math.max(0, cfg.getInt("questCacheSize", 256));
                        for (Table t : tables.values()) {
                            if (t.tree.questCacheCap() == qc) continue;
                            synchronized (t) {
                                t.tree.setQuestCacheCap(qc);
                            }
                            System.out.println("[热载] 表「" + t.name + "」查询缓存容量 → "
                                    + (qc == 0 ? "关闭" : qc));
                        }
                        // 提示词热载：全部来自提示词文件；装载失败（路径未配置/文件不可读/空白）即保留旧值
                        SemQuestTree.Prompts np = null;
                        String nra = null, nrs = null;
                        try {
                            np = PromptLoader.of(cfg);
                            nra = PromptLoader.rule(cfg, "prompt.rule.add");
                            nrs = PromptLoader.rule(cfg, "prompt.rule.search");
                        } catch (IllegalStateException e) {
                            System.out.println("[热载] 提示词装载失败，保留旧值: " + e.getMessage());
                        }
                        if (np != null && !np.equals(lastPrompts[0])) {
                            for (Table t : tables.values()) {
                                synchronized (t) {
                                    t.tree.setPrompts(np);
                                }
                            }
                            System.out.println("[热载] 判定提示词已更新（prompt.* 键指向的提示词文件内容）");
                            lastPrompts[0] = np;
                        }
                        if (nra != null && nrs != null && (!nra.equals(lastRules[0]) || !nrs.equals(lastRules[1]))) {
                            for (Table t : tables.values()) {
                                synchronized (t) {
                                    // 仅置换规则仍等于上次应用文本的表（自定义过的树不动）；置换落盘
                                    if (t.tree.swapDefaultRules(lastRules[0], nra, lastRules[1], nrs)) {
                                        persistTable(t);
                                        System.out.println("[热载] 表「" + t.name + "」树级规则已按 prompt.rule.* 更新");
                                    }
                                }
                            }
                            lastRules[0] = nra;
                            lastRules[1] = nrs;
                        }
                        DecisionBackend nb = createBackendWithModel(cfg, sel.provider, sel.model,
                                cfg.getInt("timeoutMs", 30000));
                        if (nb != null && nb.hasKey()) {
                            backend.switchTo(nb);
                            System.out.println("[热载] 后端 " + sel.provider + " 已按新配置重建（模型 " + sel.model + "）");
                        } else {
                            System.out.println("[热载] " + sel.provider + " 缺凭据，保留原后端");
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        System.out.println("[热载] 失败，保留旧配置: " + e.getMessage());
                    }
                }
            }
        }, "config-hot-reload");
        watcher.setDaemon(true);
        watcher.start();
    }

    // ── 多表持久化 ──────────────────────────────────────────────────

    /** 启动 fail-fast 装载判定提示词：prompt.* 键指向的提示词文件经 PromptLoader 严格装载，
     *  路径未配置/不可读/空白即打印并退出（代码零内置文本，不给默认值兜底）。 */
    private static SemQuestTree.Prompts promptsOrFail(Config cfg) {
        try {
            return PromptLoader.of(cfg);
        } catch (IllegalStateException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            throw e;   // 不可达：使编译器认可返回值 definite assignment
        }
    }

    /** 启动 fail-fast 装载树级规则（prompt.rule.* 提示词文件内容），语义同 {@link #promptsOrFail}。 */
    private static String ruleOrFail(Config cfg, String key) {
        try {
            return PromptLoader.rule(cfg, key);
        } catch (IllegalStateException e) {
            System.err.println("[启动失败] " + e.getMessage());
            System.exit(1);
            throw e;
        }
    }

    /** 一张表 = 一棵树：内存态 + 磁盘文件（tables/&lt;id&gt;.json）+ 增长/修改回放记录。 */
    private static final class Table {
        final String id;
        String name;      // 可改名（PUT /api/tables）
        String note;      // 可改备注
        final long createdAt;
        final boolean demo;
        final Path file;
        final List<Map<String, Object>> grown;
        final List<Map<String, Object>> edits;
        volatile SemQuestTree tree;   // --reseed 时 schema 重建整体替换
        final ExtendStatus extend = new ExtendStatus();   // 延伸任务状态（内存态不落盘，viz 进度条轮询）
        final MaintStatus maint = new MaintStatus();      // 维护任务状态（内存态不落盘，viz 进度条轮询）
        final MaintState maintState = new MaintState();   // 代谢失败记忆（内存态不落盘，重启归零）

        Table(String id, String name, String note, long createdAt, boolean demo,
              SemQuestTree tree, Path file,
              List<Map<String, Object>> grown, List<Map<String, Object>> edits) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.createdAt = createdAt;
            this.demo = demo;
            this.tree = tree;
            this.file = file;
            this.grown = grown;
            this.edits = edits;
        }

        /** 延伸任务状态（内存态，不落盘，重启归零）：viz 进度条轮询用。phase: idle/running/done/error。 */
        static final class ExtendStatus {
            volatile String phase = "idle";
            volatile String node;
            volatile int round;          // 已完成轮数（每轮长出一个收编子枝）
            volatile int maxRounds;      // growth.extend.maxNew
            volatile int rejudged;       // 全程累计已重判条数（进度分子，多轮单调）
            volatile int totalDocs;      // 全程累计待整理存量（进度分母，各轮快照累加）
            volatile int keptBranches;   // 累计收编新枝数
            volatile int movedDocs;      // 累计迁入新枝的存量条数
            volatile int rolledBack;     // 累计回滚枝数
            volatile String current = "";  // 当前动作文案
            volatile String result;        // 结束原因 / 错误信息
            volatile long startedAt;
            volatile long finishedAt;
            volatile int genIn;          // 累计生成 tokens in（延伸各轮生成调用）
            volatile int genOut;         // 累计生成 tokens out

            LinkedHashMap<String, Object> snapshot() {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("phase", phase);
                m.put("node", node);
                m.put("round", round);
                m.put("maxRounds", maxRounds);
                m.put("rejudged", rejudged);
                m.put("totalDocs", totalDocs);
                m.put("keptBranches", keptBranches);
                m.put("movedDocs", movedDocs);
                m.put("rolledBack", rolledBack);
                m.put("current", current);
                m.put("result", result);
                m.put("genIn", genIn);
                m.put("genOut", genOut);
                m.put("startedAt", startedAt);
                m.put("finishedAt", finishedAt);
                return m;
            }
        }

        /** 维护任务状态（内存态，不落盘，重启归零）：viz 轮询用。phase: idle/running/done/error。 */
        static final class MaintStatus {
            volatile String phase = "idle";
            volatile int round;          // 本任务已完成轮数
            volatile int audits;         // 累计体检分支数
            volatile int rewrites;       // 累计判据重写成功数
            volatile int recycled;       // 累计摘枝数
            volatile int grew;           // 累计生长保留枝数
            volatile int genIn;          // 累计生成 tokens in
            volatile int genOut;         // 累计生成 tokens out
            volatile String current = "";  // 当前动作文案
            volatile String result;        // 结束原因 / 错误信息
            volatile long startedAt;
            volatile long finishedAt;

            LinkedHashMap<String, Object> snapshot() {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("phase", phase);
                m.put("round", round);
                m.put("audits", audits);
                m.put("rewrites", rewrites);
                m.put("recycled", recycled);
                m.put("grew", grew);
                m.put("genIn", genIn);
                m.put("genOut", genOut);
                m.put("current", current);
                m.put("result", result);
                m.put("startedAt", startedAt);
                m.put("finishedAt", finishedAt);
                return m;
            }
        }

        /**
         * 代谢失败记忆（内存态，不落盘，重启归零）：三类「重新生成」的失败证据——下次生成以 {attempts}
         * 段带回，让模型吸取教训而非开盲盒重抽。解冻统一规则：证据变了才许再试（生长=兜底样本换血过半；
         * 重写=分支有新收编），无新证据维持冻结。
         */
        static final class MaintState {
            int age;                                                                            // 全局轮计数（出生保护期对照）
            final Map<String, Integer> branchBorn = new LinkedHashMap<String, Integer>();       // 分支 → 出生轮
            final Map<String, Integer> lastAudit = new LinkedHashMap<String, Integer>();        // 分支 → 最近体检轮
            final Map<String, GrowFail> growthFails = new LinkedHashMap<String, GrowFail>();    // 层 → 生长失败（冻结中）
            final Map<String, RewriteFail> rewriteFails = new LinkedHashMap<String, RewriteFail>();  // 分支 → 重写失败
            final Map<String, String> extendFails = new LinkedHashMap<String, String>();        // 节点 → 上次延伸零收编结论
            final Map<String, Integer> recycleStreak = new LinkedHashMap<String, Integer>();    // 层 → 连续摘枝数（防抖）
        }

        /** 兜底生长失败记忆（层粒度）：最近一次提案 + 参与验收的积压样本 id 集（解冻换血判定）+ 失败轮次与结论。 */
        static final class GrowFail {
            final List<String[]> proposals = new ArrayList<String[]>();   // 最近一次提案 [{name, yes}]
            final Set<String> sampleIds = new HashSet<String>();
            int rounds;
            String note;
        }

        /** 判据重写失败记忆（分支粒度）：上次提案与保住率；连续 fails≥2 冻结，解冻=分支新收编数超过 frozenAtDocs。 */
        static final class RewriteFail {
            String proposedYes;
            double keepRate;
            final List<String> kicked = new ArrayList<String>();   // 被踢旧成员「id: 内容截断」（≤10 条）
            int fails;
            int frozenAtDocs;
            String note;
        }
    }

    /** 载入 tables/ 下全部 &lt;id&gt;.json（按 createdAt 排序），零模型调用回放；坏文件跳过不拦启动。 */
    private static void loadTables(ConcurrentHashMap<String, Table> tables, Path dir,
                                   DecisionBackend backend) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json");
        try {
            for (Path p : ds) files.add(p);
        } finally {
            ds.close();
        }
        List<Table> loaded = new ArrayList<Table>();
        for (Path f : files) {
            try {
                Map<String, Object> m = Json.parseObject(new String(Files.readAllBytes(f), UTF8));
                loaded.add(loadTable(m, backend, f));
            } catch (Exception e) {
                System.out.println("[载入] 跳过坏表文件 " + f.getFileName() + ": " + e.getMessage());
            }
        }
        Collections.sort(loaded, new Comparator<Table>() {
            public int compare(Table a, Table b) {
                int c = Long.compare(a.createdAt, b.createdAt);
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
        for (Table t : loaded) tables.put(t.id, t);
    }

    /** 单表装载：meta + 内嵌 schema 建树 → 回放 grown/edits → 补兜底叶 → 挂正文。 */
    private static Table loadTable(Map<String, Object> m, DecisionBackend backend, Path file)
            throws IOException {
        Object idv = m.get("id");
        String id = idv instanceof String && !((String) idv).trim().isEmpty()
                ? (String) idv : file.getFileName().toString().replaceAll("\\.json$", "");
        Object nv = m.get("name");
        String name = nv instanceof String && !((String) nv).trim().isEmpty() ? (String) nv : id;
        String note = m.get("note") instanceof String ? (String) m.get("note") : "";
        long createdAt = m.get("createdAt") instanceof Number ? ((Number) m.get("createdAt")).longValue() : 0L;
        boolean demo = Boolean.TRUE.equals(m.get("demo"));
        if (!(m.get("schema") instanceof Map)) {
            throw new IOException("缺 schema: " + file.getFileName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) m.get("schema");
        SemQuestTree tree = SemQuestTree.fromMap(schema, backend);
        List<Map<String, Object>> grown = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> edits = new ArrayList<Map<String, Object>>();
        int docs = replayTableData(tree, m, grown, edits);
        System.out.println("[载入] " + file.getFileName() + "「" + name + "」"
                + (tree.choiceMode() ? "choice" : "noul") + "：落位 " + docs
                + " · 增长 " + grown.size() + " · 修改 " + edits.size() + "（零模型调用）");
        return new Table(id, name, note, createdAt, demo, tree, file, grown, edits);
    }

    /**
     * 回放增长/修改记录并挂正文（零模型调用）：先 grown 建新叶（attachPersisted 的
     * resolveLeaf 才可达），再 edits 对齐改名/判据，补齐兜底叶后挂 docs。
     */
    private static int replayTableData(SemQuestTree tree, Map<String, Object> m,
                                       List<Map<String, Object>> grown, List<Map<String, Object>> edits) {
        if (m.get("grown") instanceof List) {
            for (Object o : (List<?>) m.get("grown")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> g = (Map<?, ?>) o;
                String parent = String.valueOf(g.get("parent"));
                String name = String.valueOf(g.get("name"));
                String yes = String.valueOf(g.get("yes"));
                String no = String.valueOf(g.get("no"));
                if (tree.hasNode(name)) {
                    // 多表快照的 schema 已含增长节点（persistTable 存活树全量），记录仅补建缺失者
                    continue;
                }
                if (!tree.hasNode(parent)) {
                    System.out.println("[回放] 跳过增长记录 " + name + "：父节点 " + parent + " 已不存在");
                    continue;
                }
                tree.addNode(parent, name, yes, no);
                LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                rec.put("parent", parent);
                rec.put("name", name);
                rec.put("yes", yes);
                rec.put("no", no);
                grown.add(rec);
            }
        }
        if (m.get("edits") instanceof List) {
            for (Object o : (List<?>) m.get("edits")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> e = (Map<?, ?>) o;
                String name = String.valueOf(e.get("name"));
                String newName = e.get("newName") == null ? null : String.valueOf(e.get("newName"));
                String yes = e.get("yes") == null ? null : String.valueOf(e.get("yes"));
                String no = e.get("no") == null ? null : String.valueOf(e.get("no"));
                if (!tree.hasNode(name)) {
                    System.out.println("[回放] 跳过修改记录 " + name + "：节点已不存在");
                    continue;
                }
                tree.editNode(name, newName, yes, no);
                LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                rec.put("name", name);
                if (newName != null) rec.put("newName", newName);
                if (yes != null) rec.put("yes", yes);
                if (no != null) rec.put("no", no);
                edits.add(rec);
            }
        }
        tree.ensureOtherLeaves();   // 回放 addNode 建出的内部节点补齐兜底叶（幂等）
        int n = 0;
        if (m.get("docs") instanceof List) {
            for (Object o : (List<?>) m.get("docs")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> d = (Map<?, ?>) o;
                List<String> leaves = new ArrayList<String>();
                if (d.get("leaves") instanceof List) {
                    for (Object l : (List<?>) d.get("leaves")) {
                        leaves.add(String.valueOf(l));
                    }
                }
                tree.attachPersisted(String.valueOf(d.get("id")), String.valueOf(d.get("content")), leaves);
                n++;
            }
        }
        return n;
    }

    /**
     * 旧版单树（driver-intent-schema.json + tree-data.json）迁移为 demo 表「司机意图」：
     * 纯结构迁移 + 快照回放，零模型调用；tree-data.json 缺失时为空表（可 --reseed 补种）。
     */
    private static Table migrateLegacy(Path schemaFile, Path dataFile, DecisionBackend backend,
                                       double gateDefault, Path tablesDir) throws IOException {
        Map<String, Object> schema = Json.parseObject(new String(Files.readAllBytes(schemaFile), UTF8));
        if (!schema.containsKey("noulGate") && !schema.containsKey("gate")) schema.put("noulGate", gateDefault);
        SemQuestTree tree = SemQuestTree.fromMap(schema, backend);
        String id = newId(tablesDir);
        Path file = tablesDir.resolve(id + ".json");
        List<Map<String, Object>> grown = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> edits = new ArrayList<Map<String, Object>>();
        if (Files.exists(dataFile)) {
            Map<String, Object> data = Json.parseObject(new String(Files.readAllBytes(dataFile), UTF8));
            replayTableData(tree, data, grown, edits);
        }
        Table t = new Table(id, "司机意图", "", System.currentTimeMillis(), true, tree, file, grown, edits);
        persistTable(t);
        return t;
    }

    /** demo 表重播：schema 重建干净树（清增长/修改/落位）后 Demo.DOCS 重新下行（调模型）。 */
    private static void reseedTable(Table t, DecisionBackend backend) throws IOException {
        Map<String, Object> sm = t.tree.schemaMap();
        SemQuestTree fresh = SemQuestTree.fromMap(sm, backend);
        t.tree = fresh;
        t.grown.clear();
        t.edits.clear();
        for (String[] d : Demo.DOCS) {
            fresh.add(d[0], d[1]);
        }
        persistTable(t);
        System.out.println("[reseed] 表「" + t.name + "」种子重播完成（" + fresh.docCount() + " 条）");
        System.out.println(fresh.statsLine());
    }

    /** 新表 id：t-&lt;base36 时间戳&gt;，撞车则追加 nanoTime 尾巴。 */
    private static String newId(Path tablesDir) {
        String id = "t-" + Long.toString(System.currentTimeMillis(), 36);
        while (Files.exists(tablesDir.resolve(id + ".json"))) {
            id = "t-" + Long.toString(System.currentTimeMillis(), 36)
                    + "-" + Long.toString(System.nanoTime() % 1000000L, 36);
        }
        return id;
    }

    /**
     * 表落盘：meta + 内嵌 schema + 落位快照 + 增长/修改记录。
     * 同表并发写以 file 对象互斥；先写 &lt;id&gt;.json.tmp 再原子 rename——写一半崩溃不损旧快照。
     */
    private static void persistTable(Table t) throws IOException {
        synchronized (t.file) {
            Files.createDirectories(t.file.getParent());
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", t.id);
            m.put("name", t.name);
            m.put("mode", t.tree.choiceMode() ? "choice" : "noul");
            m.put("note", t.note);
            m.put("createdAt", t.createdAt);
            m.put("demo", t.demo);
            m.put("schema", t.tree.schemaMap());
            m.put("docs", t.tree.docsSnapshot());
            m.put("grown", t.grown);
            m.put("edits", t.edits);
            Path tmp = t.file.resolveSibling(t.file.getFileName() + ".tmp");
            Files.write(tmp, Json.write(m).getBytes(UTF8));
            try {
                Files.move(tmp, t.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, t.file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 表清单/详情条目。 */
    private static Map<String, Object> tableMeta(Table t) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", t.id);
        m.put("name", t.name);
        m.put("mode", t.tree.choiceMode() ? "choice" : "noul");
        m.put("note", t.note);
        m.put("createdAt", t.createdAt);
        m.put("demo", t.demo);
        m.put("docCount", t.tree.docCount());
        return m;
    }

    // ── 处理器 ──────────────────────────────────────────────────────

    private static abstract class BaseHandler {

        final void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
            byte[] b = body.getBytes(UTF8);
            ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            ex.sendResponseHeaders(status, b.length);
            OutputStream os = ex.getResponseBody();
            os.write(b);
            os.close();
        }

        final void json(HttpExchange ex, int status, String body) throws IOException {
            send(ex, status, body, "application/json");
        }
    }

    /** GET / → viz.html（磁盘实时读，热改热生效）。 */
    private static final class StaticHandler extends BaseHandler implements HttpHandler {
        private final Path file;

        StaticHandler(Path file) {
            this.file = file;
        }

        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!Files.exists(file)) {
                json(ex, 500, "{\"error\":" + Json.str("viz.html 不存在: " + file) + "}");
                return;
            }
            send(ex, 200, new String(Files.readAllBytes(file), UTF8), "text/html");
        }
    }

    /** GET /api/tables 表清单；POST 新建表 {name, mode, note?}；PUT ?id= 改名/备注 {name?, note?}；DELETE ?id= 删表（内存+文件）。 */
    private static final class TablesHandler extends BaseHandler implements HttpHandler {
        private final ConcurrentHashMap<String, Table> tables;
        private final Config cfg;
        private final SwitchableBackend backend;
        private final Path tablesDir;

        TablesHandler(ConcurrentHashMap<String, Table> tables, Config cfg,
                      SwitchableBackend backend, Path tablesDir) {
            this.tables = tables;
            this.cfg = cfg;
            this.backend = backend;
            this.tablesDir = tablesDir;
        }

        public void handle(HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                List<Object> out = new ArrayList<Object>();
                for (Table t : tables.values()) {
                    out.add(tableMeta(t));
                }
                json(ex, 200, Json.write(out));
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    String name = text(req, "name").trim();
                    Object mv = req.get("mode");
                    String mode = mv instanceof String ? ((String) mv).trim().toLowerCase() : "";
                    if (!"noul".equals(mode) && !"choice".equals(mode)) {
                        throw new IllegalArgumentException("mode 须为 noul 或 choice（建表时定死，不可后改）");
                    }
                    for (Table t : tables.values()) {
                        if (t.name.equals(name)) throw new IllegalArgumentException("表名已存在: " + name);
                    }
                    String note = req.get("note") instanceof String ? ((String) req.get("note")).trim() : "";
                    String id = newId(tablesDir);
                    SemQuestTree tree = SemQuestTree.createEmpty(backend, "choice".equals(mode),
                            cfg.getDouble("noulGate", 0.7), cfg.getDouble("choiceGate", 0.6),
                            PromptLoader.rule(cfg, "prompt.rule.add"),
                            PromptLoader.rule(cfg, "prompt.rule.search"));
                    tree.setQuestCacheCap(Math.max(0, cfg.getInt("questCacheSize", 256)));
                    tree.setPrompts(PromptLoader.of(cfg));
                    Table t = new Table(id, name, note, System.currentTimeMillis(), false, tree,
                            tablesDir.resolve(id + ".json"),
                            new ArrayList<Map<String, Object>>(), new ArrayList<Map<String, Object>>());
                    persistTable(t);
                    tables.put(id, t);
                    System.out.println("[建表] 「" + name + "」(" + mode + ") → " + t.file.getFileName());
                    json(ex, 200, Json.write(tableMeta(t)));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
                String id = param(ex, "id");
                Table t = id == null ? null : tables.get(id);
                if (t == null) {
                    json(ex, 404, "{\"error\":" + Json.str("未知表: " + id) + "}");
                    return;
                }
                synchronized (t) {   // 改名/备注与 /api/t 各动作共用表锁
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    boolean changed = false;
                    if (req.get("name") instanceof String) {
                        String name = ((String) req.get("name")).trim();
                        if (name.isEmpty()) throw new IllegalArgumentException("表名不可为空");
                        if (!name.equals(t.name)) {
                            for (Table o : tables.values()) {
                                if (o != t && o.name.equals(name)) {
                                    throw new IllegalArgumentException("表名已存在: " + name);
                                }
                            }
                            System.out.println("[改表] 「" + t.name + "」→「" + name + "」(" + id + ")");
                            t.name = name;
                            changed = true;
                        }
                    }
                    if (req.get("note") instanceof String) {
                        String note = ((String) req.get("note")).trim();
                        if (!note.equals(t.note)) {
                            t.note = note;
                            changed = true;
                        }
                    }
                    if (changed) persistTable(t);   // 名字/备注进 tables/<id>.json，重启保留
                    json(ex, 200, Json.write(tableMeta(t)));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                String id = param(ex, "id");
                Table t = id == null ? null : tables.remove(id);
                if (t == null) {
                    json(ex, 404, "{\"error\":" + Json.str("未知表: " + id) + "}");
                    return;
                }
                synchronized (t) {   // 等在途动作出锁再删文件，防删后回写复活
                    Files.deleteIfExists(t.file);
                }
                System.out.println("[删表] 「" + t.name + "」(" + id + ")");
                json(ex, 200, "{\"ok\":true,\"id\":" + Json.str(id) + "}");
                return;
            }
            ex.sendResponseHeaders(405, -1);
        }
    }

    /** /api/t/&lt;id&gt;/&lt;action&gt; 分发：meta(缺省)/tree/leaf/node/gate/quest/add/addBatch。 */
    private static final class TableRouter extends BaseHandler implements HttpHandler {
        private static final String PREFIX = "/api/t/";
        private final ConcurrentHashMap<String, Table> tables;
        private final Config cfg;               // 生长编排（growth.*）实时读取
        private final SwitchableBackend backend;
        private final GrowthState gstate;       // 生长开关/生成模型运行时覆盖（viz 🌱）

        TableRouter(ConcurrentHashMap<String, Table> tables, Config cfg, SwitchableBackend backend, GrowthState gstate) {
            this.tables = tables;
            this.cfg = cfg;
            this.backend = backend;
            this.gstate = gstate;
        }

        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String rest = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            String action = slash < 0 ? "" : rest.substring(slash + 1);
            Table t = id.isEmpty() ? null : tables.get(id);
            if (t == null || action.contains("/")) {
                json(ex, 404, "{\"error\":" + Json.str("未知表: " + id) + "}");
                return;
            }
            // 表级锁：同表所有动作（含判定与落盘）串行执行，不同表并行——树与统计计数非线程安全
            synchronized (t) {
                route(t, ex, action);
            }
        }

        private void route(Table t, HttpExchange ex, String action) throws IOException {
            if (action.isEmpty() || "meta".equals(action)) {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    return;
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(tableMeta(t));
                out.put("meta", t.tree.metaMap());
                json(ex, 200, Json.write(out));
                return;
            }
            if ("tree".equals(action)) {
                new TreeHandler().handle(t, ex);
            } else if ("leaf".equals(action)) {
                new LeafHandler().handle(t, ex);
            } else if ("docs".equals(action)) {
                new DocsHandler().handle(t, ex);
            } else if ("node".equals(action)) {
                new NodeHandler().handle(t, ex);
            } else if ("gate".equals(action)) {
                new GateHandler().handle(t, ex);
            } else if ("quest".equals(action)) {
                new QuestHandler().handle(t, ex);
            } else if ("add".equals(action)) {
                new AddHandler(cfg, backend, gstate).handle(t, ex);
            } else if ("addBatch".equals(action)) {
                new AddBatchHandler(cfg, backend, gstate).handle(t, ex);
            } else if ("extend".equals(action)) {
                new ExtendHandler(cfg, backend, gstate).handle(t, ex);
            } else if ("maint".equals(action)) {
                new MaintHandler(cfg, backend, gstate).handle(t, ex);
            } else if ("doc".equals(action)) {
                new DocHandler().handle(t, ex);
            } else {
                json(ex, 404, "{\"error\":" + Json.str("未知动作: " + action) + "}");
            }
        }
    }

    /** GET /api/t/&lt;id&gt;/tree → 全树结构（附表名/备注），全部展开用。 */
    private static final class TreeHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.treeMap());
            out.put("tableId", t.id);
            out.put("name", t.name);
            out.put("note", t.note);
            json(ex, 200, Json.write(out));
        }
    }

    /** GET /api/t/&lt;id&gt;/leaf?name=X → 节点详情（叶与内部节点共用）；未知节点 404。 */
    private static final class LeafHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            String name = param(ex, "name");
            Map<String, Object> info = name == null ? null : t.tree.leafInfo(name);
            if (info == null) {
                json(ex, 404, "{\"error\":" + Json.str("未知节点: " + (name == null ? "(缺 name 参数)" : name)) + "}");
                return;
            }
            json(ex, 200, Json.write(info));
        }
    }

    /** GET /api/t/&lt;id&gt;/docs?node=X → 节点直取：子树全部叶的落位内容（零模型调用）；limit 缺省 500、offset 缺省 0，超出给 truncated。 */
    private static final class DocsHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String name = param(ex, "node");
            if (name == null || name.trim().isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("缺 node 参数") + "}");
                return;
            }
            int limit = 500;
            String lim = param(ex, "limit");
            if (lim != null && !lim.trim().isEmpty()) {
                try {
                    limit = Integer.parseInt(lim.trim());
                } catch (NumberFormatException e) {
                    json(ex, 400, "{\"error\":" + Json.str("limit 须为整数") + "}");
                    return;
                }
                if (limit < 0) {
                    json(ex, 400, "{\"error\":" + Json.str("limit 不可为负") + "}");
                    return;
                }
            }
            int offset = 0;
            String off = param(ex, "offset");
            if (off != null && !off.trim().isEmpty()) {
                try {
                    offset = Integer.parseInt(off.trim());
                } catch (NumberFormatException e) {
                    json(ex, 400, "{\"error\":" + Json.str("offset 须为整数") + "}");
                    return;
                }
                if (offset < 0) {
                    json(ex, 400, "{\"error\":" + Json.str("offset 不可为负") + "}");
                    return;
                }
            }
            SemQuestTree.NodeDocs nd;
            try {
                nd = t.tree.nodeDocs(name.trim());
            } catch (IllegalArgumentException e) {
                json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "未知节点" : e.getMessage()) + "}");
                return;
            }
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("node", nd.node);
            out.put("leaves", nd.leaves);
            out.put("total", nd.docs.size());
            List<Object> docs = new ArrayList<Object>();
            int from = Math.min(offset, nd.docs.size());
            int to = Math.min(from + limit, nd.docs.size());
            for (int i = from; i < to; i++) {
                SemQuestTree.NodeDoc d = nd.docs.get(i);
                LinkedHashMap<String, Object> dm = new LinkedHashMap<String, Object>();
                dm.put("id", d.id);
                dm.put("content", d.content);
                dm.put("viaLeaves", d.viaLeaves);
                docs.add(dm);
            }
            out.put("docs", docs);
            if (to < nd.docs.size()) out.put("truncated", true);
            json(ex, 200, Json.write(out));
        }
    }

    /** GET /api/t/&lt;id&gt;/node?name=X 单层展开；POST 增长；PUT 修改节点；DELETE 删除子树。 */
    private static final class NodeHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String name = param(ex, "name");
                Map<String, Object> node = t.tree.nodeLevel(name == null || name.isEmpty() ? null : name);
                if (node == null) {
                    json(ex, 404, "{\"error\":" + Json.str("未知节点: " + name) + "}");
                    return;
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
                out.put("node", node);
                json(ex, 200, Json.write(out));
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    String parent = text(req, "parent");
                    String name = text(req, "name");
                    // choice 模式选项描述可选（留空用选项名本身）；noul 模式 yes 判据必填
                    String yes = t.tree.choiceMode()
                            ? (req.get("yes") instanceof String ? ((String) req.get("yes")).trim() : "")
                            : text(req, "yes");
                    String no = req.get("no") instanceof String ? (String) req.get("no") : "";
                    SemQuestTree.GrowReport rep = t.tree.growNode(parent, name, yes, no);
                    LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                    rec.put("parent", parent);
                    rec.put("name", name.trim());
                    rec.put("yes", yes.trim());
                    rec.put("no", no);
                    t.grown.add(rec);
                    persistTable(t);
                    json(ex, 200, Json.write(reportMap(rep)));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                } catch (IOException e) {
                    json(ex, 500, "{\"error\":" + Json.str("存量重判失败: " + e.getMessage()) + "}");
                }
                return;
            }
            if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    String name = text(req, "name");
                    String newName = req.get("newName") instanceof String ? (String) req.get("newName") : null;
                    String yes = req.get("yes") instanceof String ? (String) req.get("yes") : null;
                    String no = req.get("no") instanceof String ? (String) req.get("no") : null;
                    SemQuestTree.EditReport rep = t.tree.editNode(name, newName, yes, no);
                    LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                    rec.put("name", name.trim());
                    if (!rep.newName.equals(rep.oldName)) rec.put("newName", rep.newName);
                    rec.put("yes", rep.yes);
                    rec.put("no", rep.no);
                    t.edits.add(rec);
                    persistTable(t);
                    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
                    out.put("oldName", rep.oldName);
                    out.put("newName", rep.newName);
                    out.put("parent", rep.parent);
                    out.put("yes", rep.yes);
                    out.put("no", rep.no);
                    json(ex, 200, Json.write(out));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                return;
            }
            if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    String name = param(ex, "name");
                    SemQuestTree.RemoveReport rep = t.tree.removeNode(name);
                    // 同步清掉增长/修改记录（子树内的名字都失效），否则重启回放会复活已删节点
                    java.util.Set<String> gone = new java.util.HashSet<String>(rep.removed);
                    for (java.util.Iterator<Map<String, Object>> it = t.grown.iterator(); it.hasNext(); ) {
                        Map<String, Object> g = it.next();
                        if (gone.contains(String.valueOf(g.get("parent")))
                                || gone.contains(String.valueOf(g.get("name")))) {
                            it.remove();
                        }
                    }
                    for (java.util.Iterator<Map<String, Object>> it = t.edits.iterator(); it.hasNext(); ) {
                        Map<String, Object> e = it.next();
                        Object nn = e.get("newName");
                        if (gone.contains(String.valueOf(e.get("name")))
                                || (nn != null && gone.contains(String.valueOf(nn)))) {
                            it.remove();
                        }
                    }
                    persistTable(t);
                    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
                    out.put("parent", rep.parent);
                    out.put("removed", rep.removed);
                    out.put("docsRemoved", rep.docsRemoved);
                    json(ex, 200, Json.write(out));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                return;
            }
            ex.sendResponseHeaders(405, -1);
        }
    }

    /** POST /api/t/&lt;id&gt;/gate {gate}（noul 门控）/ {choiceGate}（choice 采纳线）→ 调参并落盘。 */
    private static final class GateHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            try {
                Map<String, Object> req = Json.parseObject(readBody(ex));
                Object g = req.get("noulGate");
                Object cg = req.get("choiceGate");
                if (!(g instanceof Number) && !(cg instanceof Number)) {
                    throw new IllegalArgumentException("缺 noulGate / choiceGate 数值字段");
                }
                if (g instanceof Number) t.tree.setGate(((Number) g).doubleValue());
                if (cg instanceof Number) t.tree.setChoiceGate(((Number) cg).doubleValue());
                persistTable(t);
                json(ex, 200, Json.write(t.tree.metaMap()));
            } catch (IllegalArgumentException e) {
                json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
            }
        }
    }

    private static Map<String, Object> reportMap(SemQuestTree.GrowReport rep) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("parent", rep.parent);
        out.put("node", rep.node);
        List<Object> moves = new ArrayList<Object>();
        int moved = 0;
        for (SemQuestTree.Move mv : rep.moves) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("docId", mv.docId);
            m.put("prob", mv.prob);
            m.put("moved", mv.moved);
            moves.add(m);
            if (mv.moved) moved++;
        }
        out.put("moves", moves);
        out.put("judged", rep.moves.size());
        out.put("moved", moved);
        return out;
    }

    /** GET /api/models?provider=X → 决策模型列表（手动清单优先 > 上游零 token 元数据 > 静态候选）。 */
    private static final class ModelsHandler extends BaseHandler implements HttpHandler {
        private final Config cfg;
        private final Selection sel;
        private final int timeoutMs;

        ModelsHandler(Config cfg, Selection sel, int timeoutMs) {
            this.cfg = cfg;
            this.sel = sel;
            this.timeoutMs = timeoutMs;
        }

        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String p = param(ex, "provider");
            String provider = p == null || p.trim().isEmpty() ? sel.provider : normalizeProvider(p);
            String cur = provider.equals(sel.provider) ? sel.model : currentModelOf(cfg, provider);

            List<Object> models;
            String source;
            List<Object> manual = manualModels(cfg, provider);
            if (!manual.isEmpty()) {
                // 手动清单优先生效：不请求上游，改 properties 保存即热载
                models = manual;
                source = "config";
            } else {
                DecisionBackend tmp = createBackendWithModel(cfg, provider, cur, 10_000);
                if (tmp == null || !tmp.hasKey()) {
                    models = staticModels(cfg, provider);
                    source = "fallback";
                } else {
                    models = tmp.listModels();
                    if (models == null) {
                        models = staticModels(cfg, provider);
                        source = "fallback";
                    } else {
                        source = "upstream";
                        if ("openrouter".equals(provider)) {
                            prependDecisionsModel(models, cfg.get("openrouter.model", "baseten/mammoth-1:free"));
                        } else if ("bailian".equals(provider)) {
                            prependDecisionsModel(models, cfg.get("bailian.model", "decision-model-preview"));
                        }
                    }
                }
            }

            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("provider", provider);
            out.put("source", source);
            out.put("current", cur);
            out.put("models", models);
            json(ex, 200, Json.write(out));
        }
    }

    /** GET 当前 provider/model；POST 热切换 {provider,model}：仅凭据校验 + 替换委托，零推理调用。 */
    private static final class ModelHandler extends BaseHandler implements HttpHandler {
        private final Config cfg;
        private final SwitchableBackend switchable;
        private final Selection sel;

        ModelHandler(Config cfg, SwitchableBackend switchable, Selection sel) {
            this.cfg = cfg;
            this.switchable = switchable;
            this.sel = sel;
        }

        public void handle(HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("backend", switchable.name());
                out.put("provider", sel.provider);
                out.put("model", sel.model);
                json(ex, 200, Json.write(out));
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    String provider = normalizeProvider(text(req, "provider"));
                    String model = text(req, "model").trim();
                    DecisionBackend nb = createBackendWithModel(cfg, provider, model, cfg.getInt("timeoutMs", 30000));
                    if (nb == null) {
                        json(ex, 400, "{\"error\":" + Json.str("未知 provider: " + provider
                                + "（可选 mock | jev | openrouter | bailian）") + "}");
                        return;
                    }
                    if (!nb.hasKey()) {
                        json(ex, 400, "{\"error\":" + Json.str(provider
                                + " 缺凭据：请在 semquest.properties 配置 " + provider + ".apikey") + "}");
                        return;
                    }
                    switchable.switchTo(nb);
                    sel.provider = provider;
                    sel.model = model;
                    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("backend", nb.name());
                    out.put("provider", provider);
                    out.put("model", model);
                    json(ex, 200, Json.write(out));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                return;
            }
            ex.sendResponseHeaders(405, -1);
        }
    }

    /**
     * GET  /api/growth → 生长开关/生成模型有效值 + openrouter chat 模型清单（手动 growth.models 优先
     * > 上游 /api/v1/models 零 token 元数据 > 静态候选；免费款排前，当前生效模型缺席置顶补入）。
     * POST /api/growth {enabled?, model?} → 运行时热改（内存覆盖，零推理；重启回 growth.* 配置值）。
     */
    private static final class GrowthHandler extends BaseHandler implements HttpHandler {
        private final Config cfg;
        private final GrowthState gstate;
        private final int timeoutMs;

        GrowthHandler(Config cfg, GrowthState gstate, int timeoutMs) {
            this.cfg = cfg;
            this.gstate = gstate;
            this.timeoutMs = timeoutMs;
        }

        public void handle(HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LinkedHashMap<String, Object> out = stateMap();
                putModelList(out);
                json(ex, 200, Json.write(out));
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                try {
                    Map<String, Object> req = Json.parseObject(readBody(ex));
                    boolean touched = false;
                    Object ev = req.get("enabled");
                    if (ev instanceof Boolean) {
                        gstate.enabled = (Boolean) ev;
                        touched = true;
                        System.out.println("[生长] 开关 → " + ev + "（viz 运行时覆盖，重启回 growth.enabled 配置值）");
                    }
                    Object mv0 = req.get("model");   // 可选字段：宽容读取（text() 对缺字段抛异常）
                    String mv = mv0 instanceof String ? ((String) mv0).trim() : "";
                    if (!mv.isEmpty()) {
                        gstate.model = mv;
                        touched = true;
                        System.out.println("[生长] 生成模型 → " + mv + "（viz 运行时覆盖，重启回 growth.model 配置值）");
                    }
                    if (!touched) {
                        json(ex, 400, "{\"error\":" + Json.str("缺 enabled / model 字段") + "}");
                        return;
                    }
                    json(ex, 200, Json.write(stateMap()));
                } catch (IllegalArgumentException e) {
                    json(ex, 400, "{\"error\":" + Json.str(e.getMessage() == null ? "非法请求" : e.getMessage()) + "}");
                }
                return;
            }
            ex.sendResponseHeaders(405, -1);
        }

        /** 生长有效值：viz 运行时覆盖优先，回退 semquest.properties 的 growth.*（热载）。 */
        private LinkedHashMap<String, Object> stateMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("enabled", Boolean.valueOf(growthEnabledOf(cfg, gstate)));
            out.put("model", growthModelOf(cfg, gstate));
            String key = cfg.get("openrouter.apikey", null);
            out.put("hasKey", Boolean.valueOf(key != null && !key.trim().isEmpty()));
            return out;
        }

        /** chat 模型清单挂到 out（growth.models 手动清单优先 > 上游零 token 元数据 > 静态候选）。 */
        private void putModelList(LinkedHashMap<String, Object> out) {
            String cur = growthModelOf(cfg, gstate);
            List<Object> models;
            String source;
            String raw = cfg.get("growth.models", "");
            if (!raw.trim().isEmpty()) {
                // 手动清单优先生效：不请求上游，改 properties 保存即热载
                models = new ArrayList<Object>();
                for (String id : raw.split("[,，]")) {
                    String v = id.trim();
                    if (v.isEmpty()) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", v);
                    m.put("free", Boolean.valueOf(v.endsWith(":free")));
                    models.add(m);
                }
                source = "config";
            } else {
                String key = cfg.get("openrouter.apikey", null);
                try {
                    String body = Http.get("https://openrouter.ai/api/v1/models",
                            key == null || key.trim().isEmpty() ? null : key, Math.min(timeoutMs, 10_000));
                    models = parseChatModels(body);
                    if (models.isEmpty()) throw new IOException("上游清单为空");
                    source = "upstream";
                } catch (Exception e) {
                    models = staticGrowthModels(cur);
                    source = "fallback";
                }
            }
            // 当前生效模型缺席时置顶补入（决策模型清单同款约定）
            boolean has = false;
            for (Object o : models) {
                if (cur.equals(((Map<?, ?>) o).get("id"))) { has = true; break; }
            }
            if (!has) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", cur);
                m.put("free", Boolean.valueOf(cur.endsWith(":free")));
                models.add(0, m);
            }
            out.put("source", source);
            out.put("models", models);
        }
    }

    // ── 树体代谢维护（add/addBatch 落兜底积压触发）：回收 → 体检 → 重写 → 批量生长 ──

    /**
     * 积压检查（表锁内调用）：全树各生长层的兜底积压达阈值（ROOT 层用 growth.bufferRoot 首批骨架规划，
     * 其余层用 growth.bufferMin）→ 提交后台维护任务；running 中幂等忽略。add/addBatch 落位后与启动补跑共用。
     */
    private static void maybeSubmitMaint(Table t, Config cfg, SwitchableBackend backend, GrowthState gstate) {
        if (!growthEnabledOf(cfg, gstate)) return;
        if ("running".equals(t.maint.phase)) return;
        int rootThreshold = cfg.getInt("growth.bufferRoot", 30);
        int layerThreshold = cfg.getInt("growth.bufferMin", 5);
        List<String[]> layers = t.tree.layerList();   // ROOT 在首
        for (int i = 0; i < layers.size(); i++) {
            int threshold = i == 0 ? rootThreshold : layerThreshold;
            if (Integer.parseInt(layers.get(i)[1]) >= threshold) {
                submitMaint(t, cfg, backend, gstate);
                return;
            }
        }
    }

    /** 提交维护任务（表锁内调用）：running 中幂等忽略；重置状态后交后台单工池（与延伸任务排队互斥）。 */
    private static void submitMaint(final Table t, final Config cfg, final SwitchableBackend backend, final GrowthState gstate) {
        Table.MaintStatus st = t.maint;
        if ("running".equals(st.phase)) return;
        st.phase = "running";
        st.round = 0;
        st.audits = 0;
        st.rewrites = 0;
        st.recycled = 0;
        st.grew = 0;
        st.genIn = 0;
        st.genOut = 0;
        st.current = "排队中…";
        st.result = null;
        st.startedAt = System.currentTimeMillis();
        st.finishedAt = 0;
        MAINT_POOL.execute(new Runnable() {
            public void run() {
                maintTask(t, cfg, backend, gstate);
            }
        });
    }

    /**
     * 维护任务体（后台单工线程，与延伸任务排队互斥）：每轮「回收扫描（零模型）→ 判据体检（轮转限额）→
     * 重写验收（体检命中才走）→ 兜底批量生长（各层独立）」。模型调用全程不持表锁，锁段只包结构变更、
     * 指针移动与落盘——add/quest 照常并行（大脑夜间加固，白天照常运转）。
     * 单任务轮数上限 growth.maintRounds（默认 4），收工前再查一遍积压，仍达标（有未冻结层）则重新提交
     * 续跑，避免一次任务占死单工池饿死延伸。失败证据全程记入 MaintState，下次生成以 {attempts} 带回。
     */
    private static void maintTask(final Table t, final Config cfg, final SwitchableBackend backend, final GrowthState gstate) {
        Table.MaintStatus st = t.maint;
        Table.MaintState ms = t.maintState;
        try {
            CriterionGenerator gen = buildMaintGenerator(cfg, backend, gstate);
            if (gen == null) {
                st.phase = "error";
                st.result = "缺 openrouter 凭据，无法生成判据";
                st.finishedAt = System.currentTimeMillis();
                return;
            }
            int maxRounds = cfg.getInt("growth.maintRounds", 4);
            int round = 0;
            for (; round < maxRounds; round++) {
                if (!growthEnabledOf(cfg, gstate)) {
                    st.result = "生长开关已关闭，维护中止";
                    break;
                }
                st.round = round + 1;
                ms.age++;
                st.current = "第 " + st.round + " 轮：回收扫描";
                synchronized (t) {
                    recycleSweep(t, ms, st, cfg);
                    persistTable(t);
                }
                st.current = "第 " + st.round + " 轮：判据体检";
                auditAndRewrite(t, ms, st, cfg, gen);
                st.current = "第 " + st.round + " 轮：兜底批量生长";
                growBuffered(t, ms, st, cfg, gen);
            }
            st.current = "";
            if (st.result == null) {
                st.result = "完成 " + round + " 轮：摘枝 " + st.recycled + " · 重写 " + st.rewrites
                        + " · 生长 " + st.grew + " · 体检 " + st.audits;
            }
            st.phase = "done";
            st.finishedAt = System.currentTimeMillis();
            synchronized (t) {
                maybeSubmitMaint(t, cfg, backend, gstate);   // 收工自查：积压仍达标且有未冻结层 → 重新提交续跑
            }
        } catch (Exception e) {
            st.phase = "error";
            st.result = e.getMessage() == null ? e.toString() : e.getMessage();
            st.finishedAt = System.currentTimeMillis();
        }
    }

    /** 维护生成器（体检+兜底生长共用）：mock 判定链路走 MockGenerator（零 token）；真实链路 openrouter chat——
     *  三个提示词文件路径构造时各自可选读取（未配置在用到时才报，由调用方记任务状态）；缺凭据回 null。 */
    private static CriterionGenerator buildMaintGenerator(Config cfg, SwitchableBackend backend, GrowthState gstate) {
        if ("mock".equals(backend.name())) return new CriterionGenerator.MockGenerator();
        String apiKey = cfg.get("openrouter.apikey", null);
        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        return new CriterionGenerator.OpenRouterGenerator(
                cfg.get("growth.url", "https://openrouter.ai/api/v1/chat/completions"),
                growthModelOf(cfg, gstate),
                apiKey, cfg.getInt("timeoutMs", 30000),
                cfg.get("growth.prompt", null),
                cfg.getInt("growth.maxTokens", 0), cfg.get("growth.thinking", null),
                cfg.get("growth.extend.prompt", null),
                cfg.get("growth.audit.prompt", null));
    }

    /**
     * 回收扫描（表锁内，零模型）：收编数为 0（nodeDocs 子树全空）且过保护期（growth.recycleProtect 轮）
     * 的真实分支摘除——undoGrow 复用：零收编枝=纯摘枝零迁移，唯一实子摘除父层自动回叶形态；
     * grown 记录同步清除防回放复活。层防抖：同一层连续摘 2 枝 → 入生长失败记忆冻结该层
     * （防「长一条烂的→摘→再长」震荡），解冻=兜底样本换血过半（与生长失败同一条规则）。
     */
    private static void recycleSweep(Table t, Table.MaintState ms, Table.MaintStatus st, Config cfg) {
        int protect = cfg.getInt("growth.recycleProtect", 3);
        List<String[]> branches = t.tree.branchList();   // 锁内快照
        for (String[] b : branches) {
            String parent = b[0], name = b[1];
            if (!ms.branchBorn.containsKey(name)) ms.branchBorn.put(name, Integer.valueOf(ms.age));   // 首见登记
            if (ms.age - ms.branchBorn.get(name).intValue() < protect) continue;   // 保护期内不回收
            int count = t.tree.nodeDocs(name).docs.size();   // 子树全空才算废枝（内部枝看子树合计，非兜底叶单层）
            if (count > 0) {   // 有收编：非废枝，顺手清该层摘枝连击
                ms.recycleStreak.remove(parent);
                continue;
            }
            if (count < 0) continue;   // 节点已消失（祖先先被摘）：跳过
            try {
                t.tree.undoGrow(parent, name);
            } catch (Exception ignore) {
                continue;   // 摘失败极罕见：留待下轮
            }
            for (java.util.Iterator<Map<String, Object>> it = t.grown.iterator(); it.hasNext(); ) {
                Map<String, Object> g = it.next();
                if (name.equals(String.valueOf(g.get("name")))) it.remove();
            }
            ms.branchBorn.remove(name);
            ms.lastAudit.remove(name);
            ms.rewriteFails.remove(name);
            st.recycled++;
            Integer streak = ms.recycleStreak.get(parent);
            int s = streak == null ? 1 : streak.intValue() + 1;
            if (s >= 2) {   // 连续摘 2 枝：该层生长防抖冻结（证据=当前兜底样本，换血过半才解冻）
                Table.GrowFail f = new Table.GrowFail();
                f.note = "连续摘枝防抖：该层生长的枝两度零收编被回收，冻结至兜底样本换血过半";
                f.sampleIds.addAll(bufferIdsOf(t, parent));
                ms.growthFails.put(parent, f);
                ms.recycleStreak.remove(parent);
            } else {
                ms.recycleStreak.put(parent, Integer.valueOf(s));
            }
        }
    }

    /**
     * 判据体检 + 重写验收（noul 专属；choice 表跳过）：轮转队列取「距上次体检最久」的 growth.auditBatch
     * 个分支（人工编辑过的分支不进队列——人工意志不被机器覆盖），锁内快照（判据/成员30/层兜底30/兄弟/规则），
     * 锁外体检生成：branches 空=健康；非空=重写提案，走两道门——
     * ① 旧成员保住率 ≥ growth.refineKeep 才许替换（否则失败记忆+连续 2 次冻结）；② 兜底同类过门迁入（奖励项）。
     * 通过后 rewriteNode 替换判据 + 同步 grown 记录 yes（回放对齐）。
     */
    private static void auditAndRewrite(Table t, Table.MaintState ms, Table.MaintStatus st, Config cfg, CriterionGenerator gen) {
        if (t.tree.choiceMode()) return;   // choice 判据=选项描述语义，代谢一期只管 noul（延伸同款边界）
        final int auditBatch = cfg.getInt("growth.auditBatch", 8);
        final double refineKeep = cfg.getDouble("growth.refineKeep", 0.9);
        // 锁内快照：体检队列（按距上次体检最久排序，滤人工锁定）+ 每分支的成员/兜底/兄弟/规则
        List<String[]> queue = new ArrayList<String[]>();
        Map<String, String[]> meta = new LinkedHashMap<String, String[]>();       // name → {parent, yes, rule}
        Map<String, List<SemQuestTree.NodeDoc>> members = new LinkedHashMap<String, List<SemQuestTree.NodeDoc>>();
        Map<String, List<SemQuestTree.NodeDoc>> fallbacks = new LinkedHashMap<String, List<SemQuestTree.NodeDoc>>();
        Map<String, List<String[]>> siblings = new LinkedHashMap<String, List<String[]>>();
        synchronized (t) {
            Set<String> locked = new HashSet<String>();
            for (Map<String, Object> e : t.edits) {   // 人工编辑过的分支（改名前后名都算）体检永久跳过
                Object nm = e.get("name");
                if (nm instanceof String) locked.add((String) nm);
                Object nn = e.get("newName");
                if (nn instanceof String) locked.add((String) nn);
            }
            List<String[]> all = new ArrayList<String[]>(t.tree.branchList());
            Collections.sort(all, new Comparator<String[]>() {
                public int compare(String[] x, String[] y) {
                    Integer a = ms.lastAudit.get(x[1]), b = ms.lastAudit.get(y[1]);
                    return (a == null ? 0 : a.intValue()) - (b == null ? 0 : b.intValue());
                }
            });
            for (String[] b : all) {
                if (queue.size() >= auditBatch) break;
                if (locked.contains(b[1])) continue;   // 人工锁定：不体检不消耗名额
                queue.add(b);
                meta.put(b[1], new String[]{b[0], b[2], t.tree.effectiveRuleOf(b[1])});
                members.put(b[1], head(t.tree.nodeDocs(b[1]).docs, 30));
                fallbacks.put(b[1], tail(bufferDocsOf(t.tree, b[0]), 30));   // 取尾部（最新积压）
                siblings.put(b[1], t.tree.branchView(b[0]));
            }
        }
        if (queue.isEmpty()) return;
        double gate = t.tree.gate();   // 门控快照：锁外判定比较用（tree.gate 非 volatile，读快照防撕裂）
        int idx = 0;
        for (String[] b : queue) {
            String name = b[1];
            Table.RewriteFail rf = ms.rewriteFails.get(name);
            if (rf != null && rf.fails >= 2) {
                synchronized (t) {   // 冻结中先查解冻：分支有新收编（证据变了）→ 清记忆解冻
                    int now = t.tree.nodeDocs(name).docs.size();
                    if (now > rf.frozenAtDocs) {
                        ms.rewriteFails.remove(name);
                        rf = null;
                    }
                }
                if (rf != null) continue;   // 仍冻结：证据没变就不许再试（不白调模型）
            }
            st.current = "体检 " + (++idx) + "/" + queue.size() + "：「" + name + "」";
            List<String> mems = new ArrayList<String>(), fbs = new ArrayList<String>();
            for (SemQuestTree.NodeDoc d : members.get(name)) mems.add(d.content);
            for (SemQuestTree.NodeDoc d : fallbacks.get(name)) fbs.add(d.content);
            CriterionGenerator.GrowthResult g;
            try {
                g = gen.generateAudit(name, meta.get(name)[1], mems, fbs, siblings.get(name), rewriteAttempts(ms, name));
            } catch (IllegalStateException e) {
                st.result = e.getMessage();   // 提示词未配置：配置问题整段跳过（生长不受影响）
                return;
            } catch (Exception e) {
                continue;   // 网络/解析瞬态失败：跳过该分支，下轮再体检
            }
            st.genIn += g.inputTokens;
            st.genOut += g.outputTokens;
            st.audits++;
            synchronized (t) {
                ms.lastAudit.put(name, Integer.valueOf(ms.age));
            }
            if (g.branches.isEmpty()) continue;   // 健康：不动
            String[] p = g.branches.get(0);
            if (!name.equals(p[0]) || p[1].equals(meta.get(name)[1])) continue;   // 提案名不符 / 判据没变：忽略
            // —— 验收门 ①：旧成员保住率 ——
            List<SemQuestTree.NodeDoc> oldDocs = members.get(name);
            int keep = 0;
            List<String> kicked = new ArrayList<String>();
            boolean judgeFail = false;
            for (SemQuestTree.NodeDoc d : oldDocs) {
                double prob;
                try {
                    prob = t.tree.judgeOne(d.content, name, p[1], null, meta.get(name)[2]);
                } catch (IOException e) {
                    judgeFail = true;   // 判定失败视作不过门：保守保留旧判据
                    break;
                }
                if (prob >= gate) keep++;
                else kicked.add(d.id + ": " + tail40(d.content));
            }
            double rate = oldDocs.isEmpty() ? 1.0 : (double) keep / oldDocs.size();
            if (judgeFail || rate < refineKeep) {
                synchronized (t) {
                    Table.RewriteFail f = ms.rewriteFails.get(name);
                    if (f == null) {
                        f = new Table.RewriteFail();
                        ms.rewriteFails.put(name, f);
                    }
                    f.proposedYes = p[1];
                    f.keepRate = rate;
                    f.kicked.clear();
                    f.kicked.addAll(kicked.subList(0, Math.min(kicked.size(), 10)));
                    f.fails++;
                    f.frozenAtDocs = t.tree.nodeDocs(name).docs.size();
                    f.note = "重写验收未过：旧成员保住率 " + Math.round(rate * 100) + "%（门限 "
                            + Math.round(refineKeep * 100) + "%）";
                }
                continue;
            }
            // —— 验收门 ②：兜底同类吸入（奖励项，不设门限；单条失败留在兜底） ——
            for (SemQuestTree.NodeDoc d : fallbacks.get(name)) {
                double prob;
                try {
                    prob = t.tree.judgeOne(d.content, name, p[1], null, meta.get(name)[2]);
                } catch (IOException e) {
                    continue;
                }
                synchronized (t) {
                    t.tree.moveJudged(meta.get(name)[0], d.id, name, prob);   // 从父层兜底叶迁入
                }
            }
            // —— 提交：判据替换 + grown 记录同步（回放对齐） ——
            synchronized (t) {
                t.tree.rewriteNode(name, p[1], null);
                for (Map<String, Object> grec : t.grown) {
                    if (name.equals(String.valueOf(grec.get("name")))) grec.put("yes", p[1]);
                }
                ms.rewriteFails.remove(name);
                st.rewrites++;
                System.out.println("[代谢] 表「" + t.name + "」判据重写「" + name + "」→ " + p[1]
                        + "（保住率 " + Math.round(rate * 100) + "%）");
            }
        }
    }

    /**
     * 兜底批量生长（各层独立，choice 表同样生效——growSeed+整题重判口径）：兜底积压 ≥ 阈值且未冻结
     * 且未层满 → 锁内快照（siblings + 积压样本均匀抽样 growth.samples 条 + 失败记忆）→ 锁外一次生成
     * ≤growth.maxNew 个候选 → 逐候选「锁内 growSeed 结构入树 → 锁外存量重判 → 锁内 moveJudged 迁移」
     * → 积压快照样本收编数 ≥ growth.acceptMin 保留（记 grown 回放记录），否则 undoGrow 换下一候选；
     * 全部回滚 → 失败记忆入档该层冻结（解冻=兜底样本换血过半）。生成器返回「无区分」也入档
     * （下次带「上次你认为无区分」重审）；网络/配置失败不入档（语义失败才值得吸取教训）。
     */
    private static void growBuffered(Table t, Table.MaintState ms, Table.MaintStatus st, Config cfg, CriterionGenerator gen) throws IOException {
        final int rootThreshold = cfg.getInt("growth.bufferRoot", 30);
        final int layerThreshold = cfg.getInt("growth.bufferMin", 5);
        final int sampleN = cfg.getInt("growth.samples", 20);
        final int acceptMin = cfg.getInt("growth.acceptMin", 2);
        final int maxNew = cfg.getInt("growth.maxNew", 2);
        final int maxBranches = cfg.getInt("growth.maxBranches", 12);
        // 解冻检查（锁内）：冻结层的兜底样本换血过半（交集 < 一半）→ 清记忆解冻；达标层清单（锁内填充）
        List<String[]> ready = new ArrayList<String[]>();
        synchronized (t) {
            for (String layer : new ArrayList<String>(ms.growthFails.keySet())) {
                Table.GrowFail f = ms.growthFails.get(layer);
                List<String> cur = bufferIdsOf(t, layer);
                long inter = 0;
                for (String id : f.sampleIds) if (cur.contains(id)) inter++;
                if (f.sampleIds.isEmpty() || inter * 2 < f.sampleIds.size()) {
                    ms.growthFails.remove(layer);
                    System.out.println("[代谢] 表「" + t.name + "」层「" + layer + "」兜底换血过半，生长解冻");
                }
            }
            // 达标层清单（跳过冻结层与层满层）
            List<String[]> layers = t.tree.layerList();
            for (int i = 0; i < layers.size(); i++) {
                int threshold = i == 0 ? rootThreshold : layerThreshold;
                String layer = layers.get(i)[0];
                if (Integer.parseInt(layers.get(i)[1]) < threshold) continue;
                if (ms.growthFails.containsKey(layer)) continue;   // 冻结中：证据没变不重试
                List<String[]> sib = t.tree.branchView(layer);
                if (sib == null || sib.size() >= maxBranches) continue;   // 层已消失 / 层满护栏
                ready.add(new String[]{layer, String.valueOf(sib.size())});
            }
        }
        // 逐层生长：快照（锁内）→ 生成（锁外）→ 逐候选「结构入树（锁内）→ 存量重判（锁外）→ 迁移（锁内）」
        for (String[] r : ready) {
            String layer = r[0];
            List<String[]> siblings;
            List<String> sampleIds = new ArrayList<String>(), sampleTexts = new ArrayList<String>();
            String rule;
            synchronized (t) {
                siblings = t.tree.branchView(layer);
                if (siblings == null) continue;   // 层已消失（并发删除等极罕见）
                List<SemQuestTree.NodeDoc> buf = bufferDocsOf(t.tree, layer);
                if (!buf.isEmpty()) {   // 均匀步长抽样（与延伸同款：整体代表性优于只取前 N）
                    int limit = Math.min(sampleN, buf.size());
                    double step = (double) buf.size() / limit;
                    for (int k = 0; k < limit; k++) {
                        SemQuestTree.NodeDoc d = buf.get((int) (k * step));
                        sampleIds.add(d.id);
                        sampleTexts.add(d.content);
                    }
                }
                rule = t.tree.effectiveRuleOf(layer);
            }
            if (sampleIds.isEmpty()) continue;
            st.current = "生长「" + layer + "」（积压 " + sampleIds.size() + "）";
            CriterionGenerator.GrowthResult g;
            try {
                g = gen.generate(layer, siblings, sampleTexts, maxNew, growthAttempts(ms, layer));
            } catch (IllegalStateException e) {
                st.result = e.getMessage();   // 提示词未配置：配置问题显式暴露，本任务不再生长
                break;
            } catch (Exception e) {
                continue;   // 网络/解析瞬态失败：不入失败记忆（语义失败才值得吸取教训），下轮重试
            }
            st.genIn += g.inputTokens;
            st.genOut += g.outputTokens;
            if (g.branches.isEmpty()) {   // 「无区分」也是语义结论：入档，下次带证据重审
                synchronized (t) {
                    Table.GrowFail f = ms.growthFails.get(layer);
                    if (f == null) {
                        f = new Table.GrowFail();
                        ms.growthFails.put(layer, f);
                    }
                    f.note = "上次生成认为该层无值得生长的区分（积压样本如下），请重新审视";
                    f.sampleIds.clear();
                    f.sampleIds.addAll(sampleIds);
                    f.rounds++;
                }
                continue;
            }
            boolean kept = false;
            for (String[] cand : g.branches) {
                SemQuestTree.GrowSeed seed;
                try {
                    synchronized (t) {
                        seed = t.tree.growSeed(layer, cand[0], cand[1], null);   // 结构入树（零模型）
                    }
                } catch (Exception e) {
                    continue;   // 撞名/非法名等：跳过该候选
                }
                int collected = 0;
                for (String[] d : seed.affected) {   // 存量重判（锁外）：口径同 growNode——过门迁入，不过留原位
                    double prob;
                    try {
                        prob = t.tree.judgeOne(d[1], cand[0], cand[1], null, seed.rule);
                    } catch (IOException e) {
                        continue;   // 单条判定失败视作不过门：留在原位
                    }
                    boolean mv;
                    synchronized (t) {
                        mv = t.tree.moveJudged(layer, d[0], cand[0], prob);
                    }
                    if (mv && sampleIds.contains(d[0])) collected++;   // 验收只看快照样本的收编数
                }
                if (collected >= acceptMin) {
                    synchronized (t) {
                        LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                        rec.put("parent", layer);
                        rec.put("name", cand[0]);
                        rec.put("yes", cand[1]);
                        rec.put("no", "");
                        rec.put("auto", Boolean.TRUE);   // 生长来源标记：回放时零模型恢复，viz 可识别为自动长出
                        t.grown.add(rec);
                        ms.branchBorn.put(cand[0], Integer.valueOf(ms.age));
                        ms.growthFails.remove(layer);
                        ms.recycleStreak.remove(layer);
                        st.grew++;
                        persistTable(t);
                    }
                    System.out.println("[代谢] 表「" + t.name + "」层「" + layer + "」生长枝「" + cand[0]
                            + "」（积压样本收编 " + collected + "/" + sampleIds.size() + "）");
                    kept = true;
                    break;   // 收编达标：余下候选不再入树
                }
                synchronized (t) {   // 验收未过：整枝回滚（零模型，存量原位还原）
                    try {
                        t.tree.undoGrow(layer, cand[0]);
                    } catch (Exception ignore) { }
                }
            }
            if (!kept) {   // 全候选回滚：失败记忆入档 + 该层冻结（换血过半才解冻）
                synchronized (t) {
                    Table.GrowFail f = ms.growthFails.get(layer);
                    if (f == null) {
                        f = new Table.GrowFail();
                        ms.growthFails.put(layer, f);
                    }
                    f.proposals.clear();
                    f.proposals.addAll(g.branches);
                    f.sampleIds.clear();
                    f.sampleIds.addAll(sampleIds);
                    f.rounds++;
                    f.note = "上次生长全部候选回滚（积压 " + sampleIds.size() + " 条收编不足 " + acceptMin
                            + "），请分析判据覆盖不足原因";
                }
            }
        }
    }

    /** 层兜底积压样本（生长输入/体检对照组）：内部层取其兜底叶存量，空树根叶取 ROOT 直挂。 */
    private static List<SemQuestTree.NodeDoc> bufferDocsOf(SemQuestTree tree, String layer) {
        try {
            return tree.nodeDocs(layer + SemQuestTree.OTHER_SUFFIX).docs;
        } catch (IllegalArgumentException e) {
            try {
                return tree.nodeDocs(layer).docs;   // 无兜底叶（空树根叶形态）
            } catch (IllegalArgumentException e2) {
                return new ArrayList<SemQuestTree.NodeDoc>();
            }
        }
    }

    /** 层兜底积压样本 id 集（生长失败解冻的换血判定用）。 */
    private static List<String> bufferIdsOf(Table t, String layer) {
        List<String> ids = new ArrayList<String>();
        for (SemQuestTree.NodeDoc d : bufferDocsOf(t.tree, layer)) ids.add(d.id);
        return ids;
    }

    /** 列表头截断（取前 n 条，成员用最早收编的代表性样本）。 */
    private static <T> List<T> head(List<T> list, int n) {
        return list.size() <= n ? list : new ArrayList<T>(list.subList(0, n));
    }

    /** 列表尾截断（取最新 n 条，兜底积压用最新样本）。 */
    private static <T> List<T> tail(List<T> list, int n) {
        return list.size() <= n ? list : new ArrayList<T>(list.subList(list.size() - n, list.size()));
    }

    /** 正文截断（失败记忆/摘要用）：超 40 字截断加省略号。 */
    private static String tail40(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    /** {attempts} 段（生长）：上次生长失败的提案与结论证据（机械拼装，非提示词）。无历史回 null。 */
    private static String growthAttempts(Table.MaintState ms, String layer) {
        Table.GrowFail f = ms.growthFails.get(layer);
        if (f == null) return null;
        StringBuilder sb = new StringBuilder();
        if (f.proposals.isEmpty()) {
            sb.append("上次生成认为该层无值得生长的区分");
        } else {
            sb.append("上次生成提案：");
            for (String[] p : f.proposals) {
                sb.append("枝「").append(p[0]).append("」（判据：").append(p[1]).append("）");
            }
        }
        sb.append("。").append(f.note).append("。本次必须改进，不得原样重提。");
        return sb.toString();
    }

    /** {attempts} 段（体检/重写）：上次重写失败的提案与保住率证据。无历史回 null。 */
    private static String rewriteAttempts(Table.MaintState ms, String name) {
        Table.RewriteFail f = ms.rewriteFails.get(name);
        if (f == null) return null;
        StringBuilder sb = new StringBuilder("上次重写提案（yes：").append(f.proposedYes)
                .append("）验收失败——").append(f.note).append("，被踢出的旧成员：");
        for (String k : f.kicked) sb.append("「").append(k).append("」");
        sb.append("。本次重写必须在修正问题的同时保住全部现有成员。");
        return sb.toString();
    }

    /** 维护状态全景（GET /maint）：任务快照 + 各层积压水位与阈值 + 冻结层与失败记忆摘要（内存态现算）。 */
    private static LinkedHashMap<String, Object> maintMap(Table t, Config cfg) {
        Table.MaintState ms = t.maintState;
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.maint.snapshot());
        int rootThreshold = cfg.getInt("growth.bufferRoot", 30);
        int layerThreshold = cfg.getInt("growth.bufferMin", 5);
        List<Object> buffers = new ArrayList<Object>();
        List<Object> frozen = new ArrayList<Object>();
        List<Object> rewriteFails = new ArrayList<Object>();
        synchronized (t) {
            List<String[]> layers = t.tree.layerList();
            for (int i = 0; i < layers.size(); i++) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("layer", layers.get(i)[0]);
                m.put("buffered", Integer.valueOf(Integer.parseInt(layers.get(i)[1])));
                m.put("threshold", Integer.valueOf(i == 0 ? rootThreshold : layerThreshold));
                buffers.add(m);
            }
            for (Map.Entry<String, Table.GrowFail> e : ms.growthFails.entrySet()) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("layer", e.getKey());
                m.put("rounds", Integer.valueOf(e.getValue().rounds));
                m.put("note", e.getValue().note);
                frozen.add(m);
            }
            for (Map.Entry<String, Table.RewriteFail> e : ms.rewriteFails.entrySet()) {
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("branch", e.getKey());
                m.put("fails", Integer.valueOf(e.getValue().fails));
                m.put("keepRate", Double.valueOf(e.getValue().keepRate));
                m.put("note", e.getValue().note);
                rewriteFails.add(m);
            }
        }
        out.put("layers", buffers);
        out.put("frozenLayers", frozen);
        out.put("rewriteFails", rewriteFails);
        out.put("stats", t.tree.statsLine());
        return out;
    }

    // ── 节点延伸（深度生长）：水位/手动双触发，后台单工串行闭环，不阻塞 add/quest ──

    /** 延伸开关有效值：生长总开关与 growth.extend（默认 true）都开才生效。 */
    private static boolean extendOnOf(Config cfg, GrowthState gstate) {
        return growthEnabledOf(cfg, gstate) && "true".equalsIgnoreCase(cfg.get("growth.extend", "true"));
    }

    /** 延伸生成器：mock 判定链路走 MockGenerator（零 token）；真实链路 openrouter chat——
     *  growth.extend.prompt 键指向的提示词文件构造时读取（缺失/空白即抛错，由 extendTask 记入任务状态）；缺凭据回 null。 */
    private static CriterionGenerator buildExtendGenerator(Config cfg, SwitchableBackend backend, GrowthState gstate) {
        if ("mock".equals(backend.name())) return new CriterionGenerator.MockGenerator();
        String apiKey = cfg.get("openrouter.apikey", null);
        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        return new CriterionGenerator.OpenRouterGenerator(
                cfg.get("growth.url", "https://openrouter.ai/api/v1/chat/completions"),
                growthModelOf(cfg, gstate),
                apiKey, cfg.getInt("timeoutMs", 30000), null,
                cfg.getInt("growth.maxTokens", 0), cfg.get("growth.thinking", null),
                cfg.get("growth.extend.prompt", null),
                null);   // 体检模板延伸用不到：不读文件，用到才报（三模板互不连坐）
    }

    /** 延伸水位：growth.splitAt × growth.splitFactor（默认 255×0.7=178），≤0 视为关闭水位触发。 */
    private static int extendWatermark(Config cfg) {
        return (int) Math.round(cfg.getInt("growth.splitAt", 255) * cfg.getDouble("growth.splitFactor", 0.7));
    }

    /**
     * 水位触发（add/addBatch 落位后、表锁内调用）：落位叶（兜底叶则上溯其父节点）存量超过水位 →
     * 提交后台延伸任务，本请求立即返回不等待；同一时刻每表至多一个延伸任务在跑（单工池排队）。
     */
    private static void maybeExtendOnWatermark(Table t, SemQuestTree.AddReport rep,
                                               Config cfg, SwitchableBackend backend, GrowthState gstate) {
        if (!extendOnOf(cfg, gstate)) return;
        int watermark = extendWatermark(cfg);
        if (watermark <= 0) return;
        if ("running".equals(t.extend.phase)) return;   // 已有延伸在跑：本条留给它下一轮快照
        for (String leaf : rep.leaves) {
            String target = leaf.endsWith(SemQuestTree.OTHER_SUFFIX)
                    ? leaf.substring(0, leaf.length() - SemQuestTree.OTHER_SUFFIX.length()) : leaf;
            if (t.tree.extendDocCount(target) > watermark) {
                submitExtend(t, target, false, cfg, backend, gstate);
                return;   // 一次落位最多触发一个，其余超水位叶由该任务完成后下次落位再触发
            }
        }
    }

    /** 提交延伸任务（表锁内调用）：running 中幂等忽略；重置状态后交后台单工池。manual=手动触发（强制拆细，不受水位限制）。 */
    private static void submitExtend(Table t, String node, boolean manual, Config cfg, SwitchableBackend backend, GrowthState gstate) {
        Table.ExtendStatus st = t.extend;
        if ("running".equals(st.phase)) return;
        st.phase = "running";
        st.node = node;
        st.current = "排队中…";
        st.result = null;
        st.round = 0;
        st.maxRounds = 0;
        st.rejudged = 0;
        st.totalDocs = 0;
        st.keptBranches = 0;
        st.movedDocs = 0;
        st.rolledBack = 0;
        st.genIn = 0;
        st.genOut = 0;
        st.startedAt = System.currentTimeMillis();
        st.finishedAt = 0;
        MAINT_POOL.execute(new Runnable() {
            public void run() {
                extendTask(t, node, manual, cfg, backend, gstate);
            }
        });
    }

    /**
     * 延伸任务体（后台单工线程）：串行闭环——每轮「锁内快照(planExtend) → 锁外生成 1 个更细子类 →
     * 锁内入树 → 逐条锁外重判 + 锁内短临界区移动 → 轮末落盘」。模型调用全程不持表锁，锁段只包
     * 结构变更与指针移动，add/quest 照常并行（大脑夜间加固，白天照常运转）。
     * 终止：水位以下（仅自动触发；手动=强制拆细不受水位限）/ 生成器返回空（无更细区分）/
     * 达 growth.extend.maxNew / 撞名 / 层满 / 存量不足 2 条 / 轮内零收编（undoGrow 零模型回滚）。
     * 全程可递复用于任意深度（2、3、4…层同一链路）。
     */
    private static void extendTask(Table t, String node, boolean manual, Config cfg, SwitchableBackend backend, GrowthState gstate) {
        Table.ExtendStatus st = t.extend;
        try {
            int maxRounds = cfg.getInt("growth.extend.maxNew", 8);
            int sampleN = cfg.getInt("growth.extend.samples", 40);
            int watermark = extendWatermark(cfg);
            st.maxRounds = maxRounds;
            CriterionGenerator gen = buildExtendGenerator(cfg, backend, gstate);
            if (gen == null) {
                st.phase = "error";
                st.result = "缺 openrouter 凭据，无法生成判据";
                st.finishedAt = System.currentTimeMillis();
                return;
            }
            int rounds = 0, movedAll = 0, rolled = 0;
            String stop = null;
            while (rounds < maxRounds) {
                if (!growthEnabledOf(cfg, gstate)) {
                    stop = "生长开关已关闭";
                    break;
                }
                SemQuestTree.ExtendPlan plan;
                synchronized (t) {
                    plan = t.tree.planExtend(node, sampleN);
                    int maxBranches = cfg.getInt("growth.maxBranches", 12);
                    if (plan.siblings.size() >= maxBranches) {
                        stop = "该层分支已满（growth.maxBranches=" + maxBranches + "）";
                        break;
                    }
                }
                if (manual) {
                    if (plan.totalDocs < 2) {   // 手动强制：不限水位，但单条已无拆细空间
                        stop = "存量不足 2 条，已无可拆细的空间";
                        break;
                    }
                } else if (plan.totalDocs <= watermark) {
                    stop = "已降至水位以下";
                    break;
                }
                int judgedBase = st.rejudged;   // 进度全程累计口径：多轮百分比单调不回跳
                st.totalDocs += plan.totalDocs;
                st.current = "生成更细的子类判据…";
                String attempts;
                synchronized (t) {
                    attempts = t.maintState.extendFails.get(node);   // 上次零收编证据：下次生成有改进方向
                }
                CriterionGenerator.GrowthResult g = gen.generateExtend(node, plan.parentYes, plan.totalDocs,
                        plan.siblings, plan.samples, attempts);
                st.genIn += g.inputTokens;   // 生成账单：与重判账单（judgeOne 累加进 stats）口径互补
                st.genOut += g.outputTokens;
                if (g.branches.isEmpty()) {
                    synchronized (t) {   // 语义失败入档（网络/配置失败不入档）：下次生成带上证据重新审视
                        t.maintState.extendFails.put(node, "上次生成认为该节点无需更细区分（存量样本未给出新簇），如有新增语料请换角度审视");
                    }
                    stop = "生成器认为现有子分支已能覆盖，无更细的区分";
                    break;
                }
                String[] b = g.branches.get(0);
                LinkedHashMap<String, Object> rec = new LinkedHashMap<String, Object>();
                synchronized (t) {
                    try {
                        t.tree.addNode(node, b[0], b[1], null);
                    } catch (IllegalArgumentException e) {
                        rolled++;
                        stop = "新分支撞名: " + b[0];
                        break;   // 树未变，无需落盘
                    }
                    rec.put("parent", node);
                    rec.put("name", b[0]);
                    rec.put("yes", b[1]);
                    rec.put("no", "");
                    rec.put("auto", Boolean.TRUE);
                    rec.put("extend", Boolean.TRUE);   // 延伸来源标记：与落兜底生长区分
                    t.grown.add(rec);
                    persistTable(t);
                }
                st.round = rounds + 1;
                int movedThis = 0, judged = 0;
                for (String[] d : plan.affected) {
                    st.current = "存量重判 " + judged + "/" + plan.affected.size();
                    double prob;
                    try {
                        prob = t.tree.judgeOne(d[1], b[0], b[1], null, plan.rule);   // 锁外调模型；no=null 由提示词模板自动生成
                    } catch (IOException e) {
                        judged++;   // 单条判定失败视作不过门：留在原位
                        continue;
                    }
                    judged++;
                    boolean mv;
                    synchronized (t) {
                        mv = t.tree.moveJudged(node, d[0], b[0], prob);   // 锁内短临界区移动
                    }
                    if (mv) movedThis++;
                    st.rejudged = judgedBase + judged;
                    st.movedDocs = movedAll + movedThis;
                }
                synchronized (t) {
                    if (movedThis == 0) {   // 验收未过：整枝回滚（undoGrow 零模型，存量原位还原）
                        try {
                            t.tree.undoGrow(node, b[0]);
                        } catch (Exception ignore) {
                            // 回滚失败极罕见（节点刚加进去）：留待人工清理
                        }
                        t.grown.remove(rec);
                        rolled++;
                    } else {
                        t.tree.bustQuestCache();   // 落位变了，查询缓存作废
                    }
                    persistTable(t);
                }
                if (movedThis == 0) {
                    st.rolledBack = rolled;
                    synchronized (t) {   // 失败证据入档：下次延伸生成带上，不再开盲盒
                        t.maintState.extendFails.put(node,
                                "上次延伸提案枝「" + b[0] + "」（判据：" + b[1] + "）零收编已回滚，请换角度提出更细区分");
                    }
                    stop = "验收未过：存量无一收编进「" + b[0] + "」，已回滚";
                    break;
                }
                rounds++;
                movedAll += movedThis;
                st.keptBranches = rounds;
                st.movedDocs = movedAll;
                synchronized (t) {
                    t.maintState.extendFails.remove(node);   // 收编成功：旧证据失效
                }
            }
            st.current = "";
            st.result = stop != null ? stop : ("完成：" + rounds + " 个新枝 · 迁入 " + movedAll + " 条存量");
            st.phase = "done";
            st.finishedAt = System.currentTimeMillis();
        } catch (Exception e) {
            st.phase = "error";
            st.result = e.getMessage() == null ? e.toString() : e.getMessage();
            st.finishedAt = System.currentTimeMillis();
        }
    }

    /**
     * GET /api/t/&lt;id&gt;/extend → 延伸状态（viz 进度条轮询）；
     * POST {node} → 手动触发对该节点的延伸（后台执行；与水位触发同链路，running 中幂等返回现状态）。
     */
    private static final class ExtendHandler extends BaseHandler {
        private final Config cfg;
        private final SwitchableBackend backend;
        private final GrowthState gstate;

        ExtendHandler(Config cfg, SwitchableBackend backend, GrowthState gstate) {
            this.cfg = cfg;
            this.backend = backend;
            this.gstate = gstate;
        }

        void handle(Table t, HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("tableId", t.id);
                out.putAll(t.extend.snapshot());
                json(ex, 200, Json.write(out));
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req;
            try {
                req = Json.parseObject(readBody(ex));
            } catch (Exception e) {
                json(ex, 400, "{\"error\":" + Json.str("请求体不是合法 JSON") + "}");
                return;
            }
            Object nv = req.get("node");
            if (!(nv instanceof String) || ((String) nv).trim().isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("缺 node 节点名") + "}");
                return;
            }
            String node = ((String) nv).trim();
            SemQuestTree.ExtendPlan pre;
            try {
                pre = t.tree.planExtend(node, 1);   // 预检：不存在/兜底叶/choice 表在这里快速报错
            } catch (Exception e) {
                json(ex, 400, "{\"error\":" + Json.str(e.getMessage()) + "}");
                return;
            }
            if (pre.totalDocs == 0) {
                json(ex, 400, "{\"error\":" + Json.str("节点「" + node + "」没有存量内容，无可延伸") + "}");
                return;
            }
            boolean already = "running".equals(t.extend.phase);
            if (!already) submitExtend(t, node, true, cfg, backend, gstate);   // 手动=强制拆细，水位只管自动触发
            LinkedHashMap<String, Object> out = t.extend.snapshot();
            out.put("alreadyRunning", Boolean.valueOf(already));
            json(ex, already ? 200 : 202, Json.write(out));
        }
    }

    /**
     * GET /api/t/&lt;id&gt;/maint → 维护任务状态 + 各层兜底水位 + 失败记忆快照（viz 维护面板轮询）；
     * POST → 手动触发一轮维护（回收/体检/重写/兜底批量生长，后台执行；running 中幂等返回现状态）。
     */
    private static final class MaintHandler extends BaseHandler {
        private final Config cfg;
        private final SwitchableBackend backend;
        private final GrowthState gstate;

        MaintHandler(Config cfg, SwitchableBackend backend, GrowthState gstate) {
            this.cfg = cfg;
            this.backend = backend;
            this.gstate = gstate;
        }

        void handle(Table t, HttpExchange ex) throws IOException {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                json(ex, 200, Json.write(maintMap(t, cfg)));
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            boolean already;
            synchronized (t) {   // 提交检查与状态复位在锁内：与 add 提交/任务收工互斥
                already = "running".equals(t.maint.phase);
                if (!already) submitMaint(t, cfg, backend, gstate);
            }
            LinkedHashMap<String, Object> out = maintMap(t, cfg);
            out.put("alreadyRunning", Boolean.valueOf(already));
            json(ex, already ? 200 : 202, Json.write(out));
        }
    }

    /** POST /api/t/&lt;id&gt;/add {content, id?} → 一条语料按判据下行落叶；id 缺省自动生成，落位随快照落盘。 */
    private static final class AddHandler extends BaseHandler {
        private final Config cfg;
        private final SwitchableBackend backend;
        private final GrowthState gstate;

        AddHandler(Config cfg, SwitchableBackend backend, GrowthState gstate) {
            this.cfg = cfg;
            this.backend = backend;
            this.gstate = gstate;
        }

        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req;
            try {
                req = Json.parseObject(readBody(ex));
            } catch (Exception e) {
                json(ex, 400, "{\"error\":" + Json.str("请求体不是合法 JSON") + "}");
                return;
            }
            Object cv = req.get("content");
            if (!(cv instanceof String) || ((String) cv).trim().isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("缺 content 正文") + "}");
                return;
            }
            Object iv = req.get("id");
            String id = iv instanceof String && !((String) iv).trim().isEmpty()
                    ? ((String) iv).trim() : "U-" + Long.toString(System.currentTimeMillis(), 36);
            try {
                SemQuestTree.AddReport rep = t.tree.add(id, ((String) cv).trim());
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
                out.put("id", id);
                out.put("leaves", new ArrayList<Object>(rep.leaves));
                out.put("steps", stepsJson(rep.steps));
                // 缓冲批量生长：落兜底即积压进兜底叶；各层积压达阈值 → 提交后台维护任务（本请求立即返回不等待）
                maybeSubmitMaint(t, cfg, backend, gstate);
                maybeExtendOnWatermark(t, rep, cfg, backend, gstate);   // 水位触发：落位叶堆积超限 → 后台延伸（不阻塞本请求）
                out.put("stats", t.tree.statsLine());   // stats 后置：含生长重判与生成器的 token
                persistTable(t);
                json(ex, 200, Json.write(out));
            } catch (IOException e) {
                json(ex, 502, "{\"error\":" + Json.str("落位失败: " + e.getMessage()) + "}");
            }
        }
    }

    /** 批量导入上限：一次请求最多语料条数。 */
    private static final int ADD_BATCH_CAP = 500;

    /**
     * POST /api/t/&lt;id&gt;/addBatch {docs:[{id?, content}…]} → 批量导入：
     * 逐条落位互不牵连（单条失败记入 failed 继续），全部结束后统一落盘一次。
     */
    private static final class AddBatchHandler extends BaseHandler {
        private final Config cfg;
        private final SwitchableBackend backend;
        private final GrowthState gstate;

        AddBatchHandler(Config cfg, SwitchableBackend backend, GrowthState gstate) {
            this.cfg = cfg;
            this.backend = backend;
            this.gstate = gstate;
        }

        @SuppressWarnings("unchecked")
        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req;
            try {
                req = Json.parseObject(readBody(ex));
            } catch (Exception e) {
                json(ex, 400, "{\"error\":" + Json.str("请求体不是合法 JSON") + "}");
                return;
            }
            if (!(req.get("docs") instanceof List)) {
                json(ex, 400, "{\"error\":" + Json.str("缺 docs 数组（每项 {id?, content}）") + "}");
                return;
            }
            List<Object> docs = (List<Object>) req.get("docs");
            if (docs.isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("docs 为空") + "}");
                return;
            }
            if (docs.size() > ADD_BATCH_CAP) {
                json(ex, 400, "{\"error\":" + Json.str("单次最多 " + ADD_BATCH_CAP + " 条，当前 "
                        + docs.size() + " 条——请分批导入") + "}");
                return;
            }
            List<Object> results = new ArrayList<Object>();
            List<Object> failed = new ArrayList<Object>();
            int added = 0;
            for (int i = 0; i < docs.size(); i++) {
                Object o = docs.get(i);
                if (!(o instanceof Map)) {
                    failed.add(batchFail(i, null, "条目不是对象"));
                    continue;
                }
                Map<?, ?> d = (Map<?, ?>) o;
                Object cv = d.get("content");
                if (!(cv instanceof String) || ((String) cv).trim().isEmpty()) {
                    failed.add(batchFail(i, d.get("id"), "缺 content 正文"));
                    continue;
                }
                Object iv = d.get("id");
                String id = iv instanceof String && !((String) iv).trim().isEmpty()
                        ? ((String) iv).trim() : "U-" + Long.toString(System.currentTimeMillis(), 36) + "-" + i;
                try {
                    SemQuestTree.AddReport rep = t.tree.add(id, ((String) cv).trim());
                    LinkedHashMap<String, Object> r = new LinkedHashMap<String, Object>();
                    r.put("id", id);
                    r.put("leaves", new ArrayList<Object>(rep.leaves));
                    r.put("steps", stepsJson(rep.steps));
                    // 缓冲批量生长：落兜底即积压；各层积压达阈值 → 提交后台维护任务（单工池排队，本请求不等）
                    maybeSubmitMaint(t, cfg, backend, gstate);
                    maybeExtendOnWatermark(t, rep, cfg, backend, gstate);   // 水位触发：单线程池排队，本请求不等
                    results.add(r);
                    added++;
                } catch (IOException e) {
                    failed.add(batchFail(i, id, "落位失败: " + e.getMessage()));
                }
            }
            persistTable(t);   // 批次结束统一落盘一次（含失败项之前的成功落位）
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
            out.put("total", docs.size());
            out.put("added", added);
            out.put("failed", failed);
            out.put("results", results);
            out.put("stats", t.tree.statsLine());
            json(ex, 200, Json.write(out));
        }

        private LinkedHashMap<String, Object> batchFail(int index, Object id, String error) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("index", index);
            if (id instanceof String) m.put("id", id);
            m.put("error", error);
            return m;
        }
    }

    /** DELETE /api/t/&lt;id&gt;/doc?name=X → 删除一条落位（零模型调用）：从全部落位叶上摘下并移出索引；未知 id 400。 */
    private static final class DocHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String name = param(ex, "name");
            if (name == null || name.trim().isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("缺 name 参数（文档 id）") + "}");
                return;
            }
            try {
                List<String> removedLeaves = t.tree.removeDoc(name.trim());
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>(t.tree.metaMap());
                out.put("id", name.trim());
                out.put("removedLeaves", removedLeaves);
                out.put("stats", t.tree.statsLine());
                persistTable(t);
                json(ex, 200, Json.write(out));
            } catch (IllegalArgumentException e) {
                json(ex, 400, "{\"error\":" + Json.str(e.getMessage()) + "}");
            }
        }
    }

    /** POST /api/t/&lt;id&gt;/quest {query} → 自然语言检索（纯读：按现有结构下行，查不到就是查不到）。 */
    private static final class QuestHandler extends BaseHandler {

        void handle(Table t, HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req;
            try {
                req = Json.parseObject(readBody(ex));
            } catch (Exception e) {
                json(ex, 400, "{\"error\":" + Json.str("请求体不是合法 JSON") + "}");
                return;
            }
            Object qv = req.get("query");
            if (!(qv instanceof String) || ((String) qv).trim().isEmpty()) {
                json(ex, 400, "{\"error\":" + Json.str("缺 query 字段") + "}");
                return;
            }
            String query = ((String) qv).trim();
            // 节点名直取：查询与节点名完全一致 → 直接返回该节点子树内容（零模型调用，复用节点直取逻辑）
            try {
                json(ex, 200, Json.write(nodeQuestMap(t.tree.nodeDocs(query))));
                return;
            } catch (IllegalArgumentException notNode) {
                // 非节点名 → 走自然语言检索
            }
            try {
                SemQuestTree.QuestReport rep = t.tree.quest(query);
                Map<String, Object> out = questMap(t.tree, rep);
                // 纯读：检索只按现有结构下行，查不到就是查不到——不为查询改树（生长在 add/addBatch 触发）
                json(ex, 200, Json.write(out));
            } catch (IOException e) {
                json(ex, 500, "{\"error\":" + Json.str("查询判定失败: " + e.getMessage()) + "}");
            }
        }
    }

    /**
     * 节点名直取 → quest 形响应：matchedLeaves=子树叶名、ranked=子树落位内容
     * （score=文档在子树内的落位叶数，多叶文档排前）、steps 空、fastPath=true。
     */
    private static Map<String, Object> nodeQuestMap(SemQuestTree.NodeDocs nd) {
        HashSet<String> sub = new HashSet<String>(nd.leaves);
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("query", nd.node);
        out.put("fastPath", Boolean.TRUE);
        out.put("matchedLeaves", new ArrayList<Object>(nd.leaves));
        List<Object> ranked = new ArrayList<Object>();
        for (SemQuestTree.NodeDoc d : nd.docs) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("docId", d.id);
            int inSub = 0;
            for (String v : d.viaLeaves) {
                if (sub.contains(v)) inSub++;
            }
            m.put("score", (double) inSub);
            m.put("viaLeaves", d.viaLeaves);
            m.put("content", d.content);
            ranked.add(m);
        }
        out.put("ranked", ranked);
        out.put("steps", new ArrayList<Object>());
        out.put("stats", "节点直取 · " + nd.docs.size() + " 条 · 零模型调用");
        return out;
    }

    // ── provider/model 辅助 ─────────────────────────────────────────

    /** 当前 provider/model 选择（volatile，热切换线程安全）。 */
    private static final class Selection {
        volatile String provider;
        volatile String model;
    }

    /** 生长运行时覆盖（viz 🌱 开关与生成模型下拉，volatile 热改）：null = 未覆盖，回退 growth.* 配置值。 */
    private static final class GrowthState {
        volatile Boolean enabled;
        volatile String model;
    }

    /** 生长开关有效值：viz 运行时覆盖优先，回退 growth.enabled 配置（热载）。 */
    private static boolean growthEnabledOf(Config cfg, GrowthState g) {
        if (g.enabled != null) return g.enabled.booleanValue();
        return "true".equalsIgnoreCase(cfg.get("growth.enabled", "false"));
    }

    /** 生长生成模型有效值：viz 运行时覆盖优先，回退 growth.model 配置（热载）。 */
    private static String growthModelOf(Config cfg, GrowthState g) {
        if (g.model != null && !g.model.trim().isEmpty()) return g.model;
        return cfg.get("growth.model", "xingchen-agi/xing4.0-29b-a4b:free");
    }

    /** provider 别名归一：mammoth → openrouter（OpenRouterBackend 类更名后的旧称兼容）。 */
    private static String normalizeProvider(String p) {
        String v = p == null ? "" : p.trim().toLowerCase();
        return "mammoth".equals(v) ? "openrouter" : v;
    }

    private static String currentModelOf(Config cfg, String provider) {
        if ("jev".equals(provider)) return cfg.get("jev.model", "jev-1.13.0");
        if ("openrouter".equals(provider)) return cfg.get("openrouter.model", "baseten/mammoth-1:free");
        if ("bailian".equals(provider)) return cfg.get("bailian.model", "decision-model-preview");
        if ("mock".equals(provider)) return cfg.get("mock.model", "keyword-match");
        return "";
    }

    /** 按 provider 建后端并应用模型 id（仅构造与凭据校验，不发推理）；未知 provider 返回 null。 */
    private static DecisionBackend createBackendWithModel(Config cfg, String provider, String model, int timeoutMs) {
        DecisionBackend b;
        if ("jev".equals(provider)) b = new JevBackend(cfg, timeoutMs);
        else if ("openrouter".equals(provider)) b = new OpenRouterBackend(cfg, timeoutMs);
        else if ("bailian".equals(provider)) b = new BailianBackend(cfg, timeoutMs);
        else if ("mock".equals(provider)) b = new MockBackend();
        else return null;
        b.useModel(model);
        return b;
    }

    /** 手动模型清单（provider.models，逗号/中文逗号分隔）：配置即清单，优先生效；空则回上游拉取。 */
    private static List<Object> manualModels(Config cfg, String provider) {
        String raw = cfg.get(provider + ".models", "");
        if (raw.trim().isEmpty()) return new ArrayList<Object>();
        List<Object> out = new ArrayList<Object>();
        for (String id : raw.split("[,，]")) {
            String v = id.trim();
            if (v.isEmpty()) continue;
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", v);
            m.put("decisions", Boolean.TRUE);
            out.add(m);
        }
        return out;
    }

    /** 静态候选：上游列表不可得或缺凭据时的兜底（仅配置里的决策模型）。 */
    private static List<Object> staticModels(Config cfg, String provider) {
        List<Object> out = new ArrayList<Object>();
        if ("jev".equals(provider)) {
            out.add(modelEntry(cfg.get("jev.model", "jev-1.13.0")));
            out.add(modelEntry("jev-latest"));
        } else if ("openrouter".equals(provider)) {
            // 官方决策模型目录快照（/api/v1/models?output_modalities=decisions，2026-10-28）：
            // 免费款置顶，其余按厂商归组；上游实时清单不可达/超时时使用
            String[] fallback = {
                "baseten/mammoth-1:free", "baseten/elephant-1:free",
                "respan/span-01-lite:free", "respan/span-01-lite",
                "liquid/d1.1", "liquid/d1", "liquid/d1-ultra", "~liquid/d1-latest", "~liquid/d1-ultra-latest",
                "typesafe/jev-1.14", "typesafe/jev-1.13", "~typesafe/jev-latest",
                "perplexity/pplx-decider-v1.2", "perplexity/pplx-decider-flash-v1.2",
                "~perplexity/pplx-decider-latest", "~perplexity/pplx-decider-flash-latest",
                "nace-ai/drex-max", "nace-ai/drex-v2.0", "nace-ai/drex-v1.5",
                "microsoft/microsoft-decision-1", "scaledown/classify-1",
                "openai/gpt-6-luna-decisions", "inception/mercury-decide",
                "cloudflare/clef-omni", "cloudflare/clef", "cloudflare/clef-flash",
                "upstage/solar-decide", "upstage/solar-decide-flash",
                "togethercomputer/tev1-4b-experimental", "jaredpalmer/kev-4b",
                "respan/span-02", "respan/span-01",
            };
            for (String id : fallback) out.add(modelEntry(id));
            prependDecisionsModel(out, cfg.get("openrouter.model", "baseten/mammoth-1:free"));
        } else if ("bailian".equals(provider)) {
            out.add(modelEntry(cfg.get("bailian.model", "decision-model-preview")));
        } else if ("mock".equals(provider)) {
            out.add(modelEntry(cfg.get("mock.model", "keyword-match")));
        }
        return out;
    }

    /** 决策端点模型不在公开目录里——配置中的 openrouter 决策模型缺席时置顶补入。 */
    private static void prependDecisionsModel(List<Object> models, String decisionsModel) {
        for (Object o : models) {
            if (o instanceof Map && decisionsModel.equals(((Map<?, ?>) o).get("id"))) return;
        }
        models.add(0, modelEntry(decisionsModel));
    }

    private static Object modelEntry(String id) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("decisions", Boolean.TRUE);
        return m;
    }

    /** openrouter /api/v1/models → chat 模型条目 {id, name, free}（免费款排前，组内按 id 字母序）。 */
    private static List<Object> parseChatModels(String body) throws IOException {
        Map<String, Object> m = Json.parseObject(body);
        if (!(m.get("data") instanceof List)) throw new IOException("上游响应无 data 字段");
        List<Object[]> rows = new ArrayList<Object[]>();   // {free, id, name}
        for (Object o : (List<?>) m.get("data")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> e = (Map<?, ?>) o;
            boolean free = false;
            Object pr = e.get("pricing");
            if (pr instanceof Map) {
                Object p = ((Map<?, ?>) pr).get("prompt");
                Object c = ((Map<?, ?>) pr).get("completion");
                free = "0".equals(String.valueOf(p)) && "0".equals(String.valueOf(c));
            }
            rows.add(new Object[]{Boolean.valueOf(free), String.valueOf(e.get("id")), e.get("name")});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                boolean fa = (Boolean) a[0], fb = (Boolean) b[0];
                if (fa != fb) return fa ? -1 : 1;   // 免费款排前
                return ((String) a[1]).compareTo((String) b[1]);
            }
        });
        List<Object> out = new ArrayList<Object>();
        for (Object[] r : rows) {
            LinkedHashMap<String, Object> em = new LinkedHashMap<String, Object>();
            em.put("id", r[1]);
            em.put("name", r[2]);
            em.put("free", r[0]);
            out.add(em);
        }
        return out;
    }

    /** 生长生成模型静态兜底清单（上游不可达/缺凭据时）：当前生效模型置顶，`:free` 款自动标免费。 */
    private static List<Object> staticGrowthModels(String current) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
        ids.add(current);
        String[] fallback = {
                "meta-llama/llama-3.3-70b-instruct:free", "deepseek/deepseek-chat-v3.1:free",
                "google/gemma-3-27b-it:free", "qwen/qwen3-coder:free",
        };
        for (String id : fallback) ids.add(id);
        List<Object> out = new ArrayList<Object>();
        for (String id : ids) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("free", Boolean.valueOf(id.endsWith(":free")));
            out.add(m);
        }
        return out;
    }

    /** 判定轨迹步骤 → JSON（choice 模式额外带胜出选项与置信度）。 */
    private static List<Object> stepsJson(List<SemQuestTree.Step> steps) {
        List<Object> out = new ArrayList<Object>();
        for (SemQuestTree.Step s : steps) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("node", s.node);
            m.put("probs", s.probs);
            if (s.choice != null) {
                m.put("choice", s.choice);
                m.put("confidence", s.confidence);
            }
            m.put("latencyMs", s.latencyMs);
            m.put("inputTokens", s.inputTokens);
            m.put("outputTokens", s.outputTokens);
            out.add(m);
        }
        return out;
    }

    /** quest 响应组装：命中叶 + 排序文档（补正文）+ 判定轨迹 + 统计行。 */
    private static Map<String, Object> questMap(SemQuestTree tree, SemQuestTree.QuestReport rep) {
        Map<String, String> contents = new LinkedHashMap<String, String>();
        for (Object o : tree.docsSnapshot()) {
            if (o instanceof Map) {
                Object id = ((Map<?, ?>) o).get("id");
                Object c = ((Map<?, ?>) o).get("content");
                if (id != null && c != null) contents.put(String.valueOf(id), String.valueOf(c));
            }
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("query", rep.query);
        out.put("fastPath", rep.fast);   // true = 查询缓存直出（同款查询零模型返回，steps 为首次慢路轨迹复放）
        out.put("matchedLeaves", new ArrayList<Object>(rep.matchedLeaves));
        List<Object> ranked = new ArrayList<Object>();
        for (SemQuestTree.RankedDoc rd : rep.ranked) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("docId", rd.docId);
            m.put("score", rd.score);
            m.put("viaLeaves", rd.viaLeaves);
            m.put("content", contents.get(rd.docId));
            ranked.add(m);
        }
        out.put("ranked", ranked);
        out.put("steps", stepsJson(rep.steps));
        out.put("stats", tree.statsLine());
        return out;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String) || ((String) v).trim().isEmpty()) {
            throw new IllegalArgumentException("缺必填字段: " + key);
        }
        return (String) v;
    }

    /** Java 8 无 InputStream.readAllBytes，手读请求体。 */
    private static String readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        InputStream in = ex.getRequestBody();
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), UTF8);
    }

    private static String param(HttpExchange ex, String key) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (URLDecoder.decode(pair.substring(0, eq), "UTF-8").equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            }
        }
        return null;
    }

    private SemQuestServer() {
    }
}
