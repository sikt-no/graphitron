---
id: R467
title: "Upgrade graphql-java 25.0 -> 26.0"
status: Backlog
bucket: tech-debt
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Upgrade graphql-java 25.0 -> 26.0

Bump `graphql-java` from 25.0 to 26.0 in the root `pom.xml` dependency-management block. Unlike the jOOQ 3.21 bump (R466) and the satellite catch-up (R465), this is a genuine breaking upgrade on two fronts, confirmed by a dry run.

**Blocked externally on federation-jvm.** graphql-java 26.0 has no compatible `federation-graphql-java-support` release yet: the newest (6.2.0) is built against graphql-java 25.0, and forcing graphql-java to 26.0 runs the federation library ahead of what it supports. This is tracked upstream at apollographql/federation-jvm#454 ("Graphql-java 26.0 support"), open as of filing, with PR #456 in progress. This item cannot land until that ships a 26-compatible federation release and we bump to it. (The roadmap `depends-on` field only references internal roadmap slugs, so this external blocker lives here in prose rather than in front-matter. R465 pulls federation to 6.2.0 on the 25 line; this item supersedes that with whatever release fixes #454, so landing R465 first is natural but not strictly required.)

**Generator source changes required.** With graphql-java 26.0 the generator module fails to compile, three breaks in graphitron's own code (surface B, compiler-caught), matching the fragility the coupling map flagged: (1) `graphql.schema.idl.DirectiveInfo` is gone/relocated, breaking `SchemaSdlEmitter.java:8,90` (`DirectiveInfo.isGraphqlSpecifiedDirective`); (2) `GraphQLSchema.Builder.additionalType(...)` narrowed its parameter from `GraphQLType` to `GraphQLNamedType`, breaking `ConnectionPromoter.java:231`. Both are mechanical. Crucially, because compilation halts in the generator, the **emitted-code surface (surface A) is still unverified**: the map's highest-risk emitted APIs (the `graphql.execution.instrumentation.*` Instrumentation SPI in `GraphitronConnectionInstrumentationGenerator`, `DataFetchingEnvironmentImpl`, and `ValuesResolver.valueToLiteral` in `AppliedDirectiveEmitter`) can only be exercised once the generator compiles and the sakila-example compile+execute pipeline runs. The Spec should treat "get the generator compiling, then assess the emitted surface via the pipeline" as the real scope, with the two known compile fixes as just the entry point. JDK floor is unaffected (26 keeps Java 21 runtime; generated output stays Java 17).
