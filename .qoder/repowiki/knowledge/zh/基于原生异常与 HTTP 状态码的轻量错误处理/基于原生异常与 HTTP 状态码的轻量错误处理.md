---
kind: error_handling
name: 基于原生异常与 HTTP 状态码的轻量错误处理
category: error_handling
scope:
    - '**'
source_files:
    - src/main/java/org/example/Http.java
    - src/main/java/org/example/SemQuestServer.java
    - src/main/java/org/example/BailianBackend.java
    - src/main/java/org/example/JevBackend.java
    - src/main/java/org/example/OpenRouterBackend.java
    - src/main/java/org/example/MockBackend.java
    - src/main/java/org/example/Json.java
    - src/main/java/org/example/NoulSpec.java
    - src/main/java/org/example/Config.java
---

## 1. 采用的方式

本项目是零依赖 JDK 8+ 服务，没有引入任何第三方错误框架（无自定义 `Exception` 子类、无错误码枚举、无 `try/catch/finally` 之外的中间件）。错误处理完全基于 Java 标准异常类型：

- `IOException`：用于 I/O 失败、网络请求失败、上游凭据缺失等**可恢复的外部错误**。
- `IllegalStateException`：用于解析上游 JSON 响应结构不合法等**不可恢复的内部契约违反**。
- `IllegalArgumentException`：用于请求参数校验失败（如 `mode` 非法、`instructions`/`yes` 为空、未知后端名）。
- `NumberFormatException`：在 `Config.java` 和处理器中捕获，用于把用户输入的非数字参数转为 400 错误。

HTTP 层使用 JDK 内置 `com.sun.net.httpserver.HttpServer`，没有全局异常处理器；每个 `HttpHandler.handle()` 方法内自行 try-catch 业务异常并返回 `{"error": ...}` 形式的 JSON 响应体，配合 200/400/404/405/500 状态码区分语义。

## 2. 关键文件

- `src/main/java/org/example/Http.java` — 唯一网络 I/O 入口，非 200 一律抛 `IOException("HTTP <code>: <body>")`。
- `src/main/java/org/example/SemQuestServer.java` — 所有 HTTP Handler（`StaticHandler`、`TablesHandler`、`TableRouter`、`TreeHandler`、`LeafHandler`、`DocsHandler`、`NodeHandler`、`GateHandler`、`ModelsHandler`、`ModelHandler`），集中负责把业务异常映射为 HTTP 状态码 + JSON error 字段。
- `src/main/java/org/example/BailianBackend.java` / `JevBackend.java` / `OpenRouterBackend.java` / `MockBackend.java` — 决策后端实现，抛出 `IOException`（缺凭据/IO 失败）或 `IllegalStateException`（上游响应结构不符）。
- `src/main/java/org/example/Json.java` — 内部 JSON 解析器，对非法 JSON 抛 `IllegalArgumentException`。
- `src/main/java/org/example/NoulSpec.java` — 对 `NoulSpec` 构造参数做非空校验，抛 `IllegalArgumentException`。
- `src/main/java/org/example/Config.java` — 配置加载，用 `catch (Exception e)` / `catch (NumberFormatException e)` 吞掉解析异常并回退默认值。

## 3. 架构与约定

### 3.1 异常分类约定

| 异常类型 | 含义 | 典型位置 |
|---|---|---|
| `IOException` | 外部系统不可用 / 网络失败 / 缺少 API Key | `Http.post/get`、各 Backend 的 `hasKey()` 检查、`loadTables` 文件读取 |
| `IllegalStateException` | 上游响应结构不符合预期（解析阶段） | `BailianBackend.parse`、`parseChoice`、`JevBackend.parse`、`parseChoice` |
| `IllegalArgumentException` | 调用方参数非法（客户端或内部调用） | `Demo.createBackend`、`NoulSpec` 构造、`SemQuestServer` 各 Handler 的参数校验 |
| `NumberFormatException` | 查询参数不是整数 | `DocsHandler` 解析 `limit`/`offset`、`Config.getInt` |

### 3.2 HTTP 层错误映射规则

每个 Handler 遵循同一模式：

```java
try {
    // 业务逻辑
} catch (IllegalArgumentException e) {
    json(ex, 400, "{\"error\":" + Json.str(e.getMessage()) + "}");
}
```

- 400：参数校验失败（`IllegalArgumentException`）。
- 404：资源不存在（表/节点/动作未知）——直接 `json(ex, 404, ...)`。
- 405：HTTP 方法不被允许——直接 `ex.sendResponseHeaders(405, -1)`。
- 500：持久化失败（如 `persistTable` 抛 `IOException`）——在 `NodeHandler.POST` 中显式 catch `IOException` 后返回 500。
- 未捕获异常：由于 `handle(HttpExchange)` 声明 `throws IOException`，未被显式捕获的 `IOException` 会由 `HttpServer` 默认处理（通常返回 500），但项目通过在各 Handler 内 try-catch 尽量避免这种情况。

### 3.3 启动期容错

`loadTables` 对每个表文件单独 try-catch：

```java
for (Path f : files) {
    try {
        loaded.add(loadTable(m, backend, f));
    } catch (Exception e) {
        System.out.println("[载入] 跳过坏表文件 " + f.getFileName() + ": " + e.getMessage());
    }
}
```

即单个损坏的表文件不会阻止服务启动，仅打印日志并跳过。配置热载 watcher 同样如此：

```java
} catch (InterruptedException e) {
    return;
} catch (Exception e) {
    System.out.println("[热载] 失败，保留旧配置: " + e.getMessage());
}
```

### 3.4 无全局中间件

项目中不存在统一的异常拦截器或全局错误处理器。错误处理是**分散在每个 Handler 的方法体内**，属于“就地捕获 + 就地转 HTTP 响应”的模式。这使错误路径短小直接，但也意味着新增 Handler 时必须手动添加 try-catch 块。

### 3.5 无 panic/recover

Java 中没有 panic/recover 概念；项目也未使用 `Thread.setDefaultUncaughtExceptionHandler` 之类的全局钩子。未捕获异常直接交给 `HttpServer` 默认行为。

## 4. 约定与约束

- **对外部 I/O 统一抛 `IOException`**：`Http.java` 注释明确约定 `非 200 抛 IOException（带状态码与响应体，截断）`，所有 Backend 都遵循此约定。
- **上游响应结构破坏抛 `IllegalStateException`**：`BailianBackend.parse`、`parseChoice` 以及 `JevBackend.parse`、`parseChoice` 在检测到上游 JSON 缺少 `answers`/`choice` 字段时抛 `IllegalStateException`，表示这是代码契约被违反而非普通 IO 错误。
- **参数校验抛 `IllegalArgumentException`**：`NoulSpec` 强制 `instructions` 与 `yes` 非空；`Demo.createBackend` 对未知 provider 抛 `IllegalArgumentException`；各 Handler 对 `mode`、`name`、数值参数等做同风格校验。
- **HTTP 错误响应体统一为 `{"error": <字符串>}`**：所有 4xx/5xx 响应体都通过 `Json.str(...)` 包裹消息，前端可直接取 `error` 字段展示。
- **启动期与热载期异常静默降级**：`loadTables` 和配置热载线程只记录日志并继续运行，不中断服务——这是项目明确实现的容错策略。
- **无自定义错误类型**：全仓未发现任何继承自 `Exception` 的业务异常类，也没有错误码常量定义。