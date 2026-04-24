# Plan: Rewrite owns schema loading + directive auto-injection

> **Status:** Ready
>
> First sub-item of the "Dissolve `graphitron-schema-transform` module"
> roadmap umbrella. Prerequisite for every subsequent migration
> (`MergeExtensions`, `MakeConnections`, `ElementRemovalFilter`,
> `DirectivesFilter`, SDL emission, feature-flag splits, federation).
> Also subsumes the Cleanup-section item "Drop `graphitron-common` build
> dependency from `graphitron-rewrite`", since that item's body describes
> the same work.

## Goal

Give `graphitron-rewrite` its own schema-loading path so it no longer
reaches into `graphitron-common` at build time. The new loader:

- Reads each configured schema source as a `Reader`, feeding them
  directly into graphql-java's `MultiSourceReader`. No intermediate
  string materialization, no manual `System.lineSeparator()` append.
- Auto-injects `directives.graphqls` from a rewrite-internal classpath
  resource, so callers (Mojo, tests) pass only user schema files.
- Returns a `TypeDefinitionRegistry`, same contract as the legacy
  `SchemaReadingHelper.getTypeDefinitionRegistry(Set<String>)`.

Post-landing, the only in-repo caller of `SchemaReadingHelper` from
rewrite code is gone, and the rewrite module's `pom.xml` loses its
`graphitron-common` build dependency.

## Scope boundaries

- **In scope:** rewrite's build-time schema parsing at
  `GraphQLRewriteGenerator.java:44` and the test-side equivalent in
  `TestSchemaHelper`. Removal of the Maven dep on `graphitron-common`
  from `graphitron-rewrite/graphitron-rewrite/pom.xml`.
- **Out of scope:** emitted runtime code. `TypeRegistryMethodGenerator`
  (in the legacy codegen module) emits `SchemaReadingHelper.getTypeDefinitionRegistry(...)`
  at the call sites it owns; that runtime dependency on graphitron-common
  stays. Consumers of generated `TypeRegistry.java` still need
  `graphitron-common` on their runtime classpath. The roadmap umbrella
  entry makes this boundary explicit; do not expand it here.
- **Out of scope:** legacy paths. `SchemaReadingHelper` itself stays
  intact; `GraphQLGenerator` (legacy) and `ValidateMojo` continue to use
  it until the legacy generator retires.

## Current state

One call site in rewrite main:

- `GraphQLRewriteGenerator.java:30`: `import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;`
- `GraphQLRewriteGenerator.java:44`: `var registry = getTypeDefinitionRegistry(RewriteConfig.generatorSchemaFiles());`

Two doc-comment references that mention the helper by name (harmless,
must be updated for accuracy):

- `ValidationError.java:9`
- `BuildWarning.java:15`

Legacy implementation, for comparison: `graphitron-common/.../SchemaReadingHelper.java`
is ~100 LOC. Its `readSchemas(Collection<String>)` iterates sources,
calls `readContent(path)` to get a `String` (classpath-first, filesystem
fallback, with a dual-probe that collapses to a `RuntimeException` if
neither finds anything), appends `System.lineSeparator()` to each
string, and hands the resulting strings to `MultiSourceReader.Builder#string`.
The intermediate `String` step is unnecessary: `MultiSourceReader.Builder`
accepts `Reader`s directly.

Directive injection, today: `GeneratorConfig.java:29` declares
`GENERATOR_DIRECTIVES_PATH = GeneratorConfig.class.getResource("schema/directives.graphqls")`,
then `GeneratorConfig.loadProperties` guards the conditional
injection at `:98` (`if (GENERATOR_DIRECTIVES_PATH != null) inputFiles.add(...)`).
The resource lookup resolves relative to
`no.sikt.graphitron.configuration`, so the classloader searches for
`no/sikt/graphitron/configuration/schema/directives.graphqls`. No
source file or JAR entry exists at that path on the codegen
module's classpath (confirmed: running
`GeneratorConfig.class.getResource("schema/directives.graphqls")`
against the compiled classes prints `null`). **The injection is a
silent no-op today.** Rewrite consumers parse `@table` etc. because
their `<userSchemaFiles>` lists explicitly include a classpath copy
of `directives.graphqls` (or because tests prepend it via
`TestSchemaHelper`), not because `GeneratorConfig` injects one.

Two directive-source copies exist in-repo:

- `graphitron-common/src/main/resources/directives.graphqls` (**292
  lines, canonical**). Contains `@table`, `@field`, `@reference`,
  `@asConnection`, `@node`, `@order`, and all other rewrite-relevant
  directives. This is the file this plan mirrors into rewrite's
  own resources.
