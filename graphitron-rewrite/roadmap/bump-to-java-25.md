---
title: "Bump generator-side Java floor 21 → 25"
status: In Review
priority: 11
---

# Plan: Bump generator-side Java floor 21 → 25

Raised the JDK floor for graphitron-rewrite generator code from Java 21 to Java 25 (LTS). Generated output stays at Java 17: `graphitron-test`'s `<release>17</release>` ratchet is preserved as the single guard that catches Java-18+ syntax leaking into JavaPoet emitters. The gap the ratchet covers widened from "21 syntax can leak" to "25 syntax can leak"; the mechanism is unchanged.

## Why now, why 25

- Java 25 is the next LTS after 21, so it picks itself as the natural successor floor.
- The LSP Phase 6 binding swap to `io.github.tree-sitter:jtreesitter` is the only concrete capability blocked on a higher floor today (jtreesitter uses FFM, finalised in 22). Lifting the floor as a separate item lets that swap land on top of an already-bumped tree rather than dragging an infra change behind it. See [`graphitron-lsp.md`](graphitron-lsp.md) Phase 6, currently gated on the bump.
- No generator-side feature today requires Java 22+. The bump is taken for the LSP unblock and for keeping the floor on a current LTS, not because emitter or pipeline code needs new syntax.

## Consumer impact

`graphitron-maven` runs in the consumer's Maven JVM, so its `<release>` directly determines the minimum JVM the consumer's `mvn` invocation must use. Maven toolchains do not help here: toolchains switch the *compile* JDK only, not the JVM hosting plugins. Consumers on JDK 17 / 21 must upgrade their dev and CI JDK in lock-step.

This is acceptable now because the rewrite has no consumers yet (everyone is on graphitron-legacy); the upgrade lands as part of the eventual switch from legacy to rewrite. By the time the rewrite has its first downstream consumer, the JDK 25 floor is just one more line on the migration checklist alongside `<release>17</release>` output, the new directive surface, and the runtime extension points.

The bump is a breaking change to the build contract; whether to bump `graphitron-rewrite-parent` from `10-SNAPSHOT` to `11-SNAPSHOT` at the same time is open and noted under "Open questions".

## Implementation (shipped)

- `graphitron-rewrite/pom.xml`: parent `<release>21</release>` → `<release>25</release>`; added `<requireJavaVersion><version>25</version></requireJavaVersion>` to the existing `enforce-versions` execution alongside `<requireMavenVersion>3.9</requireMavenVersion>` so a too-old Maven JVM fails fast with a clear message rather than an `UnsupportedClassVersionError` deep in plugin loading.
- `graphitron-rewrite/graphitron/pom.xml`: `<release>21</release>` → `<release>25</release>` (the override exists only to add `-parameters`; the release pin tracks the parent).
- `graphitron-rewrite/graphitron-test/pom.xml`: `<release>17</release>` ratchet preserved verbatim. Updated the explanatory comment so "Java-21 syntax slip" reads "Java-18+ syntax slip" (the ratchet now covers a wider gap), examples include a 25-era feature (unnamed variables), and the test-compile note reflects the parent's new floor (25 instead of 21).
- [`CLAUDE.md`](../../CLAUDE.md): "Technology constraints" Java line updated to 25, with a one-clause note about the new enforcer rule. "Environment" line updated to "Java 25 default" with a transitional fallback for sandbox images that still ship Java 21 (`apt-get install openjdk-25-jdk-headless` plus `update-alternatives` or `JAVA_HOME`).
- [`graphitron-rewrite/docs/claude-code-web-environment.md`](../docs/claude-code-web-environment.md): "Java 21 is the default JVM" → "Java 25 is the default JVM", with the same transitional fallback inline.
- [`graphitron-rewrite/roadmap/graphitron-lsp.md`](graphitron-lsp.md) Phase 6: dropped the "Bump `<release>21</release>` to `<release>25</release>` in the parent pom" step; the scope-bullet and the phase paragraph both now reference this plan instead. Phase 6 stops claiming responsibility for an infra change that has already shipped.

