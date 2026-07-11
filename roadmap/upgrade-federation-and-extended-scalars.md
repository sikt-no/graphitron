---
id: R465
title: "Upgrade federation-graphql-java-support 6.0->6.2 and extended-scalars 22->24"
status: Spec
bucket: tech-debt
priority: 4
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-11
---

# Upgrade federation-graphql-java-support 6.0->6.2 and extended-scalars 22->24

The two graphql-java satellite artifacts lag their pinned versions: `federation-graphql-java-support` is on 6.0.0 (latest 6.2.0) and `graphql-java-extended-scalars` is on 22.0 (latest 24.0), both pinned in the root `pom.xml` dependency-management block (lines ~148-157). This is the conservative, ready-now half of the graphql-java catch-up: it holds `graphql-java` itself at 25.0 (the jump to 26.0 is a separate, harder effort, it breaks generator compilation and runs ahead of what federation/extended-scalars officially support) while pulling the satellites current. A dry run confirmed the shape: with graphql-java held at 25.0, federation 6.2.0 (which is built against graphql-java 25.0) and extended-scalars 24.0 (built against 24.1) both resolve and the reactor compiles clean. Federation 6.2.0 introduced no failures. Bumping the two versions is the whole mechanical change.

**R464 has shipped** (`7786ce4`), so this is now unblocked and reduces to a pure version bump. The only fallout from the extended-scalars bump in the dry run was the convention-table drift-guard test (`ScalarTypeResolverTest.conventionTable_coversEveryExtendedScalarsField`) tripping on six new constants (`YearMonth`, `Year`, `AccurateDuration`, `NominalDuration`, `SecondsSinceEpoch`, `HexColorCode`), each demanding a map-or-exclude decision. R464 deleted that convention layer and its drift test outright, so this upgrade has nothing to curate.

## Scope

In scope: two version-property edits in the root `pom.xml` dependency-management block.

* `com.apollographql.federation:federation-graphql-java-support` 6.0.0 -> 6.2.0
* `com.graphql-java:graphql-java-extended-scalars` 22.0 -> 24.0

Out of scope: `com.graphql-java:graphql-java` stays at 25.0. The 25.0 -> 26.0 jump breaks generator compilation and runs ahead of what the two satellites officially support; it is a separate, harder effort and is deliberately excluded here.

No generator source, no generated-output, and no test changes are expected. The six new extended-scalars constants no longer need a map-or-exclude decision because R464 removed the convention table and its drift guard; the reflective `@scalarType` resolver binds any public static `GraphQLScalarType` constant on the classpath, so the new constants become resolvable candidates automatically with no code change.

## Acceptance criteria

* Both version properties bumped to 6.2.0 and 24.0; `graphql-java` untouched at 25.0.
* `mvn install -Plocal-db` is green across the full reactor (build-fixtures -> test -> compile-spec -> execute-spec), including `graphitron-sakila-example` (Java 17 generated-output guard) and `graphitron-docs`.
* No lingering reference to the deleted `ScalarTypeResolverTest.conventionTable_coversEveryExtendedScalarsField` guard or the convention table anywhere in the tree (already true post-R464; re-confirm as a grep after the bump).
* No new or modified test is required; if the bump surfaces an unexpected failure, that failure defines follow-up scope rather than being patched over inside this item.