- `graphitron-schema-transform/src/main/resources/schema/directives.graphqls`
  (14 lines, subset). Declares only `@asConnection`, `@connection`,
  `@feature`; used by `graphitron-schema-transform` internally when
  running its transforms. Irrelevant to rewrite; does not go away
  with this plan.

An unpacked build-time copy also appears at
`graphitron-codegen-parent/graphitron-java-codegen/target/graphitron-common/schema/directives.graphqls`,
produced by the `unpack-graphitron-directives-schema` execution in
`graphitron-java-codegen/pom.xml`. It's consumed by jOOQ codegen as
a schema-input path, not as a runtime classpath resource; not
relevant here.

Test-side: `TestSchemaHelper.java:31-37` loads `directives.graphqls` from
the test classpath (picked up from `graphitron-common`'s root-level
resource via `getClassLoader().getResourceAsStream("directives.graphqls")`)
and prepends it to every test SDL string. Once rewrite owns its own
directives resource, the helper updates to read from the new location.

## Proposed implementation

### 1. Move directives resource into rewrite

Copy `graphitron-common/src/main/resources/directives.graphqls` to
`graphitron-rewrite/graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`.

Rationale for the packaged path: classpath uniqueness. `graphitron-common`'s
copy lives at the root as `directives.graphqls`; keeping the rewrite
copy under a type-package-aligned path avoids collision on any test
classpath that transitively pulls both modules, and makes the resource
loadable via `RewriteSchemaLoader.class.getResourceAsStream("schema/directives.graphqls")`
(same-package relative path, no classloader context juggling).

The two files stay in sync manually for the duration of the umbrella
migration. Once the umbrella retires schema-transform and legacy, the
graphitron-common copy can be deleted and only rewrite's copy remains;
until then, a diff check is a reasonable CI ratchet but is not a
blocker for this item.

### 2. Add `RewriteSchemaLoader`

New class at `graphitron-rewrite/graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/schema/RewriteSchemaLoader.java`.

Shape:

```java
public final class RewriteSchemaLoader {

    private static final String DIRECTIVES_RESOURCE = "schema/directives.graphqls";

    private RewriteSchemaLoader() {}

    public static TypeDefinitionRegistry load(Collection<String> userSchemaPaths) {
        var builder = MultiSourceReader.newMultiSourceReader();
        addDirectivesSource(builder);
        userSchemaPaths.forEach(path -> builder.reader(openSource(path), path));
        try (var multi = builder.trackData(true).build()) {
            var document = new Parser().parseDocument(
                ParserEnvironment.newParserEnvironment()
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .document(multi)
                    .build());
            return new SchemaParser().buildRegistry(document);
        } catch (IOException e) {
            throw new RuntimeException("Schema parse failed", e);
        }
    }

    private static void addDirectivesSource(MultiSourceReader.Builder builder) {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        builder.reader(new InputStreamReader(stream, StandardCharsets.UTF_8), DIRECTIVES_RESOURCE);
    }

    private static Reader openSource(String path) {
        var filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Schema file not found: " + path);
        }
        try {
            return Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Schema file unreadable: " + path, e);
        }
    }
}
```

Target: ~70 LOC including imports. No `String` materialization per
source. Filesystem-only for user schemas: `RewriteSchemaLoader` is
build-time code, and the post-tagged-inputs caller is the resolver's
glob expansion (absolute paths). Legacy's classpath-first probe
existed because `SchemaReadingHelper` is also the runtime loader for
generated code; that helper stays intact and keeps serving the
runtime use case. The directives source remains on classpath via
`addDirectivesSource` — same-module resource, same classloader.

