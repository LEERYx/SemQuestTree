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
 * 节点只存单份属性解释（add 字段，语义域的抽象描述句），判定时由两侧模板各投影一次：
 * add 判据（内容与该含义是否有关，落位门控）与 search 判据（查询与该抽象哪怕一丝关联，检索门控）；
 * no 判据由 no 模板现场推导（"并非…"），不存储。双门控分离：addGate 从严、questGate 从松。
 * 兜底叶不设判据（choice 以 prompt.choice.other 提示词文件文案作其选项描述）。
 *
 * <pre>
 *   SemQuestTree tree = SemQuestTree.load(Path.of("driver-intent-schema.json"), new JevBackend(cfg, 30000), 0.5, 0.3);
 *   AddReport  a = tree.add("T-0317", "This order only pays 1,200… bump it to 1,500, or I'll only load 8 tons… don't blame me when the delivery runs late.");
 *   QuestReport q = tree.quest("Find drivers who ask for a higher rate and also want to load less.");
 * </pre>
 */
public final class SemQuestTree {

    /** 兜底叶后缀：节点 PRICE 的兜底叶名为 PRICE_OTHER。 */
    public static final String OTHER_SUFFIX = "_OTHER";

    /** 判定任务侧别：取 add（落位/回填）还是 search（检索）那套提示词模板。 */
    public enum Task { ADD, SEARCH }

    // ── 判定提示词（系统侧组装文案，semquest.properties 热载）───────

    /**
     * 系统侧判定提示词：结构化判定方言里由服务组装的文案。判据属性解释与规则文本归树（节点数据），不在这里。
     * 占位符：{name}=分支名、{node}=层节点名、{add}=节点属性解释（包裹后判据文本）。全部字段必填（null/空白即抛
     * IllegalArgumentException）——正文一律来自 semquest.properties 的 prompt.* 键指向的提示词文件
     * （PromptLoader 装载），代码零内置文本、零默认回退。
     * 必列键：prompt.rule.{add,search}、prompt.noul.add.instructions、prompt.noul.add.criterion、
     * prompt.noul.search.criterion、prompt.noul.search.query、prompt.choice.{add.root,add.node,other}、
     * prompt.noul.no.{han,latin}；另 3 个可选分野键（prompt.noul.search.instructions、
     * prompt.choice.search.{root,node}）未配置即继承 add 侧同键（PromptLoader 解析）。
     * 判据本体是节点上的单份属性解释，判定前由两侧模板各投影一次：落位侧 addCriterion（add 判据：内容与该含义是否有关）、
     * 检索侧 searchCriterion（search 判据：查询与该抽象哪怕一丝关联）——add 归类、search 求关联，两侧语义分野由模板措辞保证；
     * no 判据由 prompt.noul.no.{han,latin} 模板对包裹后判据现场推导（"并非…"），节点不存储 no 侧文本。
     * questQuery
     * 包裹检索请求原文（{query}=查询原文）——两侧框架配套，给判据框架里的「该查询」提供指代源，
     * 使「所有悲伤的话」这类泛查询能过机制描述型判据——框架落在模型真正读的字段里。
     * 两对框架 noul 与 choice 检索共用（choice 的 content=questQuery 包裹、选项描述=criterion 检索框架）。
     * noul instructions 仅 jev / respan 方言下发；openrouter（非 respan）与百炼下发的是判据文本（含包裹），此模板不影响它们。
     */
    public static final class Prompts {

        /** noul 题目 instructions 模板（{name}=分支名）。 */
        public final String noulAddInstructions;
        public final String noulSearchInstructions;
        /** noul 判据包裹模板（{add}=属性解释、{name}=分支名）：落位侧 addCriterion（add 判据模板）、检索侧 searchCriterion（search 判据模板）。
         *  判据本体是单份属性解释，两侧模板各投影一次——add 侧正常判定姿态，search 侧卑微姿态（沾边即算）；no 判据由 no 模板对包裹后判据现场推导。 */
        public final String noulSearchCriterion;
        /** noul 落位判据包裹模板（{add}=属性解释、{name}=分支名）：把属性解释转成落位判定任务；choice 落位不套用。 */
        public final String noulAddCriterion;
        /** 检索请求包裹模板（{query}=查询原文；noul 与 choice 检索共用）：给 criterion 检索框架里的「该查询」提供指代源。 */
        public final String noulSearchQuery;
        /** choice 根层问句。 */
        public final String choiceAddRoot;
        /** choice 子层问句模板（{node}=层节点名）。 */
        public final String choiceAddNode;
        public final String choiceSearchRoot;
        public final String choiceSearchNode;
        /** choice 兜底选项描述（criteria 里 X_OTHER 的文案）。 */
        public final String choiceOtherOption;
        /** no 侧自动生成模板（{add}=包裹后判据文本；NoulSpec.autoNo 用）：noulNoHan=判据含汉字时、noulNoLatin=纯西文时。 */
        public final String noulNoHan;
        public final String noulNoLatin;

        public final String docFilterCriterion;

        /** 类目意图题模板（{query}=检索原文、{criterion}=节点属性解释；prompt.quest.catscope 文件内容；null=禁用）。 */
        public final String catScopeCriterion;

        /** 维度题模板（首行=instructions 含 {judge}=判据文本，后续 6 行=六档 criteria 选项描述；
         * growth.dim.question 文件内容，必配）。 */
        public final String dimTemplate;

        /**
         * 全字段必填（来自提示词文件内容，分野键继承已由 PromptLoader 完成）。
         * @param noulNoHan   no 侧模板（判据含汉字时；prompt.noul.no.han 文件内容）
         * @param noulNoLatin no 侧模板（纯西文判据时；prompt.noul.no.latin 文件内容）
         * @param docFilterCriterion doc 过滤判据模板（prompt.docfilter.criterion 文件内容；null=禁用 doc 过滤）
         * @param catScopeCriterion 类目意图题模板（prompt.quest.catscope 文件内容；null=禁用类目截断）
         * @param dimTemplate 维度题模板（growth.dim.question 文件内容；首行 instructions、后续 6 行档位描述，
         *                    score 题六档各一选项一次打分，响应期望分即维度分；不传父节点/递进约束，
         *                    child > parent 由调用方比较）
         */
        public Prompts(String noulAddInstructions, String noulSearchInstructions,
                       String noulSearchCriterion, String noulAddCriterion, String noulSearchQuery,
                       String choiceAddRoot, String choiceAddNode,
                       String choiceSearchRoot, String choiceSearchNode,
                       String choiceOtherOption, String noulNoHan, String noulNoLatin,
                       String docFilterCriterion, String dimTemplate, String catScopeCriterion) {
            this.noulAddInstructions = req(noulAddInstructions, "prompt.noul.add.instructions");
            this.noulSearchInstructions = req(noulSearchInstructions, "prompt.noul.search.instructions");
            this.noulSearchCriterion = req(noulSearchCriterion, "prompt.noul.search.criterion");
            this.noulAddCriterion = req(noulAddCriterion, "prompt.noul.add.criterion");
            this.noulSearchQuery = req(noulSearchQuery, "prompt.noul.search.query");
            this.choiceAddRoot = req(choiceAddRoot, "prompt.choice.add.root");
            this.choiceAddNode = req(choiceAddNode, "prompt.choice.add.node");
            this.choiceSearchRoot = req(choiceSearchRoot, "prompt.choice.search.root");
            this.choiceSearchNode = req(choiceSearchNode, "prompt.choice.search.node");
            this.choiceOtherOption = req(choiceOtherOption, "prompt.choice.other");
            this.noulNoHan = req(noulNoHan, "prompt.noul.no.han");
            this.noulNoLatin = req(noulNoLatin, "prompt.noul.no.latin");
            this.docFilterCriterion = docFilterCriterion == null || docFilterCriterion.trim().isEmpty()
                    ? null : docFilterCriterion.trim();
            this.catScopeCriterion = catScopeCriterion == null || catScopeCriterion.trim().isEmpty()
                    ? null : catScopeCriterion.trim();
            this.dimTemplate = req(dimTemplate, "growth.dim.question");
        }

        /** 类目意图题（{query}=检索原文、{criterion}=节点属性解释包裹进题面）；模板未配置时调用方不应发起题。 */
        public String catScopeCriterion(String query, String criterion) {
            return catScopeCriterion
                    .replace("{query}", query == null ? "" : query)
                    .replace("{criterion}", criterion == null ? "" : criterion);
        }

        /** 单字段严格校验：代码零内置文本，任一提示词缺失即拒绝构造。 */
        private static String req(String s, String key) {
            if (s == null || s.trim().isEmpty())
                throw new IllegalArgumentException("判定提示词缺失: " + key + "（须指向提示词文件）");
            return s.trim();
        }

