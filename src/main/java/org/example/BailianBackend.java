package org.example;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼决策后端（decision-model-preview，裸请求沿用 DecisionModelClient）。
 *
 * <p>端点：POST {bailian.url}。noul 无 criteria，yes 断言即 instructions；
 * 限制：单请求 ≤16 题、上下文 ≤65536 tok。当前免费额度已耗尽（402/403），保留作可切换后端。
 *
 * <p>凭据与端点全部来自 semquest.properties（bailian.url / bailian.model / bailian.apikey）。
 */
public final class BailianBackend implements DecisionBackend {

    private static final String DEFAULT_MODEL = "decision-model-preview";

    private final String url;
    private String model;
    private final String apiKey;
    private final int timeoutMs;

    public BailianBackend(Config cfg, int timeoutMs) {
        this.url = cfg.get("bailian.url", JevBackend.envOr(
                "BAILIAN_URL",
                "https://ws-q25vqw3u5xzouaku.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone"));
        this.model = cfg.get("bailian.model", DEFAULT_MODEL);
        this.apiKey = cfg.get("bailian.apikey", null);
        this.timeoutMs = timeoutMs;
    }

    /** 凭据是否就绪。 */
    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String name() {
        return "bailian";
    }

    @Override
    public void useModel(String m) {
        if (m != null && !m.trim().isEmpty()) this.model = m.trim();
    }

    /** 当前模型 id。 */
    public String model() {
        return model;
    }

    /** 百炼模型列表：把决策端点的 v1/systemone 换成 v1/models，纯元数据零 token；失败返回 null 回退静态候选。 */
    @Override
    public List<Object> listModels() {
        try {
            String modelsUrl = url.replace("v1/systemone", "v1/models");
            String body = Http.get(modelsUrl, hasKey() ? apiKey : null, 15_000);
            return JevBackend.extractModelList(Json.parseObject(body));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException {
        if (!hasKey()) {
            throw new IOException("缺少百炼凭据：请在 semquest.properties 配置 bailian.apikey");
        }
        if (questions.size() > 16) {
            throw new IOException("百炼单请求 ≤16 题，当前 " + questions.size() + " 题：请收窄分支或拆批");
        }
        LinkedHashMap<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("rule", rule);
        state.put("content", content);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(state))
          .append(",\"questions\":{");
        boolean f = true;
        for (Map.Entry<String, NoulSpec> e : questions.entrySet()) {
            if (!f) sb.append(',');
            f = false;
            sb.append(Json.str(e.getKey()))
              .append(":{\"type\":\"noul\",\"instructions\":").append(Json.str(e.getValue().yes)).append('}');
        }
        sb.append("}}");

        long start = System.nanoTime();
        String body = Http.post(url, apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parse(body, latencyMs);
    }

    /** 百炼 choice 单选：{"type":"choice","instructions":…,"criteria":{<选项>:<描述>}}（同 jev 家族方言）。 */
    @Override
    public NoulReply choice(String content, String rule, String question,
                            LinkedHashMap<String, String> criteria) throws IOException {
        if (!hasKey()) {
            throw new IOException("缺少百炼凭据：请在 semquest.properties 配置 bailian.apikey");
        }
        LinkedHashMap<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("rule", rule);
        state.put("content", content);
        LinkedHashMap<String, Object> q = new LinkedHashMap<String, Object>();
        q.put("type", "choice");
        q.put("instructions", question);
        q.put("criteria", criteria);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(state))
          .append(",\"questions\":{\"q\":").append(Json.write(q)).append("}}");

        long start = System.nanoTime();
        String body = Http.post(url, apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parseChoice(body, latencyMs);
    }

    /** 解析百炼响应：answers{name:{type,noul}}, usage{input_tokens}, latency_ms。 */
    static NoulReply parse(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        Object answers = root.get("answers");
        if (!(answers instanceof Map)) {
            throw new IllegalStateException("百炼响应缺 answers 对象: " + body);
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) answers).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Object p = ((Map<?, ?>) e.getValue()).get("noul");
            if (p instanceof Number) probs.put(String.valueOf(e.getKey()), ((Number) p).doubleValue());
        }
        int in = 0, out = 0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = JevBackend.intOf(u.get("input_tokens"));
            out = JevBackend.intOf(u.get("output_tokens"));
        }
        Object lm = root.get("latency_ms");
        if (lm instanceof Number && ((Number) lm).longValue() > 0) {
            latencyMs = ((Number) lm).longValue();   // 上游真实延迟优先
        }
        return new NoulReply(probs, in, out, latencyMs, 0.0);
    }

    /** 解析 choice 响应：answers{"q":{type:"choice",choice,confidence[,probabilities]}}。 */
    static NoulReply parseChoice(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        Object answers = root.get("answers");
        if (!(answers instanceof Map) || ((Map<?, ?>) answers).isEmpty()) {
            throw new IllegalStateException("百炼 choice 响应缺 answers: " + body);
        }
        Map<?, ?> a = (Map<?, ?>) ((Map<?, ?>) answers).values().iterator().next();
        Object c = a.get("choice");
        if (!(c instanceof String) || ((String) c).trim().isEmpty()) {
            throw new IllegalStateException("百炼 choice 响应缺 choice 字段: " + body);
        }
        String choice = ((String) c).trim();
        double conf = a.get("confidence") instanceof Number ? ((Number) a.get("confidence")).doubleValue() : 0.0d;
        int in = 0, out = 0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = JevBackend.intOf(u.get("input_tokens"));
            out = JevBackend.intOf(u.get("output_tokens"));
        }
        Object lm = root.get("latency_ms");
        if (lm instanceof Number && ((Number) lm).longValue() > 0) {
            latencyMs = ((Number) lm).longValue();   // 上游真实延迟优先
        }
        return JevBackend.choiceReply(choice, conf, a.get("probabilities"), in, out, latencyMs, 0.0);
    }
}
