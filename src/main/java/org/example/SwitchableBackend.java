package org.example;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * 可切换后端包装：持有 volatile 委托，树全程只认这个引用，
 * 运行时热切换 provider/model 时仅替换委托，不重建树。
 *
 * <p>切换只做凭据校验（hasKey）与元数据拉取（listModels），
 * 不触发任何推理调用——第一次 noul 时才真正用新后端。
 */
public final class SwitchableBackend implements DecisionBackend {

    private volatile DecisionBackend target;

    public SwitchableBackend(DecisionBackend initial) {
        this.target = initial;
    }

    /** 热切换委托。 */
    public void switchTo(DecisionBackend next) {
        this.target = next;
    }

    /** 当前委托。 */
    public DecisionBackend current() {
        return target;
    }

    @Override
    public String name() {
        return target.name();
    }

    @Override
    public NoulReply noul(String content, String rule, LinkedHashMap<String, NoulSpec> questions)
            throws IOException {
        return target.noul(content, rule, questions);
    }

    @Override
    public NoulReply choice(String content, String rule, String question,
                            LinkedHashMap<String, String> criteria) throws IOException {
        return target.choice(content, rule, question, criteria);
    }
}
