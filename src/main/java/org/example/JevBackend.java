package org.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jev 决策后端（TypeSafe SystemOne Decisions API 裸请求，零依赖）。
 *
 * <p>端点：POST {jev.baseUrl}/v1/systemone，Bearer 认证。
 * jev noul 为双边判据方言：{"type":"noul","instructions":…,"criteria":{"true":…,"false":…}}，
 * 三字段必填——yes/no 原子断言直接来自 schema，规则不需要再上移 state。
 *
 * <p>凭据与端点全部来自 semquest.properties（jev.baseUrl / jev.model / jev.apikey）；
 * 配置缺 apikey 时回退环境变量 TYPESAFE_API_KEY / SEMANTICA_API_KEY。
 * 上游被区域封锁时加 JVM 参数：-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
 */
public final class JevBackend implements DecisionBackend {

    private final String baseUrl;
    private String model;
    private final String apiKey;
    private final int timeoutMs;

    public JevBackend(Config cfg, int timeoutMs) {
        this.baseUrl = cfg.get("jev.baseUrl", envOr("TYPESAFE_BASE_URL", "https://api.typesafe.ai"));
        this.model = cfg.get("jev.model", envOr("TYPESAFE_MODEL", "jev-1.13.0"));
        String k = cfg.get("jev.apikey", null);
        if (k == null) k = firstEnv("TYPESAFE_API_KEY", "SEMANTICA_API_KEY");
        this.apiKey = k;
        this.timeoutMs = timeoutMs;
    }

    /** 凭据是否就绪（Demo 用于决定是否回退备用通道）。 */
    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public void useModel(String m) {
        if (m != null && !m.trim().isEmpty()) {
            this.model = m.trim();
        }
    }

    /** 当前模型标识（服务端选择栏回显用）。 */
    public String model() {
        return model;
    }

    /**
     * 上游决策模型列表（选择栏用）：GET {baseUrl}/v1/models，零 token 元数据。
     * 端点不存在或结构不认识时返回 null，调用方回退静态候选。
     */
    @Override
    public List<Object> listModels() {
        try {
            Map<String, Object> root = Json.parseObject(
                    Http.get(baseUrl + "/v1/models", hasKey() ? apiKey : null, 10_000));
            return extractModelList(root);
        } catch (IOException e) {
            return null;
        }
    }

