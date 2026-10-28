package org.example;

import java.nio.file.Paths;
import java.util.Map;

/**
 * 问义树 Demo：货主司机聊天语料筛选（全中文判据 + 中文语料，2026-10-28 树收敛为纯中文）。
 * 预制层级分支（driver-intent-schema.json）→ 司机话术 add 落位到意图叶 →
 * 自然语言 quest 检索 → 命中叶数排序。
 *
 * <p>所有配置集中在 semquest.properties（后端选择 / 门控 / schema 路径 / 超时 / 三个后端凭据）。
 *
 * <p>用法（项目根目录）：
 * <pre>
 *   javac -encoding UTF-8 -d target/classes $(find src/main/java -name "*.java")
 *   java -cp target/classes org.example.Demo              # 按 semquest.properties 的 backend 运行
 *   java -cp target/classes org.example.Demo openrouter   # 命令行覆盖后端（mock | jev | openrouter | bailian）
 *   java -cp target/classes org.example.Demo jev 0.7      # 同时覆盖门控
 * </pre>
 * 配置文件路径可用 -Dsemquest.config=... 指定；上游被区域封锁时加：
 * -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
 */
public class Demo {

    /**
     * 语料：每条埋 1~3 个意图，Z-0666 为兜底测试（不属于任何已列判据）。
     * 四层全中文约定（2026-10-28 收敛）：规则 / 输入 / 选项 / true-false 判据同为中文。
     * 包内可见供 SemQuestServer 种子复用。
     */
    static final String[][] DOCS = {
            {"Z-0317", "这单只给一千二，去南京的油钱都不够，要么加到一千五，要么我就只拉八吨，剩下的你们找别的车。"},
            {"Z-0288", "上个月的三趟运费到现在还没结，再不打款我就停工不干了。"},
            {"Z-0502", "这批货有三箱都压瘪变形了，破的那几件我挑出来不装，运费和赔偿你们得给我个说法。"},
            {"Z-0099", "到了地方你们自己找装卸工，我只管开车，装卸费别算我头上。"},
            {"Z-0420", "高速上堵死了，我估计得晚到两个钟头，你提前跟收货方说一声。"},
            {"Z-0511", "卸完货还得空跑三百公里回来，至少给我报一半油钱。"},
            {"Z-0666", "你们系统发的定位在导航上根本找不到，货车根本开不进去。"},
    };

    /** 自然语言查询（与语料同中文）：验证多意图交集排序与单意图命中。 */
    private static final String[] QUERIES = {
            "找一下既要涨价又想少拉货的司机",
            "哪些司机嫌运费太低不赚钱",
            "有没有运费还没结算的话术",
            "哪些司机会晚到",
    };

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load();
        String backendName = args.length > 0 ? args[0] : cfg.get("backend", "jev");
        double gate = args.length > 1 ? Double.parseDouble(args[1]) : cfg.getDouble("noulGate", 0.7);
        int timeoutMs = cfg.getInt("timeoutMs", 30000);
        String schemaPath = cfg.get("schema", "driver-intent-schema.json");

        DecisionBackend backend = createBackend(cfg, backendName, timeoutMs);
        SemQuestTree tree = SemQuestTree.load(Paths.get(schemaPath), backend, gate);

        System.out.println("== 问义树 SemQuestTree ==" +
                "\n配置: " + cfg.file() + (cfg.loaded() ? "" : "（未找到，使用代码内默认）") +
                "\n后端: " + backend.name() + "   门控: " + gate +
                "\n" + tree.treeView());

        // ── add：内容下行落位 ──────────────────────────────────────
        System.out.println("== add 落位 ==");
        for (String[] d : DOCS) {
            SemQuestTree.AddReport r = tree.add(d[0], d[1]);
            System.out.println("[" + r.docId + "] " + d[1]);
            printSteps(r.steps);
            System.out.println("  → 落位: " + join(r.leaves) + "\n");
        }

        // ── quest：自然语言查询 ────────────────────────────────────
        System.out.println("== quest 检索 ==");
        for (String q : QUERIES) {
            SemQuestTree.QuestReport r = tree.quest(q);
            System.out.println("Q: " + q);
            printSteps(r.steps);
            System.out.println("  命中叶: " + (r.matchedLeaves.isEmpty() ? "（无——查询在树外）" : join(r.matchedLeaves)));
            if (r.ranked.isEmpty()) {
                System.out.println("  结果: （无命中）\n");
            } else {
                for (SemQuestTree.RankedDoc rd : r.ranked) {
                    System.out.printf("  %.0f分 %s  经由 %s%n",
                            (double) rd.score, rd.docId, join(rd.viaLeaves));
                }
                System.out.println();
            }
        }

        System.out.println("== 账单 ==");
        System.out.println(tree.statsLine());
    }

    /** 后端工厂：mock 恒就绪；缺凭据自动降级 jev→openrouter→mock。包内可见供 SemQuestServer 复用。 */
    static DecisionBackend createBackend(Config cfg, String name, int timeoutMs) {
        String n = name == null ? "" : name.trim();
        if ("mammoth".equalsIgnoreCase(n)) n = "openrouter";   // 类更名后的旧称兼容
        if ("mock".equalsIgnoreCase(n)) return new MockBackend();
        if ("jev".equalsIgnoreCase(n)) {
            JevBackend jev = new JevBackend(cfg, timeoutMs);
            if (jev.hasKey()) return jev;
            OpenRouterBackend or = new OpenRouterBackend(cfg, timeoutMs);
            if (or.hasKey()) {
                System.out.println("!! jev 凭据未配置，回退 OpenRouter 备用通道（在 semquest.properties 配置 jev.apikey 可启用 jev）\n");
                return or;
            }
            System.out.println("!! jev / OpenRouter 凭据均未配置，使用 mock 后端（确定性关键词匹配，零凭据可跑；"
                    + "配置 jev.apikey 或 openrouter.apikey 启用真实模型）\n");
            return new MockBackend();
        }
        if ("openrouter".equalsIgnoreCase(n)) {
            OpenRouterBackend m = new OpenRouterBackend(cfg, timeoutMs);
            if (!m.hasKey()) throw new IllegalArgumentException(
                    "OpenRouter 凭据未配置（semquest.properties: openrouter.apikey）；零凭据可先用 backend=mock 体验");
            return m;
        }
        if ("bailian".equalsIgnoreCase(n)) {
            BailianBackend b = new BailianBackend(cfg, timeoutMs);
            if (!b.hasKey()) throw new IllegalArgumentException(
                    "bailian 凭据未配置（semquest.properties: bailian.apikey）；零凭据可先用 backend=mock 体验");
            return b;
        }
        throw new IllegalArgumentException("未知后端: " + name + "（可选 mock | jev | openrouter | bailian）");
    }

    // ── 打印辅助 ────────────────────────────────────────────────────

    private static void printSteps(java.util.List<SemQuestTree.Step> steps) {
        for (SemQuestTree.Step s : steps) {
            StringBuilder sb = new StringBuilder("  @").append(s.node).append("  ");
            boolean f = true;
            for (Map.Entry<String, Double> e : s.probs.entrySet()) {
                if (!f) sb.append("  ");
                f = false;
                sb.append(e.getKey()).append(' ');
                if (e.getValue() == null) sb.append('—');
                else sb.append(String.format("%.2f", e.getValue()));
            }
            sb.append(String.format("   [%d ms, in %d/out %d tok]", s.latencyMs, s.inputTokens, s.outputTokens));
            System.out.println(sb);
        }
    }

    private static String join(java.util.List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
