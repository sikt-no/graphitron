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
> `GraphQLRewriteGenerator` step that plan introduces. Lands before
> the "Rewrite-owned Maven plugin" plan
> ([plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md))
> as pure rewrite-core work: resolver, appliers, and a minimal
> `RewriteContext` record threaded through `GraphQLRewriteGenerator`.
> No legacy-plugin file touched. `<schemaInputs>` XML surface and
> the full `RewriteContext` field set land with the Maven-plugin
> plan, along with the `enableRewrite` / `disableLegacy` cleanup
> and the `graphitron-rewrite-test/pom.xml` migration to the new
> Mojo. Until then, `graphitron-rewrite-test` continues to drive
> code generation via the legacy plugin's `enableRewrite=true`
> branch.

## Goal

Give rewrite a first-class `SchemaInput` concept: each input is a
glob pattern plus optional tag and optional description note, and
rewrite's pipeline attaches:

- a **tag** applied as `@tag(name: "<tag>")` to in-scope elements
  defined in the matched files, and
- a **description note** appended (with a blank-line separator) to
  the description of those elements.

One tag per entry, one note per entry. No implicit folder
convention; the list of `SchemaInput` records driving the pipeline
is the only source of truth. Two build-time errors, both owned by
rewrite with precise messages: an entry whose pattern matches zero
files, and a file matched by two or more entries.

The XML surface (Maven `<schemaInputs>` config, `SchemaInputBinding`
POJO, Mojo parameter) lands with the Maven-plugin plan; this plan
ships rewrite core and its tests.

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

- **No folder convention.** Tag and note come from the `SchemaInput`
  list (populated by Maven config when the new plugin lands), not
  from the source file's folder path. Any file anywhere can be
  tagged. Consumers eventually migrate by translating each
  `features/<name>/` directory into one `SchemaInput` entry.
- **No `description-suffix.md`.** The note is a literal string on
  the `SchemaInput` record; the Maven-plugin plan surfaces it as a
  `<descriptionNote>` element. Consumers migrating from legacy
  inline the file's contents or keep them in a `<![CDATA[ ... ]]>`
  block.
- **No global tag-disable toggle.** Legacy's
  `FeatureConfiguration(..., boolean addTags)` constructor gates all
  tag application on a single flag. Rewrite drops the toggle; any
  `<schemaInput>` with a `<tag>` applies unconditionally. Consumers
  who want to skip tagging for a subset of files omit `<tag>` on
  those entries.
- **Decouple note from `@feature`.** Legacy applies the suffix only
  when `@feature` is added; rewrite applies the note whenever a
  matching entry declares one, independent of `@tag`.
- **Description concat is platform-stable.** Legacy uses
  `System.lineSeparator()` (platform-dependent: `\n` on Linux/macOS,
  `\r\n` on Windows). Rewrite uses literal `\n\n` so generated
  descriptions are identical across build hosts.
- **Federation import auto-handled.** Legacy guards `@tag` on
  `@link(import: [...])` containing `"@tag"`. Rewrite's `TagApplier`
  auto-injects a `@tag` directive declaration when the registry has
  none; see §3 and D3.
- **Overlap is an error.** Legacy silently let arbitrary folder
  logic decide. Rewrite fails the build if a single file matches two
  entries, naming both patterns in the message.
- **Operate on `TypeDefinitionRegistry`**, not on the built schema.
  `SourceLocation` is preserved per `Definition`, so source-file
  attribution flows through without a built-schema round-trip.

`@tag` is federation-owned, not a Graphitron directive. Rewrite's
`directives.graphqls` (copied by the schema-loading plan) does not
declare it; the `TagApplier`'s auto-injection is the mechanism that
makes the feature work for consumers who haven't imported federation
via `@link`. This is deliberate — rewrite doesn't want to claim
ownership of a federation construct, but also doesn't want every
tag-using consumer to have to opt into federation first.

## Design

### 1. `SchemaInput` record

```java
public record SchemaInput(
    String pattern,              // glob, e.g. "schema/enrollment/**/*.graphqls"
    Optional<String> tag,        // e.g. "enrollment"; empty = no tag applied
    Optional<String> descriptionNote  // literal text; empty = no note appended
) {}
```

