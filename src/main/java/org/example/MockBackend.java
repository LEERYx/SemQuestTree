package org.example;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 零凭据确定性后端：无网络、无模型、零 token，供开箱体验 / 演示 / 测试用。
 *
 * <p>判定是纯关键词包含匹配：noul 问「内容里是否含节点名」（含→0.95，不含→0.05）；
 * choice 在选项里找「内容包含的最长选项名」，无命中落兜底选项（askChoice 保证兜底殿后）。
 * 置信度恒 0.95（高于默认门控），同一输入结果完全可复现。
 *
 * <p>不做真实语义判定——接真实模型请在 semquest.properties 配置 jev / openrouter / bailian。
 */
public final class MockBackend implements DecisionBackend {

    private static final double HIT = 0.95d;
    private static final double MISS = 0.05d;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean hasKey() {
        return true;   // 零凭据：永远就绪
    }

    @Override
    public NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException {
        long start = System.nanoTime();
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, NoulSpec> e : questions.entrySet()) {
            probs.put(e.getKey(), contains(content, e.getValue().instructions) ? HIT : MISS);
        }
        return new NoulReply(probs, 0, 0, elapsedMs(start), 0.0);
    }

    @Override
    public NoulReply choice(String content, String rule, String question,
                            LinkedHashMap<String, String> criteria) throws IOException {
        long start = System.nanoTime();
        // 最长命中优先：避免短名（如「涨价」）抢走本应命中的长选项（如「要求涨价」）
        String win = null;
        for (String key : criteria.keySet()) {
            if (!contains(content, key)) continue;
            if (win == null || key.length() > win.length()) win = key;
        }
        if (win == null) {
            for (String key : criteria.keySet()) win = key;   // 无命中：取最后一项（兜底选项）
        }
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        for (Map.Entry<String, String> e : criteria.entrySet()) {
            probs.put(e.getKey(), e.getKey().equals(win) ? HIT : MISS);
        }
        return new NoulReply(probs, 0, 0, elapsedMs(start), 0.0, win, HIT);
    }

    /** 内容包含选项名（双方去首尾空白）即命中；空标签不参与。 */
    private static boolean contains(String content, String label) {
        if (content == null || label == null) return false;
        String c = content.trim(), l = label.trim();
        return !l.isEmpty() && c.contains(l);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