    /** 兼容 {data:[{id,...}]} / {data:["id"]} / {models:[...]} 的模型列表提取；空或不认识返回 null。 */
    static List<Object> extractModelList(Map<String, Object> root) {
        Object data = root.get("data");
        if (!(data instanceof List)) data = root.get("models");
        if (!(data instanceof List)) return null;
        List<Object> out = new ArrayList<Object>();
        for (Object o : (List<?>) data) {
            if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                Object id = m.get("id");
                if (!(id instanceof String) || String.valueOf(id).trim().isEmpty()) continue;
                LinkedHashMap<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("id", id);
                if (m.get("name") instanceof String) e.put("name", m.get("name"));
                boolean free = m.get("pricing") instanceof Map
                        && "0".equals(String.valueOf(((Map<?, ?>) m.get("pricing")).get("prompt")));
                e.put("free", free);
                out.add(e);
            } else if (o instanceof String) {
                out.add(singleMap("id", o));
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public String name() {
        return "jev";
    }

    @Override
    public NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException {
        if (!hasKey()) {
            throw new IOException("缺少 jev 凭据：请在 semquest.properties 配置 jev.apikey（或 export TYPESAFE_API_KEY=...）");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(JevBackend.singleMap("content", content)))
          .append(",\"questions\":{");
        boolean f = true;
        for (Map.Entry<String, NoulSpec> e : questions.entrySet()) {
            if (!f) sb.append(',');
            f = false;
            NoulSpec q = e.getValue();
            sb.append(Json.str(e.getKey()))
              .append(":{\"type\":\"noul\",\"instructions\":").append(Json.str(q.instructions))
              .append(",\"criteria\":{\"true\":").append(Json.str(q.yes))
              .append(",\"false\":").append(Json.str(q.no)).append("}}");
        }
        sb.append("}}");

        long start = System.nanoTime();
        String body = Http.post(baseUrl + "/v1/systemone", apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parse(body, latencyMs);
    }

    /** jev choice 单选：{"type":"choice","instructions":…,"criteria":{<选项>:<描述>}}（SDK 抓包方言）。 */
    @Override
    public NoulReply choice(String content, String rule, String question,
                            LinkedHashMap<String, String> criteria) throws IOException {
        if (!hasKey()) {
            throw new IOException("缺少 jev 凭据：请在 semquest.properties 配置 jev.apikey（或 export TYPESAFE_API_KEY=...）");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(JevBackend.singleMap("content", content)))
          .append(",\"questions\":{\"q\":{\"type\":\"choice\",\"instructions\":")
          .append(Json.str(question)).append(",\"criteria\":")
          .append(Json.write(criteria)).append("}}}");

        long start = System.nanoTime();
        String body = Http.post(baseUrl + "/v1/systemone", apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parseChoice(body, latencyMs);
    }

    /** 解析 jev 响应：answers{name:{type,noul}}, usage{input_tokens,output_tokens[,cost]}。 */
    static NoulReply parse(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        Object answers = root.get("answers");
        if (!(answers instanceof Map)) {
            throw new IllegalStateException("jev 响应缺 answers 对象: " + body);
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) answers).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> a = (Map<?, ?>) e.getValue();
            Object p = a.get("noul");
            if (p instanceof Number) probs.put(String.valueOf(e.getKey()), ((Number) p).doubleValue());
            // refusal 等其他题型跳过，视为未通过
        }
        int in = 0, out = 0;
        double cost = 0.0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = intOf(u.get("input_tokens"));
            out = intOf(u.get("output_tokens"));
            cost = u.get("cost") instanceof Number ? ((Number) u.get("cost")).doubleValue() : 0.0;
        }
        return new NoulReply(probs, in, out, latencyMs, cost);
    }

    /**
     * 解析 choice 响应：answers{"q":{type:"choice",choice,confidence[,probabilities]}}。
     * probabilities 缺省时 probs = {选中项: confidence}。单题请求，取第一个 answer。
     */
    static NoulReply parseChoice(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        Object answers = root.get("answers");
        if (!(answers instanceof Map) || ((Map<?, ?>) answers).isEmpty()) {
            throw new IllegalStateException("jev choice 响应缺 answers: " + body);
        }
        Map<?, ?> a = (Map<?, ?>) ((Map<?, ?>) answers).values().iterator().next();
        Object c = a.get("choice");
        if (!(c instanceof String) || ((String) c).trim().isEmpty()) {
            throw new IllegalStateException("jev choice 响应缺 choice 字段: " + body);
        }
        String choice = ((String) c).trim();
        double conf = a.get("confidence") instanceof Number ? ((Number) a.get("confidence")).doubleValue() : 0.0d;
        int in = 0, out = 0;
        double cost = 0.0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = intOf(u.get("input_tokens"));
            out = intOf(u.get("output_tokens"));
            cost = u.get("cost") instanceof Number ? ((Number) u.get("cost")).doubleValue() : 0.0;
        }
        return choiceReply(choice, conf, a.get("probabilities"), in, out, latencyMs, cost);
    }

    /** choice 回复统一组装：probs=probabilities（缺省 {选中项: confidence}）。 */
    static NoulReply choiceReply(String choice, double conf, Object probabilities,
                                 int in, int out, long latencyMs, double cost) {
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        if (probabilities instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) probabilities).entrySet()) {
                if (e.getValue() instanceof Number) {
                    probs.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
                }
            }
        }
        if (probs.isEmpty()) probs.put(choice, conf);
        return new NoulReply(probs, in, out, latencyMs, cost, choice, conf);
    }

    // ---------- 小工具 ----------

    static int intOf(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    static Map<String, Object> singleMap(String k, Object v) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    static String envOr(String key, String dft) {
        String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? dft : v.trim();
    }

    static String firstEnv(String... keys) {
        for (String k : keys) {
            String v = System.getenv(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
