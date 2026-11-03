package org.example;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 判据生成器：add 落兜底（树里没有能承接这条内容的类别）时，由通用生成模型为该层提出新分支（名字 + 判据），
 * 供 server 侧生长编排验收后入树。判定后端只判分不产文——写判据是另一种能力，与判定后端分离。
 *
 * <p>实现两个：
 * <ul>
 *   <li>{@link OpenRouterGenerator}——openrouter chat completions 端点（通用生成模型；
 *       凭据复用 openrouter.apikey，模型经 growth.model 配置，落兜底生长提示词经 growth.prompt 配置
 *       （空白回 DEFAULT_PROMPT），节点延伸提示词经 growth.extend.prompt 配置（空白回 DEFAULT_EXTEND_PROMPT，
 *       层级无关——不写死「第几层」，只约束「比父节点细一层」，任意深度可递复用）；
 *       免费模型即零成本生长；思考型模型可用 growth.maxTokens 限输出、growth.thinking 控推理力度，
 *       落兜底那一次生成从几十秒降到几秒）
 *   <li>{@link MockGenerator}——零 token 确定性生成（mock 判定沙箱与 E2E 用）：
 *       名字取触发样本的尾三字，验收时（mock 按子串匹配）查询含该名字即可命中
 * </ul>
 *
 * <p>生成只是建议：server 侧会在 growNode 的存量重判里查本条是否收编做验收，收编不了当场回滚，分支不会留在树里。
 */
public interface CriterionGenerator {

    /**
     * 生成提示词内置模板（growth.prompt 可覆盖；空白回此默认）。
     * 占位符：{parent}={层节点名}、{siblings}={现有分支清单行}、{count}/{samples}={落兜底内容行}、{maxNew}={上限}。
     */
    String DEFAULT_PROMPT = "你在维护一棵判据树（用自然语言判据给内容分类）。层「{parent}」现有分支：\n"
            + "{siblings}"
            + "以下 {count} 条内容落入了兜底（现有分支都不覆盖）：\n"
            + "{samples}"
            + "提出至多 {maxNew} 个新分支让这类内容有家可归。要求：\n"
            + "- 名字是一个类别词（不超过 6 个字）；yes 写这个类别的含义——它覆盖哪些主题或维度，用名词短语列举（示例：类别「职业身份」的 yes 写「工作地域，工作内容」这样的含义列举，不要写成「内容聚焦于……」那种描述什么话会进来的句子）；\n"
            + "- 含义须与现有分支互斥不重叠；若现有分支已能覆盖这些内容，branches 返回空数组。\n"
            + "只输出 JSON 对象：{\"branches\":[{\"name\":\"…\",\"yes\":\"…\"}]}";

    /**
     * 延伸生成内置模板（growth.extend.prompt 可覆盖；空白回此默认）。层级无关：不写死「第几层」，
     * 只约束「比父节点细一层」——对任意深度的拥挤节点（2、3、4…层）同一模板递复用。
     * 占位符：{parent}={节点名}、{parentYes}={该节点判据（子类公共上限）}、{count}={存量条数}、
     * {siblings}={现有子分支清单行（串行闭环每轮最新）}、{samples}={存量样本行（内容概述）}。
     */
    String DEFAULT_EXTEND_PROMPT = "你在维护一棵判据树（用自然语言判据给内容分类）。节点「{parent}」（含义：{parentYes}）下已积累 {count} 条内容，太拥挤了，需要向下延伸出更细的子类。\n"
            + "\n它现有的子分支（可能为空，已互斥不重叠）：\n"
            + "{siblings}"
            + "\n该节点下的内容样本：\n"
            + "{samples}"
            + "提出 1 个新子分支，把这批内容进一步细分。要求：\n"
            + "- 子类比「{parent}」细一层：{parentYes} 是它们的公共上限，子类只覆盖其中一块——不能与父类同粒度，也不能与现有子分支重叠；\n"
            + "- 优先挑内容样本里占比最大、内部又相似的一簇，让最多的内容有子类可归；\n"
            + "- 名字是一个类别词（不超过 6 个字）；yes 写这个子类的含义——它在父类基础上再往哪个具体方向收，用名词短语列举（示例：父类「职业身份」含义「工作地域，工作内容」，其子类「技术研发」的 yes 写「工程师，架构师，算法研究员」这样更细的岗位列举，不要写成「内容聚焦于……」那种句子）；\n"
            + "- 若现有子分支已能覆盖这些内容，或内容之间找不到更细的共同侧面，branches 返回空数组。\n"
            + "只输出 JSON 对象：{\"branches\":[{\"name\":\"…\",\"yes\":\"…\"}]}";