Held on a new minimal `RewriteContext` record introduced by this
plan (fields: `List<SchemaInput> schemaInputs`, `Path basedir`), in
package `no.sikt.graphitron.rewrite`. `GraphQLRewriteGenerator`
accepts `RewriteContext` in its constructor; the Maven-plugin plan
expands the record with the remaining plugin knobs (output paths,
named references, scalars, etc.) without moving or renaming it.
Tests construct a `RewriteContext` directly.

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

Operates on AST `Definition` nodes via `.transform(builder -> ...)`.
`TypeDefinitionRegistry` exposes `add(SDLDefinition)` and
`remove(SDLDefinition)` but no atomic replace. The appliers use a
two-pass pattern: collect the (old, new) definition pairs in the
first pass, then apply `remove` + `add` in the second. This avoids
`ConcurrentModificationException` on iteration over
`registry.types()` and friends.

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

`ctx` is the `RewriteContext` instance passed into
`GraphQLRewriteGenerator`'s constructor. No Mojo currently
constructs one; tests are the only callers until the Maven-plugin
plan lands.

Both appliers mutate the registry in place (registry is mutable; AST
`Definition` nodes are immutable, so mutation is "replace each
transformed definition"). Order between them is independent; note
application does not depend on tag application or vice versa.

## Config surface

None. This plan ships rewrite-core code with no Maven wiring. The
`<schemaInputs>` XML surface, its `SchemaInputBinding` POJO, and the
Mojo-to-`SchemaInput` conversion all land with the Maven-plugin plan
(see [plan-rewrite-maven-plugin.md](plan-rewrite-maven-plugin.md)),
which is the only thing that will ever construct `RewriteContext`
instances carrying real schema inputs.

Legacy-plugin cleanup as part of this plan: delete the
`enableRewrite=true` branch from `graphitron-maven-plugin`'s
`GenerateMojo` and its associated `RewriteConfig.setProperties(...)`
call. Legacy plugin reverts to legacy-only; rewrite has no
Maven entry point until the new plugin lands. Consumers are not
running rewrite in production yet, so there is no regression to
smooth.

## Implementation

New package `no.sikt.graphitron.rewrite.schema.input`, landing
alongside `RewriteSchemaLoader` (created by the schema-loading plan).

All rewrite-core. No legacy-plugin file touched. The legacy Mojo's
`enableRewrite=true` branch still exists after this plan lands and
still drives `GraphQLRewriteGenerator.generate()` for
`graphitron-rewrite-test`; `RewriteConfig.setProperties(...)` in the
Mojo populates the same statics generators already read. The
Maven-plugin plan handles the `enableRewrite` / `disableLegacy`
cleanup and migrates `graphitron-rewrite-test/pom.xml` to the new
Mojo in the same commit.

1. `no.sikt.graphitron.rewrite.schema.input.SchemaInput`: record
   (pattern, Optional<tag>, Optional<descriptionNote>). ~15 LOC.
2. `SchemaInputException extends RuntimeException`: thrown by the
   resolver on empty-match / overlap / malformed glob. Matches
   legacy's `RuntimeException` contract, adds a specific type for
   catch-site precision in tests. ~10 LOC.
3. `SchemaInputResolver`: `static Map<String, SchemaInput> resolve(List<SchemaInput>, Path basedir)`
   plus the two fail-fast checks. Returns a `LinkedHashMap` keyed in
   the order the caller's `List<SchemaInput>` was traversed, so
   overlap error messages name rules deterministically and the
   emitted tag/note order is reproducible across builds. ~110 LOC.
4. `TagApplier`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`,
   including the synthetic `@tag` directive declaration when the
   registry has none, ~90 LOC.
5. `DescriptionNoteApplier`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`, ~70 LOC.
6. `no.sikt.graphitron.rewrite.RewriteContext`: minimal record
   (`List<SchemaInput> schemaInputs`, `Path basedir`). The
   Maven-plugin plan expands the record; this plan introduces the
   type so the generator signature stays stable across both
   landings. ~15 LOC.
7. `GraphQLRewriteGenerator`: constructor now takes `RewriteContext`;
   `generate()` reads `ctx.schemaInputs()` + `ctx.basedir()` for the
   resolver call, otherwise unchanged from the schema-loading plan's
   shape. Legacy-Mojo bridge: at `GenerateMojo.java:199` the Mojo
   constructs a `RewriteContext` by wrapping each `mojo.getSchemaFiles()`
   entry as a plain `SchemaInput(path, Optional.empty(), Optional.empty())`
   and calls `new GraphQLRewriteGenerator(ctx).generate()`. The glob
   matcher treats literal paths as patterns that match themselves;
   no tags or notes are applied (no `<schemaInputs>` XML yet). This
   is the only legacy-plugin line this plan changes; the branch
   itself stays until the Maven-plugin plan deletes the whole
   `enableRewrite` path.

Expected diff: ~300 LOC rewrite-core production code +
`SchemaInputException` ~10 LOC + ~300-400 LOC tests (five test
classes) = ~700 LOC added. One legacy-Mojo line touched (replacing
the static `GraphQLRewriteGenerator.generate()` call with
`new GraphQLRewriteGenerator(ctx).generate()`), no other legacy
changes.

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
parser was given. The resolver normalises every matched file to
`Path.toAbsolutePath().normalize().toString()` and hands that same
string both to `RewriteSchemaLoader.load(...)` and into the resolver's
map key. Because the loader passes the source string through
untouched, `SourceLocation.getSourceName()` at applier time matches
the map key byte-for-byte; no per-applier renormalisation step. User
paths are always filesystem paths (the schema-loading plan's
`openSource` is filesystem-only).

The auto-injected directives source has source name
`schema/directives.graphqls` (classpath-relative; set by
`RewriteSchemaLoader.addDirectivesSource`), which by construction
matches no resolver map key. Directive definitions therefore receive
no tag or note, which is the intended behaviour; don't "fix" the
apparent lookup miss.

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
- Overlap error is deterministic: given entries A then B in the
  input list, the error names A before B (verifies
  `LinkedHashMap` iteration order).
- Malformed glob (e.g. unclosed bracket): resolver throws
  `SchemaInputException` wrapping the underlying
  `PatternSyntaxException`, with the offending pattern in the
  message.
- Empty-string tag (`<tag></tag>`): resolver normalises to
  `Optional.empty()` so the POM-binder "empty element" case doesn't
  end up applying `@tag(name: "")`.
- Tag value containing quotes, backslashes, unicode: round-trips
  through the applier into a well-formed `@tag(name: "...")` AST
  (no SDL-escape breakage).
- `basedir` doesn't exist or points at a file: resolver throws
  `SchemaInputException` with the path in the message.
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
- Auto-injected declaration shape: `directive @tag(name: String!)
  repeatable` with locations exactly
  `FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE | ARGUMENT_DEFINITION | UNION`.
  Pins the Apollo-federation-compatible form so nobody narrows it
  accidentally.
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
one noted-only, one both). Assertions read through the built
`GraphitronSchema`'s directive and description accessors — not
through the pre-build registry — so any future caching the builder
might introduce between registry mutation and schema build is
covered. No emission-level assertions; this is a registry-level
transform and the emitter side is agnostic.

