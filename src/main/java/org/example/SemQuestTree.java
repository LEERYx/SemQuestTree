package org.example;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 问义树（SemQuestTree）：预制判据树当索引、noul 多选下行取代哈希定位的语义检索器。
 *
 * <p>存储规则（对表范式的替换）：节点 = 判据 + 子指针，叶 = 指针集（正文单存于 Doc），
 * 一叶可多枝（noul 多选落位）。判定推迟到查询时——add 是内容下行，quest 是查询下行，
 * 读写同构，同一套路由代码，仅 V2 规则文本不同。
 *
 * <p>每层固定一个「都不相关」兜底叶（name + {@value #OTHER_SUFFIX}）：noul 判定请求只含真实子判据
 * （兜底不出题）；choice 判定每层显式含兜底选项（归一化逃逸口）。落位与检索同条件镜像——
 * add 时该层无真实子判据过门即落兜底叶（保证必落位），quest 时该层无真实子判据过门即命中兜底叶；
 * choice 检索单路径，胜出兜底项即止（不命中兜底叶）。
 *
 * <p>层级分支由 schema JSON 预制（类似 MySQL 建表的字段声明）。
 * 判据语言随子树：节点可自带 ruleAdd/ruleSearch 覆盖本子树的规则文本，缺省继承全局；
 * 判据为事件级原子断言，yes = "是"侧断言，no = "否"侧断言（双边判据方言）。
 * 兜底叶不设判据（choice 以固定文案作其选项描述，可经 prompt.choice.other 调整）。
 *
 * <pre>
 *   SemQuestTree tree = SemQuestTree.load(Path.of("driver-intent-schema.json"), new JevBackend(cfg, 30000), 0.7);
 *   AddReport  a = tree.add("T-0317", "This order only pays 1,200… bump it to 1,500, or I'll only load 8 tons… don't blame me when the delivery runs late.");
 *   QuestReport q = tree.quest("Find drivers who ask for a higher rate and also want to load less.");
 * </pre>
 */
public final class SemQuestTree {

    /** 兜底叶后缀：节点 PRICE 的兜底叶名为 PRICE_OTHER。 */
    public static final String OTHER_SUFFIX = "_OTHER";

    /** choice 模式下兜底选项的固定描述（参与归一化的显式逃逸口）。 */
    public static final String OTHER_OPTION_DESC = "以上都不符合";

    /** 树级默认规则（schema/建表未给规则时的兜底文本；semquest.properties 的 prompt.rule.* 可覆盖）。 */
    public static final String DEFAULT_RULE_ADD = "判定输入内容与哪个分支的判据相符。";
    public static final String DEFAULT_RULE_SEARCH = "判断查询内容在寻找哪个分支下的内容。";

    /** 判定任务侧别：取 add（落位/回填）还是 search（检索）那套提示词模板。 */
    public enum Task { ADD, SEARCH }

    // ── 判定提示词（系统侧组装文案，semquest.properties 热载）───────

    /**
     * 系统侧判定提示词：结构化判定方言里由服务组装的文案。判据 yes/no 与规则文本归树（节点数据），不在这里。
     * 占位符：{name}=分支名、{node}=层节点名、{yes}=判据原文；空值回内置默认。
     * add（落位）与 search（检索）各一套，对应配置键：prompt.noul.{add,search}.instructions、
     * prompt.noul.{add,search}.criterion、prompt.choice.{add,search}.{root,node}、prompt.choice.other。
     * criterion 在判定前包裹判据文本（add 默认恒等 {yes}；search 默认包检索框架），
     * 使「所有悲伤的话」这类泛查询能过机制描述型判据——框架落在模型真正读的判据字段里。
     * noul instructions 仅 jev / respan 方言下发；openrouter（非 respan）与百炼下发的是判据文本（含包裹），此模板不影响它们。
     */
    public static final class Prompts {

        private static final String D_NOUL_INSTRUCTIONS = "{name}";
        private static final String D_NOUL_ADD_CRITERION = "{yes}";
        private static final String D_NOUL_SEARCH_CRITERION = "该查询的结果应包含满足以下描述的内容：{yes}";
        private static final String D_ROOT_QUESTION = "这句话最符合哪一类？";
        private static final String D_NODE_QUESTION = "{node}";

        /** noul 题目 instructions 模板（{name}=分支名）。 */
        public final String noulAddInstructions;
        public final String noulSearchInstructions;
        /** noul 判据包裹模板（{yes}=判据原文、{name}=分支名；add 默认恒等）。 */
        public final String noulAddCriterion;
        public final String noulSearchCriterion;
        /** choice 根层问句。 */
        public final String choiceAddRoot;
        /** choice 子层问句模板（{node}=层节点名）。 */
        public final String choiceAddNode;
        public final String choiceSearchRoot;
        public final String choiceSearchNode;
        /** choice 兜底选项描述（criteria 里 X_OTHER 的文案）。 */
        public final String choiceOtherOption;

        /** 全内置默认（未配置任何 prompt.* 键）。 */
        public Prompts() {
            this(null, null, null, null, null, null, null, null, null);
        }

        public Prompts(String noulAddInstructions, String noulSearchInstructions,
                       String noulAddCriterion, String noulSearchCriterion,
                       String choiceAddRoot, String choiceAddNode,
                       String choiceSearchRoot, String choiceSearchNode,
                       String choiceOtherOption) {
            this.noulAddInstructions = blank(noulAddInstructions) ? D_NOUL_INSTRUCTIONS : noulAddInstructions.trim();
            this.noulSearchInstructions = blank(noulSearchInstructions) ? D_NOUL_INSTRUCTIONS : noulSearchInstructions.trim();
            this.noulAddCriterion = blank(noulAddCriterion) ? D_NOUL_ADD_CRITERION : noulAddCriterion.trim();
            this.noulSearchCriterion = blank(noulSearchCriterion) ? D_NOUL_SEARCH_CRITERION : noulSearchCriterion.trim();
            this.choiceAddRoot = blank(choiceAddRoot) ? D_ROOT_QUESTION : choiceAddRoot.trim();
            this.choiceAddNode = blank(choiceAddNode) ? D_NODE_QUESTION : choiceAddNode.trim();
            this.choiceSearchRoot = blank(choiceSearchRoot) ? D_ROOT_QUESTION : choiceSearchRoot.trim();
            this.choiceSearchNode = blank(choiceSearchNode) ? D_NODE_QUESTION : choiceSearchNode.trim();
            this.choiceOtherOption = blank(choiceOtherOption) ? OTHER_OPTION_DESC : choiceOtherOption.trim();
        }

        /** noul 题目 instructions：按任务侧别取模板，{name} 替换为分支名。 */
        public String noulInstructions(Task task, String name) {
            String tpl = task == Task.ADD ? noulAddInstructions : noulSearchInstructions;
            return tpl.replace("{name}", name);
        }

        /** noul 判据文本：按任务侧别取包裹模板，{yes} 替换判据原文、{name} 替换分支名（add 默认恒等，search 默认包检索框架）。 */
        public String criterion(Task task, String yes, String name) {
            String tpl = task == Task.ADD ? noulAddCriterion : noulSearchCriterion;
            return tpl.replace("{yes}", yes == null ? "" : yes).replace("{name}", name);
        }

        /** choice 问句：根层用 root 问句，子层模板 {node} 替换层节点名。 */
        public String choiceQuestion(Task task, String node) {
            if ("ROOT".equals(node)) return task == Task.ADD ? choiceAddRoot : choiceSearchRoot;
            String tpl = task == Task.ADD ? choiceAddNode : choiceSearchNode;
            return tpl.replace("{node}", node);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Prompts)) return false;
            Prompts p = (Prompts) o;
            return noulAddInstructions.equals(p.noulAddInstructions)
                    && noulSearchInstructions.equals(p.noulSearchInstructions)
                    && noulAddCriterion.equals(p.noulAddCriterion)
                    && noulSearchCriterion.equals(p.noulSearchCriterion)
                    && choiceAddRoot.equals(p.choiceAddRoot)
                    && choiceAddNode.equals(p.choiceAddNode)
                    && choiceSearchRoot.equals(p.choiceSearchRoot)
                    && choiceSearchNode.equals(p.choiceSearchNode)
                    && choiceOtherOption.equals(p.choiceOtherOption);
        }

        @Override
        public int hashCode() {
            int h = noulAddInstructions.hashCode();
            h = h * 31 + noulSearchInstructions.hashCode();
            h = h * 31 + noulAddCriterion.hashCode();
            h = h * 31 + noulSearchCriterion.hashCode();
            h = h * 31 + choiceAddRoot.hashCode();
            h = h * 31 + choiceAddNode.hashCode();
            h = h * 31 + choiceSearchRoot.hashCode();
            h = h * 31 + choiceSearchNode.hashCode();
            return h * 31 + choiceOtherOption.hashCode();
        }

        private static boolean blank(String s) {
            return s == null || s.trim().isEmpty();
        }
    }

    /** 当前判定提示词（全表共用一份配置值；semquest.properties 保存即热载）。 */
    private volatile Prompts prompts = new Prompts();

    public Prompts prompts() {
        return prompts;
    }

    public void setPrompts(Prompts p) {
        if (p != null) {
            this.prompts = p;
            bustQuestCache();   // 判定语义变了，缓存里的旧报告全部作废
        }
    }

    // ── 结构 ────────────────────────────────────────────────────────

    /** 树节点：children 为空即叶（叶挂 Doc 指针）；internal 懒挂一个兜底叶。 */
    public static final class SemNode {
        public String name;           // 非 final：右键「修改节点」可改名（连带兜底叶与落位指针）
        public String yes;            // "是"侧原子断言（在父层被提问）；可经修改节点更新
        public String no;             // "否"侧断言；可经修改节点更新
        public final boolean other;   // 是否兜底叶
        public final String ruleAdd;    // 子树规则覆盖（如中文子树四层全中文）；null 继承上层规则
        public final String ruleSearch;
        public final List<SemNode> children;
        public final List<Doc> docs = new ArrayList<Doc>();   // 仅叶使用
        private SemNode otherLeaf;                            // 仅内部节点懒创建

        SemNode(String name, String yes, String no, boolean other, List<SemNode> children) {
            this(name, yes, no, other, children, null, null);
        }

        SemNode(String name, String yes, String no, boolean other, List<SemNode> children,
                String ruleAdd, String ruleSearch) {
            this.name = name;
            this.yes = yes;
            this.no = no;
            this.other = other;
            this.children = children;
            this.ruleAdd = ruleAdd;
            this.ruleSearch = ruleSearch;
        }

        boolean isLeaf() {
            return children.isEmpty();
        }
    }

    /** 正文单存：叶里只放 Doc 指针；leaves 记录落位叶名（一 Doc 可多叶）。 */
    public static final class Doc {
        public final String id;
        public final String content;
        final LinkedHashSet<String> leaves = new LinkedHashSet<String>();

        Doc(String id, String content) {
            this.id = id;
            this.content = content;
        }
    }

    // ── 过程报告 ────────────────────────────────────────────────────

    /** 一次批量判定的轨迹：发生在哪个节点、每题 P(yes)、延迟与 token。choice 题型另记选中项与置信度。 */
    public static final class Step {
        public final String node;
        public final Map<String, Double> probs;
        public final long latencyMs;
        public final int inputTokens;
        public final int outputTokens;
        public final String choice;      // choice 题型：选中项；noul 为 null
        public final double confidence;  // choice 题型：置信度；noul 为 0

        Step(String node, NoulReply r) {
            this.node = node;
            this.probs = r.probs;
            this.latencyMs = r.latencyMs;
            this.inputTokens = r.inputTokens;
            this.outputTokens = r.outputTokens;
            this.choice = r.choice;
            this.confidence = r.confidence;
        }
    }

    /** add 返回：落位叶 + 全程判定轨迹。 */
    public static final class AddReport {
        public final String docId;
        public final List<Step> steps = new ArrayList<Step>();
        public final List<String> leaves = new ArrayList<String>();

        AddReport(String docId) {
            this.docId = docId;
        }
    }

    /** quest 返回：命中叶 + 排序文档。choice 模式 pathScore = 路径各层胜出概率连乘（排序分）。 */
    public static final class QuestReport {
        public final String query;
        public final List<Step> steps = new ArrayList<Step>();
        public final List<String> matchedLeaves = new ArrayList<String>();
        public final List<RankedDoc> ranked = new ArrayList<RankedDoc>();
        /** true = 查询缓存直出（零模型调用，steps 为首次慢路轨迹的原样复放）；粘性——条目命中即置位。 */
        public boolean fast = false;
        public double pathScore = 1.0d;

        QuestReport(String query) {
            this.query = query;
        }
    }

    /** 命中文档：score = noul 模式为命中叶数；choice 模式为路径各层胜出概率连乘。 */
    public static final class RankedDoc {
        public final String docId;
        public final double score;
        public final List<String> viaLeaves;

        RankedDoc(String docId, double score, LinkedHashSet<String> viaLeaves) {
            this.docId = docId;
            this.score = score;
            this.viaLeaves = new ArrayList<String>(viaLeaves);
        }
    }

    // ── 状态 ────────────────────────────────────────────────────────

    private final SemNode root;
    private final DecisionBackend backend;
    private volatile double gate;
    /** choice 模式采纳线（SDK 官方语义：confidence ≥ 采纳线才认选中项，否则落兜底）。 */
    private volatile double choiceGate;
    /** true = choice 单选路由树（每层一题归一化选一支）；false = noul 多选树（默认）。 */
    private final boolean choiceMode;
    // 树级规则非 final：prompt.rule.* 热载可置换仍等于旧默认文本的表（自定义文本不动）
    private volatile String ruleAdd;
    private volatile String ruleSearch;
    private final Map<String, Doc> docIndex = new LinkedHashMap<String, Doc>();

    private int requests = 0;
    private long totalIn = 0, totalOut = 0, totalLatency = 0;
    private double totalCost = 0.0;

    // ── 查询结果缓存（LFU）：同款查询零模型直返 ─────────────────────

    /** 查询原文 → 慢路产出的完整报告。accessOrder=true：迭代序=最久未用在前，淘汰时同频取最旧。 */
    private final LinkedHashMap<String, QuestReport> questCache =
            new LinkedHashMap<String, QuestReport>(64, 0.75f, true);
    /** 各条目命中次数：调用最多越不易淘汰；新条目记 1。 */
    private final Map<String, Integer> questCacheHits = new LinkedHashMap<String, Integer>();
    /** 容量上限（0=关闭）；semquest.properties 的 questCacheSize 热载应用。仅内存，重启从零攒。 */
    private volatile int questCacheCap = 0;

    private SemQuestTree(SemNode root, DecisionBackend backend, double gate,
                         String ruleAdd, String ruleSearch) {
        this(root, backend, gate, ruleAdd, ruleSearch, false, 0.6d);
    }

    private SemQuestTree(SemNode root, DecisionBackend backend, double gate,
                         String ruleAdd, String ruleSearch, boolean choiceMode, double choiceGate) {
        this.root = root;
        this.backend = backend;
        this.gate = gate;
        this.choiceGate = choiceGate;
        this.choiceMode = choiceMode;
        this.ruleAdd = ruleAdd;
        this.ruleSearch = ruleSearch;
    }

    // ── schema 装载 ─────────────────────────────────────────────────

    /**
     * 从 schema JSON 装载预制树。字段：mode("noul"|"choice") / gate / choiceGate / ruleAdd / ruleSearch /
     * root{name, children:[{name, yes, no, ruleAdd?, ruleSearch?, children:[…]}]}——children 缺省即叶；
     * 节点可选自带 ruleAdd/ruleSearch 覆盖本子树的规则文本（如中文子树四层全中文），缺省继承全局。
     * 兜底叶由系统懒创建；noul 模式不参与判定请求，choice 模式作为显式选项参与。
     */
    public static SemQuestTree load(Path schemaFile, DecisionBackend backend, double gateDefault)
            throws IOException {
        Map<String, Object> m = Json.parseObject(
                new String(Files.readAllBytes(schemaFile), Charset.forName("UTF-8")));
        return fromMap(m, backend, gateDefault, schemaFile.toString());
    }

    /** 从表文件内嵌的 schema Map 装载（多表持久化用）。 */
    public static SemQuestTree fromMap(Map<String, Object> m, DecisionBackend backend) throws IOException {
        return fromMap(m, backend, 0.7d, "(table)");
    }

    private static SemQuestTree fromMap(Map<String, Object> m, DecisionBackend backend,
                                        double gateDefault, String source) throws IOException {
        // noulGate 新键；gate 为改名前旧表文件的兼容回退
        double gate = m.get("noulGate") instanceof Number ? ((Number) m.get("noulGate")).doubleValue()
                : (m.get("gate") instanceof Number ? ((Number) m.get("gate")).doubleValue() : gateDefault);
        double choiceGate = m.get("choiceGate") instanceof Number ? ((Number) m.get("choiceGate")).doubleValue() : 0.6d;
        String ruleAdd = optText(m, "ruleAdd");
        if (ruleAdd == null) ruleAdd = DEFAULT_RULE_ADD;
        String ruleSearch = optText(m, "ruleSearch");
        if (ruleSearch == null) ruleSearch = DEFAULT_RULE_SEARCH;
        if (!(m.get("root") instanceof Map)) {
            throw new IOException("schema 缺 root 节点: " + source);
        }
        SemNode root = parseNode((Map<?, ?>) m.get("root"));
        boolean choice = "choice".equals(m.get("mode"));
        return new SemQuestTree(root, backend, gate, ruleAdd, ruleSearch, choice, choiceGate);
    }

    /** 新建空表树：只有 ROOT 节点（建树用右键加节点；choice 模式加子节点=加选项）。 */
    public static SemQuestTree createEmpty(DecisionBackend backend, boolean choiceMode,
                                           double gate, double choiceGate, String ruleAdd, String ruleSearch) {
        SemNode root = new SemNode("ROOT", null, null, false, new ArrayList<SemNode>(),
                null, null);
        return new SemQuestTree(root, backend, gate, ruleAdd, ruleSearch, choiceMode, choiceGate);
    }

    @SuppressWarnings("unchecked")
    private static SemNode parseNode(Map<?, ?> m) {
        String name = (String) m.get("name");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("schema 节点缺 name");
        String yes = m.get("yes") instanceof String ? (String) m.get("yes") : null;
        String no = m.get("no") instanceof String ? (String) m.get("no") : null;
        String ruleAdd = optText(m, "ruleAdd");
        String ruleSearch = optText(m, "ruleSearch");
        List<SemNode> children = new ArrayList<SemNode>();
        if (m.get("children") instanceof List) {
            for (Object o : (List<Object>) m.get("children")) {
                if (o instanceof Map) children.add(parseNode((Map<?, ?>) o));
            }
        }
        SemNode node = new SemNode(name, yes, no, false, children, ruleAdd, ruleSearch);
        return node;
    }

    /** 可选字符串字段：存在且非空白才返回，否则 null。 */
    private static String optText(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof String && !((String) v).trim().isEmpty() ? (String) v : null;
    }

    // ── add：内容下行落位 ───────────────────────────────────────────

    /** 把一条正文按预制分支分类到叶。noul：无真实子过门落兜底；choice：单选下行，选中兜底项或低于采纳线落兜底。 */
    public AddReport add(String docId, String content) throws IOException {
        bustQuestCache();   // 落位变了，查询缓存全部作废
        Doc prev = docIndex.get(docId);   // 同 id 重复 add：先摘旧指针再落位（控制台可反复改写同一条）
        if (prev != null) {
            for (String ln : prev.leaves) {
                SemNode n = find(root, ln);
                if (n != null) n.docs.remove(prev);
            }
        }
        Doc doc = new Doc(docId, content);
        AddReport rep = new AddReport(docId);
        if (choiceMode) {
            descendAddChoice(root, content, doc, rep, ruleAdd);
        } else {
            descendAdd(root, content, doc, rep, ruleAdd);
        }
        docIndex.put(docId, doc);
        return rep;
    }

    private void descendAdd(SemNode node, String content, Doc doc, AddReport rep, String rule) throws IOException {
        if (node.isLeaf()) {
            node.docs.add(doc);
            doc.leaves.add(node.name);
            rep.leaves.add(node.name);
            return;
        }
        final String effRule = node.ruleAdd != null ? node.ruleAdd : rule;   // 子树规则覆盖
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        for (SemNode c : node.children) {
            qs.put(c.name, new NoulSpec(prompts.noulInstructions(Task.ADD, c.name), prompts.criterion(Task.ADD, c.yes, c.name), c.no));
        }
        // 兜底叶不进请求
        NoulReply r = ask(node.name, content, effRule, qs, rep.steps);

        List<SemNode> passing = new ArrayList<SemNode>();
        for (SemNode c : node.children) {
            Double p = r.probs.get(c.name);
            if (p != null && p >= gate) passing.add(c);
        }
        if (passing.isEmpty()) {
            SemNode other = otherLeaf(node);   // 兜底：本层无真实判据过门 → 落「都不相关」叶
            other.docs.add(doc);
            doc.leaves.add(other.name);
            rep.leaves.add(other.name);
            return;
        }
        for (SemNode c : passing) {
            descendAdd(c, content, doc, rep, effRule);
        }
    }

    // ── choice 单选下行（每层一题归一化，argmax 单走） ───────────────

    /** choice 层一次判定的结果：胜出节点 + 胜出概率（confidence 缺失时回退该选项概率）。 */
    private static final class ChoicePick {
        final SemNode node;
        final double prob;

        ChoicePick(SemNode node, double prob) {
            this.node = node;
            this.prob = prob;
        }
    }

    private void descendAddChoice(SemNode node, String content, Doc doc, AddReport rep, String rule)
            throws IOException {
        if (node.isLeaf()) {
            node.docs.add(doc);
            doc.leaves.add(node.name);
            rep.leaves.add(node.name);
            return;
        }
        final String effRule = node.ruleAdd != null ? node.ruleAdd : rule;
        ChoicePick pick = askChoice(node, content, effRule, Task.ADD, rep.steps);
        if (pick == null) {   // 选中兜底项或低于采纳线 → 落本层兜底叶（保证必落位）
            SemNode other = otherLeaf(node);
            other.docs.add(doc);
            doc.leaves.add(other.name);
            rep.leaves.add(other.name);
            return;
        }
        descendAddChoice(pick.node, content, doc, rep, effRule);
    }

    /**
     * choice 层判定：criteria = 真实子节点（描述=yes 判据或名字）+ 兜底叶显式选项（归一化逃逸口）。
     * 胜出兜底项或 confidence < choiceGate 返回 null（调用方落兜底）；否则返回胜出子节点与概率。
     * 问句按任务侧别取模板：add（落位）与 search（检索）各一套（prompt.choice.add.* / prompt.choice.search.*）。
     */
    private ChoicePick askChoice(SemNode node, String content, String rule, Task task, List<Step> steps)
            throws IOException {
        LinkedHashMap<String, String> criteria = new LinkedHashMap<String, String>();
        for (SemNode c : node.children) {
            criteria.put(c.name, c.yes != null && !c.yes.trim().isEmpty() ? c.yes : c.name);
        }
        SemNode other = otherLeaf(node);
        criteria.put(other.name, prompts.choiceOtherOption);

        String question = prompts.choiceQuestion(task, node.name);
        NoulReply r = backend.choice(content, rule, question, criteria);
        steps.add(new Step(node.name, r));
        requests++;
        totalIn += r.inputTokens;
        totalOut += r.outputTokens;
        totalLatency += r.latencyMs;
        totalCost += r.cost;

        // 胜出者：上游 choice 字段优先，缺失回退 probs argmax；未知选项按兜底处理
        String win = r.choice;
        if (win == null || win.trim().isEmpty()) {
            double best = -1;
            for (Map.Entry<String, Double> e : r.probs.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    win = e.getKey();
                }
            }
        }
        if (win == null) return null;
        SemNode target = null;
        for (SemNode c : node.children) {
            if (c.name.equals(win)) {
                target = c;
                break;
            }
        }
        if (target == null) return null;   // 兜底项或未知选项
        double conf = r.confidence > 0 ? r.confidence
                : (r.probs.get(target.name) != null ? r.probs.get(target.name) : 0.0d);
        if (conf < choiceGate) return null;   // 采纳线不达标 → 落兜底
        return new ChoicePick(target, conf);
    }

    // ── quest：查询原文下行取叶 ─────────────────────────────────────

    /**
     * 自然语言检索：查询下行收集命中叶，取叶上 Doc 并集排序。noul 分数=命中叶数；choice=路径胜出概率连乘。
     * 查询缓存开启时同款查询（原文完全一致）零模型直返；任何表变更（add/grow/edit/remove/双门控）清空缓存。
     */
    public QuestReport quest(String query) throws IOException {
        if (questCacheCap > 0) {
            QuestReport cached = questCache.get(query);
            if (cached != null) {
                questCacheHits.put(query, questCacheHits.get(query) + 1);
                cached.fast = true;   // 缓存直出标记（粘性：条目此后一直走缓存）
                return cached;
            }
        }
        QuestReport rep = new QuestReport(query);
        LinkedHashSet<SemNode> matched = new LinkedHashSet<SemNode>();
        if (choiceMode) {
            descendQuestChoice(root, query, matched, rep, ruleSearch, 1.0d);
        } else {
            descendQuest(root, query, matched, rep.steps, ruleSearch);
        }

        LinkedHashMap<Doc, LinkedHashSet<String>> via = new LinkedHashMap<Doc, LinkedHashSet<String>>();
        for (SemNode leaf : matched) {
            rep.matchedLeaves.add(leaf.name);
            for (Doc d : leaf.docs) {
                LinkedHashSet<String> set = via.get(d);
                if (set == null) {
                    set = new LinkedHashSet<String>();
                    via.put(d, set);
                }
                set.add(leaf.name);
            }
        }
        List<Map.Entry<Doc, LinkedHashSet<String>>> entries =
                new ArrayList<Map.Entry<Doc, LinkedHashSet<String>>>(via.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<Doc, LinkedHashSet<String>>>() {
            public int compare(Map.Entry<Doc, LinkedHashSet<String>> a, Map.Entry<Doc, LinkedHashSet<String>> b) {
                int byScore = Double.compare(b.getValue().size(), a.getValue().size()); // 分数高在前
                return byScore != 0 ? byScore : a.getKey().id.compareTo(b.getKey().id);
            }
        });
        for (Map.Entry<Doc, LinkedHashSet<String>> e : entries) {
            double s = choiceMode ? rep.pathScore : e.getValue().size();
            rep.ranked.add(new RankedDoc(e.getKey().id, s, e.getValue()));
        }
        if (questCacheCap > 0) cachePut(query, rep);
        return rep;
    }

    private void descendQuestChoice(SemNode node, String query, LinkedHashSet<SemNode> matched,
                                    QuestReport rep, String rule, double pathProb) throws IOException {
        if (node.isLeaf()) {
            matched.add(node);
            rep.pathScore = pathProb;
            return;
        }
        final String effRule = node.ruleSearch != null ? node.ruleSearch : rule;   // 子树规则覆盖
        ChoicePick pick = askChoice(node, query, effRule, Task.SEARCH, rep.steps);
        if (pick == null) return;   // 选中兜底或低于采纳线：检索到此为止（不落兜底）
        descendQuestChoice(pick.node, query, matched, rep, effRule, pathProb * pick.prob);
    }

    private void descendQuest(SemNode node, String query, LinkedHashSet<SemNode> matched,
                              List<Step> steps, String rule) throws IOException {
        if (node.isLeaf()) {
            matched.add(node);
            return;
        }
        final String effRule = node.ruleSearch != null ? node.ruleSearch : rule;   // 子树规则覆盖
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        for (SemNode c : node.children) {   // 兜底叶不参与检索
            // 判据包检索框架（search 侧模板）：框架落在模型读的判据字段里，泛查询才能过机制描述型判据
            qs.put(c.name, new NoulSpec(prompts.noulInstructions(Task.SEARCH, c.name), prompts.criterion(Task.SEARCH, c.yes, c.name), null));
        }
        NoulReply r = ask(node.name, query, effRule, qs, steps);
        boolean any = false;
        for (SemNode c : node.children) {
            Double p = r.probs.get(c.name);
            if (p != null && p >= gate) {
                any = true;
                descendQuest(c, query, matched, steps, effRule);
            }
        }
        if (!any && node.otherLeaf != null) {
            matched.add(node.otherLeaf);   // 与落位镜像：本层无真实判据过门 → 命中该层兜底叶（add 同条件落位）
        }
    }

    // ── 查询缓存（LFU）：按命中次数排序淘汰 ────────────────────────

    /** 缓存未命中后的慢路结果入池；超容量按「命中次数最少优先、同次数最久未用优先」淘汰。 */
    private void cachePut(String query, QuestReport rep) {
        if (questCache.containsKey(query)) return;   // 变更清空后同查询重建，不会走到
        questCache.put(query, rep);
        questCacheHits.put(query, 1);
        while (questCache.size() > questCacheCap) cacheEvict();
    }

    /** 淘汰一个条目：命中次数最少者出局；同次数取最久未用（accessOrder 迭代序最前）。 */
    private void cacheEvict() {
        String victim = null;
        int min = Integer.MAX_VALUE;
        for (String k : questCache.keySet()) {
            int h = questCacheHits.get(k);
            if (h < min) {
                min = h;
                victim = k;
            }
        }
        if (victim != null) {
            questCache.remove(victim);
            questCacheHits.remove(victim);
        }
    }

    /** 任何表变更（add / 增长 / 修改 / 删除 / 双门控调整）后缓存结果不再可信，整池清空。 */
    private void bustQuestCache() {
        if (!questCache.isEmpty()) {
            questCache.clear();
            questCacheHits.clear();
        }
    }

    /** 当前缓存容量上限（0=关闭）。 */
    public int questCacheCap() {
        return questCacheCap;
    }

    /** 设置查询缓存容量（热载调用）：0=关闭并清空；缩容时立即按 LFU 淘汰到新上限。 */
    public void setQuestCacheCap(int n) {
        this.questCacheCap = Math.max(0, n);
        while (questCache.size() > questCacheCap) cacheEvict();
    }

    // ── 公共 ────────────────────────────────────────────────────────

    /**
     * 热载置换树级默认规则（prompt.rule.*）：仅当当前规则仍等于 old 文本时替换——按旧配置自定义过的树不动，
     * 节点级 ruleAdd/ruleSearch 覆盖不受影响。发生置换即清查询缓存（规则变了旧报告不可信）。返回是否置换。
     */
    public boolean swapDefaultRules(String oldAdd, String newAdd, String oldSearch, String newSearch) {
        boolean changed = false;
        if (oldAdd != null && newAdd != null && !oldAdd.equals(newAdd) && oldAdd.equals(ruleAdd)) {
            this.ruleAdd = newAdd;
            changed = true;
        }
        if (oldSearch != null && newSearch != null && !oldSearch.equals(newSearch) && oldSearch.equals(ruleSearch)) {
            this.ruleSearch = newSearch;
            changed = true;
        }
        if (changed) bustQuestCache();
        return changed;
    }

    /** 当前树级 add 规则文本（可视化/调试读取用）。 */
    public String ruleAdd() {
        return ruleAdd;
    }

    /** 当前树级 search 规则文本（可视化/调试读取用）。 */
    public String ruleSearch() {
        return ruleSearch;
    }

    /** 运行时调整门控（0 < gate ≤ 1），只影响其后的 add / quest / grow 判定，不重排已落位数据。 */
    public void setGate(double g) {
        if (!(g > 0 && g <= 1)) throw new IllegalArgumentException("门控须在 (0, 1] 区间: " + g);
        this.gate = g;
        bustQuestCache();   // 门变了，缓存结果与重跑慢路不再一致
    }

    /** 当前门控值（热载线程读取比对用）。 */
    public double gate() {
        return gate;
    }

    /** 运行时调整 choice 采纳线（0 < choiceGate ≤ 1），只影响其后的判定。 */
    public void setChoiceGate(double g) {
        if (!(g > 0 && g <= 1)) throw new IllegalArgumentException("采纳线须在 (0, 1] 区间: " + g);
        this.choiceGate = g;
        bustQuestCache();
    }

    /** 当前 choice 采纳线。 */
    public double choiceGate() {
        return choiceGate;
    }

    /** 树分支模式：noul（多选多落）/ choice（单选单走）。创建时定死。 */
    public boolean choiceMode() {
        return choiceMode;
    }

    /** 按叶名取 Doc 列表（只读视图）。 */
    public List<Doc> docsAt(String leafName) {
        SemNode n = find(root, leafName);
        return n == null ? Collections.<Doc>emptyList() : Collections.unmodifiableList(n.docs);
    }

    public int docCount() {
        return docIndex.size();
    }

    /** 累计账单行：请求数 / tokens / 费用 / 判定耗时合计。 */
    public String statsLine() {
        String cost = totalCost > 0
                ? String.format("$%.5f (上游计费)", totalCost)
                : "未计费";
        return String.format("判定 %d 次请求 · tokens in %,d / out %,d · 费用 %s · 判定耗时合计 %,d ms",
                requests, totalIn, totalOut, cost, totalLatency);
    }

    // ── 持久化与可视化 API ──────────────────────────────────────────

    /** 把已持久化的落位结果挂回树（不调模型；叶名含兜底叶）。 */
    public void attachPersisted(String docId, String content, List<String> leafNames) {
        Doc doc = new Doc(docId, content);
        for (String ln : leafNames) {
            SemNode n = resolveLeaf(ln);
            if (n != null) {
                n.docs.add(doc);
                doc.leaves.add(n.name);
            }
        }
        docIndex.put(docId, doc);
    }

    /** 为所有内部节点预创建兜底叶（持久化载入后 find 可达；幂等）。 */
    public void ensureOtherLeaves() {
        ensureOthers(root);
    }

    /** 落位正文快照（持久化用）：[{id, content, leaves}]。 */
    public List<Object> docsSnapshot() {
        List<Object> out = new ArrayList<Object>();
        for (Doc d : docIndex.values()) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", d.id);
            m.put("content", d.content);
            m.put("leaves", new ArrayList<String>(d.leaves));
            out.add(m);
        }
        return out;
    }

    /** 全局元信息：后端 / 模式 / 门控 / 采纳线 / 落位总数。 */
    public Map<String, Object> metaMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backend", backend != null ? backend.name() : "(未接)");
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("noulGate", gate);
        m.put("choiceGate", choiceGate);
        m.put("docCount", docIndex.size());
        return m;
    }

    /**
     * 单层节点信息（可视化逐层展开一次取一层）：节点本身 + 直接子节点（子不带孙）。
     * name 传 null 取根。找不到返回 null。
     */
    public Map<String, Object> nodeLevel(String name) {
        SemNode n = name == null ? root : find(root, name);
        if (n == null) return null;
        Map<String, Object> m = shallowMap(n);
        List<Object> children = new ArrayList<Object>();
        for (SemNode c : n.children) children.add(shallowMap(c));
        if (n.otherLeaf != null) children.add(shallowMap(n.otherLeaf));
        m.put("children", children);
        return m;
    }

    private Map<String, Object> shallowMap(SemNode n) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", n.name);
        m.put("leaf", n.isLeaf());
        m.put("other", n.other);
        if (n.yes != null) m.put("yes", n.yes);
        if (n.no != null) m.put("no", n.no);
        m.put("docCount", n.docs.size());
        return m;
    }

    /** 全树结构 → 可 JSON 化的 Map（含每叶 docCount，不含正文——正文点击才查）。 */
    public Map<String, Object> treeMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backend", backend != null ? backend.name() : "(未接)");
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("noulGate", gate);
        m.put("choiceGate", choiceGate);
        m.put("docCount", docIndex.size());
        m.put("root", nodeMap(root));
        return m;
    }

    /** 表文件内嵌 schema（持久化用）：mode / 双门控 / 规则 / 干净节点结构（不含 docCount）。 */
    public Map<String, Object> schemaMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("noulGate", gate);
        m.put("choiceGate", choiceGate);
        m.put("ruleAdd", ruleAdd);
        m.put("ruleSearch", ruleSearch);
        m.put("root", schemaNode(root));
        return m;
    }

    private Map<String, Object> schemaNode(SemNode n) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", n.name);
        if (n.yes != null) m.put("yes", n.yes);
        if (n.no != null) m.put("no", n.no);
        if (n.ruleAdd != null) m.put("ruleAdd", n.ruleAdd);
        if (n.ruleSearch != null) m.put("ruleSearch", n.ruleSearch);
        List<Object> children = new ArrayList<Object>();
        for (SemNode c : n.children) children.add(schemaNode(c));
        if (!children.isEmpty()) m.put("children", children);
        return m;
    }

    /**
     * 节点详情（可视化点击时调用，叶与内部节点共用一个视图）：路径 + 判据 + 落位正文；
     * 内部节点另带直接子节点列表（shallow，含兜底叶）与子树落位合计 docTotal。找不到返回 null。
     */
    public Map<String, Object> leafInfo(String name) {
        List<String> path = new ArrayList<String>();
        if (!findPath(root, name, path)) return null;
        SemNode n = find(root, name);
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", n.name);
        m.put("leaf", n.isLeaf());
        m.put("other", n.other);
        m.put("path", path);
        if (n.yes != null) m.put("yes", n.yes);
        if (n.no != null) m.put("no", n.no);
        List<Object> docs = new ArrayList<Object>();
        for (Doc d : n.docs) {
            LinkedHashMap<String, Object> dm = new LinkedHashMap<String, Object>();
            dm.put("id", d.id);
            dm.put("content", d.content);
            docs.add(dm);
        }
        m.put("docs", docs);
        if (!n.isLeaf()) {
            List<Object> children = new ArrayList<Object>();
            for (SemNode c : n.children) children.add(shallowMap(c));
            if (n.otherLeaf != null) children.add(shallowMap(n.otherLeaf));
            m.put("children", children);
            m.put("docTotal", docsUnder(n).size());
        }
        return m;
    }

    /** 子树内全部落位 Doc 去重集合（一 Doc 多叶只计一次）。 */
    private LinkedHashSet<Doc> docsUnder(SemNode n) {
        LinkedHashSet<Doc> acc = new LinkedHashSet<Doc>();
        collectDocs(n, acc);
        return acc;
    }

    private static void collectDocs(SemNode n, LinkedHashSet<Doc> acc) {
        acc.addAll(n.docs);
        for (SemNode c : n.children) collectDocs(c, acc);
        if (n.otherLeaf != null) collectDocs(n.otherLeaf, acc);
    }

    // ── 节点直取：指定节点返回子树全部叶内容（零模型调用） ──────────

    /** 节点直取的一条文档：id + 正文 + 落位叶名列表（全量叶指针，子树外的落位叶也可见）。 */
    public static final class NodeDoc {
        public final String id;
        public final String content;
        public final List<String> viaLeaves;

        NodeDoc(Doc d) {
            this.id = d.id;
            this.content = d.content;
            this.viaLeaves = new ArrayList<String>(d.leaves);
        }
    }

    /**
     * 节点直取报告：指定节点的子树内全部叶的落位内容（叶与内部节点通用）。
     * 文档按叶 DFS 顺序去重（一 Doc 多叶只出一次）；兜底叶内容包含在内，viaLeaves 露出 X_OTHER 后缀可辨。
     */
    public static final class NodeDocs {
        public final String node;
        public final List<String> leaves;   // 子树叶名（DFS 序，含兜底叶）
        public final List<NodeDoc> docs;

        NodeDocs(String node, List<String> leaves, List<NodeDoc> docs) {
            this.node = node;
            this.leaves = leaves;
            this.docs = docs;
        }
    }

    /** 指定节点子树内全部叶的落位内容（零模型调用，未知节点抛 400）。 */
    public NodeDocs nodeDocs(String nodeName) {
        SemNode n = find(root, nodeName);
        if (n == null) throw new IllegalArgumentException("未知节点: " + nodeName);
        List<String> leaves = new ArrayList<String>();
        collectLeafNames(n, leaves);
        List<NodeDoc> docs = new ArrayList<NodeDoc>();
        for (Doc d : docsUnder(n)) docs.add(new NodeDoc(d));
        return new NodeDocs(n.name, leaves, docs);
    }

    private static void collectLeafNames(SemNode n, List<String> out) {
        if (n.isLeaf()) {
            out.add(n.name);
            return;
        }
        for (SemNode c : n.children) collectLeafNames(c, out);
        if (n.otherLeaf != null) out.add(n.otherLeaf.name);
    }

    // ── 增长：补层 + 存量回填 ───────────────────────────────────────

    /** 增长报告：新判据下受影响存量的逐条重判结果。 */
    public static final class GrowReport {
        public final String parent;
        public final String node;
        public final List<Move> moves = new ArrayList<Move>();

        GrowReport(String parent, String node) {
            this.parent = parent;
            this.node = node;
        }
    }

    /** 一条存量的重判结果：过门迁入新叶（内部父级同时挪出兜底叶），不过留原位。 */
    public static final class Move {
        public final String docId;
        public final double prob;
        public final boolean moved;

        Move(String docId, double prob, boolean moved) {
            this.docId = docId;
            this.prob = prob;
            this.moved = moved;
        }
    }

    /** 纯结构增长（零模型调用）：在 parent 下追加判断节点；回放持久化增长记录用。 */
    public SemNode addNode(String parent, String name, String yes, String no) {
        SemNode p = find(root, parent);
        if (p == null) throw new IllegalArgumentException("父节点不存在: " + parent);
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("节点名不可为空");
        String trimmed = name.trim();
        if (find(root, trimmed) != null) throw new IllegalArgumentException("节点名已存在: " + trimmed);
        SemNode n = new SemNode(trimmed, yes, no, false, new ArrayList<SemNode>());
        p.children.add(n);
        otherLeaf(p);   // 首个实子节点落地即生成兜底叶：choice 每层请求恒含兜底选项，UI 同步可见
        return n;
    }

    /**
     * 补层增长 + 挂靠回填：新增判断节点后，把受影响存量逐条重判（每条 1 次请求）：
     * noul——父为内部节点 → 重判其兜底叶存量，P(yes)≥门迁入新叶（挪出兜底），不过留兜底；
     * 父为叶（分化）→ 该叶全部正文重判，过门进新叶，不过进新建兜底叶。
     * choice——新增节点=父层新选项，受影响存量对父层整题（全部选项+兜底）重选，
     * 选中新选项且达采纳线迁入，否则留/落兜底。no 判据留空时自动生成（仅 noul）。
     */
    public GrowReport growNode(String parent, String name, String yes, String no) throws IOException {
        bustQuestCache();   // 结构变了，查询缓存全部作废
        if (!choiceMode && (yes == null || yes.trim().isEmpty())) {
            throw new IllegalArgumentException("yes 判据不可为空");
        }
        String yesT = yes != null ? yes.trim() : "";
        String noT = choiceMode
                ? (no != null && !no.trim().isEmpty() ? no.trim() : null)          // choice：no 无意义不造
                : (no != null && !no.trim().isEmpty() ? no.trim() : NoulSpec.autoNo(yesT));
        SemNode p = find(root, parent);
        if (p == null) throw new IllegalArgumentException("父节点不存在: " + parent);
        String effRule = ruleAdd;   // 父所在子树若有规则覆盖（如中文子树），重判沿用它
        List<SemNode> pathNodes = new ArrayList<SemNode>();
        if (findNodePath(root, parent, pathNodes)) {
            for (int i = pathNodes.size() - 1; i >= 0; i--) {
                if (pathNodes.get(i).ruleAdd != null) {
                    effRule = pathNodes.get(i).ruleAdd;
                    break;
                }
            }
        }

        boolean differentiate = p.isLeaf();
        List<Doc> affected = new ArrayList<Doc>(differentiate ? p.docs
                : (p.otherLeaf != null ? p.otherLeaf.docs : new ArrayList<Doc>()));
        SemNode target = addNode(parent, name, yesT.isEmpty() ? null : yesT, noT);
        GrowReport rep = new GrowReport(parent, target.name);

        if (choiceMode) {
            List<Step> discarded = new ArrayList<Step>();   // 回填轨迹不进任何报告，账单照记
            for (Doc d : affected) {
                ChoicePick pick = askChoice(p, d.content, effRule, Task.ADD, discarded);
                boolean pass = pick != null && pick.node == target;
                if (differentiate) {
                    p.docs.remove(d);
                    d.leaves.remove(p.name);
                } else {
                    SemNode other = p.otherLeaf;
                    other.docs.remove(d);
                    d.leaves.remove(other.name);
                }
                if (pass) {
                    target.docs.add(d);
                    d.leaves.add(target.name);
                } else if (differentiate) {
                    SemNode other = otherLeaf(p);
                    other.docs.add(d);
                    d.leaves.add(other.name);
                }   // 非分化且未过：留在兜底叶（指针未动）
                rep.moves.add(new Move(d.id, pick != null ? pick.prob : 0.0d, pass));
            }
            return rep;
        }

        if (differentiate) {
            SemNode other = otherLeaf(p);
            for (Doc d : affected) {
                double prob = judgeOne(d.content, target.name, yesT, noT, effRule);
                boolean pass = prob >= gate;
                p.docs.remove(d);
                d.leaves.remove(p.name);
                if (pass) {
                    target.docs.add(d);
                    d.leaves.add(target.name);
                } else {
                    other.docs.add(d);
                    d.leaves.add(other.name);
                }
                rep.moves.add(new Move(d.id, prob, pass));
            }
        } else {
            SemNode other = p.otherLeaf;   // 兜底存量存在则必已建
            for (Doc d : affected) {
                double prob = judgeOne(d.content, target.name, yesT, noT, effRule);
                if (prob >= gate) {
                    other.docs.remove(d);
                    d.leaves.remove(other.name);
                    target.docs.add(d);
                    d.leaves.add(target.name);
                    rep.moves.add(new Move(d.id, prob, true));
                } else {
                    rep.moves.add(new Move(d.id, prob, false));
                }
            }
        }
        return rep;
    }

    /** 单题重判（增长回填用），计入账单。规则取父子树的有效规则（有覆盖用覆盖，无则全局）。 */
    private double judgeOne(String content, String name, String yes, String no, String rule) throws IOException {
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        qs.put(name, new NoulSpec(prompts.noulInstructions(Task.ADD, name), prompts.criterion(Task.ADD, yes, name), no));
        NoulReply r = backend.noul(content, rule, qs);
        requests++;
        totalIn += r.inputTokens;
        totalOut += r.outputTokens;
        totalLatency += r.latencyMs;
        totalCost += r.cost;
        Double p = r.probs.get(name);
        return p == null ? 0.0d : p.doubleValue();
    }

    // ── 修改节点：改名 + 判据编辑（零模型调用） ─────────────────────

    /** 修改报告：旧名 / 新名 / 生效判据 / 父节点名（可视化刷新用）。 */
    public static final class EditReport {
        public final String oldName;
        public String newName;
        public String yes;
        public String no;
        public String parent;

        EditReport(String oldName) {
            this.oldName = oldName;
            this.newName = oldName;
        }
    }

    /**
     * 修改节点：改名与 yes/no 判据编辑，零模型调用。
     * 叶改名连带更新其上落位 Doc 的叶指针；内部节点改名连带重命名其已创建的兜底叶（含指针）。
     * 改判据不重判已落位数据（判据语义变了，落位由后续 add/quest 自然收正或 --reseed 重跑）。
     * yes 缺省视为不改；no 留空且 yes 有新值时按语言自动生成否定侧。
     */
    public EditReport editNode(String name, String newName, String yes, String no) {
        bustQuestCache();   // 判据/名字变了，缓存结果与重跑慢路不再一致
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("节点名不可为空");
        if (root.name.equals(name.trim())) throw new IllegalArgumentException("根节点不可修改");
        SemNode n = find(root, name.trim());
        if (n == null) throw new IllegalArgumentException("节点不存在: " + name);
        if (n.other) throw new IllegalArgumentException("兜底叶不参与判定请求，无判据可改；名称跟随父节点，随父节点连带管理");
        EditReport rep = new EditReport(n.name);

        List<SemNode> pathNodes = new ArrayList<SemNode>();
        if (findNodePath(root, n.name, pathNodes) && pathNodes.size() >= 2) {
            rep.parent = pathNodes.get(pathNodes.size() - 2).name;
        }

        if (newName != null && !newName.trim().isEmpty() && !newName.trim().equals(n.name)) {
            String nn = newName.trim();
            if (find(root, nn) != null) throw new IllegalArgumentException("节点名已存在: " + nn);
            String old = n.name;
            n.name = nn;
            rep.newName = nn;
            if (n.isLeaf()) {
                for (Doc d : n.docs) {
                    if (d.leaves.remove(old)) d.leaves.add(nn);
                }
            }
            if (n.otherLeaf != null && find(root, nn + OTHER_SUFFIX) == null) {
                String oldOther = old + OTHER_SUFFIX;
                n.otherLeaf.name = nn + OTHER_SUFFIX;
                for (Doc d : n.otherLeaf.docs) {
                    if (d.leaves.remove(oldOther)) d.leaves.add(n.otherLeaf.name);
                }
            }
        }
        if (yes != null && !yes.trim().isEmpty()) {
            n.yes = yes.trim();
            if (choiceMode) {
                n.no = (no == null || no.trim().isEmpty()) ? null : no.trim();   // choice：选项描述，no 不用
            } else {
                n.no = (no == null || no.trim().isEmpty()) ? NoulSpec.autoNo(n.yes) : no.trim();
            }
        } else if (no != null && !no.trim().isEmpty()) {
            n.no = no.trim();   // 只改 no
        }
        rep.yes = n.yes;
        rep.no = n.no;
        return rep;
    }

    // ── 删除：剪除子树 + 清理落位 ──────────────────────────────────

    /** 删除报告：被移除的节点名（父先于子，含各级兜底叶）与被清理的文档 id。 */
    public static final class RemoveReport {
        public final String parent;
        public final List<String> removed = new ArrayList<String>();
        public final List<String> docsRemoved = new ArrayList<String>();

        RemoveReport(String parent) {
            this.parent = parent;
        }
    }

    /**
     * 删除节点及其整个子树（含各级兜底叶）：子树各叶上的落位指针一并清理；
     * 文档因此不再挂任何叶的（孤儿正文）从索引移除并记入 docsRemoved。
     * 根节点与兜底叶不可直接删除（兜底叶随父节点连带移除）。
     */
    public RemoveReport removeNode(String name) {
        bustQuestCache();   // 结构变了，查询缓存全部作废
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("节点名不可为空");
        String trimmed = name.trim();
        if (trimmed.equals(root.name)) throw new IllegalArgumentException("根节点不可删除");
        if (trimmed.endsWith(OTHER_SUFFIX)) {
            throw new IllegalArgumentException("兜底叶不可单独删除——删除其父节点即可连带移除");
        }
        List<SemNode> path = new ArrayList<SemNode>();
        if (!findNodePath(root, trimmed, path)) throw new IllegalArgumentException("节点不存在: " + trimmed);
        SemNode target = path.get(path.size() - 1);
        SemNode parent = path.get(path.size() - 2);
        if (parent.children.size() == 1 && parent.otherLeaf != null && !parent.otherLeaf.docs.isEmpty()) {
            throw new IllegalArgumentException("不可删除: " + target.name + " 是 " + parent.name
                    + " 下最后一个实子节点，且其兜底叶仍有 " + parent.otherLeaf.docs.size()
                    + " 条落位——先处理兜底数据（否则快照回放会丢落位）");
        }

        RemoveReport rep = new RemoveReport(parent.name);
        collectNames(target, rep.removed);

        for (Doc d : new ArrayList<Doc>(docIndex.values())) {
            if (d.leaves.removeAll(rep.removed) && d.leaves.isEmpty()) {
                docIndex.remove(d.id);
                rep.docsRemoved.add(d.id);
            }
        }
        parent.children.remove(target);
        if (parent.children.isEmpty() && parent.otherLeaf != null && parent.otherLeaf.docs.isEmpty()) {
            parent.otherLeaf = null;   // 层回到叶形态：空兜底叶连带撤掉（有落位时上方护栏已拒删）
        }
        return rep;
    }

    /** 收集 node 及其全部子孙的名字（含各内部节点的兜底叶），父先于子。 */
    private static void collectNames(SemNode n, List<String> out) {
        out.add(n.name);
        for (SemNode c : n.children) collectNames(c, out);
        if (n.otherLeaf != null) collectNames(n.otherLeaf, out);
    }

    /** SemNode 版路径查找：找到返回 true，path 即根到目标。 */
    private static boolean findNodePath(SemNode n, String name, List<SemNode> path) {
        path.add(n);
        if (n.name.equals(name)) return true;
        for (SemNode c : n.children) {
            if (findNodePath(c, name, path)) return true;
        }
        if (n.otherLeaf != null && findNodePath(n.otherLeaf, name, path)) return true;
        path.remove(path.size() - 1);
        return false;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private NoulReply ask(String nodeName, String content, String rule,
                          LinkedHashMap<String, NoulSpec> qs, List<Step> steps) throws IOException {
        NoulReply r = backend.noul(content, rule, qs);
        steps.add(new Step(nodeName, r));
        requests++;
        totalIn += r.inputTokens;
        totalOut += r.outputTokens;
        totalLatency += r.latencyMs;
        totalCost += r.cost;
        return r;
    }

    /** 内部节点的兜底叶（懒创建）。不参与判定请求，故无判据（yes/no 置 null，可视化不展示）。 */
    private SemNode otherLeaf(SemNode node) {
        if (node.otherLeaf == null) {
            node.otherLeaf = new SemNode(node.name + OTHER_SUFFIX, null, null, true,
                    new ArrayList<SemNode>());
        }
        return node.otherLeaf;
    }

    /** 节点名是否存在（快照回放判重用）。 */
    public boolean hasNode(String name) {
        return name != null && find(root, name.trim()) != null;
    }

    private static SemNode find(SemNode n, String name) {
        if (n.name.equals(name)) return n;
        for (SemNode c : n.children) {
            SemNode r = find(c, name);
            if (r != null) return r;
        }
        return n.otherLeaf != null ? find(n.otherLeaf, name) : null;
    }

    /** 按叶名定位叶：真实叶须已存在；X_OTHER 在 X 为内部节点时懒创建。 */
    private SemNode resolveLeaf(String name) {
        if (name.endsWith(OTHER_SUFFIX)) {
            String parent = name.substring(0, name.length() - OTHER_SUFFIX.length());
            SemNode p = find(root, parent);
            return p != null && !p.isLeaf() ? otherLeaf(p) : null;
        }
        SemNode n = find(root, name);
        return n != null && n.isLeaf() ? n : null;
    }

    /** 递归为所有内部节点预创建兜底叶（持久化载入后 find/findPath 可达）。 */
    private void ensureOthers(SemNode n) {
        if (n.isLeaf()) return;
        otherLeaf(n);
        for (SemNode c : n.children) ensureOthers(c);
    }

    /** 根到 name 的路径名累积；找到返回 true（acc 即路径），未找到回溯并返回 false。 */
    private static boolean findPath(SemNode n, String name, List<String> acc) {
        acc.add(n.name);
        if (n.name.equals(name)) return true;
        for (SemNode c : n.children) {
            if (findPath(c, name, acc)) return true;
        }
        if (n.otherLeaf != null && findPath(n.otherLeaf, name, acc)) return true;
        acc.remove(acc.size() - 1);
        return false;
    }

    /** 节点 → 可 JSON 化 Map：结构 + 判据 + docCount（不含正文）。 */
    private Map<String, Object> nodeMap(SemNode n) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", n.name);
        m.put("leaf", n.isLeaf());
        m.put("other", n.other);
        if (n.yes != null) m.put("yes", n.yes);
        if (n.no != null) m.put("no", n.no);
        m.put("docCount", n.docs.size());
        List<Object> children = new ArrayList<Object>();
        for (SemNode c : n.children) children.add(nodeMap(c));
        if (n.otherLeaf != null) children.add(nodeMap(n.otherLeaf));
        if (!children.isEmpty()) m.put("children", children);
        return m;
    }

    /** 供 Demo 打印的树形概览。 */
    public String treeView() {
        StringBuilder sb = new StringBuilder();
        view(root, 0, sb);
        return sb.toString();
    }

    private void view(SemNode n, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(n.isLeaf() ? "· " : "├ ").append(n.name);
        if (n.other) sb.append(" (兜底)");
        if (n.isLeaf() && !n.docs.isEmpty()) sb.append("  [" + n.docs.size() + " docs]");
        sb.append('\n');
        for (SemNode c : n.children) view(c, depth + 1, sb);
        if (n.otherLeaf != null) view(n.otherLeaf, depth + 1, sb);
    }
}
