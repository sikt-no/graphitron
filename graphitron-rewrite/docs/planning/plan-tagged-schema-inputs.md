# Plan: Pattern-matched schema inputs with tags + description notes

> **Status:** Spec
>
> Slice of the "Dissolve `graphitron-schema-transform` module" roadmap
> umbrella. Replaces legacy `FeatureConfiguration`'s `@tag` +
> description-suffix behaviour with an explicit, config-driven model:
> each schema input is a glob pattern plus an optional tag and an
> optional description note. Carries a tighter contract than legacy
> (overlap is an error, not a silent union).
>
> Excludes `@feature` directive and `<outputSchemas>` splits; those
> land with the remaining "Rewrite owns feature-flag SDL splits" item.
>
> Depends on "Rewrite owns schema loading + directive auto-injection"
> landing first; this transform runs inside the same
> `GraphQLRewriteGenerator` step that plan introduces. Also depends on
> the "Rewrite-owned Maven plugin" plan
> ([plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md)) for
> the `<schemaInputs>` config surface and the `SchemaInputBinding`
> POJO; `RewriteContext.schemaInputs()` is the source this transform
> reads from.

## Goal

Give the rewrite Mojo an explicit `<schemaInputs>` list where each
entry matches one or more schema files by glob pattern and optionally
attaches:

- a **tag** applied as `@tag(name: "<tag>")` to in-scope elements
  defined in the matched files, and
- a **description note** appended (with a blank-line separator) to
  the description of those elements.

One tag per entry, one note per entry. No implicit folder convention;
the Maven config is the only source of truth. Two build-time errors,
both owned by rewrite with precise messages: an entry whose pattern
matches zero files, and a file matched by two or more entries.

Out of scope: the `@feature(flags: [...])` directive, the
`<outputSchemas>` feature-flag splits, `SchemaFeatureFilter`. Those
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
and takes the first path segment as the tag/feature name. The
description-suffix reads a `description-suffix.md` file alongside the
matched folder.

Key legacy behaviours the rewrite port preserves:

- Skip elements that already declare an explicit `@tag` (don't
  override author intent).
- Description suffix is `existing.strip() + "\n\n" + note.strip()`
  when an existing description is present; otherwise just `note`.
- Skip internal / built-in elements.

Key legacy behaviours this port changes:

- **No folder convention.** Tag and note come from Maven config, not
  from the source file's folder path. Any file anywhere can be tagged.
  Consumers migrate by translating each `features/<name>/` directory
  into a `<schemaInput><pattern>features/<name>/**</pattern><tag><name></tag>...` entry.
- **No `description-suffix.md`.** The note is a literal string in the
  Maven config. Consumers migrating from legacy can inline the file's
  contents or keep them in a `<![CDATA[ ... ]]>` block.
- **Decouple note from `@feature`.** Legacy applies the suffix only
  when `@feature` is added; rewrite applies the note whenever a
  matching entry declares one, independent of `@tag`.
- **Federation import check drops.** Legacy guards `@tag` on
  `@link(import: [...])` containing `"@tag"`. Rewrite requires the
  `@tag` directive to be declared in the schema; if it isn't, the
  build fails with a pointer to the offending `<schemaInput>` entry.
- **Overlap is an error.** Legacy silently let arbitrary glob /
  folder logic decide. Rewrite fails the build if a single file
  matches two `<schemaInput>` patterns, naming both rules in the
  message.
- **Operate on `TypeDefinitionRegistry`**, not on the built schema.
  `SourceLocation` is preserved per `Definition`, so source-file
  attribution flows through without a built-schema round-trip.

## Design

### 1. `SchemaInput` record

```java
public record SchemaInput(
    String pattern,              // glob, e.g. "schema/enrollment/**/*.graphqls"
    Optional<String> tag,        // e.g. "enrollment"; empty = no tag applied
    Optional<String> descriptionNote  // literal text; empty = no note appended
) {}
```

