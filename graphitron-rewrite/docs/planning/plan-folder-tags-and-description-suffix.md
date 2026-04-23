# Plan: Folder-based `@tag` + description suffix

> **Status:** Spec
>
> Slice of the "Dissolve `graphitron-schema-transform` module" roadmap
> umbrella. Carves the `@tag` + description-suffix behaviour out of the
> larger "Rewrite owns feature-flag SDL splits" item so it can ship
> independently of the `@feature` directive and the `<outputSchemas>`
> splits.
>
> Depends on "Rewrite owns schema loading + directive auto-injection"
> landing first; this transform runs inside the same
> `GraphQLRewriteGenerator` step that plan introduces.

## Goal

Port the legacy `FeatureConfiguration` behaviour that mutates schema
elements based on the folder their source `.graphqls` file lives in,
restricted to two concerns:

1. **Tag application.** Add `@tag(name: "<folder>")` to elements under
   `features/<folder>/...`, where `<folder>` is the first path segment
   after the `features/` marker.
2. **Description suffix.** Append the contents of
   `features/<folder>/description-suffix.md` (if present) to the
   description of every element whose source file lives under that
   folder.

Out of scope for this slice: the `@feature(flags: [...])` directive,
the `<outputSchemas>` feature-flag splits, `SchemaFeatureFilter`. Those
land with the remaining "Rewrite owns feature-flag SDL splits" work.

## Scope

**In scope**

- Fields on object + interface types (`FieldDefinition`).
- Input object fields (`InputValueDefinition` under `InputObjectTypeDefinition`).
- Enum values (`EnumValueDefinition`).
- Field arguments (`InputValueDefinition` under `FieldDefinition`).
- Union type declarations themselves (`UnionTypeDefinition`).

(Same element set as legacy's visitor. No object or interface type
itself gets tagged; only its fields.)

**Out of scope**

- `@feature` directive application.
- `<outputSchemas>` schema-file splits.
- Object / interface / enum / input type declarations themselves
  (neither legacy nor this plan tag those).
- Connection / Edge / PageInfo auto-generated types. Rewrite's
  Connection synthesis is still emitter-side; there are no
  registry-level synthesized nodes to tag at this pipeline stage.
  Revisit when "Rewrite owns `@asConnection` → Connection synthesis"
  lands.

## Context

Legacy implementation: `graphitron-schema-transform/.../FeatureConfiguration.java`.
Operates on the built `GraphQLSchema`; visits each node; reads
`node.getDefinition().getSourceLocation().getSourceName()` to recover
the source file; splits on `File.separator + "features" + File.separator`
and takes the first path segment as the tag/feature name.

Key legacy behaviours the rewrite port preserves:

- Tag name = first path segment after `features/` (not nested folder
  depth). `features/a/b/foo.graphqls` → tag `a`, not `a/b`.
- Skip elements whose source file is not under a `features/` directory.
- Skip elements that already declare an explicit `@tag` (don't
  override author intent).
- Description suffix is `existing.strip() + "\n\n" + suffix.strip()`
  when an existing description is present; otherwise just `suffix`.
- Skip internal / built-in elements.

Key legacy behaviours this port changes:

- **Decouple description suffix from `@feature`.** Legacy applies the
  suffix only when `@feature` is added. Rewrite applies it whenever
  the element's source folder has a `description-suffix.md`, whether
  or not `@tag` is also added. This makes description suffix usable
  independently.
