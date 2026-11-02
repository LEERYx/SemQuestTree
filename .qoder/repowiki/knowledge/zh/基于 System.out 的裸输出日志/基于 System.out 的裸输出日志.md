---
kind: logging_system
name: 基于 System.out 的裸输出日志
category: logging_system
scope:
    - '**'
source_files:
    - src/main/java/org/example/SemQuestServer.java
    - src/main/java/org/example/Demo.java
    - src/main/resources/
    - pom.xml
---

## 1. 使用的系统/方法

仓库没有引入任何日志框架（无 SLF4J、Logback、Log4j2、java.util.logging 等依赖），也没有 `src/main/resources` 下的日志配置文件。所有“日志”输出均通过 JDK 原生的 `System.out.println` / `System.out.printf` 直接打印到标准输出。

在 `pom.xml` 中未声明任何 logging 相关依赖，`grep_code` 对 `*.java` 的搜索仅匹配到 `System.out` 调用，未发现任何 logger 实例化或框架 import。

## 2. 关键文件

- `src/main/java/org/example/SemQuestServer.java`：服务启动、热重载配置、表加载等运行时状态全部通过 `System.out.println` 输出，并使用 `[启动]`、`[热载]`、`[reseed]` 等方括号前缀作为简易级别标记。
- `src/main/java/org/example/Demo.java`：演示入口，使用 `System.out` 打印交互结果与提示。
- `src/main/resources/`：空目录，无任何日志配置文件。

## 3. 架构与约定

- **无分层**：业务类与演示类直接耦合 `System.out`，不存在统一的 Logger 抽象或工具类。
- **伪级别标记**：通过字符串前缀模拟级别语义，如 `[启动]`、`[热载]`、`[reseed]`，而非真正的 log level。
- **无结构化字段**：日志为纯文本拼接，不包含 JSON、MDC、traceId、请求 ID 等结构化字段。
- **无 sink 路由**：所有输出统一走 `System.out`，无法按级别或模块分流到不同目的地。
- **错误处理**：未见 `System.err` 的使用；异常路径通常以 `System.out` 打印回退信息（如 Demo 中 jev/OpenRouter 凭据缺失时的降级提示）。

## 4. 约定与约束

- 观察到的约定：所有运行期诊断输出均通过 `System.out.println` / `System.out.printf` 输出，并以中文为主。
- 约束来源：仓库未声明任何日志框架依赖，`resources` 下无配置文件——这构成事实上的约束：新增日志点应沿用现有 `System.out` 方式，并建议复用 `[启动]` / `[热载]` 等方括号前缀风格以保持可读性。
- 该方案适合单机演示与服务，但缺乏可观测性能力（无分级、无异步、无外部聚合）。