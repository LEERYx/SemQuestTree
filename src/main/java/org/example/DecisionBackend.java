package org.example;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 决策后端抽象：对一批 noul 问题做一次前向判定。
 *
 * <p>可切换实现：{@link JevBackend}（jev-1.13.0，双边判据）、
 * {@link OpenRouterBackend}（OpenRouter decisions，baseten/mammoth-1:free 同族备用）、
 * {@link BailianBackend}（阿里云百炼 decision-model-preview）、
 * {@link MockBackend}（零凭据确定性关键词匹配，体验/测试用）。
 * 三族真实后端共用 {model, state, questions} 家族契约，仅 noul 题型方言不同。
 */
public interface DecisionBackend {

    /** 后端名（jev / openrouter / bailian），用于 Demo 选择与日志。 */
    String name();

    /**
     * 对一批问题做一次 noul 判定。
     *
     * @param content   待判定正文（司机原话或检索查询原文）
     * @param rule      场景规则文本（V2：判定规则上移 state；jev 后端可忽略——判据已自带规则）
     * @param questions 问题名 -> 规格，保持插入顺序
     * @return 每个问题 P(yes)；非 200 抛 IOException（带状态码与响应体）
     */
    NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException;

    /**
     * 做一次 choice 单选判定：从给定选项中选出一个。
     *
     * @param content   待判定正文
     * @param rule      场景规则文本
     * @param question  问题指令（如分支层的问题名）
     * @param criteria  选项 key -> 选项描述，保持插入顺序（含兜底选项）
     * @return choice=选中项、confidence=分布集中度、probs=各选项概率（缺分布时 {选中项: confidence}）
     */
    NoulReply choice(String content, String rule, String question, LinkedHashMap<String, String> criteria)
            throws IOException;

    /** 凭据是否就绪（选择/切换后端前检查）。 */
    default boolean hasKey() {
        return true;
    }

    /** 运行时切换模型（可视化模型选择栏用）；默认忽略。 */
    default void useModel(String model) {
    }

    /**
     * 上游决策模型列表（模型选择栏用）：纯元数据 GET，零 token。
     * 上游无此端点或结构不认识时返回 null，调用方回退静态候选。
     */
    default List<Object> listModels() {
        return null;
    }
}
