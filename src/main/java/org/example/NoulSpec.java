package org.example;

/**
 * 一个 noul 问题规格：name 由外层 Map 的 key 决定。
 *
 * <p>jev 需要三段全非空（双边判据方言）；百炼/OpenRouter 只用 {@code yes} 作为 instructions，
 * {@code no} 忽略。yes/no 应写成事件级原子断言（见 schema）。
 */
public final class NoulSpec {

    /** 短指令/标签（即节点名，如「运费价格」）。jev 必填。 */
    public final String instructions;

    /** "是"侧原子断言（英文）。 */
    public final String yes;

    /** "否"侧断言（英文）。仅 jev 使用。 */
    public final String no;

    public NoulSpec(String instructions, String yes, String no) {
        if (instructions == null || instructions.trim().isEmpty())
            throw new IllegalArgumentException("instructions 不能为空");
        if (yes == null || yes.trim().isEmpty())
            throw new IllegalArgumentException("yes 判据不能为空");
        this.instructions = instructions;
        this.yes = yes;
        this.no = (no == null || no.trim().isEmpty()) ? autoNo(yes) : no;
    }

    /** 未提供 no 时自动生成否定侧：含汉字的 yes 配中文否定框架，否则英文框架。 */
    public static String autoNo(String yes) {
        for (int i = 0; i < yes.length(); i++) {
            if (Character.UnicodeScript.of(yes.charAt(i)) == Character.UnicodeScript.HAN) {
                return "并非以下情况：" + yes;
            }
        }
        return "It is not the case that: " + yes;
    }
}