    /** 一次生成的产物与账单：branches 为空 = 生成方认为无值得生长的区分。 */
    final class GrowthResult {
        public final List<String[]> branches = new ArrayList<String[]>();   // 每项 {name, yes}
        public int inputTokens;
        public int outputTokens;
        public long latencyMs;
    }

    /**
     * 为层 parentName 提出至多 maxNew 个新分支。
     *
     * @param siblings 现有兄弟分支（每项 {name, yes}，判据空回名字）——生成须与其不重叠
     * @param samples  落兜底的正文（同步生长时就是触发本条内容）
     */
    GrowthResult generate(String parentName, List<String[]> siblings, List<String> samples, int maxNew)
            throws IOException;

    /**
     * 延伸生成：为拥挤节点 parentName 提出 1 个更细的子分支（串行闭环每轮一次，siblings 恒为最新状态）。
     * parentYes=父节点判据（子类的公共上限，提示模型粒度要再收一层）；totalDocs=待整理存量条数；
     * samples=存量样本（内容概述，模型从中挑最大且内部相似的一簇）。
     */
    GrowthResult generateExtend(String parentName, String parentYes, int totalDocs,
                                List<String[]> siblings, List<String> samples) throws IOException;

    /** openrouter chat completions 通用生成（与决策端点同平台不同端点，同一把 key）。 */
    final class OpenRouterGenerator implements CriterionGenerator {
        private final String url;
        private final String model;
        private final String apiKey;
        private final int timeoutMs;
        private final String promptTemplate;   // growth.prompt 配置（空白回 DEFAULT_PROMPT）
        private final String extendTemplate;   // growth.extend.prompt 配置（空白回 DEFAULT_EXTEND_PROMPT）
        private final int maxTokens;           // growth.maxTokens（<=0 = 不发 max_tokens，沿用模型默认）
        private final String thinking;         // growth.thinking（none/low/medium/high；空白 = 不发 reasoning）

        public OpenRouterGenerator(String url, String model, String apiKey, int timeoutMs, String promptTemplate,
                                   int maxTokens, String thinking, String extendPrompt) {
            this.url = url;
            this.model = model;
            this.apiKey = apiKey;
            this.timeoutMs = timeoutMs;
            this.promptTemplate = promptTemplate != null && !promptTemplate.trim().isEmpty()
                    ? promptTemplate.trim() : DEFAULT_PROMPT;
            this.extendTemplate = extendPrompt != null && !extendPrompt.trim().isEmpty()
                    ? extendPrompt.trim() : DEFAULT_EXTEND_PROMPT;
            this.maxTokens = maxTokens;
            this.thinking = thinking != null && !thinking.trim().isEmpty() ? thinking.trim() : null;
        }

        public GrowthResult generate(String parentName, List<String[]> siblings, List<String> samples, int maxNew)
                throws IOException {
            String prompt = promptTemplate
                    .replace("{parent}", parentName)
                    .replace("{siblings}", siblingsBlock(siblings))
                    .replace("{count}", String.valueOf(samples.size()))
                    .replace("{samples}", samplesBlock(samples))
                    .replace("{maxNew}", String.valueOf(maxNew));
            return callModel(prompt);
        }

        public GrowthResult generateExtend(String parentName, String parentYes, int totalDocs,
                                           List<String[]> siblings, List<String> samples) throws IOException {
            String prompt = extendTemplate
                    .replace("{parent}", parentName)
                    .replace("{parentYes}", parentYes == null ? "" : parentYes)
                    .replace("{count}", String.valueOf(totalDocs))
                    .replace("{siblings}", siblingsBlock(siblings))
                    .replace("{samples}", samplesBlock(samples));
            return callModel(prompt);
        }

        /** 现有子分支清单行（两种生成的 {siblings} 占位符共用）。 */
        private static String siblingsBlock(List<String[]> siblings) {
            StringBuilder sib = new StringBuilder();
            if (siblings.isEmpty()) {
                sib.append("（尚无分支）\n");
            } else {
                for (String[] s : siblings) {
                    sib.append("- ").append(s[0]).append("：").append(s[1]).append('\n');
                }
            }
            return sib.toString();
        }

        /** 样本编号行（两种生成的 {samples} 占位符共用）。 */
        private static String samplesBlock(List<String> samples) {
            StringBuilder smp = new StringBuilder();
            for (int i = 0; i < samples.size(); i++) {
                smp.append(i + 1).append(". ").append(samples.get(i)).append('\n');
            }
            return smp.toString();
        }

