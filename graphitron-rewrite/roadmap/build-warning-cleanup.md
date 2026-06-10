---
id: R293
title: "Clean up build-time warnings in the full rewrite build"
status: Backlog
bucket: cleanup
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Clean up build-time warnings in the full rewrite build

A full `mvn install -Plocal-db` build of the rewrite emits roughly 130 `[WARNING]` lines (plus a handful of raw JVM `WARNING:` lines). None of them fail the build, but the volume buries any new, meaningful warning; a warning-clean build would let regressions surface immediately. Inventory from a clean build on 2026-06-10, grouped by likely fix:

**Generated code is not warning-clean (~45 lines, graphitron-sakila-example).** The generator emits Java that javac flags: ~30 `unchecked cast` warnings (concentrated in `MutationFetchers`, `QueryFetchers`, and the `*PayloadType` classes), ~10 `found raw type: java.util.List` in generated `inputs/*Input.java` filter classes, and one `redundant cast to org.jooq.Field<Integer>` in `DeletedFilmsIdPayloadType`. Fix belongs in the generator: emit properly parameterised types, or emit targeted `@SuppressWarnings` where the cast is inherently unchecked.

**Duplicate `junit-platform.properties` (36 lines, the single largest source).** Every surefire launch in graphitron-sakila-example warns that both `graphitron-10-SNAPSHOT-tests.jar` and `quarkus-junit-3.34.5.jar` bundle a `junit-platform.properties`; only the first is used. Decide which configuration should win and stop shipping (or stop seeing) the other, for example by excluding the file from the test-jar or relocating the shared config.

**Handwritten source warnings (~20 lines).** `graphitron`: raw `graphql.language.Value` in `CatalogBuilder.java:798`, dangling javadoc in `BuildContext.java:1166`, plus raw-type/unchecked/deprecated-`getType` warnings in the test-side `classifieddsl` harness. `graphitron-sakila-service`: four exceptions missing `serialVersionUID`, a raw `org.jooq.Row2` in `FilmActorCarrierService`, a dangling javadoc in `FilmService`. `graphitron-sakila-example`: two dangling javadoc comments in `FederationBuildSmokeTest`. `graphitron-javapoet`: deprecated `Charsets.UTF_8` in `TypesEclipseTest`. All are straightforward local fixes.

**Build infrastructure (~15 lines).** The maven-plugin's javadoc processing warns 8 times about an unresolvable `{@link String#endsWith}` in `AbstractRewriteMojo`. jOOQ codegen on sakila warns 3 times about ambiguous inbound key method names (`store`/`staff` mutual FKs, `category` self-reference) and once about the PostgreSQL version being older than the dialect expects. `graphitron-lsp` triggers `java.lang.foreign.SymbolLookup::libraryLookup is a restricted method` at compile time and a JVM native-access warning at test time; the test-time warning wants `--enable-native-access=ALL-UNNAMED` in the surefire argLine, the compile-time one a deliberate suppression. (JVM `sun.misc.Unsafe` warnings from Maven's own Guice are environmental noise we cannot fix in this repo.)

**Intentional fixture warnings need a policy (~14 lines, emitted twice because the generator runs for both the regular and federated schema).** The sakila-example schema deliberately exercises warning paths: 11 `Type 'X' carries @record(...); the directive is redundant; remove it` warnings, one redundant `@splitQuery`, and the `asConnectionSameTableHygiene` warning on `filmsConnectionByRequiredIds`. At least the same-table `@nodeId` case is a documented fixture (R88 comments nearby); some redundant `@record` directives may be genuinely stale. The Spec phase should decide per warning: remove the stale directive, or keep the fixture but assert the warning in a test and suppress it from the build output so expected warnings stop masquerading as problems.

The goal state is a build whose warning output is empty or near-empty, so CI/local logs make new warnings visible. Consider finishing with a guard (for example `-Werror` on selected modules, or a log-scan check) once the count reaches zero, so the cleanup does not erode.
