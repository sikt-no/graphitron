---
id: R466
title: "Upgrade jOOQ 3.20.11 -> 3.21.6"
status: Backlog
bucket: tech-debt
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Upgrade jOOQ 3.20.11 -> 3.21.6

Adopt the jOOQ 3.21 line, bumping `version.org.jooq` in the root `pom.xml` (line 33) from 3.20.11 to 3.21.6. In jOOQ's release cadence a minor bump (3.20 -> 3.21) is the effective major boundary: it is where deprecated API is removed and code-generation output shape can change, so it is worth treating as a real upgrade even though the version delta looks small. 3.21 keeps the JDK-21 runtime floor 3.20 already established, so the "generated output targets Java 17" contract is undisturbed (the sakila-example module, which compiles generated code at `--release 17`, is the guard).

A full-pipeline dry run (`mvn install -Plocal-db`) with 3.21.6 came back completely clean: all four jOOQ artifacts (`jooq`, `jooq-meta`, `jooq-codegen`, `jooq-codegen-maven`) resolved, all 13 modules built, all tests passed, and the three risk areas all held: the `graphitron-sakila-db` codegen (with our custom `NodeIdFixtureGenerator` strategy, `OrgCodeStringConverter` forcedType, and the stock-`JavaGenerator` execution), the generator's own `JooqCatalog` meta-model reflection, and the generated-code compile + DB execution in sakila-example. None of the 3.21 deprecations from the release notes (`DataType.isQualifiedRecord`, `Internal.createParameter`, XML-formatting methods, the retired `INGRES`/`ORACLE10G` dialects) touch our surface. So this is expected to be a one-line version bump with no code changes, confirming the single-version-lockstep strategy: the emitted jOOQ surface is all stable core `org.jooq` API, and the pipeline verifies the parts that are not compile-checked in the generator.

One process note for the implementer: switching jOOQ versions leaves stale jOOQ-generated sources under `graphitron-sakila-db/target/generated-sources/jooq/`. During the research a bump followed by a revert produced spurious `org.jooq.impl.Internal.condition(...)` / `TableLike` cannot-convert errors because 3.21-shaped generated code met a reverted 3.20 runtime; a `mvn clean` before rebuilding avoids it. Independent of the graphql-java items (R465, R467); can land in any order.