The legacy modules at the repo root (`graphitron-codegen-parent` etc.) are out of scope per `CLAUDE.md` and keep whatever Java floor they have today. The `.github/workflows/maven-build.yml` workflow targets the legacy root pom only and is unchanged.

## Sandbox / CI provisioning

Two environments need JDK 25 available before the bump can ship cleanly:

- **Claude Code Web sandbox.** Today's image ships OpenJDK 21 as the default `java`; OpenJDK 25 is installable via `apt-get install openjdk-25-jdk-headless` (verified, lives at `/usr/lib/jvm/java-25-openjdk-amd64`). For sessions started before the image is refreshed, `update-alternatives --set java /usr/lib/jvm/java-25-openjdk-amd64/bin/java` (or setting `JAVA_HOME`) is the workaround. The `claude-code-web-environment.md` update should mention this transitional path, not just the eventual default.
- **GitHub Actions.** No CI workflow targets the rewrite reactor today (`.github/workflows/maven-build.yml` builds the legacy root pom only). When the rewrite acquires its own workflow, `actions/setup-java@v5` with `java-version: '25'` and `distribution: 'temurin'` is the contract. Out of scope for this plan; called out so the next CI-wiring item knows the floor.

No sandbox / CI changes block the pom flips themselves: the local sandbox can install 25 in a single apt step, and there is no rewrite CI yet to break.

## Verification (run before the implementation commit landed)

- **Full reactor build on JDK 25.** `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -f graphitron-rewrite/pom.xml install -Plocal-db` exits 0 with no test changes; all eight modules pass (parent, javapoet, fixtures-codegen, fixtures, graphitron, lsp, maven, test, roadmap-tool). Confirms the bump is mechanical and the existing suites do not depend on JDK 21-specific behaviour.
- **Toolchain-level ratchet check.** `javac --release 17` running under JDK 25 still rejects post-17 syntax. A throwaway file containing `var _ = 1;` (unnamed variable, stable in Java 22 per JEP 456) compiled at `--release 25` (OK), then at `--release 17` (rejected with `error: unnamed variables are not supported in -source 17 (use -source 22 or higher to enable unnamed variables)`). The maven-compiler-plugin's `<release>17</release>` configuration reduces to that same `--release` flag, so the `graphitron-test` ratchet still catches post-17 syntax leaking out of any emitter. No emitter-level injection performed; the toolchain contract is the relevant guarantee.
- **Enforcer rule rejects too-old JDK.** `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -f graphitron-rewrite/pom.xml -N validate` fails at the `enforce-versions` execution with `Rule 1: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed with message: Detected JDK /usr/lib/jvm/java-21-openjdk-amd64 is version 21.0.10 which is not in the allowed range [25,).` before any compile or plugin classloading runs; consumers on too-old JDKs get a coherent error rather than a `UnsupportedClassVersionError` deep in plugin loading.

No new test code; this plan does not touch any source tree under `src/`.

## Decisions taken

- **Version coordinate stays at `10-SNAPSHOT`.** The SNAPSHOT coordinate carries no compatibility promise and the rewrite has no published releases; the version bump can ride along with the first non-SNAPSHOT release rather than fragmenting the SNAPSHOT line over a single floor change. Reviewer can revisit if a different release cadence is preferred.
- **Enforcer wording is a lower bound only.** `<requireJavaVersion><version>25</version></requireJavaVersion>` requires *at least* JDK 25, allowing any later JDK. Matches the parent `<release>25</release>`'s "compile to 25 bytecode, run on 25-or-newer" semantics; no range upper bound, we don't try to forbid future JDKs.

## Roadmap entries

- The [`graphitron-lsp.md`](graphitron-lsp.md) Phase 6 update landed in this plan's commit; no separate item.
- A future "rewrite CI workflow" backlog item inherits the JDK 25 floor as a fixed constraint, not a decision.