Carried through `RewriteContext.schemaInputs()` (the per-invocation
config object introduced by the Maven-plugin plan). The new plugin
has no `<schemaFiles>` element at all; `<schemaInputs>` is the sole
input mechanism, so there is no parallel attribution-less path to
keep in sync.

### 2. Resolver (`SchemaInputResolver`)

Expands each `SchemaInput.pattern` to a concrete set of file paths
using `java.nio.file.FileSystem.getPathMatcher("glob:...")` rooted at
the project basedir. Returns `Map<String, SchemaInput>` keyed by
canonical source-name (the same string value `RewriteSchemaLoader.load(...)`
receives and `SourceLocation.getSourceName()` returns at applier
time). No wrapper record; the map itself is the contract.

Fail-fast checks, in order:

1. **Empty match.** A pattern that matches zero files is an error:
   `"<schemaInput pattern='...'> matched no files"`.
2. **Overlap.** A file matched by two entries is an error:
   `"schema file X is matched by two <schemaInput> patterns: '<A>' and '<B>'. Each file must belong to exactly one entry."`

Missing `@tag` declaration is not an error: the `TagApplier` injects
a synthetic declaration if any `<tag>` entry is configured and the
registry carries no prior `@tag` definition (see §3).

The resolver runs once, before `RewriteSchemaLoader.load(...)`. Its
keyset is the file set handed to the loader; the map itself is
consumed by the two appliers.

### 3. `TagApplier`

Before walking: if any `SchemaInput` carries a `tag` and the registry
has no `@tag` directive definition, add one:
`directive @tag(name: String!) repeatable on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE | ARGUMENT_DEFINITION | UNION`.
This matches the Apollo federation shape without requiring consumers
to import `@link` or hand-declare the directive. If the schema
already declares `@tag` (federation import or explicit), use the
existing declaration.

Walks the `TypeDefinitionRegistry`; for every in-scope element
(`FieldDefinition` / `InputValueDefinition` / `EnumValueDefinition` /
`UnionTypeDefinition`) whose `SourceLocation.getSourceName()` matches
a source-name key in the map whose `SchemaInput` has a `tag`,
appends `@tag(name: "<tag>")` to the element's directives, unless the
element already declares `@tag` explicitly.

Operates on AST `Definition` nodes via `.transform(builder -> ...)`;
`TypeDefinitionRegistry` is mutable and we replace each transformed
definition in place.

### 4. `DescriptionNoteApplier`

Same walk; for every in-scope element defined in a file whose
`SchemaInput` carries a `descriptionNote`, sets description to
`existing.strip() + "\n\n" + note.strip()` when an existing
description is present, or `note` alone when not. Identical
element-kind scope as `TagApplier` (see D2 for whether to widen to
type declarations).

No state; resolver map is the sole input. Applier order is
independent; both walks read the same map.

## Placement in the pipeline

`GraphQLRewriteGenerator.generate()` today:

```
registry = getTypeDefinitionRegistry(...)         // plan: RewriteSchemaLoader.load(...)
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

After this plan:

```
bySource = SchemaInputResolver.resolve(ctx.schemaInputs(), ctx.basedir())  // new; fails on empty/overlap
registry = RewriteSchemaLoader.load(bySource.keySet())
TagApplier.apply(registry, bySource)                                        // new
DescriptionNoteApplier.apply(registry, bySource)                            // new
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

