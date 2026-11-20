package org.example;

/**
 * 一个 noul 问题规格：name 由外层 Map 的 key 决定。
 *
 * <p>jev 需要三段全非空（双边判据方言）；百炼/OpenRouter 只用 {@code yes} 作为 instructions，
 * {@code no} 忽略。yes = 包裹后的 add/search 判据文本（属性解释经模板投影），no 由 {@link #autoNo}
 * 按 prompt.noul.no.{han,latin} 配置的提示词文件模板现场推导——种子传裸属性解释（而非包裹后的
 * 问句），否则 no 会变成「并非以下情况：这条内容是否…？」的模板套模板嵌套句；模板按是否含汉字自动选择。
 * no 侧不存储。
 */
public final class NoulSpec {

    /** 短指令/标签（即节点名，如「运费价格」）。jev 必填。 */
    public final String instructions;

    /** 包裹后的判据文本（add 判据或 search 判据；属性解释经模板投影）。 */
    public final String yes;

    /** "否"侧断言。仅 jev / respan 使用；构造时由 no 模板对 noSeed 现场推导。 */
    public final String no;

    /** score 题的档位选项描述数组（非 null = score 题型：响应直接回期望分 score，无需代码加权）；
     * null = noul 题型。用于维度打分：六档描述各一选项，每档单独打分而非重复多道题。 */
    public final java.util.List<String> scoreCriteria;

    private NoulSpec(String instructions, java.util.List<String> scoreCriteria) {
        this.instructions = instructions;
        this.yes = instructions;
        this.no = "";
        this.scoreCriteria = scoreCriteria;
    }

    /**
     * score 题工厂：多档位选项各打分，响应 answer{type:"score", score:期望分, probabilities:{档位索引:概率}}。
     * @param instructions   题目指令（含判定对象，如「判据「X」属于哪一档」）
     * @param scoreCriteria  档位选项描述数组（索引即档位值，期望分 = Σ 索引×概率）
     */
    public static NoulSpec score(String instructions, java.util.List<String> scoreCriteria) {
        if (instructions == null || instructions.trim().isEmpty())
            throw new IllegalArgumentException("score 题 instructions 不能为空");
        if (scoreCriteria == null || scoreCriteria.size() < 2)
            throw new IllegalArgumentException("score 题至少两个档位选项");
        return new NoulSpec(instructions, scoreCriteria);
    }

    /**
     * @param noSeed         no 侧推导种子：裸属性解释或裸文本（null 回退包裹判据 yes——如 doc 过滤场景）
     * @param noHanTemplate  判据含汉字时的 no 侧模板（prompt.noul.no.han 文件内容，含 {add}）
     * @param noLatinTemplate 纯西文判据时的 no 侧模板（prompt.noul.no.latin 文件内容，含 {add}）
     */
    public NoulSpec(String instructions, String yes, String noSeed,
                    String noHanTemplate, String noLatinTemplate) {
        if (instructions == null || instructions.trim().isEmpty())
            throw new IllegalArgumentException("instructions 不能为空");
        if (yes == null || yes.trim().isEmpty())
            throw new IllegalArgumentException("yes 判据不能为空");
        if (noHanTemplate == null || noHanTemplate.trim().isEmpty())
            throw new IllegalArgumentException("no 侧模板（prompt.noul.no.han）不能为空");
        if (noLatinTemplate == null || noLatinTemplate.trim().isEmpty())
            throw new IllegalArgumentException("no 侧模板（prompt.noul.no.latin）不能为空");
        this.instructions = instructions;
        this.yes = yes;
        this.no = autoNo(noSeed == null || noSeed.trim().isEmpty() ? yes : noSeed.trim(),
                noHanTemplate, noLatinTemplate);
        this.scoreCriteria = null;
    }

    /** no 侧自动推导：按种子是否含汉字选中/英模板（{add}=no 种子文本；模板来自 prompt.noul.no.* 提示词文件）。 */
    public static String autoNo(String yes, String hanTemplate, String latinTemplate) {
        for (int i = 0; i < yes.length(); i++) {
            if (Character.UnicodeScript.of(yes.charAt(i)) == Character.UnicodeScript.HAN) {
                return hanTemplate.replace("{add}", yes == null ? "" : yes);
            }
        }
        return latinTemplate.replace("{add}", yes == null ? "" : yes);
    }
}