### Emission ratchet

None expected. Generated Java output shouldn't change, because
`@tag` and descriptions don't participate in fetcher emission. If a
lint ratchet is warranted, it's on the SDL-emission side, deferred
to "Rewrite emits the client SDL as generated output" (umbrella item).

## Open decisions

**D1. `<schemaInputs>` vs. `<schemaFiles>`.** Resolved: this plan
adds no XML at all. `<schemaInputs>` lives only on the new plugin
when the Maven-plugin plan lands; `<schemaFiles>` and
`<userSchemaFiles>` never migrate (they belong to the retiring
legacy plugin).

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

**D5. Legacy compatibility window.** Legacy's `FeatureConfiguration`,
`<outputSchemas>`, and `features/<name>/` folder convention stay
intact on the legacy-only path; existing legacy consumers are
unaffected. The legacy Mojo's `enableRewrite=true` branch also stays,
keeping `graphitron-rewrite-test` running on its current build path.
The Maven-plugin plan deletes that branch and migrates
`graphitron-rewrite-test/pom.xml` to the new plugin in the same
commit. The umbrella's closing item retires legacy once every
consumer has migrated.

## Roadmap integration

The roadmap already carries a sub-item pointing at this plan under the
"Dissolve `graphitron-schema-transform` module" umbrella (title:
"Rewrite owns pattern-matched `@tag` + description notes"). "Rewrite
owns feature-flag SDL splits" keeps the narrower ~400 LOC scope
(`@feature` directive arm + `SchemaFeatureFilter` + `splitFeatures` +
`<outputSchemas>`).

On landing, move this plan's entry to `## Done` with a one-line
summary pointing at the commit sha(s) and the key tests.
