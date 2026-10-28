package org.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter 决策后端（baseten/mammoth-1:free，同族备用传感器；原 MammothBackend 更名）。
 *
 * <p>端点：POST {openrouter.url}。noul 无 criteria 字段，yes 断言即 instructions；
 * V2 规则随 state 下发：{"rule":…,"content":…}。respan 族例外：state 走 {input,output} 消息协议、
 * 题型仅收 noul 且为 jev 同款三段方言（instructions + criteria{true,false}，上游实测）。
 * 响应 answers 兼容两种形态（name->对象 Map 或带 name 字段的数组），P(yes) 取 noul/probability 字段。
 *
 * <p>凭据与端点全部来自 semquest.properties——配置前缀 openrouter.url / openrouter.model /
 * openrouter.apikey；缺 apikey 时回退环境变量 OPENROUTER_API_KEY。
 */
public final class OpenRouterBackend implements DecisionBackend {

    private static final String DEFAULT_URL = "https://openrouter.ai/api/alpha/decisions";
    private static final String DEFAULT_MODEL = "baseten/mammoth-1:free";

    private final String url;
    private String model;
    private final String apiKey;
    private final int timeoutMs;

    public OpenRouterBackend(Config cfg, int timeoutMs) {
        this.url = cfg.get("openrouter.url", DEFAULT_URL);
        this.model = cfg.get("openrouter.model", DEFAULT_MODEL);
        String k = cfg.get("openrouter.apikey", null);
        if (k == null) k = JevBackend.envOr("OPENROUTER_API_KEY", null);
        this.apiKey = k;
        this.timeoutMs = timeoutMs;
    }

    /** 凭据是否就绪。 */
    public boolean hasKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String name() {
        return "openrouter";
    }

    @Override
    public void useModel(String m) {
        if (m != null && !m.trim().isEmpty()) this.model = m.trim();
    }

    /** 当前模型 id（切换后供日志/展示）。 */
    public String model() {
        return model;
    }

