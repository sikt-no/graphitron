---
title: "Bump generator-side Java floor 21 → 25"
status: Spec
priority: 11
---

# Plan: Bump generator-side Java floor 21 → 25

Raise the JDK floor for graphitron-rewrite generator code from Java 21 to Java 25 (LTS). Generated output stays at Java 17: `graphitron-test`'s `<release>17</release>` ratchet is preserved as the single guard that catches Java-18+ syntax leaking into JavaPoet emitters. After this lands, the gap the ratchet covers widens from "21 syntax can leak" to "25 syntax can leak"; the mechanism is unchanged.

## Why now, why 25

- Java 25 is the next LTS after 21, so it picks itself as the natural successor floor.
- The LSP Phase 6 binding swap to `io.github.tree-sitter:jtreesitter` is the only concrete capability blocked on a higher floor today (jtreesitter uses FFM, finalised in 22). Lifting the floor as a separate item lets that swap land on top of an already-bumped tree rather than dragging an infra change behind it. See [`graphitron-lsp.md`](graphitron-lsp.md) Phase 6, currently gated on the bump.
- No generator-side feature today requires Java 22+. The bump is taken for the LSP unblock and for keeping the floor on a current LTS, not because emitter or pipeline code needs new syntax.

## Consumer impact

`graphitron-maven` runs in the consumer's Maven JVM, so its `<release>` directly determines the minimum JVM the consumer's `mvn` invocation must use. Maven toolchains do not help here: toolchains switch the *compile* JDK only, not the JVM hosting plugins. Consumers on JDK 17 / 21 must upgrade their dev and CI JDK in lock-step.

This is acceptable now because the rewrite has no consumers yet (everyone is on graphitron-legacy); the upgrade lands as part of the eventual switch from legacy to rewrite. By the time the rewrite has its first downstream consumer, the JDK 25 floor is just one more line on the migration checklist alongside `<release>17</release>` output, the new directive surface, and the runtime extension points.

The bump is a breaking change to the build contract; whether to bump `graphitron-rewrite-parent` from `10-SNAPSHOT` to `11-SNAPSHOT` at the same time is open and noted under "Open questions".

## Implementation

Two `<release>` flips, one supporting enforcer rule, and four documentation touch-ups. None of the module / source / dependency layout changes.

- `graphitron-rewrite/pom.xml` L172: `<release>21</release>` → `<release>25</release>` (parent default for all generator modules).
- `graphitron-rewrite/graphitron/pom.xml` L70: `<release>21</release>` → `<release>25</release>` (the override exists only to add `-parameters`; the release pin tracks the parent).
- `graphitron-rewrite/pom.xml` enforcer block (around L189): add a `<requireJavaVersion><version>25</version></requireJavaVersion>` rule alongside the existing `<requireMavenVersion>3.9</requireMavenVersion>`. Goal: a clear enforcer error when a consumer's Maven JVM is too old, instead of an `UnsupportedClassVersionError` from a class load. The legacy root reactor is unaffected (separate pom).
- `graphitron-rewrite/graphitron-test/pom.xml`:
  - Keep `<release>17</release>` on the `default-compile` execution. This is the ratchet; do not touch.
  - Update the comment block at L116-L124: replace "release 21" with "release 25" so the rationale ("a Java-21 syntax slip ... fails this module's build") becomes "a Java-25 syntax slip", and the test-compile note ("Tests stay at the parent's release 21") becomes "release 25".