        /** noul 题目 instructions：按任务侧别取模板，{name} 替换为分支名。 */
        public String noulInstructions(Task task, String name) {
            String tpl = task == Task.ADD ? noulAddInstructions : noulSearchInstructions;
            return tpl.replace("{name}", name);
        }

        /** noul 落位判据包裹：属性解释 → add 判据任务（{add}/{name} 可用；choice 不套用）。 */
        public String addCriterion(String add, String name) {
            return noulAddCriterion.replace("{add}", add == null ? "" : add).replace("{name}", name);
        }

        /** 存量重判判据包裹：addCriterion 模板中的「这条内容」替换为「」框起来的被判内容原文
         * （重判请求无 state，rule/content 均省略，内容随题走）；模板无该短语时内容前置。 */
        public String rejudgeCriterion(String add, String name, String content) {
            String base = addCriterion(add, name);
            String c = content == null ? "" : content.trim();
            String wrapped = "「" + c + "」";
            int at = base.indexOf("这条内容");
            return at < 0 ? wrapped + base : base.substring(0, at) + wrapped + base.substring(at + "这条内容".length());
        }

        /** 判据文本（choice 选项共用）：落位侧恒等属性解释直下；检索侧包 search 判据模板（{add}/{name} 替换）。 */
        public String criterion(Task task, String add, String name) {
            if (task == Task.ADD) return add == null ? "" : add;
            return noulSearchCriterion.replace("{add}", add == null ? "" : add).replace("{name}", name);
        }

        /** 检索请求文本：包裹模板 {query} 替换查询原文（仅检索侧，add 内容原文直下；noul 与 choice 共用）。 */
        public String questQuery(String query) {
            return noulSearchQuery.replace("{query}", query == null ? "" : query);
        }

        /** doc 过滤判据包裹（{yes}=doc 原文截断后）；docFilterCriterion 为 null 时调用方不应发起过滤。 */
        public String docFilterCriterion(String docText) {
            return docFilterCriterion.replace("{yes}", docText == null ? "" : docText);
        }

        /** choice 问句：根层用 root 问句，子层模板 {node} 替换层节点名。 */
        public String choiceQuestion(Task task, String node) {
            if ("ROOT".equals(node)) return task == Task.ADD ? choiceAddRoot : choiceSearchRoot;
            String tpl = task == Task.ADD ? choiceAddNode : choiceSearchNode;
            return tpl.replace("{node}", node);
        }

        /**
         * 维度 score 题组装：首行为题目指令（{judge} 替换判据文本），后续 6 行为六档选项描述（criteria 数组，
         * 索引即档位值）——一道 score 题六档各打分，响应期望分即维度分（方向一致：分数越高粒度越细）。
         */
        public NoulSpec dimSpec(String judge) {
            String j = judge == null ? "" : judge.trim();
            List<String> criteria = dimCriteria();
            return NoulSpec.score(dimTemplate.split("\n", 2)[0].replace("{judge}", j), criteria);
        }

