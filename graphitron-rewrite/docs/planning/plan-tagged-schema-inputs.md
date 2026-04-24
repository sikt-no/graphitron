# Plan: Tagged schema inputs with tags + description notes

> **Status:** In Review
>
> Slice of the "Dissolve `graphitron-schema-transform` module" roadmap
> umbrella. Replaces legacy `FeatureConfiguration`'s `@tag` +
> description-suffix behaviour with an explicit, config-driven model:
> each schema input is a resolved source file plus an optional tag
> and an optional description note. Tighter contract than legacy:
> the same source in two entries is an error, not a silent union.
>
> Excludes `@feature` directive and `<outputSchemas>` splits; those
> land with the remaining "Rewrite owns feature-flag SDL splits" item.
>
> Depends on "Rewrite owns schema loading + directive auto-injection"
> landing first; this transform runs inside the same
> `GraphQLRewriteGenerator` step that plan introduces. Pure
> rewrite-core work: the `SchemaInput` data type, attribution
> builder, two appliers, and a new entry point on
> `GraphQLRewriteGenerator` that takes them. Purely additive: no
> legacy-plugin file touched; the existing static `generate()` entry
> point stays intact for `graphitron-rewrite-test`'s current
> rewrite-via-legacy-Mojo path. `<schemaInputs>` XML surface and
> the glob expansion that turns user patterns into concrete
> `SchemaInput` records both land with the
> [Maven-plugin plan](plan-rewrite-maven-plugin.md), alongside the
> `enableRewrite` / `disableLegacy` cleanup and the
> `graphitron-rewrite-test/pom.xml` migration to the new Mojo.

## Goal

Give rewrite-core a first-class `SchemaInput` concept: each input
is one resolved source file plus an optional tag and an optional
description note. The rewrite pipeline attaches:

- a **tag** applied as `@tag(name: "<tag>")` to in-scope elements
  defined in that source, and
- a **description note** appended (with a blank-line separator) to
  the description of those elements.

One tag per entry, one note per entry. The list of `SchemaInput`
records driving the pipeline is the only source of truth; no
implicit folder convention, no glob matching inside rewrite-core.
One build-time error owned by rewrite-core with a precise message:
the same source name appearing in two or more entries.

The user-facing XML surface (Maven `<schemaInputs>` with `<pattern>`
entries, glob expansion to concrete files, empty-match fail-fast)
lives in the Maven-plugin plan. This separation keeps rewrite-core
filesystem-agnostic and its tests in-memory.

Out of scope: the `@feature(flags: [...])` directive, the
`<outputSchemas>` feature-flag splits, `SchemaFeatureFilter`. Those
land with the remaining "Rewrite owns feature-flag SDL splits" work.

## Scope

`TagApplier` and `DescriptionNoteApplier` have different element
scopes by design; see D2 for rationale.

**`TagApplier` in scope** (legacy parity):

- Fields on object + interface types (`FieldDefinition`).
- Input object fields (`InputValueDefinition` under `InputObjectTypeDefinition`).
- Enum values (`EnumValueDefinition`).
- Field arguments (`InputValueDefinition` under `FieldDefinition`).
- Union type declarations themselves (`UnionTypeDefinition`).

No object / interface / enum / input type declaration itself gets
tagged.

**`DescriptionNoteApplier` in scope** (widened past legacy):

- Everything `TagApplier` touches (above).
- Plus the type declarations themselves: `ObjectTypeDefinition`,
  `InterfaceTypeDefinition`, `EnumTypeDefinition`,
  `InputObjectTypeDefinition`.

**Out of scope**