    /** OpenRouter 公开模型目录按决策模态过滤（/api/v1/models?output_modalities=decisions）：实时决策模型清单，纯元数据零 token；失败返回 null 回静态候选。 */
    @Override
    public List<Object> listModels() {
        try {
            String origin = url.substring(0, url.indexOf("/api/"));
            String body = Http.get(origin + "/api/v1/models?output_modalities=decisions", hasKey() ? apiKey : null, 15_000);
            return JevBackend.extractModelList(Json.parseObject(body));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException {
        if (!hasKey()) {
            throw new IOException("缺少 OpenRouter 凭据：请在 semquest.properties 配置 openrouter.apikey（或 export OPENROUTER_API_KEY=...）");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(state(content, rule)))
          .append(",\"questions\":{");
        boolean f = true;
        for (Map.Entry<String, NoulSpec> e : questions.entrySet()) {
            if (!f) sb.append(',');
            f = false;
            NoulSpec q = e.getValue();
            sb.append(Json.str(e.getKey()));
            if (model.startsWith("respan/")) {
                // respan 族仅收 noul 且为 jev 同款三段方言（上游 400 实测：choice 不收、criteria 须对象）
                sb.append(":{\"type\":\"noul\",\"instructions\":").append(Json.str(q.instructions))
                  .append(",\"criteria\":{\"true\":").append(Json.str(q.yes))
                  .append(",\"false\":").append(Json.str(q.no)).append("}}");
            } else {
                sb.append(":{\"type\":\"noul\",\"instructions\":").append(Json.str(q.yes)).append('}');
            }
        }
        sb.append("}}");

        long start = System.nanoTime();
        String body = Http.post(url, apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parse(body, latencyMs);
    }

    /** OpenRouter choice 单选：{"type":"choice","instructions":…,"criteria":{<选项>:<描述>}}（同 jev 家族方言）。 */
    @Override
    public NoulReply choice(String content, String rule, String question,
                            LinkedHashMap<String, String> criteria) throws IOException {
        if (model.startsWith("respan/")) {
            // 上游 400 实测：respan 族仅收 noul 题型，choice 硬拒——快速失败给出可操作提示
            throw new IllegalArgumentException("当前模型 " + model
                    + " 仅支持 noul 题型（上游不收 choice）：choice 表请切换 jev 或 elephant 族模型");
        }
        if (!hasKey()) {
            throw new IOException("缺少 OpenRouter 凭据：请在 semquest.properties 配置 openrouter.apikey（或 export OPENROUTER_API_KEY=...）");
        }
        LinkedHashMap<String, Object> q = new LinkedHashMap<String, Object>();
        q.put("type", "choice");
        q.put("instructions", question);
        q.put("criteria", criteria);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(model).append("\",\"state\":")
          .append(Json.write(state(content, rule)))
          .append(",\"questions\":{\"q\":").append(Json.write(q)).append("}}");

        long start = System.nanoTime();
        String body = Http.post(url, apiKey, sb.toString(), timeoutMs);
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return parseChoice(body, latencyMs);
    }

    /**
     * state 组装：Respan 家族状态协议不同——不认 {rule,content}，只认 {input:消息数组, output:单条 assistant 消息}
     * （各消息 content 须为字符串、role ∈ system/user/assistant/tool）；规则进 system，被判内容进 output。
     */
    private LinkedHashMap<String, Object> state(String content, String rule) {
        LinkedHashMap<String, Object> state = new LinkedHashMap<String, Object>();
        if (model.startsWith("respan/")) {
            LinkedHashMap<String, Object> sys = new LinkedHashMap<String, Object>();
            sys.put("role", "system");
            sys.put("content", rule);
            List<Object> input = new ArrayList<Object>();
            input.add(sys);
            state.put("input", input);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("role", "assistant");
            out.put("content", content);
            state.put("output", out);
        } else {
            state.put("rule", rule);
            state.put("content", content);
        }
        return state;
    }

    /** 兼容 answers 为 Map（name->answer）或数组（元素带 name）两种形态。 */
    static NoulReply parse(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        LinkedHashMap<String, Double> probs = new LinkedHashMap<String, Double>();
        Object answers = root.get("answers");
        if (answers instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) answers).entrySet()) {
                if (e.getValue() instanceof Map) {
                    Double p = probOf((Map<?, ?>) e.getValue());
                    if (p != null) probs.put(String.valueOf(e.getKey()), p);
                }
            }
        } else if (answers instanceof List) {
            for (Object o : (List<?>) answers) {
                if (o instanceof Map) {
                    Map<?, ?> a = (Map<?, ?>) o;
                    Object name = a.get("name");
                    Double p = probOf(a);
                    if (name instanceof String && p != null) probs.put((String) name, p);
                }
            }
        } else {
            throw new IllegalStateException("openrouter 响应缺 answers: " + body);
        }
        int in = 0, out = 0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = JevBackend.intOf(u.get("input_tokens"));
            out = JevBackend.intOf(u.get("output_tokens"));
        }
        return new NoulReply(probs, in, out, latencyMs, 0.0);
    }

    /** P(yes) 字段兼容 noul / probability / p；refusal 等无值题型返回 null。 */
    private static Double probOf(Map<?, ?> a) {
        Object p = a.get("noul");
        if (!(p instanceof Number)) p = a.get("probability");
        if (!(p instanceof Number)) p = a.get("p");
        return p instanceof Number ? ((Number) p).doubleValue() : null;
    }

    /** 解析 choice 响应：answers{"q":{type:"choice",choice,confidence[,probabilities]}}（兼容 Map/数组两种 answers 形态）。 */
    static NoulReply parseChoice(String body, long latencyMs) {
        Map<String, Object> root = Json.parseObject(body);
        Object answers = root.get("answers");
        Map<?, ?> a = null;
        if (answers instanceof Map) {
            if (((Map<?, ?>) answers).isEmpty()) {
                throw new IllegalStateException("openrouter choice 响应 answers 为空: " + body);
            }
            a = (Map<?, ?>) ((Map<?, ?>) answers).values().iterator().next();
        } else if (answers instanceof List && !((List<?>) answers).isEmpty()) {
            Object first = ((List<?>) answers).get(0);
            if (first instanceof Map) a = (Map<?, ?>) first;
        }
        if (a == null) {
            throw new IllegalStateException("openrouter choice 响应缺 answers: " + body);
        }
        Object c = a.get("choice");
        if (!(c instanceof String) || ((String) c).trim().isEmpty()) {
            throw new IllegalStateException("openrouter choice 响应缺 choice 字段: " + body);
        }
        String choice = ((String) c).trim();
        double conf = a.get("confidence") instanceof Number ? ((Number) a.get("confidence")).doubleValue() : 0.0d;
        int in = 0, out = 0;
        if (root.get("usage") instanceof Map) {
            Map<?, ?> u = (Map<?, ?>) root.get("usage");
            in = JevBackend.intOf(u.get("input_tokens"));
            out = JevBackend.intOf(u.get("output_tokens"));
        }
        return JevBackend.choiceReply(choice, conf, a.get("probabilities"), in, out, latencyMs, 0.0);
    }
}
