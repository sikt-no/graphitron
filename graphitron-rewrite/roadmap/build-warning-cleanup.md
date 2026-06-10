---
id: R293
title: Clean up build-time warnings in the full rewrite build
status: Spec
bucket: cleanup
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Clean up build-time warnings in the full rewrite build

## Motivation

A full `mvn install -Plocal-db` build of the rewrite emits roughly 130 `[WARNING]` lines (plus a handful of raw JVM `WARNING:` lines). None of them fail the build, but the volume buries any new, meaningful warning; a warning-clean build lets regressions surface immediately. This item covers every category except the generator's own `BuildWarning` channel, which is R294 (fixture-warnings-as-errors). Inventory from a clean build on 2026-06-10, with the fix per category below.

The endgame is a guard, not just a one-time sweep: the parent pom (graphitron-rewrite/pom.xml:216) already sets `-Xlint:all` globally, and graphitron-sakila-example/pom.xml:243-257 carries a ratchet comment that deliberately omits `-Werror` because "existing generated code has raw-type warnings that are a separate concern". This item is that separate concern; once each module is clean, `-Werror` flips on and the comment comes out.

## Design

### Generated code compiles warning-free (~45 lines, the design-relevant part)

The generator emits Java that javac flags: ~30 `unchecked cast` warnings (concentrated in generated `MutationFetchers`, `QueryFetchers`, the `*PayloadType` classes, and `QueryConditions`), ~10 `found raw type: java.util.List` in generated `inputs/*Input.java`, and one `redundant cast to org.jooq.Field<Integer>` in `DeletedFilmsIdPayloadType`.

Two-step preference order, per "Classifier guarantees shape emitter assumptions":

1. **Emit correctly-typed code where the type is expressible.** The raw `List` cast emitted at `InputRecordGenerator.java:245` (`$T $L = ($T) in.get($S)` with raw `LIST`) becomes `(List<?>)`; the wildcard is honest there because the element type genuinely arrives erased off the graphql-java argument map, a wire boundary the principles accept. Note that this clears only the raw-type warning on the `_raw` local; the per-element coercion that follows (the `(MAP_STRING_OBJECT) element` / scalar-element casts in `readComponent`) is inherently unchecked off a `List<?>`, so the enclosing emitted member still needs the step-2 suppression. `(List<?>)` is not a one-line fix for the whole emission site. The redundant `Field<Integer>` cast is dropped at its payload-type emission site.
2. **Where the cast is inherently unchecked** (e.g. `(List<Map<?, ?>>) env.getArgument("in")` in the fetcher emitters, `(List<Integer>) map.get(...)` in `QueryConditionsGenerator`), stamp `@SuppressWarnings("unchecked")` on the narrowest enclosing emitted member, exactly as `InputRecordGenerator.java:222-224` already does for `fromMap`. **Narrowest-scope suppression is an invariant, not an example:** method-level or tighter, never class-level. A class-level annotation would silence the next emitter regression invisibly and defeat the `-Werror` guard this item ends with.

Verification is the `-Werror` compile itself (see Guard below); no golden-file or code-string assertion on the emitted spelling, which the testing principles ban.

### Duplicate `junit-platform.properties` (36 lines, the largest single source)

Every surefire launch in sakila-example warns that both `graphitron-10-SNAPSHOT-tests.jar` and `io.quarkus:quarkus-junit:3.34.5` bundle a `junit-platform.properties`; only the first is used. graphitron's copy (`graphitron/src/test/resources/junit-platform.properties`) contains a single line, `junit.jupiter.extensions.autodetection.enabled=true`, which configures graphitron's own test runs, not its consumers'.

Fix: exclude `junit-platform.properties` from the test-jar packaging (the `test-jar` execution at graphitron/pom.xml:93-103). sakila-example is the only reactor consumer of the test-jar (graphitron-sakila-example/pom.xml:72-79); before landing, confirm whether any of its tests rely on transitively-inherited extension autodetection, and if so declare the property in sakila-example's own test resources. Quarkus-junit's copy then stands alone and the warning disappears.

### Handwritten-source warnings (~25 lines, mechanical)

