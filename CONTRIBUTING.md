**English** | [中文](CONTRIBUTING.zh.md)

# Contributing

## Engineering conventions

- **JDK**: Java 21 (`maven.compiler.release=21`). Local builds need JDK 21+.
- **Build**: `mvn verify` — must be green before merging. Includes unit + integration tests, a JaCoCo report and Spotless format checks.
- **Formatting**: Palantir Java Format. Run `mvn spotless:apply` before committing.
- **Module dependency direction** (violating it means architectural decay — see [docs/DESIGN.md](docs/DESIGN.md) §3):

  ```
  proxy → router → client → core
  providers/* → core (client discovers providers at runtime via ServiceLoader, no direct dependency on implementations)
  cache / callbacks → core
  ```

- **Commit format**: `<type>(<scope>): <subject>`, where type ∈ feat / fix / docs / refactor / test / chore and scope is the module name (e.g. `core`, `provider-openai`).
- **Tests**: provider transformers must have fixture-based golden tests; HTTP behavior uses WireMock — do not hit real provider APIs from unit tests.

## Adding a new provider

1. Create `litellm-providers/provider-<name>` under `litellm-providers/`, depending only on `litellm-core`.
2. Implement the `LlmProvider` SPI and register it via `META-INF/services`.
3. Keep the transformer (request/response mapping) as a pure function and add fixture-driven tests.