- [`CLAUDE.md`](../../CLAUDE.md) "Technology constraints" bullet: replace "**Java 21** for generator code" with "**Java 25** for generator code", update the parenthetical pom reference, and adjust the trailing sentence ("Generator implementation may use Java 21 features freely") to read "Java 25 features freely".
- [`CLAUDE.md`](../../CLAUDE.md) "Environment" line: "Java 21 default" → "Java 25 default" (sandbox image; coordinated with the sandbox refresh, see "Sandbox / CI provisioning" below).
- [`graphitron-rewrite/docs/claude-code-web-environment.md`](../docs/claude-code-web-environment.md) L83: same "Java 21 is the default JVM" → "Java 25 is the default JVM" replacement.
- [`graphitron-rewrite/roadmap/graphitron-lsp.md`](graphitron-lsp.md) Phase 6: drop the "Bump `<release>21</release>` to `<release>25</release>` in the parent pom" step; replace with a one-line "JDK 25 floor is in place per `bump-to-java-25.md`". The Phase 6 description stops claiming responsibility for an infra change that has already shipped.

The legacy modules at the repo root (`graphitron-codegen-parent` etc.) are out of scope per `CLAUDE.md`; they keep whatever Java floor they have today.

## Sandbox / CI provisioning

Two environments need JDK 25 available before the bump can ship cleanly:

- **Claude Code Web sandbox.** Today's image ships OpenJDK 21 as the default `java`; OpenJDK 25 is installable via `apt-get install openjdk-25-jdk-headless` (verified, lives at `/usr/lib/jvm/java-25-openjdk-amd64`). For sessions started before the image is refreshed, `update-alternatives --set java /usr/lib/jvm/java-25-openjdk-amd64/bin/java` (or setting `JAVA_HOME`) is the workaround. The `claude-code-web-environment.md` update should mention this transitional path, not just the eventual default.
- **GitHub Actions.** No CI workflow targets the rewrite reactor today (`.github/workflows/maven-build.yml` builds the legacy root pom only). When the rewrite acquires its own workflow, `actions/setup-java@v5` with `java-version: '25'` and `distribution: 'temurin'` is the contract. Out of scope for this plan; called out so the next CI-wiring item knows the floor.

No sandbox / CI changes block the pom flips themselves: the local sandbox can install 25 in a single apt step, and there is no rewrite CI yet to break.

## Tests

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes on JDK 25, with no test changes. Confirms the bump is mechanical and the existing test suites do not depend on JDK 21-specific behaviour.
- The 17 ratchet still bites. Confidence check, not a permanent test: temporarily slip a Java 21 expression (e.g. a record pattern in a `switch`) into a JavaPoet emitter, verify `graphitron-test` fails at `default-compile` with a Java 17 release error, then revert. Document the result in the implementation commit message; do not land the slip.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on JDK 21 fails fast at the enforcer step, naming Java 25 as the required minimum. Confirms consumers on too-old JDKs get a coherent error rather than a `UnsupportedClassVersionError` deep in plugin loading.

No new test code; this plan does not touch any source tree under `src/`.

## Open questions

- **Version coordinate.** Bump `graphitron-rewrite-parent` from `10-SNAPSHOT` to `11-SNAPSHOT` at the same time, signalling the breaking JDK-floor change at the artifact level? Argument for: the floor is a real consumer-facing contract change. Argument against: the rewrite has no published releases yet, the SNAPSHOT coordinate carries no compatibility promise, and a `11-SNAPSHOT` that only differs from `10-SNAPSHOT` by one floor change is noisy. Recommendation: stay on `10-SNAPSHOT`, take the version bump when the first non-SNAPSHOT release is cut. Reviewer to confirm.
- **Enforcer floor wording.** `<requireJavaVersion><version>25</version></requireJavaVersion>` requires *at least* JDK 25, allowing any later JDK. That matches the parent `<release>25</release>`'s "compile to 25 bytecode, run on 25-or-newer" semantics. No range upper bound; we don't try to forbid future JDKs.

## Roadmap entries

- [`graphitron-lsp.md`](graphitron-lsp.md) Phase 6 currently owns the bump line; its update (delete that step, reference this plan) is part of this plan's implementation, not a separate item.
- A future "rewrite CI workflow" backlog item inherits the JDK 25 floor as a fixed constraint, not a decision.