- `graphitron` main sources: raw `graphql.language.Value` (`CatalogBuilder.java:803`, the `av.getValues()` loop variable; the library hands back a raw `List`), dangling javadoc (`BuildContext.java:1195`).
- `graphitron` test sources: the `classifieddsl` harness carries raw `graphql.language.TypeDefinition` plus an unchecked `directive(...)` invocation/conversion (`ClassifiedHarness.java:74,85`), and `QueryViewRenderer` carries raw `graphql.language.Definition` in its `doc.getDefinitions()` loops (`QueryViewRenderer.java:138,167`). The deprecated `TypeDefinitionRegistry.getType(String)` and its two-arg `getType(String, Class<T>)` overload recur across the test sources, `ClassifiedHarness.java:146`, `QueryViewRenderer.java:116,121,126`, `SchemaSdlEmitterTest.java:151,155,178,182,188`, and `ConnectionFederationTagPipelineTest.java:114`; sweep for `getType(` rather than fixing only these anchors, since the line list drifts as the harness evolves. The non-deprecated replacement is `getTypeOrNull(String)` / `getTypeOrNull(String, Class<T>)`, which return `null` rather than `Optional`, so call sites that chain `.ifPresent(...)` / `.map(...)` need their control flow adapted, not a blind signature swap.
- `graphitron-sakila-service`: `serialVersionUID` on the four exception classes (`FilmReviewBadRatingException`, `FilmReviewMissingFilmException`, `FilmLookupNotFoundException`, `FilmLookupInvalidIdException`), raw `org.jooq.Row2` (`FilmActorCarrierService.java:51`), dangling javadoc (`FilmService.java:94`, `FilmCarrierWithErrorsService.java:44`).
- `graphitron-sakila-example`: dangling javadoc (`FederationBuildSmokeTest.java:94,159`).
- `graphitron-javapoet`: deprecated `Charsets.UTF_8` to `StandardCharsets.UTF_8` (`TypesEclipseTest.java:136`).

### Build infrastructure (~15 lines)

- **Javadoc link (8 warnings):** `{@link String#endsWith}` at `AbstractRewriteMojo.java:47` lacks the parameter signature; `{@link String#endsWith(String)}` resolves. Sweep the mojo for other parameterless method links.
- **jOOQ ambiguous key names (3 warnings):** sakila's `store`/`staff` mutual FKs and the `category` self-FK generate colliding inbound-key method names. Follow the warning's own advice; implementer decides between a custom generator strategy that disambiguates the names and disabling the inbound-key feature, after checking whether any generated catalog consumer uses those methods. Config lives in `graphitron-sakila-db/pom.xml`.
- **graphitron-lsp FFM restricted-method warnings (3 compile + 1 JVM):** runtime access is already declared (`--enable-native-access=ALL-UNNAMED` argLine, graphitron-lsp/pom.xml:75-90). Add `@SuppressWarnings("restricted")` at the three call sites (`BundledLibraryLookup.java:66,86`, `GraphqlLanguage.java:121`) with a comment pointing at the argLine declaration. Verify every JVM that loads the lsp jar declares native access so the runtime warning also disappears.
- **Environment-only, declared out of scope:** the jOOQ "database version is older than the dialect supports" warning only fires in the web sandbox (native PostgreSQL 16; CI runs `postgres:18` per .github/workflows/rewrite-build.yml:27), and the `sun.misc.Unsafe` JVM warnings come from Maven's own bundled Guice, not this repo.

### Guard: per-module `-Werror`

Once a module is warning-clean, set maven-compiler-plugin `failOnWarning` for it. The decisive one is **graphitron-sakila-example**: with `-Werror` on the release-17 compile of generated sources, a generator change that emits warning-producing code fails the cross-module compile that the principles already designate as the backstop ("Compilation against real jOOQ is a test tier"). Remove the `-Werror is deliberately omitted` paragraph from the ratchet comment at graphitron-sakila-example/pom.xml:243-257 when flipping it on.

`-Xlint:all` + `-Werror` may force noise-suppressions in categories like `serial` or `processing`. The lint set under `-Werror` is an explicit per-category decision: enumerate which categories are enforced, and record the rationale for each excluded category in a pom comment (the existing ratchet comment is the model). No silent curation.

## Verification

- Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` log contains no `[WARNING]` lines from javac, surefire properties discovery, javadoc descriptor generation, or jOOQ codegen key resolution (the environment-only PG-version line excepted in the sandbox).
- `-Werror` active on every cleaned module, including sakila-example's generated-source compile; the build proves generated code stays warning-free thereafter.
- Existing test tiers stay green; the junit-platform change is verified by sakila-example's surefire run still autodetecting (or explicitly declaring) the extensions it needs.

## Phasing

1. **Mechanical sweep:** handwritten-source fixes, javadoc link, FFM suppressions, test-jar exclusion (with the consumer check).
2. **Emitter fixes:** parameterize the raw `List` emission, drop the redundant cast, add narrowest-scope suppressions at the inherently-unchecked emission sites.
3. **jOOQ codegen decision** for the ambiguous key names.
4. **Guard:** flip `failOnWarning` per module with the curated, documented lint set; update the sakila-example ratchet comment.

## Out of scope

- **Generator `BuildWarning` channel and fixture warnings:** R294 (fixture-warnings-as-errors) establishes warnings-as-errors for fixture schemas; R296 (deprecated-usage-warnings) extends it to deprecated functionality.
- **Environment-only warnings:** the sandbox PG-version mismatch and Maven's own Guice/Unsafe JVM warnings; not fixable in this repo.
- **jOOQ/PostgreSQL version bumps:** version alignment is its own decision, not warning hygiene.