- `@feature` directive application.
- `<outputSchemas>` schema-file splits.
- `@tag` on type declarations (tracked by D2; federation's tag
  vocabulary targets fields, not types).
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
  logic decide. Rewrite fails the build if a single source name
  appears in two `SchemaInput` entries, naming both entries in the
  message. The check runs in rewrite-core at the attribution-
  building boundary (before tagging or note application), so it
  applies uniformly regardless of who supplies the inputs (Maven
  plugin, test, hypothetical CLI driver).
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
    String sourceName,              // canonical source-name (the same string
                                    //   RewriteSchemaLoader.load receives and
                                    //   SourceLocation.getSourceName() echoes back)
    Optional<String> tag,            // e.g. "enrollment"; empty = no tag applied
    Optional<String> descriptionNote // literal text; empty = no note appended
) {}
```

One entry per resolved schema source. No patterns, no globs:
rewrite-core never touches the filesystem to build this list. The
[Maven-plugin plan](plan-rewrite-maven-plugin.md) owns the
`<pattern>` → one-or-more-`SchemaInput` expansion via Maven's
`DirectoryScanner` and owns the empty-match fail-fast (a plugin-
level user-config diagnostic). Tests hand-construct `SchemaInput`
lists directly.

Held on a new minimal `RewriteContext` record introduced by this
plan (fields: `List<SchemaInput> schemaInputs`, `Path basedir`), in
package `no.sikt.graphitron.rewrite`. `GraphQLRewriteGenerator`
gains a new entry point that takes `RewriteContext`; the existing
static `generate()` stays unchanged for the legacy-Mojo call path.
The Maven-plugin plan expands `RewriteContext` with the remaining
plugin knobs (output paths, named references, scalars, etc.)
without moving or renaming it. Tests construct a `RewriteContext`
directly.

### 2. Attribution builder (`SchemaInputAttribution`)

```java
public final class SchemaInputAttribution {
    public static Map<String, SchemaInput> build(List<SchemaInput> inputs) { ... }
}
```

Builds a `LinkedHashMap<String, SchemaInput>` keyed by `sourceName`,
preserving input-list order so overlap error messages name entries
deterministically and tag/note application is reproducible across
builds. ~30 LOC.

One fail-fast check: the same `sourceName` appearing in two entries
throws `SchemaInputException` with both offending entries in the
message:
`"source 'X' is declared in two <schemaInput> entries: #N with tag=<T1>/note=<N1> and #M with tag=<T2>/note=<N2>. Each source must belong to exactly one entry."`

Missing `@tag` declaration is not an error: the `TagApplier` injects
a synthetic declaration if any entry carries a tag and the registry
has no prior `@tag` definition (see §3).

The builder runs once, before `RewriteSchemaLoader.load(...)`. Its
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

Same walk shape, wider element scope (D2 resolved: widen). For every
in-scope element defined in a file whose `SchemaInput` carries a
`descriptionNote`, sets description to
`existing.strip() + "\n\n" + note.strip()` when an existing
description is present, or `note` alone when not. Element scope is
`TagApplier`'s set plus the four type-declaration kinds
(`ObjectTypeDefinition`, `InterfaceTypeDefinition`,
`EnumTypeDefinition`, `InputObjectTypeDefinition`). Union declarations
are in both scopes; they sit in one kind, not two.

No state; resolver map is the sole input. Applier order is
independent; both walks read the same map.

## Placement in the pipeline

Today's static entry point stays as-is so the legacy Mojo's
`enableRewrite=true` branch keeps calling it and
`graphitron-rewrite-test` keeps building:

```
// GraphQLRewriteGenerator.generate() — unchanged
registry = RewriteSchemaLoader.load(RewriteConfig.generatorSchemaFiles())
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

This plan adds a parallel instance entry point wired to
`RewriteContext`:

```
// new GraphQLRewriteGenerator(ctx).run()
bySource = SchemaInputAttribution.build(ctx.schemaInputs())   // new; fails on overlap
registry = RewriteSchemaLoader.load(bySource.keySet())
TagApplier.apply(registry, bySource)                          // new
DescriptionNoteApplier.apply(registry, bySource)              // new
schema   = GraphitronSchemaBuilder.build(registry)
GraphitronSchemaValidator.validate(schema)
```

`ctx` is the `RewriteContext` passed into the constructor. Tests
are the only callers during the tagged-inputs window. The
Maven-plugin plan makes it the sole entry point by constructing a
`RewriteContext` from `<schemaInputs>` and deleting the static path
+ the legacy-Mojo branch in the same commit.