        /** 维度题六档选项描述（模板非首行部分），须恰好 6 行（索引=档位值 0~5）。 */
        public List<String> dimCriteria() {
            List<String> out = new ArrayList<String>();
            String[] lines = dimTemplate.split("\n");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].trim().isEmpty()) out.add(lines[i].trim());
            }
            if (out.size() != 6)
                throw new IllegalArgumentException("growth.dim.question 须首行 instructions + 6 行档位描述，当前描述 " + out.size() + " 行");
            return out;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Prompts)) return false;
            Prompts p = (Prompts) o;
            return noulAddInstructions.equals(p.noulAddInstructions)
                    && noulSearchInstructions.equals(p.noulSearchInstructions)
                    && noulSearchCriterion.equals(p.noulSearchCriterion)
                    && noulAddCriterion.equals(p.noulAddCriterion)
                    && noulSearchQuery.equals(p.noulSearchQuery)
                    && choiceAddRoot.equals(p.choiceAddRoot)
                    && choiceAddNode.equals(p.choiceAddNode)
                    && choiceSearchRoot.equals(p.choiceSearchRoot)
                    && choiceSearchNode.equals(p.choiceSearchNode)
                    && choiceOtherOption.equals(p.choiceOtherOption)
                    && noulNoHan.equals(p.noulNoHan)
                    && noulNoLatin.equals(p.noulNoLatin)
                    && (docFilterCriterion == null ? p.docFilterCriterion == null
                        : docFilterCriterion.equals(p.docFilterCriterion))
                    && (catScopeCriterion == null ? p.catScopeCriterion == null
                        : catScopeCriterion.equals(p.catScopeCriterion))
                    && dimTemplate.equals(p.dimTemplate);
        }

        @Override
        public int hashCode() {
            int h = noulAddInstructions.hashCode();
            h = h * 31 + noulSearchInstructions.hashCode();
            h = h * 31 + noulSearchCriterion.hashCode();
            h = h * 31 + noulAddCriterion.hashCode();
            h = h * 31 + noulSearchQuery.hashCode();
            h = h * 31 + choiceAddRoot.hashCode();
            h = h * 31 + choiceAddNode.hashCode();
            h = h * 31 + choiceSearchRoot.hashCode();
            h = h * 31 + choiceSearchNode.hashCode();
            h = h * 31 + choiceOtherOption.hashCode();
            h = h * 31 + noulNoHan.hashCode();
            h = h * 31 + noulNoLatin.hashCode();
            return h * 31 + (docFilterCriterion == null ? 0 : docFilterCriterion.hashCode()) * 31
                    + (catScopeCriterion == null ? 0 : catScopeCriterion.hashCode());
        }
    }

    /** 当前判定提示词（全表共用一份；服务启动时从提示词文件装载注入，未注入不可判定——代码零内置文本）。 */
    private volatile Prompts prompts;

    /** 判定提示词取用：未注入即抛错（fail-fast，不静默回退任何内置文本）。 */
    private Prompts promptsOrThrow() {
        Prompts ps = prompts;
        if (ps == null)
            throw new IllegalStateException("判定提示词未注入：应经 setPrompts(PromptLoader.of(cfg)) 在服务启动时装载");
        return ps;
    }

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
        public String add;            // 属性解释：该节点语义域的抽象描述句（add/search 双模板投影的本体；
                                      // 判定时由模板包裹成 add 判据与 search 判据，no 判据由 no 模板现场推导——不存储）
        public double dim;            // 维度分 0~5（0=未评）：判据的语义粒度，越低越宽泛越高越细致；
                                      // 生长验收时由维度打分写入，硬约束：子节点维度 > 父节点维度
        public final boolean other;   // 是否兜底叶
        public final String ruleAdd;    // 子树规则覆盖（如中文子树四层全中文）；null 继承上层规则
        public final String ruleSearch;
        public final List<SemNode> children;
        public final List<Doc> docs = new ArrayList<Doc>();   // 仅叶使用
        private SemNode otherLeaf;                            // 仅内部节点懒创建

        SemNode(String name, String add, boolean other, List<SemNode> children) {
            this(name, add, other, children, null, null);
        }

        SemNode(String name, String add, boolean other, List<SemNode> children,
                String ruleAdd, String ruleSearch) {
            this.name = name;
            this.add = add;
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
        // doc 过滤摘要：total=去重待过滤数；kept=过滤后保留数（未执行=0）；calls=判定批次数；
        // confirmRequired=true 时 ranked 保持未过滤全量、结果不入缓存，等客户端确认后带 confirm 重发
        public boolean confirmRequired = false;
        public int docFilterTotal = 0;
        public int docFilterKept = 0;
        public int docFilterCalls = 0;
        // 类目意图命中：catScopeNode=命中节点名（null=未命中，正常下行）；catScopeDocs=该节点子树全量 doc 数。
        // 命中时 matched 即子树全部真实叶（含兜底叶），docFilter 跳过——内容全量直接返回
        public String catScopeNode = null;
        public int catScopeDocs = 0;

        QuestReport(String query) {
            this.query = query;
        }
    }

    /** 命中文档：score = noul 模式为命中叶数；choice 模式为路径各层胜出概率连乘。docP = doc 过滤判定 P(相关)（未过滤恒 1.0）。 */
    public static final class RankedDoc {
        public final String docId;
        public final double score;
        public final List<String> viaLeaves;
        public final double docP;

        RankedDoc(String docId, double score, LinkedHashSet<String> viaLeaves, double docP) {
            this.docId = docId;
            this.score = score;
            this.viaLeaves = new ArrayList<String>(viaLeaves);
            this.docP = docP;
        }
    }

    // ── 状态 ────────────────────────────────────────────────────────

    private final SemNode root;
    private final DecisionBackend backend;
    /** 落位门控（add 侧）：判据为正常判定姿态（内容与属性解释是否有关），门槛从严。 */
    private volatile double addGate;
    /** 检索门控（search 侧）：判据为卑微姿态（查询与属性解释哪怕一丝关联），门槛从松——宽进由 docFilter 细筛补齐。 */
    private volatile double questGate;
    /** choice 模式采纳线（SDK 官方语义：confidence ≥ 采纳线才认选中项，否则落兜底）。 */
    private volatile double choiceGate;
    /** 动作流归属表 id（viz 🔎 追踪按表过滤；Table 创建时回填，CLI/Demo 下为 null 不过滤）。 */
    private volatile String traceTag;
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

    /** doc 过滤组配置（quest 命中叶后的相关性出检；semquest.properties 热载应用，见 setDocFilter）。 */
    private volatile boolean docFilterOn = false;      // 另须 prompt.docfilter.criterion 模板就位（Prompts）才实际生效
    private volatile int docFilterBatch = 20;          // 单次打包判定条数
    private volatile double docFilterGate = 0.6;       // P(相关) 采纳线
    private volatile int docFilterMaxLen = 200;        // 判据里 doc 原文截断上限
    private volatile int docFilterMaxDocs = 100;       // 单次 quest 待过滤 总量上限（跨命中叶去重；超过需 confirm 确认）

    // ── 类目意图截断（quest cat-scope）：每层每个子节点双题同请求（关系+完全包含），yes 直接收编子树 ──
    /** 完全包含判定题 key 前缀（__cat.<子节点名>）；同请求多题区分，响应 probs 里按前缀取回。 */
    private static final String CAT_PREFIX = "__cat.";
    private volatile boolean catScopeOn = false;
    private volatile double questCatGate = 0.85d;      // 类目意图采纳线（宁缺勿滥：假阳性代价是返回全子树）
    private volatile int catScopeMaxDocs = 200;        // 类目命中子树 doc 数上限：超过需 confirm 确认后才全量返回

    private SemQuestTree(SemNode root, DecisionBackend backend, double addGate,
                         double questGate, String ruleAdd, String ruleSearch) {
        this(root, backend, addGate, questGate, ruleAdd, ruleSearch, false, 0.6d);
    }

    private SemQuestTree(SemNode root, DecisionBackend backend, double addGate,
                         double questGate, String ruleAdd, String ruleSearch,
                         boolean choiceMode, double choiceGate) {
        this.root = root;
        this.backend = backend;
        this.addGate = addGate;
        this.questGate = questGate;
        this.choiceGate = choiceGate;
        this.choiceMode = choiceMode;
        this.ruleAdd = ruleAdd;
        this.ruleSearch = ruleSearch;
    }

    // ── schema 装载 ─────────────────────────────────────────────────

    /**
     * 从 schema JSON 装载预制树。字段：mode("noul"|"choice") / addGate / questGate / choiceGate / ruleAdd / ruleSearch /
     * root{name, children:[{name, add, dim?, ruleAdd?, ruleSearch?, children:[…]}]}——children 缺省即叶；
     * 节点可选自带 ruleAdd/ruleSearch 覆盖本子树的规则文本（如中文子树四层全中文），缺省继承全局。
     * 兼容旧键：节点 add 回退读 yes；树级 addGate 回退读 noulGate / gate（questGate 缺省继承 addGate）。
     * 兜底叶由系统懒创建；noul 模式不参与判定请求，choice 模式作为显式选项参与。
     */
    public static SemQuestTree load(Path schemaFile, DecisionBackend backend, double addGateDefault,
                                    double questGateDefault)
            throws IOException {
        Map<String, Object> m = Json.parseObject(
                new String(Files.readAllBytes(schemaFile), Charset.forName("UTF-8")));
        return fromMap(m, backend, addGateDefault, questGateDefault, schemaFile.toString());
    }

    /** 从表文件内嵌的 schema Map 装载（多表持久化用）。 */
    public static SemQuestTree fromMap(Map<String, Object> m, DecisionBackend backend) throws IOException {
        return fromMap(m, backend, 0.7d, 0.3d, "(table)");
    }

    private static SemQuestTree fromMap(Map<String, Object> m, DecisionBackend backend,
                                        double addGateDefault, double questGateDefault,
                                        String source) throws IOException {
        // addGate 新键；noulGate / gate 为改名前旧表文件的兼容回退
        double addGate = m.get("addGate") instanceof Number ? ((Number) m.get("addGate")).doubleValue()
                : (m.get("noulGate") instanceof Number ? ((Number) m.get("noulGate")).doubleValue()
                : (m.get("gate") instanceof Number ? ((Number) m.get("gate")).doubleValue() : addGateDefault));
        // questGate 新键；缺省继承 addGate（旧表单门控语义：两侧同门槛）
        double questGate = m.get("questGate") instanceof Number ? ((Number) m.get("questGate")).doubleValue() : addGate;
        double choiceGate = m.get("choiceGate") instanceof Number ? ((Number) m.get("choiceGate")).doubleValue() : 0.6d;
        String ruleAdd = optText(m, "ruleAdd");      // null 合法：建表时由 server 显式传入规则文件内容，schema 可选自带覆盖
        String ruleSearch = optText(m, "ruleSearch");
        if (!(m.get("root") instanceof Map)) {
            throw new IOException("schema 缺 root 节点: " + source);
        }
        SemNode root = parseNode((Map<?, ?>) m.get("root"));
        boolean choice = "choice".equals(m.get("mode"));
        return new SemQuestTree(root, backend, addGate, questGate, ruleAdd, ruleSearch, choice, choiceGate);
    }

    /** 新建空表树：只有 ROOT 节点（建树用右键加节点；choice 模式加子节点=加选项）。 */
    public static SemQuestTree createEmpty(DecisionBackend backend, boolean choiceMode,
                                           double addGate, double questGate, double choiceGate,
                                           String ruleAdd, String ruleSearch) {
        SemNode root = new SemNode("ROOT", null, false, new ArrayList<SemNode>(),
                null, null);
        return new SemQuestTree(root, backend, addGate, questGate, ruleAdd, ruleSearch, choiceMode, choiceGate);
    }

    @SuppressWarnings("unchecked")
    private static SemNode parseNode(Map<?, ?> m) {
        String name = (String) m.get("name");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("schema 节点缺 name");
        // add = 属性解释（判据本体）；旧表文件兼容回退读 yes。no 侧不存储——判定时由 no 模板现场推导
        String add = m.get("add") instanceof String ? (String) m.get("add")
                : (m.get("yes") instanceof String ? (String) m.get("yes") : null);
        double dim = m.get("dim") instanceof Number ? ((Number) m.get("dim")).doubleValue() : 0.0d;
        String ruleAdd = optText(m, "ruleAdd");
        String ruleSearch = optText(m, "ruleSearch");
        List<SemNode> children = new ArrayList<SemNode>();
        if (m.get("children") instanceof List) {
            for (Object o : (List<Object>) m.get("children")) {
                if (o instanceof Map) children.add(parseNode((Map<?, ?>) o));
            }
        }
        SemNode node = new SemNode(name, add, false, children, ruleAdd, ruleSearch);
        node.dim = dim;
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

    /** 删除一条落位（零模型调用）：从全部落位叶上摘下并移出索引；返回原落位叶名；未知 id 抛 400。 */
    public List<String> removeDoc(String docId) {
        Doc d = docIndex.remove(docId);
        if (d == null) throw new IllegalArgumentException("未知文档: " + docId);
        for (String ln : d.leaves) {
            SemNode n = find(root, ln);
            if (n != null) n.docs.remove(d);
        }
        bustQuestCache();   // 落位变了，查询缓存全部作废
        return new ArrayList<String>(d.leaves);
    }

    private void descendAdd(SemNode node, String content, Doc doc, AddReport rep, String rule) throws IOException {
        if (node.isLeaf()) {
            node.docs.add(doc);
            doc.leaves.add(node.name);
            rep.leaves.add(node.name);
            return;
        }
        final Prompts ps = promptsOrThrow();
        final String effRule = node.ruleAdd != null ? node.ruleAdd : rule;   // 子树规则覆盖
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        for (SemNode c : node.children) {
            // add 判据 = addCriterion 模板包裹属性解释；no 判据由 no 模板对裸属性解释现场推导（不存储）
            qs.put(c.name, new NoulSpec(ps.noulInstructions(Task.ADD, c.name), ps.addCriterion(c.add, c.name),
                    c.add, ps.noulNoHan, ps.noulNoLatin));
        }
        // 兜底叶不进请求
        NoulReply r = ask("add", node.name, content, effRule, qs, rep.steps);

        List<SemNode> passing = new ArrayList<SemNode>();
        for (SemNode c : node.children) {
            Double p = r.probs.get(c.name);
            if (p != null && p >= addGate) passing.add(c);
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
        final Prompts ps = promptsOrThrow();
        LinkedHashMap<String, String> criteria = new LinkedHashMap<String, String>();
        for (SemNode c : node.children) {
            // 判据按任务侧别包裹（与 noul 共用 criterion 模板）：检索侧包 search 判据模板给「该查询」落点，落位侧恒等属性解释
            String add = c.add != null && !c.add.trim().isEmpty() ? c.add : c.name;
            criteria.put(c.name, ps.criterion(task, add, c.name));
        }
        SemNode other = otherLeaf(node);
        criteria.put(other.name, ps.choiceOtherOption);

        String question = ps.choiceQuestion(task, node.name);
        long t0 = System.currentTimeMillis();
        NoulReply r;
        try {
            r = backend.choice(content, rule, question, criteria);
        } catch (IOException e) {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("task", task == Task.ADD ? "add" : "search");
            d.put("node", node.name);
            d.put("question", question);
            d.put("criteria", criteria);
            d.put("error", String.valueOf(e.getMessage()));
            traceLlm("llm.decision", "choice 判定失败 @ " + node.name, d, null, t0);
            throw e;
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("task", task == Task.ADD ? "add" : "search");
        d.put("node", node.name);
        d.put("question", question);
        d.put("criteria", criteria);
        d.put("probs", r.probs);
        traceLlm("llm.decision", "choice 判定 @ " + node.name + "（" + criteria.size() + " 选项）", d, r, t0);
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
        return quest(query, false);
    }

    /**
     * 同上，confirm 仅在 doc 过滤开启且待过滤 doc 总量（跨命中叶去重）超过 docFilterMaxDocs 时有意义：
     * 超量且 confirm=false → 返回 confirmRequired=true 的待确认响应（ranked 保持未过滤全量，不入缓存），
     * 由客户端向用户确认花销后带 confirm=true 重发才执行过滤；总量未超时 confirm 被忽略。
     */
    public QuestReport quest(String query, boolean confirm) throws IOException {
        if (questCacheCap > 0) {
            QuestReport cached = questCache.get(query);
            if (cached != null) {
                questCacheHits.put(query, questCacheHits.get(query) + 1);
                cached.fast = true;   // 缓存直出标记（粘性：条目此后一直走缓存）
                return cached;
            }
        }
        QuestReport rep = new QuestReport(query);
        final Prompts ps = promptsOrThrow();
        LinkedHashSet<SemNode> matched = new LinkedHashSet<SemNode>();
        if (choiceMode) {
            // choice 检索同样先包查询角色框架（与 noul 共用 questQuery 模板）：缓存键/报告/节点直取均保持原始查询
            descendQuestChoice(root, ps.questQuery(query), matched, rep, ruleSearch, 1.0d);
        } else {
            // 检索请求先包角色框架再下发：给判据检索框架里的「该查询」提供指代 源（缓存键/报告/节点直取均保持原始查询）
            descendQuest(root, ps.questQuery(query), matched, rep, rep.steps, ruleSearch);
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

        // doc 级出检过滤：补齐判定链最后一环（叶命中 → 该叶哪条内容真正与查询相关，如「车」叶下只放行火车）。
        // 跨命中叶去重后分批打包判定（每批一次调用），P(相关) ≥ docFilterGate  才保留；
        // state 只带 content 不带 rule（rule=null，state() 省略键）——相关性框架全在判据模板（「doc」与 state 内容强相关）。
        // 类目意图命中时跳过：子树全量即答案（模型已确认查询强包含节点判据全部内容），无需再过滤。
        LinkedHashMap<String, Double> docProbs = null;   // docId -> P(相关)；null = 未执行过滤（全保留）
        if (docFilterOn && ps.docFilterCriterion != null && !via.isEmpty() && rep.catScopeNode == null) {
            List<Doc> all = new ArrayList<Doc>(via.keySet());
            rep.docFilterTotal = all.size();
            if (all.size() > docFilterMaxDocs && !confirm) {
                rep.confirmRequired = true;   // 花销过闸：等用户确认后客户端带 confirm=true 重发
            } else {
                docProbs = new LinkedHashMap<String, Double>();
                for (int i = 0; i < all.size(); i += docFilterBatch) {
                    List<Doc> batch = all.subList(i, Math.min(i + docFilterBatch, all.size()));
                    LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
                    for (Doc d : batch) {
                        qs.put(d.id, new NoulSpec(d.id, ps.docFilterCriterion(truncateForFilter(d.content)),
                                truncateForFilter(d.content), ps.noulNoHan, ps.noulNoLatin));
                    }
                    long t0 = System.currentTimeMillis();
                    NoulReply r = backend.docFilter(query, null, qs);   // rule=null：state 不带 rule，只带 content
                    Map<String, Object> d = new LinkedHashMap<String, Object>();
                    d.put("query", clip(query));
                    d.put("docs", Integer.valueOf(qs.size()));
                    d.put("probs", r.probs);
                    traceLlm("llm.docfilter", "doc 过滤判定 · " + qs.size() + " 条", d, r, t0);
                    rep.steps.add(new Step("DOC_FILTER", r));
                    synchronized (this) {   // 过滤判定与下行判定共用树账单（并发安全同 ask）
                        requests++;
                        totalIn += r.inputTokens;
                        totalOut += r.outputTokens;
                        totalLatency += r.latencyMs;
                        totalCost += r.cost;
                    }
                    rep.docFilterCalls++;
                    docProbs.putAll(r.probs);
                }
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
            Double p = docProbs == null ? null : docProbs.get(e.getKey().id);
            if (p != null && p < docFilterGate) continue;   // 过滤执行且明确不相关 → 不进结果；上游缺答（null）保留——宁可多给
            double s = choiceMode ? rep.pathScore : e.getValue().size();
            rep.ranked.add(new RankedDoc(e.getKey().id, s, e.getValue(), p == null ? 1.0d : p.doubleValue()));
            if (docProbs != null) rep.docFilterKept++;
        }
        // 类目命中护栏：子树 doc 数超预算时不自动全量返回——confirmRequired 待确认（ranked 保持全量、不入缓存），
        // 客户端确认后带 confirm=true 重发才放行；防误 yes 时一次吐出超大响应。
        if (rep.catScopeNode != null && rep.catScopeDocs > catScopeMaxDocs && !confirm) {
            rep.confirmRequired = true;
        }
        if (!rep.confirmRequired && questCacheCap > 0) cachePut(query, rep);
        return rep;
    }

    /** 子树全部真实叶（含兜底叶——其落位内容也是该类目的一部分）入 matched；返回收集叶数。 */
    private static int collectLeaves(SemNode node, LinkedHashSet<SemNode> matched) {
        if (node.isLeaf()) {
            matched.add(node);
            return 1;
        }
        int n = 0;
        for (SemNode c : node.children) n += collectLeaves(c, matched);
        if (node.otherLeaf != null) {
            matched.add(node.otherLeaf);
            n++;
        }
        return n;
    }

    /** 类目意图截断组配置热载应用；任一值变化即清查询缓存（截断口径变了旧报告不可信）并返回 true。
     *  实际生效还须 Prompts.catScopeCriterion 模板就位（未配置即禁用）。 */
    public boolean setQuestCatScope(boolean on, double gate, int maxDocs) {
        boolean changed = catScopeOn != on || questCatGate != gate || catScopeMaxDocs != maxDocs;
        catScopeOn = on;
        questCatGate = Math.min(1.0d, Math.max(0.0d, gate));
        catScopeMaxDocs = Math.max(1, maxDocs);
        if (changed) bustQuestCache();
        return changed;
    }

    /** doc 原文截断：超过 docFilterMaxLen 取前缀加省略号（判据只求判定相关性， 防长文撑爆单批）。 */
    private String truncateForFilter(String content) {
        if (content == null) return "";
        String c = content.trim();
        return c.length() <= docFilterMaxLen ? c : c.substring(0, docFilterMaxLen) + "…";
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
                              QuestReport rep, List<Step> steps, String rule) throws IOException {
        if (node.isLeaf()) {
            matched.add(node);
            return;
        }
        final Prompts ps = promptsOrThrow();
        final String effRule = node.ruleSearch != null ? node.ruleSearch : rule;   // 子树规则覆盖
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        // 每层每个子节点双题同请求：有关系判定（原下探判定）+ 完全包含判定（__cat.<名>）——
        // 「查询所寻找的内容是否强包含该子节点判据囊括的全部内容」。第一步（ROOT 层）就能触发：
        // 任一子节点 P(包含) ≥ questCatGate → 直接收编该子节点子树全量返回（关系低也拦不住），
        // 省掉下探一层的往返时延与后续判定/docFilter；P(包含) 低但关系过门 → 正常下探，行为与现状一致。
        boolean catProbe = catScopeOn && ps.catScopeCriterion != null;
        for (SemNode c : node.children) {   // 兜底叶不参与检索
            // search 判据 = searchCriterion 模板包裹属性解释（卑微姿态：沾边即 算）；no 判据对裸属性解释现场推导
            qs.put(c.name, new NoulSpec(ps.noulInstructions(Task.SEARCH, c.name), ps.criterion(Task.SEARCH, c.add, c.name),
                    c.add, ps.noulNoHan, ps.noulNoLatin));
            if (catProbe) {
                qs.put(CAT_PREFIX + c.name, new NoulSpec(c.name, ps.catScopeCriterion(query, c.add),
                        c.add, ps.noulNoHan, ps.noulNoLatin));
            }
        }
        NoulReply r = ask("search", node.name, query, effRule, qs, steps);
        // 完全包含优先：命中子节点直接收编（可多个，多标签语义），不再下探任何分支
        if (catProbe) {
            List<SemNode> catHits = new ArrayList<SemNode>();
            for (SemNode c : node.children) {
                Double cp = r.probs.get(CAT_PREFIX + c.name);
                if (cp != null && cp >= questCatGate) catHits.add(c);
            }
            if (!catHits.isEmpty()) {
                int n = 0;
                StringBuilder names = new StringBuilder();
                for (SemNode c : catHits) {
                    n += collectLeaves(c, matched);
                    if (names.length() > 0) names.append("、");
                    names.append(c.name);
                }
                rep.catScopeNode = names.toString();
                rep.catScopeDocs = n;
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("node", names.toString());
                d.put("query", clip(query));
                d.put("gate", Double.valueOf(questCatGate));
                d.put("docs", Integer.valueOf(n));
                Trace.record(traceTag, "act.catscope", "类目意图命中 @ " + names
                        + " · 全量返回 " + n + " 条（不下探不过滤）", d, null, null, 0, 0, 0);
                return;
            }
        }
        boolean any = false;
        for (SemNode c : node.children) {
            Double p = r.probs.get(c.name);
            if (p != null && p >= questGate) {
                any = true;
                descendQuest(c, query, matched, rep, steps, effRule);
            }
        }
        if (!any && node.otherLeaf != null) {
            matched.add(node.otherLeaf);   // 与落位镜像：本层无真实判据过门 →  命中该层兜底叶（add 同条件落位）
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

    /** 任何表变更（add / 增长 / 修改 / 删除 / 双门控调整 / 延伸落位）后缓存结果不再可信，整池清空。 */
    public void bustQuestCache() {
        if (!questCache.isEmpty()) {
            questCache.clear();
            questCacheHits.clear();
        }
    }

    /** doc 过滤组配置热载应用；任一值变化即清查询缓存（过滤口径变了旧报告不可信）并返回 true。实际过滤还须 Prompts.docFilterCriterion 就位。 */
    public boolean setDocFilter(boolean on, int batch, double gate, int maxLen, int maxDocs) {
        boolean changed = docFilterOn != on || docFilterBatch != batch || docFilterGate != gate
                || docFilterMaxLen != maxLen || docFilterMaxDocs != maxDocs;
        docFilterOn = on;
        docFilterBatch = Math.max(1, batch);
        docFilterGate = Math.min(1.0d, Math.max(0.0d, gate));
        docFilterMaxLen = Math.max(1, maxLen);
        docFilterMaxDocs = Math.max(1, maxDocs);
        if (changed) bustQuestCache();
        return changed;
    }

    /** doc 过滤采纳线（viz 轨迹详情标 kept 用；过滤执行时判定口径与 quest 排序一致）。 */
    public double docFilterGate() {
        return docFilterGate;
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

    /** 运行时调整落位门控（0 < addGate ≤ 1），只影响其后的 add / grow 判定，不重排已落位数据。 */
    public void setAddGate(double g) {
        if (!(g > 0 && g <= 1)) throw new IllegalArgumentException("落位门控须在 (0, 1] 区间: " + g);
        this.addGate = g;
        bustQuestCache();   // 门变了，缓存结果与重跑慢路不再一致
    }

    /** 当前落位门控值（热载线程读取比对用）。 */
    public double addGate() {
        return addGate;
    }

    /** 运行时调整检索门控（0 < questGate ≤ 1），只影响其后的 quest 判定。 */
    public void setQuestGate(double g) {
        if (!(g > 0 && g <= 1)) throw new IllegalArgumentException("检索门控须在 (0, 1] 区间: " + g);
        this.questGate = g;
        bustQuestCache();
    }

    /** 当前检索门控值。 */
    public double questGate() {
        return questGate;
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

    /** 全局元信息：后端 / 模式 / 双门控 / 采纳线 / 落位总数。 */
    public Map<String, Object> metaMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backend", backend != null ? backend.name() : "(未接)");
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("addGate", addGate);
        m.put("questGate", questGate);
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
        if (n.add != null) m.put("add", n.add);
        if (n.dim > 0) m.put("dim", round3(n.dim));   // 展示统一三位小数
        m.put("docCount", n.docs.size());
        return m;
    }

    /** 层结构速览（生长编排用）：指定节点的真实子分支清单，每项 {name, add 属性解释（空回名字）}；节点不存在返回 null。 */
    public List<String[]> branchView(String nodeName) {
        SemNode n = find(root, nodeName);
        if (n == null) return null;
        List<String[]> out = new ArrayList<String[]>();
        for (SemNode c : n.children) {
            out.add(new String[]{c.name, c.add == null || c.add.trim().isEmpty() ? c.name : c.add});
        }
        return out;
    }

    /** 全树结构 → 可 JSON 化的 Map（含每叶 docCount，不含正文——正文点击才查）。 */
    public Map<String, Object> treeMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("backend", backend != null ? backend.name() : "(未接)");
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("addGate", addGate);
        m.put("questGate", questGate);
        m.put("choiceGate", choiceGate);
        m.put("docCount", docIndex.size());
        m.put("root", nodeMap(root));
        return m;
    }

    /** 表文件内嵌 schema（持久化用）：mode / 双门控 / 规则 / 干净节点结构（不含 docCount）。 */
    public Map<String, Object> schemaMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", choiceMode ? "choice" : "noul");
        m.put("addGate", addGate);
        m.put("questGate", questGate);
        m.put("choiceGate", choiceGate);
        m.put("ruleAdd", ruleAdd);
        m.put("ruleSearch", ruleSearch);
        m.put("root", schemaNode(root));
        return m;
    }

    private Map<String, Object> schemaNode(SemNode n) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", n.name);
        if (n.add != null) m.put("add", n.add);
        if (n.dim > 0) m.put("dim", round3(n.dim));   // 展示统一三位小数
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
        if (n.add != null) m.put("add", n.add);
        if (n.dim > 0) m.put("dim", round3(n.dim));   // 展示统一三位小数
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
     * 文档按x叶 DFS 顺序去重（一 Doc 多叶只出一次）；兜底叶内容包含在内，viaLeaves 露出 X_OTHER 后缀可辨。
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

    // ── doc 直取定位：按 id / 原文查落位（零模型调用） ─────────────────

    /** 一条命中 doc 的落位：id、原文、落位叶清单（一 Doc 可多叶）。 */
    public static final class LocatedDoc {
        public final String id;
        public final String content;
        public final List<String> leaves;

        LocatedDoc(Doc d) {
            this.id = d.id;
            this.content = d.content;
            this.leaves = new ArrayList<String>(d.leaves);
        }
    }

    /** doc 直取定位报告：mode=id/content（命中方式），docs=命中清单（同原文可多条）。 */
    public static final class LocateReport {
        public final String mode;
        public final List<LocatedDoc> docs = new ArrayList<LocatedDoc>();

        LocateReport(String mode) {
            this.mode = mode;
        }
    }

    /**
     * doc 直取定位（零模型调用）：key 先按 doc id 精确匹配，未命中再按原文精确匹配（两侧 trim）。
     * 同原文多 doc 全部返回；无命中抛 IllegalArgumentException——调用方回退节点直取 / 自然语言检索。
     */
    public LocateReport locateDocs(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) throw new IllegalArgumentException("空定位键");
        Doc byId = docIndex.get(k);
        if (byId != null) {
            LocateReport r = new LocateReport("id");
            r.docs.add(new LocatedDoc(byId));
            return r;
        }
        LocateReport r = new LocateReport("content");
        for (Doc d : docIndex.values()) {
            if (d.content != null && d.content.trim().equals(k)) r.docs.add(new LocatedDoc(d));
        }
        if (r.docs.isEmpty()) throw new IllegalArgumentException("无 doc 命中");
        return r;
    }

    // ── 增长：补层 + 存量回填 ───────────────────────────────────────

    /** 增长报告：新判据下受影响存量的逐条重判结果。dimProb = 维度 score 题（首条重判请求顺带；
     * choice 链/父判据空为 null——维度验收降级放行）。 */
    public static final class GrowReport {
        public final String parent;
        public final String node;
        public final List<Move> moves = new ArrayList<Move>();
        public Double dimProb;

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
    public SemNode addNode(String parent, String name, String add) {
        SemNode p = find(root, parent);
        if (p == null) throw new IllegalArgumentException("父节点不存在: " + parent);
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("节点名不可为空");
        String trimmed = name.trim();
        if (find(root, trimmed) != null) throw new IllegalArgumentException("节点名已存在: " + trimmed);
        SemNode n = new SemNode(trimmed, add, false, new ArrayList<SemNode>());
        p.children.add(n);
        otherLeaf(p);   // 首个实子节点落地即生成兜底叶：choice 每层请求恒含兜底选项，UI 同步可见
        return n;
    }

    /**
     * 补层增长 + 挂靠回填：新增判断节点后，把受影响存量逐条重判（每条 1 次请求）：
     * noul——父为内部节点 → 重判其兜底叶存量，P(yes)≥落位门控迁入新叶（挪出兜底），不过留兜底；
     * 父为叶（分化）→ 该叶全部正文重判，过门进新叶，不过进新建兜底叶。
     * choice——新增节点=父层新选项，受影响存量对父层整题（全部选项+兜底）重选，
     * 选中新选项且达采纳线迁入，否则留/落兜底。add 判据为属性解释；no 判据由 no 模板现场推导（仅 noul）。
     */
    public GrowReport growNode(String parent, String name, String add) throws IOException {
        bustQuestCache();   // 结构变了，查询缓存全部作废
        if (!choiceMode && (add == null || add.trim().isEmpty())) {
            throw new IllegalArgumentException("add 判据（属性解释）不可为空");
        }
        long t0Grow = System.currentTimeMillis();   // 动作流：生长验收（存量重判）整体耗时
        String addT = add != null ? add.trim() : "";
        SemNode p = find(root, parent);
        if (p == null) throw new IllegalArgumentException("父节点不存在: " + parent);
        String effRule = effectiveRuleOf(p);   // 父所在子树若有规则覆盖（如中文子树），重判沿用它

        boolean differentiate = p.isLeaf();
        List<Doc> affected = new ArrayList<Doc>(differentiate ? p.docs
                : (p.otherLeaf != null ? p.otherLeaf.docs : new ArrayList<Doc>()));
        SemNode target = addNode(parent, name, addT.isEmpty() ? null : addT);
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
            traceRejudge(rep, parent, addT, affected.size(), t0Grow);
            return rep;
        }

        if (differentiate) {
            SemNode other = otherLeaf(p);
            NoulSpec dimSpec = dimSpecOf(addT, p.add);   // 维度 score 题并入首条重判请求（一次请求双任务）
            for (Doc d : affected) {
                JudgeReply jr = judgeOne(d.content, target.name, addT, dimSpec);
                if (rep.dimProb == null) rep.dimProb = jr.dimProb;   // 首条顺带的维度分
                dimSpec = null;   // 仅首条带维度题，后续重判不再重复
                double prob = jr.prob;
                boolean pass = prob >= addGate;
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
            NoulSpec dimSpec = dimSpecOf(addT, p.add);
            for (Doc d : affected) {
                JudgeReply jr = judgeOne(d.content, target.name, addT, dimSpec);
                if (rep.dimProb == null) rep.dimProb = jr.dimProb;
                dimSpec = null;
                double prob = jr.prob;
                if (prob >= addGate) {
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
        traceRejudge(rep, parent, addT, affected.size(), t0Grow);
        return rep;
    }

    /** 追踪埋点：存量重判验收动作（act.rejudge，生长收编第一测）——逐条 P 值与移动结果。 */
    private void traceRejudge(GrowReport rep, String parent, String addT, int affectedN, long t0) {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("parent", parent);
        d.put("node", rep.node);
        d.put("add", addT);
        d.put("affected", Integer.valueOf(affectedN));
        List<Map<String, Object>> mv = new ArrayList<Map<String, Object>>();
        for (Move m : rep.moves) {
            Map<String, Object> mm = new LinkedHashMap<String, Object>();
            mm.put("docId", m.docId);
            mm.put("prob", Double.valueOf(m.prob));
            mm.put("moved", Boolean.valueOf(m.moved));
            mv.add(mm);
        }
        d.put("moves", mv);
        Trace.record(traceTag, "act.rejudge", "存量重判验收 · " + rep.moves.size() + " 条",
                d, null, null, 0, 0, System.currentTimeMillis() - t0);
    }

    /**
     * 生长回滚（growNode 的逆操作，零模型调用）：删掉刚长出的分支，把其收编的存量放回原位。
     * 本枝为父层唯一实子（父层原为叶，分化路径）→ 收编存量连同分化新建的兜底叶存量全部还原到父叶本身，
     * 父层回叶形态（空兜底叶连带撤掉）；否则（挂靠回填路径）→ 收编存量还原到父层兜底叶。
     * 与 removeNode 不同：不删任何文档、不受「兜底叶仍有落位」护栏限制（专为生长验收回滚用）。
     */
    public void undoGrow(String parentName, String nodeName) {
        bustQuestCache();   // 结构变了，查询缓存全部作废
        SemNode p = find(root, parentName);
        SemNode n = p == null ? null : find(root, nodeName);
        if (p == null || n == null || n.other || !p.children.contains(n)) {
            throw new IllegalArgumentException("回滚目标非法: " + nodeName);
        }
        boolean toLeaf = p.children.size() == 1;   // 本枝是唯一实子 → 删除后父层回叶形态
        List<Doc> back = new ArrayList<Doc>(docsUnder(n));
        if (toLeaf && p.otherLeaf != null) back.addAll(p.otherLeaf.docs);
        for (Doc d : back) {
            stripFromSubtree(n, d);   // 从收编枝（及其子树）上摘下
            if (toLeaf) {
                if (p.otherLeaf != null) {   // 分化新建的兜底叶：存量一并还原到父叶
                    p.otherLeaf.docs.remove(d);
                    d.leaves.remove(p.otherLeaf.name);
                }
                p.docs.add(d);
                d.leaves.add(p.name);
            } else {
                SemNode o = otherLeaf(p);
                o.docs.add(d);
                d.leaves.add(o.name);
            }
        }
        p.children.remove(n);
        if (toLeaf && p.otherLeaf != null && p.otherLeaf.docs.isEmpty()) {
            p.otherLeaf = null;   // 分化新建的空兜底叶连带撤掉，父层回叶形态
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("parent", parentName);
        d.put("node", nodeName);
        d.put("restored", Integer.valueOf(back.size()));
        d.put("toLeaf", Boolean.valueOf(toLeaf));
        Trace.record(traceTag, "act.rollback", "生长回滚 · " + nodeName + "（还原 " + back.size() + " 条）",
                d, null, null, 0, 0, 0);
    }

    /** 从 n 子树内的各叶上摘下 doc（含兜底叶；叶内指针与 doc 叶名指针双向清除）。 */
    private static void stripFromSubtree(SemNode n, Doc d) {
        if (n.isLeaf()) {
            n.docs.remove(d);
            d.leaves.remove(n.name);
            return;
        }
        for (SemNode c : n.children) stripFromSubtree(c, d);
        if (n.otherLeaf != null) stripFromSubtree(n.otherLeaf, d);
    }

    // ── 节点延伸（深度生长）：拥挤叶由 Server 编排在表锁外串行拆细，任意层可递复用 ──

    /** 延伸准备快照（表锁内调用）：延伸串行闭环每一轮的输入——目标含义、现有子分支、存量样本与待重判清单。 */
    public static final class ExtendPlan {
        public final String node;              // 延伸目标节点名
        public final String parentAdd;         // 目标节点属性解释（子类的公共上限，生成提示词 {parentAdd}）
        public final int totalDocs;            // 待整理存量条数
        public final List<String[]> siblings;  // 现有子分支 {name, add}（互斥基准，可能为空）
        public final List<String> samples;     // 存量均匀抽样（内容概述，生成器输入）
        public final List<String[]> affected;  // 全量待重判存量 {id, content}
        public final String rule;              // 有效 add 规则（重判沿用）

        ExtendPlan(String node, String parentAdd, int totalDocs, List<String[]> siblings,
                   List<String> samples, List<String[]> affected, String rule) {
            this.node = node;
            this.parentAdd = parentAdd;
            this.totalDocs = totalDocs;
            this.siblings = siblings;
            this.samples = samples;
            this.affected = affected;
            this.rule = rule;
        }
    }

    /**
     * 延伸目标校验 + 快照。目标=真实叶（拆直挂存量）或内部节点（拆其兜底叶存量），口径与 growNode
     * 受影响存量一致；兜底叶本身不可延伸（对其父节点触发）。choice 模式不支持：选项整题重选依赖
     * 树结构，锁外化代价高，手动与水位触发均拒绝。节点不存在抛 IllegalArgumentException。
     */
    public ExtendPlan planExtend(String nodeName, int sampleLimit) {
        SemNode n = nodeName == null ? null : find(root, nodeName.trim());
        if (n == null) throw new IllegalArgumentException("节点不存在: " + nodeName);
        if (choiceMode) throw new IllegalStateException("choice 表不支持延伸（仅 noul 多标签树）");
        if (n.other) throw new IllegalArgumentException("兜底叶不可直接延伸，请对其父节点触发");
        List<Doc> src = n.isLeaf() ? n.docs : (n.otherLeaf != null ? n.otherLeaf.docs : new ArrayList<Doc>());
        List<String[]> affected = new ArrayList<String[]>();
        for (Doc d : src) affected.add(new String[]{d.id, d.content});
        List<String> samples = new ArrayList<String>();
        if (!affected.isEmpty()) {   // 均匀步长抽样：整体代表性优于只取前 N 条
            int limit = Math.min(Math.max(sampleLimit, 1), affected.size());
            double step = (double) affected.size() / limit;
            for (int i = 0; i < limit; i++) samples.add(affected.get((int) (i * step))[1]);
        }
        List<String[]> siblings = new ArrayList<String[]>();
        for (SemNode c : n.children) {
            siblings.add(new String[]{c.name, c.add == null || c.add.trim().isEmpty() ? c.name : c.add});
        }
        return new ExtendPlan(n.name, n.add == null ? "" : n.add, affected.size(),
                siblings, samples, affected, effectiveRuleOf(n));
    }

    /** 延伸口径的存量条数（真实叶直挂数 / 内部节点兜底叶数）；非法目标（不存在/兜底叶/choice 表）返回 -1。水位触发检查用。 */
    public int extendDocCount(String nodeName) {
        if (choiceMode) return -1;
        SemNode n = nodeName == null ? null : find(root, nodeName.trim());
        if (n == null || n.other) return -1;
        return n.isLeaf() ? n.docs.size() : (n.otherLeaf != null ? n.otherLeaf.docs.size() : 0);
    }

    /**
     * 延伸逐条落位（表锁内短临界区调用）：一条存量按新子枝判定结果移动——P≥门从原位（真实叶或
     * 父层兜底叶）迁入新枝返回 true；不过门从真实叶落进兜底叶（与 growNode 口径一致）、已在兜底
     * 原地不动返回 false。doc 已被并发增删或节点缺失返回 false（留待下一轮快照自然收正）。
     */
    public boolean moveJudged(String nodeName, String docId, String targetName, double prob) {
        SemNode p = find(root, nodeName);
        SemNode t = p == null ? null : find(root, targetName);
        if (p == null || t == null) return false;
        Doc d = docIndex.get(docId);
        if (d == null) return false;
        boolean fromLeaf = p.docs.contains(d);
        if (!fromLeaf && (p.otherLeaf == null || !p.otherLeaf.docs.contains(d))) return false;
        if (prob >= addGate) {   // 延伸迁移过落位门控（与 growNode 存量重判同口径）
            if (fromLeaf) {
                p.docs.remove(d);
                d.leaves.remove(p.name);
            } else {
                p.otherLeaf.docs.remove(d);
                d.leaves.remove(p.otherLeaf.name);
            }
            t.docs.add(d);
            d.leaves.add(t.name);
            return true;
        }
        if (fromLeaf) {
            p.docs.remove(d);
            d.leaves.remove(p.name);
            SemNode o = otherLeaf(p);
            o.docs.add(d);
            d.leaves.add(o.name);
        }
        return false;
    }

    /** 父子树有效 add 规则（有覆盖用覆盖，无则全局）——growNode 与 planExtend 共用。 */
    private String effectiveRuleOf(SemNode n) {
        List<SemNode> pathNodes = new ArrayList<SemNode>();
        if (findNodePath(root, n.name, pathNodes)) {
            for (int i = pathNodes.size() - 1; i >= 0; i--) {
                if (pathNodes.get(i).ruleAdd != null) return pathNodes.get(i).ruleAdd;
            }
        }
        return ruleAdd;
    }

    /**
     * 单题重判结果：prob=目标节点 P(yes)；dimProb=维度 score 题期望分（未带维度题或 refusal 时 null）。
     */
    public static final class JudgeReply {
        public final double prob;
        public final Double dimProb;

        JudgeReply(double prob, Double dimProb) {
            this.prob = prob;
            this.dimProb = dimProb;
        }
    }

    /** 维度 score 题组装：只要求新枝判据非空（ROOT 层生长无父基准也照样打分存档，供后续作父基准）；
     * 判据空（choice 链）返回 null（维度题缺席 → 验收降级放行）。 */
    private NoulSpec dimSpecOf(String childAdd, String parentAdd) throws IOException {
        if (childAdd == null || childAdd.trim().isEmpty()) return null;
        return promptsOrThrow().dimSpec(childAdd);
    }

    /** score 期望分统一保留三位小数（维度分展示/存档/比较共用精度）。 */
    private static double round3(double v) {
        return Math.round(v * 1000.0d) / 1000.0d;
    }

    /**
     * 单题重判（增长回填与延伸编排用），计入账单。存量验收请求无 state（rule/content 均不带）：
     * 被判内容拼入题目（addCriterion 模板中的「这条内容」替换为内容原文），全是 question。
     * add 判据=属性解释，add 判据模板包裹、no 判据模板现场推导。纯参数化不读树结构：
     * 延伸编排可在表锁外调用，账单累加同步保证并发安全。
     *
     * @param dimSpec 维度 score 题（非 null 时同一请求附加一道 __dim__ 题，score 期望分=新枝判据维度分；
     *                null=不带）
     */
    public JudgeReply judgeOne(String content, String name, String add,
                               NoulSpec dimSpec) throws IOException {
        final Prompts ps = promptsOrThrow();
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        qs.put(name, new NoulSpec(ps.noulInstructions(Task.ADD, name), ps.rejudgeCriterion(add, name, content),
                add, ps.noulNoHan, ps.noulNoLatin));
        if (dimSpec != null) qs.put("__dim__", dimSpec);
        long t0 = System.currentTimeMillis();
        NoulReply r;
        try {
            r = backend.noul(null, null, qs);   // 无 state：内容已拼入题目，rule 不用于存量验收
        } catch (IOException e) {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("task", "rejudge");
            d.put("node", name);
            d.put("content", clip(content));
            d.put("questions", qsDetail(qs));
            d.put("error", String.valueOf(e.getMessage()));
            traceLlm("llm.decision", "存量重判失败 @ " + name, d, null, t0);
            throw e;
        }
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("task", "rejudge");
        d.put("node", name);
        d.put("content", clip(content));
        d.put("questions", qsDetail(qs));
        Double pv0 = r.probs.get(name);
        d.put("prob", pv0 == null ? Double.valueOf(0.0d) : pv0);
        if (dimSpec != null && r.probs.get("__dim__") != null)
            d.put("dim", round3(r.probs.get("__dim__")));   // 同请求顺带的维度 score 期望分（三位小数）
        traceLlm("llm.decision", "存量重判 @ " + name, d, r, t0);
        synchronized (this) {   // 延伸线程在表锁外调用：账单计数并发安全
            requests++;
            totalIn += r.inputTokens;
            totalOut += r.outputTokens;
            totalLatency += r.latencyMs;
            totalCost += r.cost;
        }
        Double p = r.probs.get(name);
        Double dp = dimSpec == null ? null : r.probs.get("__dim__");
        return new JudgeReply(p == null ? 0.0d : p.doubleValue(), dp == null ? null : round3(dp));
    }

    /**
     * 维度双题打分（延伸链验收用）：一次决策请求两道 score 题（父判据 + 子判据各一题，共用 state、
     * 无 rule——维度打分无判定规则语义），score 期望分即维度分（0~5 精确小数，方向一致：分数越高粒度越细）。
     * 题目不含对判据的递进约束，child > parent 由调用方完成。计入账单，表锁外可调。
     *
     * @return double[]{parentDim, childDim}
     */
    public double[] judgeDimPair(String parentAdd, String childAdd) throws IOException {
        final Prompts ps = promptsOrThrow();
        LinkedHashMap<String, NoulSpec> qs = new LinkedHashMap<String, NoulSpec>();
        qs.put("q_parent", ps.dimSpec(parentAdd));
        qs.put("q_child", ps.dimSpec(childAdd));
        long t0 = System.currentTimeMillis();
        NoulReply r;
        try {
            r = backend.noul(parentAdd == null ? "" : parentAdd, null, qs);   // state 不带 rule
        } catch (IOException e) {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("task", "dim");
            d.put("parent", clip(parentAdd));
            d.put("child", clip(childAdd));
            d.put("questions", qsDetail(qs));
            d.put("error", String.valueOf(e.getMessage()));
            traceLlm("llm.decision", "维度打分失败", d, null, t0);
            throw e;
        }
        Double pd = r.probs.get("q_parent");
        Double cd = r.probs.get("q_child");
        double pdv = pd == null ? 0.0d : round3(pd);
        double cdv = cd == null ? 0.0d : round3(cd);
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("task", "dim");
        d.put("parent", clip(parentAdd));
        d.put("child", clip(childAdd));
        d.put("questions", qsDetail(qs));
        d.put("parentDim", pdv);
        d.put("childDim", cdv);
        traceLlm("llm.decision", "维度打分（延伸验收） → 父 " + pdv + " / 子 " + cdv, d, r, t0);
        synchronized (this) {
            requests++;
            totalIn += r.inputTokens;
            totalOut += r.outputTokens;
            totalLatency += r.latencyMs;
            totalCost += r.cost;
        }
        return new double[]{pdv, cdv};
    }

    // ── 修改节点：改名 + 判据编辑（零模型调用） ─────────────────────

    /** 修改报告：旧名 / 新名 / 生效属性解释 / 父节点名（可视化刷新用）。 */
    public static final class EditReport {
        public final String oldName;
        public String newName;
        public String add;
        public String parent;

        EditReport(String oldName) {
            this.oldName = oldName;
            this.newName = oldName;
        }
    }

    /**
     * 修改节点：改名与 add 判据（属性解释）编辑，零模型调用。
     * 叶改名连带更新其上落位 Doc 的叶指针；内部节点改名连带重命名其已创建的兜底叶（含指针）。
     * 改判据不重判已落位数据（判据语义变了，落位由后续 add/quest 自然收正或 --reseed 重跑）。
     * no 判据由 no 模板现场推导，不存储、不可编辑。
     */
    public EditReport editNode(String name, String newName, String add) {
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
        if (add != null && !add.trim().isEmpty()) {
            n.add = add.trim();
        }
        rep.add = n.add;
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

    /** 动作流归属表 id 回填（SemQuestServer 建表/迁移时调用）。 */
    public void setTraceTag(String tableId) {
        this.traceTag = tableId;
    }

    /** noul 问题规格 → detail 键值（每分支：判据文本 + 短指令；完整 req/resp 体在事件外层）。 */
    private static Map<String, Object> qsDetail(LinkedHashMap<String, NoulSpec> qs) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, NoulSpec> en : qs.entrySet()) {
            Map<String, Object> q = new LinkedHashMap<String, Object>();
            q.put("criterion", en.getValue().yes);
            q.put("instructions", en.getValue().instructions);
            if (en.getValue().scoreCriteria != null) {
                q.put("type", "score");
                q.put("criteria", en.getValue().scoreCriteria);   // score 题展示档位选项
            } else {
                q.put("no", en.getValue().no);
            }
            out.put(en.getKey(), q);
        }
        return out;
    }

    /** 内容/查询文本截断（detail 用；完整原文在 act.add / act.quest 事件 detail 里也截断，防动作流单条过大）。 */
    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** 追踪埋点：模型调用事件（llm.*）。req/resp 体取自 Http.LAST_POST 线程捕获；r 为 null 表示调用失败。 */
    private void traceLlm(String type, String title, Map<String, Object> detail,
                          NoulReply r, long t0) {
        String[] cap = Http.LAST_POST.get();
        Trace.record(traceTag, type, title, detail,
                cap == null ? null : cap[1], cap == null ? null : cap[2],
                r == null ? 0 : r.inputTokens, r == null ? 0 : r.outputTokens,
                System.currentTimeMillis() - t0);
    }

    private NoulReply ask(String taskName, String nodeName, String content, String rule,
                          LinkedHashMap<String, NoulSpec> qs, List<Step> steps) throws IOException {
        long t0 = System.currentTimeMillis();
        NoulReply r;
        try {
            r = backend.noul(content, rule, qs);
        } catch (IOException e) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("task", taskName);
            detail.put("node", nodeName);
            detail.put("content", clip(content));
            detail.put("questions", qsDetail(qs));
            detail.put("error", String.valueOf(e.getMessage()));
            traceLlm("llm.decision", taskName + " 判定失败 @ " + nodeName, detail, null, t0);
            throw e;
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("task", taskName);
        detail.put("node", nodeName);
        detail.put("content", clip(content));
        detail.put("questions", qsDetail(qs));
        detail.put("probs", r.probs);
        traceLlm("llm.decision", taskName + " 批量判定 @ " + nodeName
                + "（" + qs.size() + " 题）", detail, r, t0);
        steps.add(new Step(nodeName, r));
        requests++;
        totalIn += r.inputTokens;
        totalOut += r.outputTokens;
        totalLatency += r.latencyMs;
        totalCost += r.cost;
        return r;
    }

    /** 内部节点的兜底叶（懒创建）。不参与判定请求，故无判据（add 置 null，可视化不展示）。 */
    private SemNode otherLeaf(SemNode node) {
        if (node.otherLeaf == null) {
            node.otherLeaf = new SemNode(node.name + OTHER_SUFFIX, null, true,
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

    // ── 维度存取（生长验收打分回存；不影响判定路径） ────────────────

    /** 节点维度分（0=未评；0~5，越低越宽泛越高越细致，统一保留三位小数）。节点不存在返回 0。 */
    public double dimOf(String nodeName) {
        SemNode n = nodeName == null ? null : find(root, nodeName.trim());
        return n == null || n.dim <= 0 ? 0.0d : round3(n.dim);   // 历史满精度存档读取时也归一三位
    }

    /** 写入节点维度分（统一三位小数；不影响判定路径，不清查询缓存；生长/延伸验收通过后回存，重启回放零模型恢复）。 */
    public void setDim(String nodeName, double dim) {
        SemNode n = nodeName == null ? null : find(root, nodeName.trim());
        if (n == null) throw new IllegalArgumentException("节点不存在: " + nodeName);
        n.dim = Math.min(5.0d, Math.max(0.0d, round3(dim)));
    }

    /** 读节点属性解释（无此节点或未配置回 null；ROOT 判据为 null）。供生长语境包取父判据。 */
    public String nodeAdd(String nodeName) {
        SemNode n = nodeName == null ? null : find(root, nodeName.trim());
        return n == null ? null : n.add;
    }

    // ── 落位指针一致性（悬空自愈） ─────────────────────────────────

    /** 一条悬空指针：docId + 指向已不存在节点的叶名。 */
    public static final class DanglingLeaf {
        public final String docId;
        public final String leaf;

        DanglingLeaf(String docId, String leaf) {
            this.docId = docId;
            this.leaf = leaf;
        }
    }

    /**
     * 悬空叶指针清单（零模型调用）：doc.leaves 里 find 不可达的叶名。
     * 成因：异常中断把落位中间态固化进快照 / 旧版回放对缺失叶静默跳过——载入后校验自愈用。
     */
    public List<DanglingLeaf> danglingLeaves() {
        List<DanglingLeaf> out = new ArrayList<DanglingLeaf>();
        for (Doc d : docIndex.values()) {
            for (String ln : d.leaves) {
                if (find(root, ln) == null) out.add(new DanglingLeaf(d.id, ln));
            }
        }
        return out;
    }

    /** 摘除全部悬空叶指针（自愈，零模型调用），返回摘除清单；无悬空返回空表。 */
    public List<DanglingLeaf> detachDanglingLeaves() {
        List<DanglingLeaf> out = danglingLeaves();
        if (out.isEmpty()) return out;
        for (DanglingLeaf d : out) {
            Doc doc = docIndex.get(d.docId);
            if (doc != null) doc.leaves.remove(d.leaf);
        }
        return out;
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
        if (n.add != null) m.put("add", n.add);
        if (n.dim > 0) m.put("dim", round3(n.dim));   // 展示统一三位小数
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