        /** 共用请求与解析：发 chat completions、记 token 账单、剥栅栏、解析 branches（空/结构不符=无值得生长的区分）。 */
        private GrowthResult callModel(String prompt) throws IOException {
            Map<String, Object> m0 = new java.util.LinkedHashMap<String, Object>();
            m0.put("role", "user");
            m0.put("content", prompt);
            java.util.List<Object> msgs = new ArrayList<Object>();
            msgs.add(m0);
            Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
            body.put("model", model);
            body.put("messages", msgs);
            // 提速护栏（均「配了才发」，不配则请求体与原来逐字一致）：耗时几乎正比于输出 token 数，
            // 思考型模型的思维链常占输出的九成，限住这两项即可把落兜底那一次生成从几十秒压到几秒。
            if (maxTokens > 0) body.put("max_tokens", maxTokens);
            if (thinking != null) {
                Map<String, Object> rs = new java.util.LinkedHashMap<String, Object>();
                rs.put("effort", thinking);   // OpenRouter 统一 reasoning 参数，下发给各上游
                body.put("reasoning", rs);
            }

            long start = System.nanoTime();
            String resp = Http.post(url, apiKey, Json.write(body), timeoutMs);
            GrowthResult out = new GrowthResult();
            out.latencyMs = (System.nanoTime() - start) / 1_000_000L;

            Map<String, Object> ro = Json.parseObject(resp);
            Object usage = ro.get("usage");
            if (usage instanceof Map) {
                Object p = ((Map<?, ?>) usage).get("prompt_tokens");
                Object c = ((Map<?, ?>) usage).get("completion_tokens");
                if (p instanceof Number) out.inputTokens = ((Number) p).intValue();
                if (c instanceof Number) out.outputTokens = ((Number) c).intValue();
            }
            Object choices = ro.get("choices");
            if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
                throw new IllegalStateException("生成响应缺 choices: " + resp.substring(0, Math.min(200, resp.length())));
            }
            Object first = ((List<?>) choices).get(0);
            String content = null;
            String finish = null;
            if (first instanceof Map) {
                Object fr = ((Map<?, ?>) first).get("finish_reason");
                if (fr instanceof String) finish = (String) fr;
                Object mo = ((Map<?, ?>) first).get("message");
                if (mo instanceof Map) {
                    Object co = ((Map<?, ?>) mo).get("content");
                    if (co instanceof String) content = (String) co;
                }
            }
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("生成响应缺 message.content"
                        + (finish != null ? "（finish_reason=" + finish + "）" : "")
                        + ("length".equals(finish)
                        ? "：疑被 growth.maxTokens 截断（只出了思维链没出正文），调大 growth.maxTokens 或收紧 growth.thinking" : ""));
            }
            content = stripFences(content.trim());
            Map<String, Object> parsed = Json.parseObject(content);
            Object arr = parsed.get("branches");
            if (!(arr instanceof List)) return out;   // 空或结构不符：视为无值得生长的区分
            for (Object o : (List<?>) arr) {
                if (!(o instanceof Map)) continue;
                Object n = ((Map<?, ?>) o).get("name");
                Object y = ((Map<?, ?>) o).get("yes");
                if (n instanceof String && !((String) n).trim().isEmpty()
                        && y instanceof String && !((String) y).trim().isEmpty()) {
                    out.branches.add(new String[]{((String) n).trim(), ((String) y).trim()});
                }
            }
            return out;
        }

        /** 剥 markdown 代码栅栏（```json … ```）：部分模型爱包一层。 */
        private static String stripFences(String s) {
            if (s.startsWith("```")) {
                int nl = s.indexOf('\n');
                if (nl > 0) s = s.substring(nl + 1);
                int end = s.lastIndexOf("```");
                if (end >= 0) s = s.substring(0, end);
            }
            return s.trim();
        }
    }

    /** 零 token 确定性生成（mock 判定链路配套）：名字取触发样本尾三字，验收按子串匹配即可命中。 */
    final class MockGenerator implements CriterionGenerator {
        public GrowthResult generate(String parentName, List<String[]> siblings, List<String> samples, int maxNew) {
            return mockBranch(siblings, samples);
        }

        public GrowthResult generateExtend(String parentName, String parentYes, int totalDocs,
                                           List<String[]> siblings, List<String> samples) {
            return mockBranch(siblings, samples);
        }

        /** 两种生成的共用 mock 语义：样本尾三字做名字，与现有分支重名视作已覆盖（返回空=闭环终止）。 */
        private static GrowthResult mockBranch(List<String[]> siblings, List<String> samples) {
            GrowthResult out = new GrowthResult();
            if (samples == null || samples.isEmpty()) return out;
            String s = samples.get(samples.size() - 1).trim();
            if (s.length() < 2) return out;   // 过短样本无区分价值，不生长
            String name = s.length() > 3 ? s.substring(s.length() - 3) : s;
            for (String[] sib : siblings) {
                if (sib[0].equals(name)) return out;   // 与现有分支重名：视作已覆盖，不生长
            }
            out.branches.add(new String[]{name, "内容表达与" + name + "相关的事"});
            return out;
        }
    }
}
