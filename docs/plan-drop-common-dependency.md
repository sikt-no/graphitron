# Drop `graphitron-common` Runtime Dependency from `graphitron-rewrite`

## Overview

`graphitron-rewrite` declares `graphitron-common` as a compile-scope dependency but uses
only two things from it:

| Usage | Location | Type |
|---|---|---|
| `SchemaReadingHelper.getTypeDefinitionRegistry` | `GraphQLRewriteGenerator.java:24` | Production import |
| `directives.graphqls` classpath resource | `TestSchemaHelper.java:27` | Test classpath resource |

Everything else attributed to `graphitron-common` — `GraphitronContext`, `NodeIdStrategy`,
and related types — is referenced only as strings via JavaPoet `ClassName.get(...)`.
The generator emits those names into generated code; it never imports or instantiates them.

The goal is to make `graphitron-rewrite` self-contained: it should load the directives
it owns, parse user schemas without requiring callers to pre-bundle the directive file,
and carry no dependency on `graphitron-common` at build or runtime.

## Why the current approach is wrong

`SchemaReadingHelper.getTypeDefinitionRegistry` loads whatever files the caller provides.
If `directives.graphqls` is not in the user's schema list, the parser either rejects the
unknown `@table`, `@field`, `@reference`, etc. directives or silently ignores them —
depending on the SDL parser's leniency mode.

Users should not have to know about or declare the Graphitron directive file. The generator
owns that file and should inject it automatically on every parse.

## What `SchemaReadingHelper` actually does

`SchemaReadingHelper` wraps three graphql-java primitives that the rewrite can use directly:

- `MultiSourceReader.Builder.reader(Reader, String)` — accepts a `Reader` per source with
  a source name for error location tracking. `MultiSourceReader` itself extends `Reader`.
- `SchemaParser.parse(Reader)` — accepts any `Reader` and returns `TypeDefinitionRegistry`
  directly. Accepts a `MultiSourceReader` without any wrapper.

The existing helper reads each file to a `String` first, then adds the string to
`MultiSourceReader`, then calls `new Parser().parseDocument(...)`, then wraps the `Document`
in `SchemaParser.buildRegistry(Document)`. Every intermediate step is unnecessary.
`SchemaParser.parse(MultiSourceReader)` covers the full chain in one call.

## Plan

### Step 1 — Copy `directives.graphqls` into graphitron-rewrite's main resources

Add a Maven `<resources>` entry that copies `directives.graphqls` from
`graphitron-common`'s source tree into graphitron-rewrite's compiled resources.
This gives the generator classpath access to the file without duplicating it or
depending on the common jar.

In `graphitron-rewrite/pom.xml`:

```xml
<build>
    <resources>
        <resource>
            <directory>src/main/resources</directory>
        </resource>
        <resource>
            <directory>../graphitron-common/src/main/resources</directory>
            <includes>
                <include>directives.graphqls</include>
            </includes>
        </resource>
    </resources>
</build>
```

This is the single source of truth: the file lives in `graphitron-common`, is copied
by Maven at build time, and is available on the classpath wherever graphitron-rewrite runs.

### Step 2 — Inline schema loading with automatic directive injection

Replace the `SchemaReadingHelper` import in `GraphQLRewriteGenerator` with a private
helper that:

1. Opens `directives.graphqls` from the module's own classpath as the first source.
2. Opens each user schema file from the filesystem.
3. Feeds all sources into a single `MultiSourceReader` (preserving per-file error locations).
4. Parses in one call to `SchemaParser.parse(Reader)`.

```java
private static TypeDefinitionRegistry loadSchemas(Set<String> userSchemaPaths) {
    var builder = MultiSourceReader.newMultiSourceReader().trackData(true);
    try (var is = GraphQLRewriteGenerator.class.getResourceAsStream("/directives.graphqls")) {
        if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
        builder.reader(new InputStreamReader(is, StandardCharsets.UTF_8), "directives.graphqls");
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
    for (var path : userSchemaPaths) {
        try {
            builder.reader(Files.newBufferedReader(Path.of(path)), path);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read schema: " + path, e);
        }
    }
    return new SchemaParser().parse(builder.build());
}
```

Call site in `generate()` becomes:

```java
var registry = loadSchemas(RewriteConfig.generatorSchemaFiles());
```

### Step 3 — Update `TestSchemaHelper`

`TestSchemaHelper` currently loads `directives.graphqls` from the classpath manually
and prepends it to the inline SDL string before parsing. With Step 1 in place, the file
is on the test classpath from the module's own resources (no dependency on the
`graphitron-common` jar needed). The `TestSchemaHelper.loadDirectives()` method and
the manual concatenation can be removed; the parser already receives the directives as
a separate named source via a helper analogous to `loadSchemas` above, or test schemas
can be parsed through a shared `TestSchemaHelper.buildSchema(String)` that delegates to
a version of `loadSchemas` that accepts an inline SDL string as an additional source.

The test resource entry in `pom.xml` is not needed separately — Step 1's `<resources>`
block already places `directives.graphqls` on both the main and test classpaths (Maven
includes main resources in test compilation by default).

### Step 4 — Remove `graphitron-common` dependency

Remove the `<dependency>` block for `graphitron-common` from `graphitron-rewrite/pom.xml`.

Verify by building: `mvn clean install -pl graphitron-rewrite --am -Pquick`.

## What is explicitly out of scope

- **`graphitron-schema-transform`** — `SchemaReader` there wraps `SchemaReadingHelper`
  and adds federation-specific transforms. Keeping that module's dependency on
  `graphitron-common` is correct and intentional; no changes needed there.
- **Writing `directives.graphqls` to the build output for developer tooling** — this is
  a separate concern (IDE / graphql-config integration) that deserves its own decision.
  The question of whether `generate-code` or `transform` should materialise the directives
  file for graphql-config consumption is deferred.
- **`graphitron-java-codegen`** — the legacy generator also depends on
  `graphitron-common` for `SchemaReadingHelper` and many other types. That dependency
  is legitimate and in-scope for the legacy module; this plan covers the rewrite only.

## Verification checklist

- [ ] `graphitron-rewrite` compiles with no `graphitron-common` on the classpath.
- [ ] All existing `GraphitronSchemaBuilderTest` / `TablePipelineTest` / `FetcherPipelineTest`
      tests pass — they build schemas via `TestSchemaHelper`, which exercises the directive
      injection path.
- [ ] A schema that uses `@table`, `@field`, `@reference` etc. but does not list
      `directives.graphqls` in the configured schema files is accepted by the validator
      (directives injected automatically).
- [ ] A schema that explicitly lists `directives.graphqls` in its schema files does not
      produce a "type already defined" conflict (directives are idempotent to re-declare,
      or the loader deduplicates the source).
