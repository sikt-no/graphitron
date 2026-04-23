# Plan: Rewrite owns schema loading + directive auto-injection

> **Status:** Spec
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
  `GraphQLRewriteGenerator.java:38` and the test-side equivalent in
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

- `GraphQLRewriteGenerator.java:24`: `import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;`
- `GraphQLRewriteGenerator.java:38`: `var registry = getTypeDefinitionRegistry(RewriteConfig.generatorSchemaFiles());`

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

Directive injection, today: `GeneratorConfig.loadProperties` appends
`GENERATOR_DIRECTIVES_PATH.getPath()` to the `generatorSchemaFiles` set
before rewrite runs, so `RewriteConfig.generatorSchemaFiles()` inherits
the legacy injection implicitly. The directives file lives at
`graphitron-codegen-parent/graphitron-java-codegen/src/main/resources/schema/directives.graphqls`
(tracked) and is also copied to `graphitron-common/src/main/resources/directives.graphqls`
(the canonical file that the runtime helper loads from classpath). The
two files currently agree on the rewrite-relevant directives; rewrite
should get its own copy so it stops depending on legacy's pre-stuff.

Test-side: `TestSchemaHelper.java:26-33` loads `directives.graphqls` from
the test classpath (picked up from `graphitron-common`'s resource) and
prepends it to every test SDL string. Once rewrite owns its own
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
        var multi = builder.trackData(true).build();
        var document = new Parser().parseDocument(
            ParserEnvironment.newParserEnvironment()
                .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                .document(multi)
                .build());
        return new SchemaParser().buildRegistry(document);
    }

    private static void addDirectivesSource(MultiSourceReader.Builder builder) {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        builder.reader(new InputStreamReader(stream, StandardCharsets.UTF_8), DIRECTIVES_RESOURCE);
    }

    private static Reader openSource(String path) {
        // classpath first, filesystem fallback (matches SchemaReadingHelper's probe order)
        var normalized = path.startsWith("/") ? path.substring(1) : path;
        var cl = Thread.currentThread().getContextClassLoader();
        var stream = cl.getResourceAsStream(normalized);
        if (stream != null) {
            return new InputStreamReader(stream, StandardCharsets.UTF_8);
        }
        var filePath = Paths.get(path);
        if (Files.exists(filePath)) {
            try {
                return Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Schema file unreadable: " + path, e);
            }
        }
        throw new RuntimeException("Schema file not found: " + path);
    }
}
```

Target: ~80 LOC including imports. No `String` materialization per
source. The classpath-first / filesystem-fallback probe is preserved
verbatim because existing schema-file configs rely on both resolution
modes (Quarkus dev mode in particular uses the context classloader
path).

### 3. Switch `GraphQLRewriteGenerator` to the new loader

Replace the static import and the call site:

- `GraphQLRewriteGenerator.java:24`: remove `import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;`, add
  `import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;`
- `GraphQLRewriteGenerator.java:38`: `var registry = RewriteSchemaLoader.load(RewriteConfig.generatorSchemaFiles());`

`RewriteSchemaLoader.load(Collection<String>)` is agnostic to where
the path list comes from; today it reads `RewriteConfig.generatorSchemaFiles()`
(the static singleton populated by legacy's two-stage Mojo wiring). Once
[plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md) lands, the
caller becomes `RewriteContext` (a per-invocation record threaded
through the constructor), with the file list derived from the
`<schemaInputs>` resolver; see
[plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md). Neither
downstream plan alters `RewriteSchemaLoader`'s public API.

### 4. Stop the legacy pre-injection for rewrite

`GeneratorConfig.loadProperties` appends `GENERATOR_DIRECTIVES_PATH.getPath()`
to the schema file set at legacy-module level; once rewrite injects
its own directives, rewrite would double-parse the legacy file.

Fix: at `GenerateMojo.java:192-198`, populate
`RewriteConfig.generatorSchemaFiles` from the unmodified
`mojo.getSchemaFiles()` list rather than from `GeneratorConfig.generatorSchemaFiles()`.
One-line change; rewrite's file set never sees the legacy directives
path. Legacy continues to read `GeneratorConfig.generatorSchemaFiles()`
unchanged.

### 5. Update `TestSchemaHelper`

Change `TestSchemaHelper.loadDirectives()` to read rewrite's resource
instead of graphitron-common's:

```java
try (InputStream is = TestSchemaHelper.class.getResourceAsStream("schema/directives.graphqls")) {
    if (is == null) throw new IllegalStateException("schema/directives.graphqls not found on classpath");
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
}
```

Note: this helper stays a test utility (prepend SDL + parse via
`SchemaParser.parse(String)`) because the inline-SDL shape is what 14
test files want. It does not go through `RewriteSchemaLoader`; the
loader is the production entry point, the helper is the inline-test
shortcut. Same directives file, two consumers.

### 6. Drop the Maven dep

In `graphitron-rewrite/graphitron-rewrite/pom.xml`, remove:

```xml
<dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>graphitron-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