Both appliers mutate the registry in place (registry is mutable; AST
`Definition` nodes are immutable, so mutation is "replace each
transformed definition"). Order between them is independent; note
application does not depend on tag application or vice versa.

## Config surface

Maven plugin gains one new element, `<schemaInputs>`, on the rewrite
Mojo. The new `graphitron-rewrite-maven` plugin has no
`<schemaFiles>`; `<schemaInputs>` is the only declared input
mechanism (see [plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md)).
The POM-level binding POJO is `SchemaInputBinding` (owned by the
Maven-plugin plan); this plan owns the `SchemaInput.fromBinding(...)`
converter, the resolver that turns a list of `SchemaInput` records
into a source-keyed map, and the two appliers that consume it.

```xml
<schemaInputs>
  <schemaInput>
    <pattern>schema/common/**/*.graphqls</pattern>
    <!-- no tag, no note: loaded plain -->
  </schemaInput>
  <schemaInput>
    <pattern>schema/enrollment/**/*.graphqls</pattern>
    <tag>enrollment</tag>
    <descriptionNote><![CDATA[Part of the enrollment feature. See https://docs.example/enrollment for details.]]></descriptionNote>
  </schemaInput>
  <schemaInput>
    <pattern>schema/grading/**/*.graphqls</pattern>
    <tag>grading</tag>
  </schemaInput>
</schemaInputs>
```

Each `<schemaInput>` has:

- **`<pattern>`** (required): Ant-style glob relative to the project
  basedir. `**` spans directories; `*` spans one path segment.
- **`<tag>`** (optional): string. When present, `@tag(name: "<tag>")`
  is applied to every in-scope element defined in a matched file.
- **`<descriptionNote>`** (optional): literal string, may be wrapped
  in `<![CDATA[ ... ]]>` for multi-line / special-character content.
  When present, appended to the description of every in-scope
  element defined in a matched file.

`RewriteContext` exposes the parsed list as `List<SchemaInput>
schemaInputs()`. No other configuration.

## Implementation

New package `no.sikt.graphitron.rewrite.schema.input`, landing
alongside `RewriteSchemaLoader` (created by the schema-loading plan).

1. `SchemaInput.java`: record above, plus `static SchemaInput fromBinding(SchemaInputBinding)`
   which maps POM fields to the record (the explicit handoff between
   the Maven-plugin module and rewrite core). ~20 LOC.
2. `SchemaInputResolver.java`: `static Map<String, SchemaInput> resolve(List<SchemaInput>, Path basedir)`
   plus the two fail-fast checks, ~110 LOC.
3. `TagApplier.java`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`,
   including the synthetic `@tag` directive declaration when the
   registry has none, ~90 LOC.
4. `DescriptionNoteApplier.java`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`, ~70 LOC.
5. Call sites added in `GraphQLRewriteGenerator.generate()`.
6. `RewriteContext.schemaInputs()` populated by calling
   `SchemaInput.fromBinding(...)` per element of `mojo.schemaInputs`
   inside `RewriteContext.from(mojo, project)`. `SchemaInputBinding`
   is defined by [plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md);
   the conversion is this plan's.

Expected diff: ~300 LOC added, plus tests.

### Element walking

`TypeDefinitionRegistry.types()`, `.objectTypeExtensions()`,
`.inputObjectTypeExtensions()`, and friends expose definition maps.
For each in-scope definition, recursively visit children (fields,
input fields, enum values, arguments) and rebuild via
`.transform(builder -> ...)`.

Type extensions (`extend type Foo`) are handled per-extension; each
extension definition carries its own `SourceLocation`, so fields in
different extension files of the same type can receive different
tags (or notes) if the two files fall into different `<schemaInput>`
entries. Consistent with legacy behaviour.

### Path normalisation

`SourceLocation.getSourceName()` echoes back exactly the string the
parser was given. The resolver pre-normalises each matched file to a
canonical source-name (e.g. `Path.toAbsolutePath().normalize().toString()`
for filesystem matches, classpath-resource name for classpath matches)
and hands that same string both to `RewriteSchemaLoader.load(...)` and
into the resolver's map key. Because the loader passes the source
string through untouched, `SourceLocation.getSourceName()` at applier
time matches the map key byte-for-byte; no per-applier renormalisation
step.

## Tests

### Unit: `SchemaInputResolverTest`

- Pattern matches one file; tag + note both present: resolver map
  carries the file with the entry.
- Pattern matches three files; map has three entries, all pointing
  at the same `SchemaInput`.
- Pattern matches zero files: resolver throws
  `SchemaInputException` with the pattern text in the message.
- Two patterns match the same file: throws with both pattern strings
  and the offending file path in the message.
- Path normalisation: pattern `./schema/*.graphqls` matches a
  project-relative entry `schema/foo.graphqls`; lookup resolves.

### Unit: `TagApplierTest`

- Field defined in a tagged file gains `@tag(name: "<tag>")`.
- Field with explicit `@tag(name: "x")` retains `"x"` and is not
  double-tagged.
- Field defined in an untagged entry (pattern matched, tag absent)
  is untouched.
- Tagged elements: fields, input fields, enum values, arguments,
  unions (one test per kind).
- Object / interface / enum / input type declarations themselves are
  untouched (parity with legacy).
- `@tag` directive auto-inject: registry without `@tag` gains the
  synthetic declaration when any entry has a `tag`; registry with a
  prior `@tag` declaration keeps the original untouched.
- No entry carries a `tag`: no synthetic declaration added.

### Unit: `DescriptionNoteApplierTest`

- Element in a noted file with an existing description gets
  `existing + "\n\n" + note`.
- Element with no description gets `note` alone.
- Element in an entry with no note is untouched.
- Literal-string round-trip: multi-line note, note containing
  backticks, note containing GraphQL-string-escape characters.

### Pipeline: `GraphitronSchemaBuilderTest` additions

One end-to-end case: two `<schemaInput>` entries (one tagged-only,
one noted-only, one both). Assert on the built `GraphitronSchema`
that the attributions reach the generator as expected. No
emission-level assertions; this is a registry-level transform and
the emitter side is agnostic.

### Emission ratchet

None expected. Generated Java output shouldn't change, because
`@tag` and descriptions don't participate in fetcher emission. If a
lint ratchet is warranted, it's on the SDL-emission side, deferred
to "Rewrite emits the client SDL as generated output" (umbrella item).

## Open decisions

**D1. `<schemaInputs>` vs. `<schemaFiles>`.** Resolved by the
Maven-plugin plan: the new `graphitron-rewrite-maven` plugin has no
`<schemaFiles>` element at all, so `<schemaInputs>` is necessarily
the only input mechanism. Consumers migrate to the new plugin and
its `<schemaInputs>` configuration in one diff; see
[plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md)'s
Migration table.

**D2. Note element scope.** Legacy applies description changes only
to fields / input fields / enum values / arguments / unions. Rewrite
could widen to type declarations themselves (object, interface,
enum, input, union). Widening makes documentation more useful;
narrowing keeps parity with legacy. Recommend widen; call it out in
the roadmap Done entry.

**D3. Missing `@tag` directive declaration.** Resolved: `TagApplier`
auto-injects a default declaration
(`directive @tag(name: String!) repeatable on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE | ARGUMENT_DEFINITION | UNION`)
when the registry has none and any entry carries a `tag`. Matches
Apollo federation's shape; consumers don't have to hand-declare or
import via `@link` for rewrite's tagging feature to work. If the
schema already declares `@tag` (via federation `@link` or explicit
declaration), the existing definition wins.

**D4. Idempotency.** If resolver/appliers run twice (e.g. Maven
re-executes the Mojo in a multi-module build), notes duplicate.
Options: (a) accept: Maven plugin executions are single-entry per
build; (b) detect and skip when the element already carries the note
suffix. Recommend (a) unless a concrete duplicate-run failure
appears.

**D5. Legacy compatibility window.** Does this plan's landing touch
legacy's `FeatureConfiguration` / `<outputSchemas>`? No: legacy path
is unchanged. Schemas that still use legacy's `features/<name>/`
convention keep working on the legacy Mojo. Rewrite Mojo consumers
adopt `<schemaInputs>` when they migrate. The umbrella's "Retire
`graphitron-schema-transform`" landing marker closes the legacy
path later.

## Roadmap integration

The roadmap already carries a sub-item pointing at this plan under the
"Dissolve `graphitron-schema-transform` module" umbrella (title:
"Rewrite owns pattern-matched `@tag` + description notes"). "Rewrite
owns feature-flag SDL splits" keeps the narrower ~400 LOC scope
(`@feature` directive arm + `SchemaFeatureFilter` + `splitFeatures` +
`<outputSchemas>`).

On landing, move this plan's entry to `## Done` with a one-line
summary pointing at the commit sha(s) and the key tests.