- **Federation import check drops.** Legacy guards `@tag` application
  on `federation.getDirective("@link").import` containing `"@tag"`.
  Rewrite assumes `@tag` is either declared in the schema or it
  isn't; if the directive is undeclared, application is a no-op
  silently (same as legacy's null-guard).
- **Operate on `TypeDefinitionRegistry`**, not on the built schema.
  The rewrite slot is after registry production, before
  `GraphitronSchemaBuilder.build()`. `SourceLocation` is preserved
  on every `Definition`, so we don't need the built schema for
  source-file tracking.

## Design

Two independently-toggleable transforms sharing one folder detector.

### 1. `FolderPartitioner` (shared helper)

Pure function from a `Definition` to `Optional<String>` (the folder
name, or empty if the definition is not under a `features/` folder).

```java
final class FolderPartitioner {
    private static final String MARKER = File.separator + "features" + File.separator;

    static Optional<String> folderFor(Node<?> node) {
        var loc = node.getSourceLocation();
        if (loc == null || loc.getSourceName() == null) return Optional.empty();
        var idx = loc.getSourceName().indexOf(MARKER);
        if (idx < 0) return Optional.empty();
        var after = loc.getSourceName().substring(idx + MARKER.length());
        var firstSep = after.indexOf(File.separatorChar);
        if (firstSep < 0) return Optional.empty();
        return Optional.of(after.substring(0, firstSep));
    }
}
```

No state. Called from both transforms.

### 2. `FolderTagTransform`

Walks the `TypeDefinitionRegistry`; for every in-scope element whose
source file resolves to a folder, appends a `@tag(name: "<folder>")`
directive, unless the element already carries an explicit `@tag`.

Rewrite-specific simplification: operates on `Definition` directly via
`TypeDefinitionRegistry.parse()`-produced AST, not on
`GraphQLAppliedDirective` wrappers. Directive application uses
`DirectivesContainer.transform(builder -> builder.directive(...))`
pattern.

### 3. `FolderDescriptionSuffixTransform`

Reads `description-suffix.md` files from the configured schema root(s)
into `Map<String, String>` (folder-name → suffix content). Walks the
`TypeDefinitionRegistry`; for every in-scope element whose source
folder has a suffix, replaces the description with
`existing + "\n\n" + suffix` (or just `suffix` if existing is null /
blank).

Also applies to the type declarations themselves (object, interface,
input, enum, union) when their source file is under a folder with a
suffix. This is a natural widening over legacy; the suffix is
documentation; tagging element granularity need not match description
granularity. (Open decision: keep legacy's narrower set. See D2.)

## Placement in the pipeline

`GraphQLRewriteGenerator.generate()` today:

```
registry = getTypeDefinitionRegistry(...)         // plan: RewriteSchemaLoader.load(...)
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

After this plan:

```
registry = RewriteSchemaLoader.load(...)
FolderTagTransform.apply(registry, config)              // ← new
FolderDescriptionSuffixTransform.apply(registry, config)// ← new
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

Both transforms mutate the registry in place (registry is mutable;
AST `Definition` nodes are immutable, so mutation is "replace each
transformed definition"). Order between the two is independent;
description suffix doesn't depend on tag application or vice versa.

## Config surface

Two independent toggles on `RewriteConfig` (default both off for
existing projects; turn on in consumer pom.xml):

- `folderTagsEnabled: boolean` (default `false`)
- `folderDescriptionSuffixEnabled: boolean` (default `false`)
- `folderDescriptionSuffixFilename: String` (default `"description-suffix.md"`)

Maven plugin exposes the same three keys on
`GenerateMojo` / `TransformPluginConfiguration` (whichever owns the
rewrite pass config; see Open decisions D1).

No `schemaRootDirectories` key: the folders are discovered from the
paths already in `RewriteConfig.generatorSchemaFiles()`. Each schema
file's path reveals which `features/<folder>/` it lives under; the
description-suffix transform walks up from each schema file to find
adjacent `description-suffix.md` files, keyed by folder name.

## Implementation

New package `no.sikt.graphitron.rewrite.schema.transform`, landing
alongside `RewriteSchemaLoader` (created by the schema-loading plan).

1. `FolderPartitioner.java`: static helper above, ~25 LOC.
2. `FolderTagTransform.java`: `static void apply(TypeDefinitionRegistry, RewriteConfig)`, ~80 LOC.
3. `FolderDescriptionSuffixTransform.java`: same shape, plus a
   private `loadSuffixMap(Collection<String> schemaPaths, String filename)`
   that walks the file system, ~100 LOC.
4. Two call sites added in `GraphQLRewriteGenerator.generate()`
   between registry load and schema build.
5. Two config fields on `RewriteConfig` plumbed through from the Mojo.

Expected diff: ~300 LOC added, plus tests.

### Element walking

`TypeDefinitionRegistry.types()`, `.objectTypeExtensions()`,
`.inputObjectTypeExtensions()`, and friends expose definition maps.
For each in-scope definition, recursively visit children (fields,
input fields, enum values, arguments) and rebuild via
`.transform(builder -> ...)`.

Type extensions (`extend type Foo`) are handled per-extension; each
extension definition carries its own `SourceLocation`, so fields in
different extension files of the same type can receive different tags.
Consistent with legacy.

### Directive declaration requirement

`@tag` must be declared in the schema for this transform to add it
validly. Today that comes from federation's bundled directives
(`@link`-imported) or from the caller's `directives.graphqls`. If
`@tag` is not declared, `FolderTagTransform` logs a warning and
returns without mutating. (Open decision D3: hard error vs. warn.)

## Tests

### Unit: `FolderPartitionerTest`

- `features/a/foo.graphqls` → `"a"`.
- `features/a/b/foo.graphqls` → `"a"` (first segment only).
- `features/a/foo.graphqls` at root (no trailing segment after `a/`) → empty.
- `schema/foo.graphqls` (no `features/` marker) → empty.
- `null` source name → empty.

### Unit: `FolderTagTransformTest`

- Field under `features/a/foo.graphqls` gains `@tag(name: "a")`.
- Field with explicit `@tag(name: "x")` retains `"x"` and does not
  receive `"a"`.
- Field outside `features/` is untouched.
- Tagged elements: fields, input fields, enum values, arguments,
  unions (one test per kind).
- Object / interface / enum / input type declarations themselves are
  untouched.
- When `@tag` is undeclared in the schema, the transform warns and
  produces no changes (D3).

### Unit: `FolderDescriptionSuffixTransformTest`

- Field under `features/a/foo.graphqls` with existing description
  gets `existing + "\n\n" + suffix`.
- Field with no description gets `suffix` alone.
- Field outside `features/` is untouched.
- Missing `description-suffix.md` for a folder is a no-op for that
  folder (not an error).
- Double-apply is idempotent? (Open decision D4: detect prior
  application, or leave as author responsibility.)

### Pipeline: `GraphitronSchemaBuilderTest` additions

Two cases: tag-enabled, suffix-enabled. Assert on the built
`GraphitronSchema` that tags / descriptions reach the generator as
expected. No emission-level assertions; this is a registry-level
transform and the emitter side is agnostic.

### Emission ratchet

None expected. Generated Java output shouldn't change, because
`@tag` and descriptions don't participate in fetcher emission. If a
lint ratchet is warranted, it's on the SDL-emission side, deferred
to "Rewrite emits the client SDL as generated output" (umbrella item).

## Open decisions

**D1. Config home.** Put the toggles on `RewriteConfig` (plan's
preference) or on a nested `RewriteConfig.SchemaTransformConfig`
record? Recommend flat until there's a third related knob.

**D2. Description-suffix element scope.** Legacy applies description
changes only to fields / input fields / enum values / arguments /
unions (because it couples suffix to `@feature` which was also
element-level). Rewrite decouples suffix from `@feature`; should
suffix also apply to the type declarations themselves (object,
interface, enum, input, union)? Widening makes documentation more
useful; narrowing keeps behavioural parity with legacy. Recommend
widen; call it out in the roadmap Done entry.

**D3. Missing `@tag` directive declaration.** Warn-and-skip (current
proposal) vs. hard error vs. inject the declaration automatically
(`directive @tag(name: String!) repeatable on FIELD_DEFINITION |
...`). Auto-injection is friendliest but ties us to a specific @tag
shape; if the federation spec evolves we might drift. Recommend warn-
and-skip; document the required declaration in the knob's Javadoc.

**D4. Idempotency of description suffix.** If a suffix is applied
twice (e.g. re-runs in dev mode), the description duplicates. Options:
(a) accept: the transform is build-time, dev iteration rebuilds from
source; (b) detect a marker line and skip; (c) store the original in
the AST via a synthetic directive and diff on re-apply. Recommend (a);
the AST is not reused across builds.

**D5. Legacy compatibility window.** Should we keep legacy's
`@feature` + split behaviour running in parallel for schemas that
still enable it via the legacy Mojo path? Yes, this plan lands
purely as additive rewrite behaviour. Legacy path is unchanged. The
umbrella's "Retire `graphitron-schema-transform`" landing marker
closes the legacy path later.

## Roadmap integration

Amend `rewrite-roadmap.md` umbrella checklist:

- **Add** a new sub-item between "Rewrite owns directive stripping..."
  and "Rewrite emits the client SDL...":
  `**Rewrite owns folder-based @tag + description suffix** **[Spec]**: port the @tag(name:) application and description-suffix append behaviour from `FeatureConfiguration`, keyed on source-file folder. Excludes @feature directive and <outputSchemas> splits (covered by the next item). ([plan](plan-folder-tags-and-description-suffix.md))`
- **Reduce scope** on the existing "Rewrite owns feature-flag SDL
  splits" entry: strike the `FeatureConfiguration` mention, leaving
  `SchemaFeatureFilter` + `splitFeatures` + `<outputSchemas>`
  plumbing. LOC estimate drops from ~500 to ~400.

On landing, move to `## Done` with a one-line summary pointing at the
commit sha(s) and the key tests.