Confirmation: `grep -rln 'no\.sikt\.graphql\|no\.sikt\.graphitron\.common'
graphitron-rewrite/graphitron-rewrite/src/main/` must show only
`ClassName.get(...)` references in `GeneratorUtils` / `TypeFetcherGenerator`
(javapoet strings for emitted code, not imports). No actual import
statement from `graphitron-common` should remain.

The test source tree still touches `graphitron-common` symbols
indirectly via `graphitron-rewrite-fixtures` (which may transitively
depend on common). Audit: if the fixtures module also has no
`graphitron-common` compile dep at this point, the umbrella can close
the Cleanup item entirely. If fixtures still depends on common, the
rewrite-main dep drop is sufficient for this plan; the fixtures
side lands with its own sub-item.

### 7. Update stale doc comments

- `ValidationError.java:9`: replace "when the schema is parsed via
  {@code SchemaReadingHelper}" with "{@code RewriteSchemaLoader}".
- `BuildWarning.java:15`: same change.

Pure documentation; no behavioural impact.

## Tests

- **Unit:** `RewriteSchemaLoaderTest`
  - Loads a two-file fixture from classpath + filesystem, asserts the
    resulting `TypeDefinitionRegistry` contains both types.
  - Asserts the `@table` directive is resolvable without the caller
    providing a directives path (auto-injection proof).
  - Asserts a missing source throws `RuntimeException` with the path in
    the message (matches legacy's contract).
  - Asserts each `Reader` is closed on the happy path (use
    `InputStreamReader` wrapping a `ByteArrayInputStream` the test
    controls, assert `close()` was called).
- **Pipeline:** the existing `GraphitronSchemaBuilderTest` suite passes
  unchanged. If any case fails, it indicates a directive-parsing
  regression, not a scope issue.
- **Emitted-output ratchet:** `GeneratedSourcesLintTest` (if present
  for this module) adds a rule: rewrite-emitted fetcher sources contain
  no `no.sikt.graphql.schema.SchemaReadingHelper` import references.
  (Sanity check that no future generator quietly reaches back through
  the legacy helper.) Skip if the existing lint tests don't cover
  reference-origin assertions; unit tests above are sufficient.

## Deliverable

One commit covering six regions:

1. Add `graphitron-rewrite/graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`
   (copied verbatim from `graphitron-common/src/main/resources/directives.graphqls`).
2. Add `RewriteSchemaLoader.java` + `RewriteSchemaLoaderTest.java`.
3. Switch `GraphQLRewriteGenerator.java` to the new loader.
4. `GenerateMojo` populates `RewriteConfig.generatorSchemaFiles`
   from `mojo.getSchemaFiles()` (pre-pre-injection), not from
   `GeneratorConfig.generatorSchemaFiles()`.
5. Switch `TestSchemaHelper.loadDirectives()` to the rewrite resource.
6. Remove the Maven dep from `graphitron-rewrite/graphitron-rewrite/pom.xml`;
   update Javadoc in `ValidationError.java` and `BuildWarning.java`.

Expected diff size: ~200 lines added (mostly the copied directives
file, ~100 LOC, plus the ~80 LOC loader + its test). Net deletion in
the rewrite main tree: one import plus one static method reference.

## Rollout

Post-landing, update the roadmap:

- Move "Rewrite owns schema loading + directive auto-injection" from
  the umbrella's checklist to a `Done` line in the roadmap's Done
  section.
- Delete the Cleanup-section entry "Drop `graphitron-common` build
  dependency from `graphitron-rewrite`" (absorbed).
- The next two umbrella sub-items are already in Spec: "Rewrite-owned
  Maven plugin" ([plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md))
  and "Rewrite owns pattern-matched `@tag` + description notes"
  ([plan-tagged-schema-inputs.md](plan-tagged-schema-inputs.md)); both
  reference this plan's `RewriteSchemaLoader` and can move to Ready
  once this lands. "Rewrite owns type-extension merging" is the next
  Backlog item to promote.

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
`directives.graphqls`? Options: (i) leave it manual for the migration
window, (ii) add a build-time diff assertion, (iii) have rewrite's
resource be a symlink or a generated copy. Recommend (i) for the
migration window; drift between the two is detectable because legacy
tests fail fast on unknown directives.
