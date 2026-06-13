# 发布指南（Maven Central）

发布到 Maven Central 的完整流程。**所有需要凭证的步骤都在 GitHub Actions 里跑**，本地只能 dry-run。

## 一次性准备（首次发布前做）

### 1. 注册 Sonatype Central 账号

1. 打开 https://central.sonatype.com，用 GitHub 账号登录（推荐——会自动验证你的 `io.github.vincentye` 命名空间）。
2. 进入 [Namespaces](https://central.sonatype.com/account)：你应该能看到 `io.github.vincentye`（与 GitHub 用户名匹配，自动 verified）。
3. 进入 [Settings → User Tokens](https://central.sonatype.com/account)，点 "Generate User Token"，保存得到的 **username** 和 **password**。

> 如果显示的是 `io.github.vincent-ye` 而不是 `io.github.vincentye`，需要把 pom 里所有 `<groupId>io.github.vincentye</groupId>` 改为 `<groupId>io.github.vincent-ye</groupId>`（Maven 接受 groupId 里的连字符）。

### 2. 生成 GPG 签名密钥

Maven Central 要求所有 jar 经 GPG 签名。

```bash
# 生成密钥（一路按提示填，邮箱用你的 GitHub 邮箱）
gpg --full-generate-key
# 选 RSA and RSA、4096 位、不过期或填一个合理过期、邮箱用 GitHub 公开邮箱

# 列出密钥，记下长 ID（KEYID 是最后 16 位）
gpg --list-secret-keys --keyid-format=long
# 示例输出：sec   rsa4096/AB12CD34EF567890 ...

# 把公钥发布到 keyserver（Central 会从这里验证签名）
gpg --keyserver keys.openpgp.org --send-keys AB12CD34EF567890

# 导出私钥（用于 GitHub Secrets）
gpg --armor --export-secret-keys AB12CD34EF567890 > private.key
```

### 3. 把凭证加到 GitHub Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret，依次加 4 个：

| 名字 | 值 |
|------|------|
| `CENTRAL_USERNAME` | 第 1 步生成的 user token username |
| `CENTRAL_PASSWORD` | 第 1 步生成的 user token password |
| `GPG_PRIVATE_KEY` | 第 2 步生成的 `private.key` 文件全部内容（含 `-----BEGIN/END PGP PRIVATE KEY BLOCK-----`） |
| `GPG_PASSPHRASE` | 第 2 步生成密钥时设置的 passphrase |

完事后**删掉本地的 `private.key` 文件**（`shred -u private.key` 或 `rm -P`）。

## 每次发布的流程

### 选项 A：自动化（推荐）

1. 在 GitHub 仓库进 Actions → "Release to Maven Central" → "Run workflow"
2. 输入版本号，如 `0.1.0`
3. workflow 会自动：
   - 把所有 pom 的版本设为 `0.1.0`
   - 跑全套测试（包括 Redis Testcontainers）
   - GPG 签名所有 jar
   - 上传到 Central（`autoPublish=true`，自动通过校验后立即发布）
   - 推 git tag `v0.1.0` 并创建 GitHub Release

发布后 5–30 分钟在 https://search.maven.org 能搜到。

### 选项 B：手动本地发布（不推荐——容易泄漏密钥）

需要本地配 `~/.m2/settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_USERNAME</username>
      <password>YOUR_CENTRAL_PASSWORD</password>
    </server>
  </servers>
</settings>
```

然后：

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
mvn -Prelease clean deploy -Dgpg.passphrase=YOUR_GPG_PASSPHRASE
```

## 本地干跑（不上传）

提交前想验证 release profile 没坏：

```bash
# 产出 sources + javadoc jar，不签名也不上传
mvn -Prelease package -DskipTests

# 产物：litellm-core/target/litellm-core-0.1.0-sources.jar 等
```

如果配了本地 GPG 密钥，也可以试签名（仍不上传）：

```bash
mvn -Prelease verify -Dgpg.passphrase=YOUR_GPG_PASSPHRASE
```

## 发布的工件

`litellm-proxy` 模块 **不发** Maven Central（fat jar 不适合作为依赖；它走 Docker 镜像分发）。发布的库模块：

- `io.github.vincentye:litellm-bom` — 版本对齐 BOM
- `io.github.vincentye:litellm-core` — 统一类型 + 异常 + SPI
- `io.github.vincentye:litellm-client` — SDK 门面
- `io.github.vincentye:litellm-router` — 路由层
- `io.github.vincentye:litellm-cache` — 缓存抽象
- `io.github.vincentye:litellm-callbacks` — 回调抽象
- `io.github.vincentye:provider-openai` / `provider-anthropic` / `provider-azure-openai` / `provider-mistral` / `provider-gemini` / `provider-bedrock` — 各供应商适配

## 故障排查

| 错误 | 原因 / 解决 |
|------|------|
| `Namespace not verified` | 1.2 步：Central Portal 上没看到 `io.github.vincentye`。GitHub 账号验证失败，看你的 GitHub 用户名大小写是否一致。 |
| `Invalid signature` | 公钥没发到 keyserver，或者发了但还没传播——`gpg --keyserver keys.openpgp.org --send-keys` 后等几分钟。 |
| `Version 0.x.x already exists` | Central 的版本是 immutable 的，不能覆盖。bump 版本重发。 |
| `401 Unauthorized` | `CENTRAL_USERNAME/PASSWORD` 不对，或用了登录账号密码而不是 user token。 |

## 跳过的步骤（路线图 M6 验收项里需要你的人为参与）

- **性能压测**：网关 P99 自身开销 < 15ms 的验收需要在你的实际部署环境跑（裸机/容器/带 Redis 等），仓库里没有压测脚本。
- **真实发布到 Central**：本仓库的代码已经备好，等你配完上述凭证按"选项 A"跑一次即可。
