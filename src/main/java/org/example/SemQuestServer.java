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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

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
 *   POST /api/t/&lt;id&gt;/quest    → 查询 {query}：与节点名完全一致时直接返回该节点子树内容（零模型）；
 *                                  否则自然语言检索，返回命中叶 + 排序文档 + 判定轨迹
 *   GET  /api/models          → 决策模型列表 ?provider=mock|jev|openrouter|bailian（上游零 token 元数据；
 *                               失败/缺凭据回静态候选；openrouter 置顶补入配置里的决策模型）
 *   GET/POST /api/model       → 当前 provider/model / 热切换 {provider,model}（仅凭据校验，零推理）
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
        // 判定提示词同上铺初始值（prompt.*，热载见 watcher）
        final SemQuestTree.Prompts promptsInit = promptsOf(cfg);
        for (Table t : tables.values()) {
            synchronized (t) {
                t.tree.setPrompts(promptsInit);
            }
        }
        // 树级规则建表默认值取自 prompt.rule.*；启动时把仍带代码默认文本的旧表置换为配置值（自定义文本不动，置换落盘）
        final String ruleAddInit = ruleOf(cfg, "prompt.rule.add", SemQuestTree.DEFAULT_RULE_ADD);
        final String ruleSearchInit = ruleOf(cfg, "prompt.rule.search", SemQuestTree.DEFAULT_RULE_SEARCH);
        for (Table t : tables.values()) {
            synchronized (t) {
                if (t.tree.swapDefaultRules(SemQuestTree.DEFAULT_RULE_ADD, ruleAddInit,
                        SemQuestTree.DEFAULT_RULE_SEARCH, ruleSearchInit)) {
                    persistTable(t);
                    System.out.println("[启动] 表「" + t.name + "」树级规则已按 prompt.rule.* 置换");
                }
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new StaticHandler(vizFile));
        server.createContext("/api/tables", new TablesHandler(tables, cfg, backend, tablesDir));
        server.createContext("/api/t/", new TableRouter(tables));
        server.createContext("/api/models", new ModelsHandler(cfg, sel, timeoutMs));
        server.createContext("/api/model", new ModelHandler(cfg, backend, sel));
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
        final SemQuestTree.Prompts[] lastPrompts = { null };
        final String[] lastRules = { ruleAddInit, ruleSearchInit };   // 上次应用的 prompt.rule.* 值（启动铺底时已置换旧默认表）
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
                        SemQuestTree.Prompts np = promptsOf(cfg);
                        if (!np.equals(lastPrompts[0])) {
                            for (Table t : tables.values()) {
                                synchronized (t) {
                                    t.tree.setPrompts(np);
                                }
                            }
                            System.out.println("[热载] 判定提示词已更新（prompt.noul.* instructions/criterion / prompt.choice.* 问句与兜底文案）");
                        }
                        lastPrompts[0] = np;
                        String nra = ruleOf(cfg, "prompt.rule.add", SemQuestTree.DEFAULT_RULE_ADD);
                        String nrs = ruleOf(cfg, "prompt.rule.search", SemQuestTree.DEFAULT_RULE_SEARCH);
                        if (!nra.equals(lastRules[0]) || !nrs.equals(lastRules[1])) {
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

    /** semquest.properties → 判定提示词（prompt.*，空值回内置默认）。
     *  3 个可选分野键（noul.search.instructions、choice.search.{root,node}）缺省/留空即继承 add 侧同键。 */
    private static SemQuestTree.Prompts promptsOf(Config cfg) {
        String noulAdd = cfg.get("prompt.noul.add.instructions", null);
        String choiceRoot = cfg.get("prompt.choice.add.root", null);
        String choiceNode = cfg.get("prompt.choice.add.node", null);
        String noulSearch = cfg.get("prompt.noul.search.instructions", null);
        String searchRoot = cfg.get("prompt.choice.search.root", null);
        String searchNode = cfg.get("prompt.choice.search.node", null);
        return new SemQuestTree.Prompts(
                noulAdd,
                noulSearch != null ? noulSearch : noulAdd,
                cfg.get("prompt.noul.search.criterion", null),
                cfg.get("prompt.noul.search.query", null),
                choiceRoot, choiceNode,
                searchRoot != null ? searchRoot : choiceRoot,
                searchNode != null ? searchNode : choiceNode,
                cfg.get("prompt.choice.other", null));
    }

    /** 配置字符串键：空白回默认值（规则键 prompt.rule.* 用，缺省 = 代码内置默认文本）。 */
    private static String ruleOf(Config cfg, String key, String def) {
        String v = cfg.get(key, null);
        return v == null || v.trim().isEmpty() ? def : v.trim();
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
                            ruleOf(cfg, "prompt.rule.add", SemQuestTree.DEFAULT_RULE_ADD),
                            ruleOf(cfg, "prompt.rule.search", SemQuestTree.DEFAULT_RULE_SEARCH));
                    tree.setQuestCacheCap(Math.max(0, cfg.getInt("questCacheSize", 256)));
                    tree.setPrompts(promptsOf(cfg));
                    Table t = new Table(id, name, note, System.currentTimeMillis(), false, tree,
                            tablesDir.resolve(id + ".json"),
                            new ArrayList<Map<String, Object>>(), new ArrayList<Map<String, Object>>());
                    persistTable(t);
                    tables.put(id, t);
                    System.out.println("[建表] 「" + name + "」(" + mode + ") → " + t.file.getFileName());
                    json(ex, 200, Json.write(tableMeta(t)));
                } catch (IllegalArgumentException e) {
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

        TableRouter(ConcurrentHashMap<String, Table> tables) {
            this.tables = tables;
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
                new AddHandler().handle(t, ex);
            } else if ("addBatch".equals(action)) {
                new AddBatchHandler().handle(t, ex);
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

    /** POST /api/t/&lt;id&gt;/add {content, id?} → 一条语料按判据下行落叶；id 缺省自动生成，落位随快照落盘。 */
    private static final class AddHandler extends BaseHandler {

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
                out.put("stats", t.tree.statsLine());
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

    /** POST /api/t/&lt;id&gt;/quest {query} → 自然语言检索（走当前后端逐层判定）。 */
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
                json(ex, 200, Json.write(questMap(t.tree, rep)));
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
