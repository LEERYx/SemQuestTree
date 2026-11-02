---
kind: configuration_system
name: 基于 Properties + JVM 系统属性的热重载配置系统
category: configuration_system
scope:
    - '**'
source_files:
    - src/main/java/org/example/Config.java
    - src/main/java/org/example/SemQuestServer.java
    - semquest.properties.example
---

## 1. 采用的方案

项目使用自实现的 `org.example.Config`（`src/main/java/org/example/Config.java`）作为唯一配置入口，底层基于 JDK `java.util.Properties`，通过 UTF-8 编码的 `.properties` 文件加载键值对。没有引入任何第三方配置框架（如 Spring Boot、Lightbend Config、Hikari 等），属于零依赖实现。

## 2. 关键文件

- `src/main/java/org/example/Config.java` — 配置加载、类型转换、热重载核心类
- `semquest.properties.example` — 配置键示例模板（二进制文件，无法直接读取）
- `src/main/java/org/example/SemQuestServer.java` — 启动时消费配置并注册热重载 watcher
- `pom.xml` — 构建入口（无额外配置依赖声明）

## 3. 架构与约定

### 3.1 配置源优先级

`Config.get(key, dft)` 按以下顺序解析（见第 88–92 行）：

1. JVM 系统属性 `-Dsemquest.<key>=value`
2. `semquest.properties` 文件中同名 key
3. Java 代码传入的默认值 `dft`

系统属性前缀固定为 `semquest.`，配置文件路径可通过 `-Dsemquest.config=<path>` 覆盖，默认值为根目录下的 `semquest.properties`。

### 3.2 配置项分组

从 `SemQuestServer.main()` 和 `promptsOf()` 可归纳出以下键命名空间：

| 命名空间 | 用途 | 示例键 |
|---|---|---|
| `server.*` | 服务元参数 | `server.port`、`server.data`、`server.viz`、`server.tables` |
| `timeoutMs` | LLM 调用超时（毫秒） | — |
| `noulGate` / `choiceGate` | noul 多选门控、choice 单选采纳线 | — |
| `backend` | 首选决策后端 provider | — |
| `schema` | 旧版 schema 迁移路径 | — |
| `prompt.noul.*` | noul 模式判定提示词 | `prompt.noul.add.instructions`、`prompt.noul.search.criterion` |
| `prompt.choice.*` | choice 模式判定提示词 | `prompt.choice.add.root`、`prompt.choice.other` |
| `prompt.rule.*` | 树级规则文案 | `prompt.rule.add`、`prompt.rule.search` |
| `questCacheSize` | 查询缓存容量 | — |

所有数值型配置通过 `getInt()` / `getDouble()` 解析，解析失败回退到默认值（不抛异常）。

### 3.3 热重载机制

`SemQuestServer` 在启动后创建一个守护线程 `config-hot-reload`，每 2 秒轮询 `semquest.config` 指定文件的 `lastModified()`。当 mtime 变化时调用 `Config.reload()`：

- `reload()` 原子地用新 `Properties` 替换内部 `volatile Properties p`（读路径无锁）。
- 如果文件不存在或 IO 异常（例如编辑器半写入），保留旧配置并返回 `false`。
- 热重载生效的配置项包括：`noulGate`、`choiceGate`、`questCacheSize`、`prompt.*`、`timeoutMs`、凭据及模型清单、各后端 url/model。
- **不会**热重载：`server.port`、`server.data`、`server.viz`、`server.tables`、`schema`、`backend` 首选——这些是进程级参数，重启才生效。

## 4. 约定与约束

- **单一配置文件**：所有运行时配置集中在一个 `.properties` 文件（注释中明确“所有配置集中在一个文件”），不支持多环境 profile 或多文件合并。
- **键名规范**：JVM 系统属性必须以 `semquest.` 为前缀；配置文件 key 不带该前缀。
- **类型安全**：新增配置必须通过 `Config.getInt()` / `getDouble()` / `get()` 访问，禁止直接反射读取 `Properties`。
- **默认值兜底**：每个 key 都有代码内默认值，缺少配置文件时服务仍可启动（仅日志显示未加载）。
- **热重载边界**：只有被 watcher 显式处理的 key 才会热生效；其余 key 修改后需重启进程。
- **并发安全**：`Config` 内部 `Properties` 字段为 `volatile`，`reload()` 同步方法保证原子替换；`get()` 系列无锁读。
- **容错策略**：`reload()` 捕获所有异常返回 `false`，避免一次坏文件导致整个服务降级；数值解析异常直接回退默认值。
- **字符集**：配置文件强制以 UTF-8 解码（`Charset.forName("UTF-8")`）。