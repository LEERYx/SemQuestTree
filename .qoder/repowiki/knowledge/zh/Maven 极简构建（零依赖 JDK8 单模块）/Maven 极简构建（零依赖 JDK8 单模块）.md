---
kind: build_system
name: Maven 极简构建（零依赖 JDK8 单模块）
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
---

## 1. 使用的系统/方法

本项目采用 **Apache Maven** 作为唯一的构建工具，使用最简 POM，不引入任何插件或第三方依赖。源码为纯 Java（JDK 8），通过 `java` 命令直接运行主类，无需打包成可执行 JAR。

## 2. 关键文件

- `pom.xml`：唯一构建描述文件，声明 groupId=`org.example`、artifactId=`SemQuestTree`、version=`1.0-SNAPSHOT`，并设置 `maven.compiler.source/target=8`、`project.build.sourceEncoding=UTF-8`。
- `.mvn/`：存在但为空目录，未包含 `maven-wrapper.jar` 或 `maven.config`。
- 无 `Makefile`、`Dockerfile`、`*.sh` 构建脚本、`.github/workflows` CI 配置、`release` 流程脚本。

## 3. 架构与约定

- **单模块、零依赖**：POM 中无任何 `<dependencies>`，所有业务逻辑（LLM 后端、HTTP 客户端、JSON 处理等）均基于 JDK 标准库实现，因此构建产物仅为编译后的 class 文件。
- **源码布局**：遵循 Maven 默认约定 `src/main/java/org/example/...` + `src/test/java/...`。
- **运行方式**：由项目自身提供 `Demo.java` / `SemQuestServer.java` 等入口，通过 `java -cp target/classes org.example.SemQuestServer` 之类的方式启动（见 README 中的运行说明）。
- **版本策略**：固定为 `1.0-SNAPSHOT`，未见语义化版本发布流程或版本号注入机制。

## 4. 约定与约束

- 编码强制为 UTF-8（由 `project.build.sourceEncoding=UTF-8` 声明）。
- 编译器源/目标字节码级别锁定为 JDK 8（`maven.compiler.source/target=8`），确保向后兼容。
- 仓库内不存在 Docker 镜像构建、CI 流水线、自动化发布或跨平台交叉编译配置；部署文档（`docs/handoff-20261030-1539-semquesttree-awesome-submission.md`）仅记录手动在 macOS Apple M5 上运行的步骤，未定义标准化制品输出。

综上，该项目的构建系统是一个**最小化的 Maven 工程**，其“构建”本质上等价于 `mvn compile` 后直接用 `java` 运行主类，没有额外的打包、测试、发布管线。