Close semantics: the `try-with-resources` on `MultiSourceReader`
closes it after the `SchemaParser` finishes. `MultiSourceReader`
extends `java.io.Reader` and has its own `close()` method (verified
against graphql-java 25.0's class file). The plan's assumption is
that `MultiSourceReader.close()` cascades to each source-part
`Reader`; verify this in `RewriteSchemaLoaderTest` by wrapping the
fixture reader in an `InputStreamReader` over a test-controlled
`ByteArrayInputStream` and asserting `close()` was invoked. If the
cascade is absent, fall back to tracking each opened `Reader` in a
`List<Reader>` and closing them in a `finally` block inside
`load(...)`.

### 3. Switch `GraphQLRewriteGenerator` to the new loader

Replace the static import and the call site:

- `GraphQLRewriteGenerator.java:30`: remove `import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;`, add
  `import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;`
- `GraphQLRewriteGenerator.java:44`: `var registry = RewriteSchemaLoader.load(RewriteConfig.generatorSchemaFiles());`

`RewriteSchemaLoader.load(Collection<String>)` is agnostic to where
the path list comes from; today it reads `RewriteConfig.generatorSchemaFiles()`
(the static singleton populated by legacy's two-stage Mojo wiring).
The next umbrella item
([plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md))
refactors `GraphQLRewriteGenerator` to take a `RewriteContext` record
in its constructor and derives the file list from its
`SchemaInputResolver`; the Maven-plugin plan then fills in the rest
of the `RewriteContext` shape. Neither downstream plan alters
`RewriteSchemaLoader`'s public API.

### 4. Update `TestSchemaHelper`

Change `TestSchemaHelper.loadDirectives()` to read rewrite's resource
instead of graphitron-common's:

```java
try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("schema/directives.graphqls")) {
    if (is == null) throw new IllegalStateException("schema/directives.graphqls not found on classpath");
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
}
```

Note: this helper stays a test utility (prepend SDL + parse via
`SchemaParser.parse(String)`) because the inline-SDL shape is what 14
test files want. It does not go through `RewriteSchemaLoader`; the
loader is the production entry point, the helper is the inline-test
shortcut. Same directives file, two consumers.

### 5. Drop the Maven dep

In `graphitron-rewrite/graphitron-rewrite/pom.xml`, remove:

```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>graphitron-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

Confirmation: `grep -rln 'no\.sikt\.graphql\|no\.sikt\.graphitron\.common'
graphitron-rewrite/graphitron-rewrite/src/main/` surfaces both live
imports and javapoet `ClassName.get("no.sikt.graphql.…", …)` string
literals in emitter sources (today: `GeneratorUtils`,
`TypeFetcherGenerator`, `GraphitronContextInterfaceGenerator`, and
any future emitter following the same pattern). The assertion this
plan makes is narrower: **no live `import` statements** from
`no.sikt.graphql.*` or `no.sikt.graphitron.common.*` may remain
(only the post-`RewriteSchemaLoader` switch at `GraphQLRewriteGenerator.java:30`
would violate this). String-literal javapoet references are
expected and unaudited; they are emitted into generated Java, not
executed in rewrite's own classpath.

Scope boundary: `graphitron-rewrite-fixtures/pom.xml` has **no**
`graphitron-common` dependency today (verified). `graphitron-rewrite-test/pom.xml`
has it as a compile dep (also verified). This plan drops the dep
from `graphitron-rewrite/graphitron-rewrite/pom.xml` only; the
`graphitron-rewrite-test` dep stays and retires with its own
follow-up sub-item once `TestSchemaHelper` and other test-support
code no longer reach into legacy symbols. The roadmap's Cleanup
entry "Drop `graphitron-common` build dependency from
`graphitron-rewrite`" absorbs into this plan; the analogous
rewrite-test entry is tracked separately.

### 6. Update stale doc comments

- `ValidationError.java:9`: replace "when the schema is parsed via
  {@code SchemaReadingHelper}" with "{@code RewriteSchemaLoader}".
- `BuildWarning.java:15`: same change.
- `TypeFetcherGenerator.java:1174`: the
  `{@link no.sikt.graphql.GraphitronContext#getTenantId}` Javadoc
  link becomes an unresolvable cross-module reference once the
  `graphitron-common` dep drops. Switch `{@link}` → `{@code}` so
  the reference stays stylistic and doesn't trip Javadoc with
  `-Xdoclint`. Sibling `GraphitronContextInterfaceGenerator.java`
  already uses `{@code}` and needs no change.

Pure documentation; no behavioural impact.

## Tests

- **Unit:** `RewriteSchemaLoaderTest`
  - Loads a two-file fixture from classpath + filesystem, asserts the
    resulting `TypeDefinitionRegistry` contains both types.
  - Asserts the `@table` directive is resolvable without the caller
    providing a directives path (auto-injection proof).
  - Asserts a missing source throws `RuntimeException` with the path in
    the message (matches legacy's contract).
  - Asserts each source-part `Reader` is closed on the happy path.
    Wrap a fixture `Reader` in a counting / tracking subclass (or
    use Mockito to spy on `close()`) and assert it was invoked
    after `load(...)` returns. If the assertion fails, the loader
    falls back to explicit per-Reader close-tracking (see §2's close
    semantics paragraph).
- **Pipeline:** the existing `GraphitronSchemaBuilderTest` suite passes
  unchanged. If any case fails, it indicates a directive-parsing
  regression, not a scope issue.
- **Emitted-output ratchet:** `GeneratedSourcesLintTest` (in
  `graphitron-rewrite-test`) gains a rule: rewrite-emitted fetcher
  sources contain no `no.sikt.graphql.schema.SchemaReadingHelper`
  import references. Sanity check that no future generator quietly
  reaches back through the legacy helper. The test class already
  does import-origin assertions (`emittedSourcesDoNotUseVar`,
  `entityConditionsClassesHaveNoGraphqlJavaImports`); the new rule
  copies one of those with a different filter. ~15 LOC.

## Deliverable

One commit covering five regions:

1. Add `graphitron-rewrite/graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
   (copied verbatim from `graphitron-common/src/main/resources/directives.graphqls`,
   the 292-line canonical source).
2. Add `RewriteSchemaLoader.java` + `RewriteSchemaLoaderTest.java`.
3. Switch `GraphQLRewriteGenerator.java` to the new loader.
4. Switch `TestSchemaHelper.loadDirectives()` to the rewrite resource.
5. Remove the Maven dep from `graphitron-rewrite/graphitron-rewrite/pom.xml`;
   update Javadoc in `ValidationError.java` and `BuildWarning.java`.

Expected diff size: ~200 lines added (mostly the copied directives
file, ~100 LOC, plus the ~80 LOC loader + its test). Net deletion in
the rewrite main tree: one import plus one static method reference.

## Rollout

**Pre-landing consumer-pom sweep.** The new loader auto-injects
`directives.graphqls` from rewrite's own classpath and does not
filter the caller's schema-file list. Any consumer pom that lists
a `directives.graphqls` path in `<userSchemaFiles>` (or its
rewrite-equivalent once the Maven-plugin plan lands) will fail
schema parse with "directive '@table' redefined" on first build
after this lands. Sweep and update consumer poms to drop the
explicit directives entry before merging. In-house-only consumers;
no external-consumer migration window required.

Post-landing, update the roadmap:

- Move "Rewrite owns schema loading + directive auto-injection" from
  the umbrella's checklist to a `Done` line in the roadmap's Done
  section.
- Delete the Cleanup-section entry "Drop `graphitron-common` build
  dependency from `graphitron-rewrite`" (absorbed).
- The next umbrella sub-item in Spec is "Rewrite owns pattern-matched
  `@tag` + description notes"
  ([plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md)),
  which depends on this plan's `RewriteSchemaLoader` and can move
  to Ready once this lands. The "Rewrite-owned Maven plugin"
  ([plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md))
  lands after tagged-inputs. "Rewrite owns type-extension merging"
  is the next Backlog item to promote.

## Open decisions

**D1.** Where does the rewrite copy of `directives.graphqls` live?
Recommend `graphitron-rewrite/graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
to match the package-aligned classpath lookup pattern. Alternative:
root classpath as `directives.graphqls`. Package-scoped avoids the
collision risk on shared classpaths and is cheap to maintain.

**D2.** Does `RewriteSchemaLoader` belong in `no.sikt.graphitron.rewrite.schema`
or in the existing `no.sikt.graphitron.rewrite` top-level package?
Recommend a new `schema` sub-package so the future migrations
(`MergeExtensions`, `MakeConnections`, `ElementRemovalFilter`,
`DirectivesFilter`, feature-flag splits) land alongside it. This is the
first citizen of what will become rewrite's schema-pre-processing
module boundary.

**D3.** Keep the sync-check between graphitron-common's and rewrite's
`directives.graphqls`? Canonical source is
`graphitron-common/src/main/resources/directives.graphqls` (the
292-line copy). `graphitron-schema-transform`'s 14-line
`schema/directives.graphqls` is not in scope: it's a transform-time
subset (`@asConnection`, `@connection`, `@feature`) consumed only
by `graphitron-schema-transform` itself and retires with that module.
Options for the common ↔ rewrite sync: (i) leave it manual for the
migration window, (ii) add a build-time diff assertion, (iii) have
rewrite's resource be a generated copy via maven-resources-plugin
filtering or similar. Recommend (i) for the migration window; drift
between common and rewrite is detectable because legacy tests fail
fast on unknown directives. Once the schema-transform module retires
and graphitron-common's copy can be deleted, only rewrite's copy
remains and the sync question is moot.