Both appliers mutate the registry in place (registry is mutable; AST
`Definition` nodes are immutable, so mutation is "replace each
transformed definition"). Order between them is independent; note
application does not depend on tag application or vice versa.

## Config surface

None in rewrite-core. This plan ships data types, the attribution
builder, the two appliers, and the new generator entry point, all
under `no.sikt.graphitron.rewrite.schema.input` and
`no.sikt.graphitron.rewrite`. The `<schemaInputs>` XML, its
`SchemaInputBinding` POJO, the `<pattern>` → concrete
`SchemaInput` expansion (via Maven's `DirectoryScanner`), and the
empty-match fail-fast all land in the
[Maven-plugin plan](plan-rewrite-maven-plugin.md). Rewrite-core
stays filesystem-agnostic.

## Implementation

New package `no.sikt.graphitron.rewrite.schema.input`, landing
alongside `RewriteSchemaLoader` (created by the schema-loading plan).

All rewrite-core, all additive. The legacy Mojo's `enableRewrite=true`
branch and its `GraphQLRewriteGenerator.generate()` (static) call
stay intact; `graphitron-rewrite-test` keeps building through the
same path it uses today. No legacy-plugin file touched.

1. `no.sikt.graphitron.rewrite.schema.input.SchemaInput`: record
   (sourceName, Optional<tag>, Optional<descriptionNote>). ~15 LOC.
2. `SchemaInputException extends RuntimeException`: thrown on
   overlap. Specific type for catch-site precision in tests. ~10 LOC.
3. `SchemaInputAttribution`: `static Map<String, SchemaInput> build(List<SchemaInput>)`
   plus the overlap fail-fast. Returns a `LinkedHashMap` keyed in
   the order the caller's list was traversed, so overlap error
   messages name entries deterministically and tag/note application
   is reproducible across builds. ~30 LOC.
4. `TagApplier`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`,
   including the synthetic `@tag` directive declaration when the
   registry has none. ~90 LOC.
5. `DescriptionNoteApplier`: `static void apply(TypeDefinitionRegistry, Map<String, SchemaInput>)`. ~70 LOC.
6. `no.sikt.graphitron.rewrite.RewriteContext`: minimal record
   (`List<SchemaInput> schemaInputs`, `Path basedir`). The
   Maven-plugin plan expands the record; this plan introduces the
   type so the new generator entry-point signature stays stable
   across both landings. ~15 LOC.
7. `GraphQLRewriteGenerator`: add an instance constructor that takes
   `RewriteContext` and an instance `run()` method that executes the
   attribution + loader + appliers pipeline (see §Placement). The
   existing static `generate()` stays unchanged, still reads
   `RewriteConfig.generatorSchemaFiles()`, still drives
   `graphitron-rewrite-test` through the legacy Mojo. Named `run()`
   (not `generate()`) because Java disallows a static and an instance
   method sharing the same signature on one class; the Maven-plugin
   plan folds both paths onto a single `generate()` name once the
   static entry retires.

Expected diff: ~250 LOC rewrite-core production code +
`SchemaInputException` ~10 LOC + ~300-400 LOC tests (five test
classes) = ~600 LOC added in rewrite-core. Zero legacy files
touched.

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

### Source-name convention

`SchemaInput.sourceName` is whatever string the supplier produces.
That same string is handed to `RewriteSchemaLoader.load(...)` as a
schema-file path and appears back as
`SourceLocation.getSourceName()` at applier time, so map lookups
match byte-for-byte and require no per-applier renormalisation. The
Maven-plugin expander uses `Path.toAbsolutePath().normalize().toString()`
when producing `SchemaInput` entries from matched files (filesystem
paths); tests may use any stable string (typically a short fixture
name). Rewrite-core does not normalise.

The auto-injected directives source has source name
`directives.graphqls` (classpath-relative; set by
`RewriteSchemaLoader.addDirectivesSource`), which by construction
matches no `SchemaInput` key. Directive definitions therefore
receive no tag or note, which is the intended behaviour; don't
"fix" the apparent lookup miss.

## Tests

### Unit: `SchemaInputAttributionTest`

In-memory only; no `@TempDir`, no filesystem, no globs.

- Single entry with tag + note: map has one key mapped to the entry.
- Three distinct entries: map has three keys, each to its own
  `SchemaInput`, iteration order matches the input-list order
  (verifies `LinkedHashMap`).
- Two entries with the same `sourceName`: throws
  `SchemaInputException` with both offending entries in the message.
- Overlap error is deterministic: given entries A then B in the
  input list, the error names A before B.
- Empty input list: returns an empty map.
- Tag value containing quotes, backslashes, unicode: round-trips
  through the applier into a well-formed `@tag(name: "...")` AST
  (no SDL-escape breakage). Covered in `TagApplierTest` too but
  pinned here on the data boundary.

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
- Widened element kinds (one test per kind, pinned against a
  `TagApplier`-untouchable decl): `ObjectTypeDefinition`,
  `InterfaceTypeDefinition`, `EnumTypeDefinition`,
  `InputObjectTypeDefinition` each receive the note on the type
  declaration itself, not just on their members.

### Pipeline: `GraphitronSchemaBuilderTest` additions

One end-to-end case: a hand-constructed `RewriteContext` with three
`SchemaInput` entries (one tagged-only, one noted-only, one both),
pointing at three fixture schema files. Assertions read through
the built `GraphitronSchema`'s directive and description accessors
(not through the pre-build registry) so any future caching the
builder might introduce between registry mutation and schema build
is covered. No emission-level assertions; this is a registry-level
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

**D2. Note element scope.** Resolved: widen `DescriptionNoteApplier`
to type declarations themselves (object, interface, enum, input);
`TagApplier` stays narrow (legacy parity). The asymmetry is
intentional. Notes are documentation: the type declaration is the
most discoverable place a reader looks, so a `<descriptionNote>`
like "part of the enrollment feature" lands naturally alongside
`"""An enrolled student."""` on the type. `@tag`, in contrast, is
federation vocabulary where field-level targeting is the established
idiom; tagging a type declaration adds no routing information and
would diverge from legacy without a consumer ask. Call it out in the
roadmap Done entry.

**D3. Missing `@tag` directive declaration.** Resolved: `TagApplier`
auto-injects a default declaration
(`directive @tag(name: String!) repeatable on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ENUM_VALUE | ARGUMENT_DEFINITION | UNION`)
when the registry has none and any entry carries a `tag`. Matches
Apollo federation's shape; consumers don't have to hand-declare or
import via `@link` for rewrite's tagging feature to work. If the
schema already declares `@tag` (via federation `@link` or explicit
declaration), the existing definition wins.

**D4. Idempotency.** If the appliers run twice (e.g. Maven
re-executes the Mojo in a multi-module build), notes duplicate.
Options: (a) accept: Maven plugin executions are single-entry per
build; (b) detect and skip when the element already carries the
note suffix. Recommend (a) unless a concrete duplicate-run failure
appears.

**D5. Legacy compatibility window.** Legacy's `FeatureConfiguration`,
`<outputSchemas>`, and `features/<name>/` folder convention stay
intact on the legacy-only path; existing legacy consumers are
unaffected. The legacy Mojo's `enableRewrite=true` branch also stays
(this plan is purely additive on rewrite-core), keeping
`graphitron-rewrite-test` running on its current build path. The
Maven-plugin plan deletes that branch and migrates
`graphitron-rewrite-test/pom.xml` to the new plugin in the same
commit. The umbrella's closing item retires legacy once every
consumer has migrated.

**D6. Glob expansion: rewrite-core vs. Maven plugin.** Resolved:
the plugin. Rewrite-core's `SchemaInput` carries concrete resolved
sources; the Maven plugin uses `DirectoryScanner` to turn
`<pattern>` entries into per-file `SchemaInput` records and owns
the empty-match fail-fast. Rationale: keeps rewrite-core
filesystem-agnostic (in-memory tests, no `@TempDir`, no
classloader-path pattern-escape gotchas), uses Maven's native
idiom, and keeps a clean API boundary for hypothetical non-Maven
drivers. Cross-boundary invariants (overlap on `sourceName`) stay
in rewrite-core; user-config diagnostics (empty match) stay in the
plugin.

## Roadmap integration

The roadmap already carries a sub-item pointing at this plan under the
"Dissolve `graphitron-schema-transform` module" umbrella (title:
"Rewrite owns pattern-matched `@tag` + description notes"). "Rewrite
owns feature-flag SDL splits" keeps the narrower ~400 LOC scope
(`@feature` directive arm + `SchemaFeatureFilter` + `splitFeatures` +
`<outputSchemas>`).

On landing, move this plan's entry to `## Done` with a one-line
summary pointing at the commit sha(s) and the key tests.
