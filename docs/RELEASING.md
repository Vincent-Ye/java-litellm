**English** | [中文](RELEASING.zh.md)

# Release guide (Maven Central)

End-to-end process for publishing to Maven Central. **All credential-handling steps run inside GitHub Actions**; locally you can only dry-run.

## One-time setup (before the first release)

### 1. Register a Sonatype Central account

1. Go to https://central.sonatype.com and sign in with your GitHub account (recommended — it auto-verifies your `io.github.vincent-ye` namespace).
2. Check [Namespaces](https://central.sonatype.com/account): you should see `io.github.vincent-ye` (matching your GitHub username, automatically verified).
3. Go to [Settings → User Tokens](https://central.sonatype.com/account) and click **Generate User Token**. Save the **username** and **password** the dialog shows.

> Maven groupIds allow hyphens (Java package names don't — they're a different rule), so `io.github.vincent-ye` is fine as a groupId. The Java package (`dev.javalitellm.*`) does not need to match the groupId.

### 2. Generate a GPG signing key

Maven Central requires every jar to be GPG-signed.

```bash
# Generate a key (interactive; pick RSA and RSA, 4096 bits, no expiry or a reasonable one,
# use your GitHub public email or any real email)
gpg --full-generate-key

# List keys and grab the long ID (last 16 hex chars)
gpg --list-secret-keys --keyid-format=long
# Sample output: sec   rsa4096/AB12CD34EF567890 ...

# Publish the public key to a keyserver (Central will fetch from here to verify signatures)
gpg --keyserver keys.openpgp.org --send-keys AB12CD34EF567890

# Export the private key (for the GitHub Secret)
gpg --armor --export-secret-keys AB12CD34EF567890 > private.key
```

### 3. Add credentials to GitHub Secrets

In your repo → Settings → Secrets and variables → Actions → New repository secret. Add four:

| Name | Value |
|------|------|
| `CENTRAL_USERNAME` | The user-token username from step 1 |
| `CENTRAL_PASSWORD` | The user-token password from step 1 |
| `GPG_PRIVATE_KEY` | Full contents of the `private.key` file from step 2 (incl. `-----BEGIN/END PGP PRIVATE KEY BLOCK-----`) |
| `GPG_PASSPHRASE` | The passphrase you set when generating the key |

Then **delete the local `private.key` file** (`shred -u private.key` or `rm -P`).

## Each release

### Option A: Automated (recommended)

1. In the repo, go to Actions → "Release to Maven Central" → "Run workflow"
2. Enter a version, e.g. `0.1.0`
3. The workflow will:
   - Set every pom version to `0.1.0`
   - Run the full test suite (including Redis Testcontainers)
   - GPG-sign every jar
   - Upload to Central (`autoPublish=true`, auto-released after validation)
   - Push git tag `v0.1.0` and create a GitHub Release

After release, artifacts show up on https://search.maven.org within 5–30 minutes.

### Option B: Manual local release (not recommended — easy to leak credentials)

Configure `~/.m2/settings.xml`:

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

Then:

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
mvn -Prelease clean deploy -Dgpg.passphrase=YOUR_GPG_PASSPHRASE
```

## Local dry-run (no upload)

Want to verify the release profile didn't break before committing:

```bash
# Produces sources + javadoc jars; no signing, no upload
mvn -Prelease package -DskipTests

# Output: litellm-core/target/litellm-core-0.1.0-sources.jar, etc.
```

If you have a local GPG key, you can also test signing (still no upload):

```bash
mvn -Prelease verify -Dgpg.passphrase=YOUR_GPG_PASSPHRASE
```

## What gets published

The `litellm-proxy` module is **not published** to Maven Central — a fat jar isn't useful as a dependency; it's distributed as a Docker image. The library modules that are published:

- `io.github.vincent-ye:litellm-bom` — version-alignment BOM
- `io.github.vincent-ye:litellm-core` — canonical types + exceptions + SPI
- `io.github.vincent-ye:litellm-client` — SDK facade
- `io.github.vincent-ye:litellm-router` — routing layer
- `io.github.vincent-ye:litellm-cache` — cache abstraction
- `io.github.vincent-ye:litellm-callbacks` — callback abstraction
- `io.github.vincent-ye:provider-openai` / `provider-anthropic` / `provider-azure-openai` / `provider-mistral` / `provider-gemini` / `provider-bedrock` — provider adapters

## Troubleshooting

| Error | Reason / fix |
|------|------|
| `Namespace not verified` | Step 1.2: `io.github.vincent-ye` isn't visible in your Central Portal. GitHub account verification didn't take — confirm the case of your GitHub username. |
| `Invalid signature` | The public key wasn't sent to a keyserver, or was sent but hasn't propagated yet — run `gpg --keyserver keys.openpgp.org --send-keys` and wait a few minutes. |
| `Version 0.x.x already exists` | Central versions are immutable; bump the version and re-release. |
| `401 Unauthorized` | `CENTRAL_USERNAME/PASSWORD` are wrong, or you used your login credentials instead of a generated user token. |

## Out-of-scope (still requires manual steps from you)

- **Performance benchmarking**: the roadmap's M6 acceptance criterion (gateway P99 self-overhead < 15ms) has to be measured in your actual deployment environment (bare metal / containers / with Redis etc.); the repo doesn't ship benchmarking scripts.
- **Actually publishing to Central**: the code is ready; once you've completed the credential setup above, run Option A.
