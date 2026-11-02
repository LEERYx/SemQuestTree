---
kind: external_dependency
name: Jev 决策模型后端
slug: jev
category: external_dependency
category_hints:
    - vendor_identity
    - sdk_real_api
    - client_constraint
scope:
    - '**'
source_files:
    - src/main/java/org/example/JevBackend.java
    - src/main/java/org/example/Config.java
    - semquest.properties.example
---

### 身份与角色
Jev 是本项目主要对接的决策模型提供商，提供 noul 多标签并发判定和 choice 单路径判定两种模式。

### 集成方式
- 通过 `JevBackend.java` 实现 DecisionBackend 接口；鉴权凭据来自 `semquest.properties` 的 `jev.*` 键（url / model / apikey）。
- 当未配置 jev 凭据时自动回退到 openrouter，再回退到 mock。
- 支持通过 `jev.models` 手动指定模型列表。

### 协议约束
- Jev 采用三段式判据方言：规则（ruleAdd/ruleSearch）、输入（inputs）、选项（options）+ true/false 判据。
- respan 族免费模型走 `{input,output}` 消息协议，仅接受 noul 模式；choice 表会快速失败并报错，需切换到 jev / elephant 家族模型。
- 门控参数 `noulGate`（默认 0.7）控制每层分支是否继续下探；`choiceGate`（默认 0.6）为采纳线。

### 注意事项
- 具体 API 调用细节（消息体结构、头部）需对照 Jev 官方文档确认。