[English](CONTRIBUTING.md) | **中文**

# Contributing

## 工程规范

- **JDK**：Java 21（`maven.compiler.release=21`）。本地构建请使用 **JDK 21**——palantir-java-format 尚不兼容 JDK 25 的 javac 内部 API，在 JDK 25 上 `spotless:check` 会报 `NoSuchMethodError`。
- **构建**：`mvn verify`——必须全绿才能合并，包含单测、JaCoCo 报告与 Spotless 格式检查。
- **格式化**：Palantir Java Format，提交前运行 `mvn spotless:apply`。
- **模块依赖方向**（违反即架构腐化，见 [docs/DESIGN.md](docs/DESIGN.zh.md) §3）：

  ```
  proxy → router → client → core
  providers/* → core（client 通过 ServiceLoader 在运行时发现 provider，不直接依赖具体实现）
  cache / callbacks → core
  ```

- **Commit 约定**：`<type>(<scope>): <subject>`，type ∈ feat / fix / docs / refactor / test / chore，scope 用模块名（如 `core`、`provider-openai`）。
- **测试**：provider 转换器必须有 fixture 黄金用例；HTTP 行为用 WireMock，不在单测中调真实供应商 API。

## 新增 Provider

1. 在 `litellm-providers/` 下新建 `provider-<name>` 模块，只依赖 `litellm-core`。
2. 实现 `LlmProvider` SPI 并注册 `META-INF/services` 文件。
3. 转换器（请求/响应映射）保持纯函数，附 fixture 测试。
