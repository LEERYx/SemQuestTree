package org.example;

import java.util.Collections;
import java.util.Map;

/** 一次批量判定的结果：name -> 概率，附 token / 延迟 / 费用元数据；choice 题型另带选中项与置信度。 */
public final class NoulReply {

    /** 问题名 -> 概率。noul=P(yes)；choice=各选项概率分布（上游缺分布时为 {选中项: confidence}）。
     *  后端 refusal 或缺失的问题不出现在 map 里。 */
    public final Map<String, Double> probs;

    public final int inputTokens;
    public final int outputTokens;
    public final long latencyMs;

    /** 上游真实费用（USD）；后端未返回时为 0。 */
    public final double cost;

    /** choice 题型：上游选中的选项 key；noul 题型为 null。 */
    public final String choice;

    /** choice 题型：置信度（分布集中度，[0,1]）；noul 题型为 0。 */
    public final double confidence;

    public NoulReply(Map<String, Double> probs, int inputTokens, int outputTokens,
                     long latencyMs, double cost) {
        this(probs, inputTokens, outputTokens, latencyMs, cost, null, 0.0d);
    }

    public NoulReply(Map<String, Double> probs, int inputTokens, int outputTokens,
                     long latencyMs, double cost, String choice, double confidence) {
        this.probs = Collections.unmodifiableMap(probs);
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.cost = cost;
        this.choice = choice;
        this.confidence = confidence;
    }
}
