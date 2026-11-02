---
kind: external_dependency
name: OpenRouter 决策模型后端
slug: openrouter
category: external_dependency
category_hints:
    - vendor_identity
    - auth_protocol
scope:
    - '**'
source_files:
    - src/main/java/org/example/OpenRouterBackend.java
    - src/main/java/org/example/Config.java
    - semquest.properties.example
---

### 身份与角色
OpenRouter 是本项目支持的决策模型（LLM）提供商之一，通过 `backend=openrouter` 切换使用。项目同时支持 jev、bailian（预留）、mock 四种后端。

### 集成方式
- 通过 `OpenRouterBackend.java` 实现统一的 DecisionBackend 接口；鉴权凭据从 `semquest.properties` 的 `openrouter.*` 键注入（url / model / apikey），由 `Config.java` 加载。
- 支持通过 `openrouter.models` 手动指定模型列表，否则从上游拉取。
- 运行时可在 UI 中热切换后端与模型，无需重启服务。

### 认证协议
需要用户在 OpenRouter 平台申请 API Key，填入 `openrouter.apikey`；请求携带该 key 调用其决策端点。具体签名/头部格式以 OpenRouter 官方文档为准。

### 注意事项