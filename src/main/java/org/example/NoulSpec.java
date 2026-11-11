package org.example;

/**
 * 一个 noul 问题规格：name 由外层 Map 的 key 决定。
 *
 * <p>jev 需要三段全非空（双边判据方言）；百炼/OpenRouter 只用 {@code yes} 作为 instructions，
 * {@code no} 忽略。yes/no 应写成事件级原子断言（见 schema）。
 *
 * <p>no 断言不再在代码内自动造文：调用方未提供时由 {@link #autoNo} 按 prompt.noul.no.{han,latin}
 * 配置的提示词文件模板生成（{yes}=判据原文，模板是否含汉字由构造器按 yes 自动选择）。
 */
public final class NoulSpec {

    /** 短指令/标签（即节点名，如「运费价格」）。jev 必填。 */
    public final String instructions;

    /** "是"侧原子断言（英文）。 */
    public final String yes;

    /** "否"侧断言（英文）。仅 jev 使用。 */
    public final String no;

    /**
     * @param noHanTemplate  yes 含汉字时的 no 侧模板（prompt.noul.no.han 文件内容，含 {yes}）
     * @param noLatinTemplate yes 不含汉字时的 no 侧模板（prompt.noul.no.latin 文件内容，含 {yes}）
     */
    public NoulSpec(String instructions, String yes, String no,
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
        this.no = (no == null || no.trim().isEmpty()) ? autoNo(yes, noHanTemplate, noLatinTemplate) : no.trim();
    }

    /** no 侧自动生成：按 yes 是否含汉字选中/英模板（{yes}=判据原文；模板来自 prompt.noul.no.* 提示词文件）。 */
    public static String autoNo(String yes, String hanTemplate, String latinTemplate) {
        for (int i = 0; i < yes.length(); i++) {
            if (Character.UnicodeScript.of(yes.charAt(i)) == Character.UnicodeScript.HAN) {
                return hanTemplate.replace("{yes}", yes == null ? "" : yes);
            }
        }
        return latinTemplate.replace("{yes}", yes == null ? "" : yes);
    }
}
