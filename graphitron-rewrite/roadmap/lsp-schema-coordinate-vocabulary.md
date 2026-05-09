---
id: R119
title: "LSP completion / diagnostics keyed by schema coordinates"
status: Ready
bucket: architecture
priority: 5
theme: lsp
depends-on: []
---

# LSP completion / diagnostics keyed by schema coordinates

## In Review feedback (back to Ready)

The architectural payoff shipped cleanly across `531495a` (Phase 1
foundation), `6a644f1` / `698a21a` / `22dfb8a` / `54fc7c9` (Phase 2
consumer migration), `4ae827d` (Phase 3 deletions), and `04daf62`
(self-review: lift `siblingStringAt`, collapse `DeprecationTarget`,
file R123). Build green; LSP test surface healthy; the R110
`@sourceRow` user-facing gap closes via the canonical overlay; the
structural-invariant + drift-detection seams hold; `DirectiveDefinitions`
(124 lines) and `DeprecationMarkers` (164 lines) are gone as the
spec promised.

Two material gaps, surfaced at the In Review → Done gate:

1. **Phase 1's named generic-capability deliverables not shipped.**
   The "Phasing" section's Phase 1 reads "Wire the generic capabilities
   listed under 'Generic capabilities the parse unlocks':
   unknown-directive / unknown-arg diagnostics, required-arg
   validation, arg-name completion, hover-from-docstring." Of those
   five, only hover-from-docstring shipped (`Hovers.java:60-61`
   docstring fallback wired off `LspVocabulary.descriptionOf`). The
   other four leave no fingerprint in the LSP main sources: a `grep`
   for `Unknown directive` / `Unknown arg` / `missing.*required` /
   `arg.*name.*complet` over `graphitron-lsp/src/main` returns only
   javadoc references in `LspVocabulary.java`. The spec's "Open
   questions" section acknowledges severity policy as an
   implementation choice (warnings first, error later) but does
   **not** mark the diagnostics themselves as out of scope; the
   "free wins" framing of the section is descriptive, the Phasing
   imperative is the contract.

   The Phase 1 commit message is candid that "Existing per-directive
   consumers ... are untouched in phase 1; they migrate to
   behavior-arm dispatch in phase 2," which is correct for the
   *consumer migration* axis but does not address the generic
   capabilities that were supposed to sit alongside the migration.

2. **Plan housekeeping not done.** The spec body still reads as the
   pre-implementation Spec; phases are not collapsed to one-line
   `shipped at <sha>` notes; the four undelivered Phase 1 items are
   not named. Per the In Review → Done review checklist, "the spec
   body should be marked up to reflect what shipped: phases
   collapsed to one-line `shipped at <sha>` notes, remaining work
   clearly named." This feedback section is a placeholder; the
   next implementation pass should fold the markup in properly.

Suggested next pass — implementer's choice between two routes:

- **Ship the four Phase 1 deliverables.** Add unknown-directive /
  unknown-arg / missing-required-arg validators in `Diagnostics.java`
  driven off `vocabulary.registry()`, and arg-name completion at
  `@<directive>(|)` cursor positions in a new (or existing)
  completions provider. Default severity = Warning per the spec's
  "Open question" guidance. Each item is a small additive walk over
  the parsed registry. Pipeline-tier tests pin the new arms; existing
  fixture-SDL infra already has the shapes (`DiagnosticsTest`).
- **Defer the four cleanly.** File a follow-up roadmap item naming
  the four missing capabilities (see R123 for the parent-context
  follow-up shape), update this Spec's "Phasing" section to mark
  Phase 1 as "shipped at `531495a`, with the four generic-capability
  diagnostics deferred to R<n>", and re-request In Review.

Either route fixes (1) and resolves (2) by collapsing phases into
their shipped-at notes plus naming what remains. Pick whichever the
implementer judges right; the decision is design-fork-dependent and
does not need reviewer pre-approval.

(Reviewer note: only `R123` was filed in the self-review pass; it
covers parent-context-aware coordinates, not the generic-capability
diagnostics. Two distinct follow-ups are in play if route (b) is
picked.)

## Problem

The LSP's directive vocabulary is keyed by ad-hoc string-typed identifiers
that nothing in the type system or the build verifies against the actual
directive surface:

- `Diagnostics.VALIDATE_METHOD = Set.of("service", "condition", "externalField",
  "tableMethod", "batchKeyLifter", "reference")` — a manually curated `Set<String>`.
  `"batchKeyLifter"` is dead today (R110 removed it from `directives.graphqls`)
  and `"sourceRow"` is missing.
- `DirectiveDefinitions.ENTRIES` — a hand-written `List<DirectiveDef(name, args)>`
  parallel to `directives.graphqls`. Drifted in the same R110 episode.
- `DirectiveDefinitions.argsByInputType("ExternalCodeReference")` — a derived
  view, but the key is just a type-name string. Stops matching the moment a
  directive moves off the ECR wrapper (which `@sourceRow` did).

The drift was invisible at CI: the LSP's tests are self-consistent against
its own copy of the registry, and the two modules build independently.
Schema authors using the LSP today get unknown-directive diagnostics on
`@sourceRow` and continued autocompletion of the removed `@batchKeyLifter`.

## Design

Move the LSP's behavior table to be keyed by GraphQL **schema coordinates**
(per the GraphQL spec), populated from `directives.graphqls` at LSP startup.

### Schema coordinates

A `SchemaCoordinate` value identifies a unique element of the directive
schema. Cases in scope for this item:

- **Directive.** `@service`, `@reference`, `@sourceRow`.
- **Directive argument.** `@service(service:)`, `@sourceRow(className:)`.
- **Input type.** `ExternalCodeReference`, `ReferenceElement`.
- **Input-type field.** `ExternalCodeReference.className`,
  `ExternalCodeReference.method`, `ExternalCodeReference.argMapping`,
  `ReferenceElement.condition`.

Modeled as a sealed interface:

```java
public sealed interface SchemaCoordinate {
    record Directive(String name) implements SchemaCoordinate {}
    record DirectiveArg(String directive, String arg) implements SchemaCoordinate {}
    record InputType(String name) implements SchemaCoordinate {}
    record InputField(String type, String field) implements SchemaCoordinate {}
}
```

The string forms (`@service(service:)`, `ExternalCodeReference.className`)
follow the GraphQL spec's schema-coordinate syntax for log / error use; the
sealed records are the comparable type. Type / field coordinates beyond the
directive surface (e.g. `Query.user(id:)`) are not in scope here — graphitron's
LSP does not validate against user-authored types — but the same sealed
hierarchy admits them later.

`SdlAction.DeprecationTarget` (sealed `Member(parent, name) | WholeDirective(name)`)
collapses into `SchemaCoordinate`: a `Member` is an `InputField` when
`parent` is a type name, a `DirectiveArg` when `parent` is `@<name>`; a
`WholeDirective` is a `Directive`. Today's parallel hierarchy in
`code_action/SdlAction.java` is replaced by the single sealed type.

### Behavior table

```java
public record LspVocabulary(
    Map<SchemaCoordinate, Behavior> overlay,
    TypeDefinitionRegistry registry
) {}

public sealed interface Behavior {
    /** Class-name binding: complete and validate against the catalog scan. */
    record ClassNameBinding() implements Behavior {}
    /** Method-name binding: depends on a sibling className coordinate. */
    record MethodNameBinding(SchemaCoordinate classNameCoord) implements Behavior {}
    /** Catalog table-name binding (validates against jOOQ catalog). */
    record CatalogTableBinding() implements Behavior {}
    /** Catalog column-name binding (validates against the enclosing @table). */
    record CatalogColumnBinding() implements Behavior {}
    /** Catalog FK-name binding. */
    record CatalogFkBinding() implements Behavior {}
    /** argMapping syntax string (validator filed separately). */
    record ArgMappingBinding() implements Behavior {}
}
```

Each `Behavior` is a marker for "what completes / validates / hovers at this
coordinate." The actual completion / diagnostic / hover code lives in the
consumer files (`ClassNameCompletions`, `Diagnostics`, `Hovers`); the
overlay is the dispatch index.

**Deprecation is not an overlay arm.** Two shapes feed it, both off the
parsed `TypeDefinitionRegistry`:

- **Member-level deprecation.** Native GraphQL: `@deprecated(reason: ...)`
  on an argument or input-type field. GraphQL-Java surfaces this
  directly via `InputValueDefinition.getDirectives("deprecated")`; the
  reason string is on the directive's `reason:` arg.
- **Whole-directive deprecation.** Graphitron's convention: an
  `@deprecated` token inside the `DirectiveDefinition`'s docstring. The
  GraphQL spec disallows `@deprecated` on directive definitions, so we
  carry the marker as Javadoc-style prose in the description string
  (today: `@index`'s docstring at `directives.graphqls:194` reads
  `"... @deprecated use @order(index:) instead"`). GraphQL-Java hands
  back the raw description text but does not interpret the convention;
  `LspVocabulary` layers a token-scan on top — match `@deprecated`
  preceded by a word boundary, treat the trailing prose up to a sentence
  break as the replacement hint. This mirrors what `DeprecationMarkers`
  does today (see `DESCRIPTION_DEPRECATED_TOKEN` pattern), but the scan
  runs against the parsed description text rather than over the raw SDL
  bytes.

`LspVocabulary` exposes `Optional<DeprecationInfo> deprecationOf(coord)`
unifying both shapes; consumers query without caring which form
declared it.

### Startup population

`graphitron-lsp/pom.xml:23-27` already declares a runtime dependency on
`graphitron`, so `directives.graphqls` is on the classpath and GraphQL-Java
is reachable transitively. Both modules already have private constants
naming the resource (`RewriteSchemaLoader.DIRECTIVES_RESOURCE` on the
producer side, `DeprecationMarkers.DIRECTIVES_RESOURCE` on the LSP side);
this Spec consolidates them by adding one accessor on
`RewriteSchemaLoader`:

```java
// graphitron module — public, the single producer-side surface
public static String directivesSdl() { /* read classpath resource */ }
```

Both LSP-side consumers (`LspVocabulary`, the soon-to-go `DeprecationMarkers`)
call into it. The LSP no longer hard-codes a classpath path.

Startup sequence:

1. Call `RewriteSchemaLoader.directivesSdl()` to get the SDL text.
2. Parse with `SchemaParser.parse(String)` into a `TypeDefinitionRegistry`.
   This is the same parser graphitron itself uses; no schema-build /
   resolver-wiring step. The registry exposes `getDirectiveDefinitions()`
   and `getType(name)`, which is the full surface the structural
   invariant needs.
3. Walk the registry's directives, their arguments, and the input types
   those arguments reference. Emit `SchemaCoordinate` keys for each.
4. Apply a hand-written `Map<SchemaCoordinate, Behavior>` overlay declaring
   what each coordinate does. Failures here are **structural**: a
   coordinate in the overlay that doesn't resolve against the registry
   fails the LSP startup with a message naming the unknown coordinate.
5. Hand the resulting `LspVocabulary` to `Workspace`.

The overlay is the only hand-maintained surface left; today's
`DirectiveDefinitions.ENTRIES` shrinks from ~50 lines of `(name, args, types)`
declarations to ~15 lines of coordinate → behavior mappings. The parsed
registry contributes the rest — every directive, every arg, every input
type — without duplication.

`directives.graphqls` declares ~25 directives (`@table`, `@field`,
`@externalField`, `@enum`, `@service`, `@error`, `@reference`,
`@multitableReference`, `@sourceRow`, `@condition`, `@lookupKey`,
`@mutation`, `@asConnection`, `@orderBy`, `@index`, `@order`,
`@defaultOrder`, `@record`, `@discriminate`, `@discriminator`, `@node`,
`@nodeId`, `@tableMethod`, `@experimental_constructType`, `@splitQuery`,
`@notGenerated`) and several input types (`ExternalCodeReference`,
`ReferenceElement`, `ErrorHandler`, `ReferencesForType`, `FieldSort`).
Every coordinate the parse exposes is part of the vocabulary; the *overlay*
declares semantics only for the subset the LSP knows how to act on today.
Filing semantics for a new directive becomes an additive overlay entry,
not a parse change.

### Provability

The structural guarantee:

```java
for (var entry : overlay.entrySet()) {
    if (!resolves(entry.getKey(), registry)) {
        throw new LspStartupException(
            "Schema coordinate " + entry.getKey() + " does not resolve against " +
            "directives.graphqls. Either update the overlay or check directive surface.");
    }
}
```

A `LoadBearingClassifierCheck`-flavored audit test in `graphitron-lsp/src/test/`
constructs an overlay pointing at a fictional coordinate and asserts the LSP
fails to start with a coordinate-level error. R110-style drift becomes a
loud startup failure before any IDE session ever runs the LSP.

## Generic capabilities the parse unlocks

Once the LSP holds a parsed `TypeDefinitionRegistry`, six DX wins fall out
without any new overlay arms. Today's LSP can't do any of these because
its hand-written registry doesn't carry the data.

1. **Unknown-directive diagnostic.** `@tabel(name: "actor")` — silent
   today (the consumer's switch in `Diagnostics` only validates the
   directives it recognises). After: any directive name that doesn't
   resolve in `registry.getDirectiveDefinitions()` is flagged.
2. **Unknown-arg diagnostic.** `@table(neme: "actor")` — same shape. The
   parse names every legal arg per directive; flag the rest.
3. **Required-arg validation.** Non-null directive args (e.g.
   `@table(name: String!)` — `name` is required) get a missing-arg
   diagnostic when the user writes `@table()`.
4. **Arg-name completion.** Cursor at `@table(|)` → completes `name:`.
   Today's `DirectiveDefinitions.ENTRIES` already has the data; nothing
   surfaces it. With the registry on hand, this is one method.
5. **Hover from SDL docstrings.** Every `DirectiveDefinition` and
   `InputValueDefinition` exposes `description`. Today `Hovers.java`
   carries hand-coded markdown for six directives. After: the default
   hover at any directive / arg / input-field coordinate is the SDL
   docstring; the per-coordinate `Behavior` arm only overrides when it
   wants richer content (catalog data on `@table(name:)`, method
   signature on `ExternalCodeReference.method`, etc.). Authoring effort
   for hover content moves from "edit `Hovers.java`" to "edit the SDL".
6. **Deprecation diagnostic and quick-fix coverage.** `DeprecationMarkers`
   regex-walks the raw SDL today. After: member-level deprecation rides
   on `InputValueDefinition.getDirectives("deprecated")` (native GraphQL);
   whole-directive deprecation rides on a token-scan over
   `DirectiveDefinition.description()` (graphitron's docstring
   convention). The `@deprecated` recogniser moves into `LspVocabulary`
   where it works against parsed text instead of over byte ranges. Drift
   between the registry and `SdlActions` collapses too: every
   `SdlAction.targets` coordinate must resolve and must carry a
   deprecation marker (in either shape), both checked at startup against
   the vocabulary.

These are not in addition to the migration; they fall out of phase 1 the
moment the parse is wired in.

## Coordinate vocabulary in scope

The full overlay after migration:

| Coordinate                          | Behavior                                                       |
|-------------------------------------|----------------------------------------------------------------|
| `ExternalCodeReference.className`   | `ClassNameBinding`                                             |
| `ExternalCodeReference.method`      | `MethodNameBinding(ExternalCodeReference.className)`           |
| `ExternalCodeReference.argMapping`  | `ArgMappingBinding`                                            |
| `@sourceRow(className:)`            | `ClassNameBinding`                                             |
| `@sourceRow(method:)`               | `MethodNameBinding(@sourceRow(className:))`                    |
| `@table(name:)`                     | `CatalogTableBinding`                                          |
| `@field(name:)`                     | `CatalogColumnBinding`                                         |
| `ReferenceElement.key`              | `CatalogFkBinding`                                             |
| `ReferenceElement.table`            | `CatalogTableBinding`                                          |
| `ReferenceElement.condition`        | (no overlay; ECR field semantics ride on `ExternalCodeReference.*`) |

`ExternalCodeReference.name` does not appear in the overlay: the parsed
registry already marks it `@deprecated`, the LSP's existing `SdlAction`
provides the auto-migration, and any future deprecation diagnostic
fires off the parse-derived deprecation set, not an overlay arm.

`@reference(path:)` is not a leaf coordinate; the cursor's *element-level*
coordinate inside the path resolves to one of the `ReferenceElement.*`
coordinates above, picked up via input-type traversal of the parsed schema.

## Consumer surface migration

Every consumer that today reads `DirectiveDefinitions` or reimplements a
tree-sitter walk to get at directive arg context is unified on the same
"compute coordinate from cursor; look up Behavior; dispatch" shape.

### `Diagnostics.java`

Today: hand-coded `case "table" / "field" / "reference"` plus a generic
`ecrBindings` walk for `className`/`method`.

After: a single `dispatchAt(coordinate, valueNode)` that looks up the
coordinate's behavior and calls the matching validator. The `case "table"`
arm becomes `CatalogTableBinding → validateTable(...)`; the ECR walk
becomes the `ClassNameBinding` and `MethodNameBinding` arms.

The "structurally inert on `@externalField` / `@enum` / `@record`" rule for
`argMapping` (today carried implicitly by *which* directives the LSP looks
at) lives on the `ArgMappingBinding` arm: when applied to a coordinate
whose enclosing directive is in the inert set, the validator emits a
specific diagnostic.

### `ClassNameCompletions.java` / `MethodCompletions.java`

Today: each calls `argsByInputType("ExternalCodeReference")` and walks the
nested-arg path looking for `className` / `method` keys.

After: at the cursor position, compute the coordinate (using the parsed
registry's directive args + input-type field tree to walk from the
directive node down to the cursor's leaf field). Look up the behavior.
If `ClassNameBinding`, emit completions from `catalog.externalReferences()`.

Same code path now serves both `ExternalCodeReference.className` (inside
`@service(service: { className: ... })`) and `@sourceRow(className:)`
without a special-case parallel path — the user-facing gap R110 left in
place.

### `FieldCompletions.java` / `TableCompletions.java` / `ReferenceCompletions.java`

Today: each switches on directive name internally; each reimplements
"find the argument the cursor is in" and "walk nested object_fields".
Three near-identical walks across these three files plus `MethodCompletions`
plus `ClassNameCompletions` plus `Diagnostics` plus `Hovers` plus
`SdlActions`.

After: a single `LspVocabulary.coordinateAt(file, pos)` walk replaces all
of them. `FieldCompletions` becomes "if Behavior is `CatalogColumnBinding`,
emit columns of the enclosing table"; `TableCompletions` becomes
`CatalogTableBinding` → emit tables; `ReferenceCompletions` splits its
two arms (`key` → `CatalogFkBinding`, `table` → `CatalogTableBinding`) so
its switch on `nested.nestedFieldNameText()` disappears.

### `Hovers.java`

Today: per-directive switch (`case "table" / "field" / "reference" /
"service" / "condition" / "record"`), each branch reimplementing the
nested-arg walk to find the cursor's leaf and dispatching by hard-coded
field name.

After: `Hovers` becomes a Behavior-arm dispatcher. Each `Behavior`
exposes a `hover(coord, registry, catalog) -> Optional<MarkupContent>`
hook (default: SDL docstring); arms with richer content (catalog tables,
column types, method signatures) override. Adding a new directive's hover
becomes either zero work (docstring) or one Behavior arm; no `Hovers.java`
edits.

### `SdlActions.java` / `DeprecationMarkers.java`

Today: `SdlActions` enumerates code actions keyed by
`SdlAction.DeprecationTarget`; `MANUAL_MIGRATION_DEPRECATIONS` is the
allow-list for deprecations the LSP intentionally won't auto-migrate;
`SdlActionDriftTest` asserts every target points at a deprecation marker
in the SDL via the regex-driven `DeprecationMarkers.parseFromClasspath`.

After: `DeprecationTarget` collapses into `SchemaCoordinate`. The
parsed registry supplies the deprecation set directly
(`InputValueDefinition.getDirectives("deprecated")` and the description
walk for whole-directive markers). `DeprecationMarkers.java` is deleted;
its 164 lines and two regex patterns go with it. The drift test becomes
"every action's coordinate resolves and is marked deprecated; every
deprecated coordinate is covered by an action or the manual allow-list",
both checked against `LspVocabulary` rather than a parallel registry.

### `DirectiveDefinitions.java`

Today: hand-written `ENTRIES` list, `argsByInputType` view.

After: thin wrapper around `LspVocabulary` for back-compat during the
migration; deleted at the end. The three pinning tests
(`DirectiveDefinitionsTest`, `DiagnosticsTest`, `ClassNameCompletionsTest`)
get rewritten to assert against coordinates parsed from the actual SDL:
the registry-as-source-of-truth tests become drift-detection tests.

## Test plan

Tier-by-tier:

- **L1 unit (parsing).** New `LspVocabularyTest` in
  `graphitron-lsp/src/test/`: build a vocabulary against a fixture SDL,
  assert the coordinate set matches expectations, and assert the
  startup-time invariant fires when the overlay points at a fictional
  coordinate.
- **L1 unit (behaviors).** `BehaviorTest` per binding type:
  `ClassNameBinding` against a synthetic `CompletionData`, `MethodNameBinding`
  against a parent classname, etc. Coverage matches today's `MethodCompletionsTest`
  / `ClassNameCompletionsTest` shape but routed through the vocabulary
  instead of `argsByInputType`.
- **L2 integration (LSP request).** Existing `DiagnosticsTest`,
  `ClassNameCompletionsTest`, `HoversTest` keep their fixture SDL and
  assertion shape; only the underlying registry path moves. After
  migration, add a case for `@sourceRow(className:)` completion / method
  validation that demonstrates the coverage parity (and is the regression
  guard for the R110 gap).
- **L2 integration (drift).** New `DriftDetectionTest` builds the LSP
  vocabulary from `directives.graphqls` on the runtime classpath and
  asserts every overlay coordinate resolves. This is the R119 invariant
  the rest of the architecture leans on.

Tests do *not* assert the SDL's literal content; they assert structural
properties (presence of expected coordinates, behavior at each, drift
fires on synthetic mismatch).

## What collapses

The unification is the simplification. Today every consumer reimplements
two walks — "innermost object_field at cursor" and "read sibling field by
name" — and the second walk has near-identical copies in `MethodCompletions`,
`ClassNameCompletions`, `Hovers`, `Diagnostics`, and `SdlActions`. The
`Behavior` arms also encode dispatch state that today lives in three
shapes: a `Set<String>` (`Diagnostics.VALIDATE_METHOD`), a `boolean`
flag (`supportsMethod` on `Hovers.externalCodeReferenceHover`), and a
hand-curated map (`MANUAL_MIGRATION_DEPRECATIONS` allow-list).

Concrete deletions:

- `DirectiveDefinitions.java` (124 lines) — replaced by `LspVocabulary`.
- `DeprecationMarkers.java` (164 lines, 2 regex patterns) — replaced by
  GraphQL-Java's `description` / `getDirectives()` access on the parsed
  registry.
- `Diagnostics.VALIDATE_METHOD` set — replaced by `MethodNameBinding`
  arms only being attached to coordinates where a method validation
  applies.
- `Hovers` per-directive switch (~80 lines of dispatch + four
  reimplementations of `innermostObjectField` / `readNestedString`) —
  replaced by Behavior-arm `hover()` hook, default falling out of the
  SDL docstring.
- `Hovers.externalCodeReferenceHover.supportsMethod` flag — same
  reasoning as `VALIDATE_METHOD`, structural now.
- The five copies of `innermostObjectField` / nested walkers across
  `Diagnostics`, `Hovers`, `NestedArgs`, `SdlActions`,
  `MethodCompletions` — collapse into the single `coordinateAt(pos)`
  walk on `LspVocabulary`.

Net effect: roughly 300 lines of hand-written dispatch and registry
duplication retire; the parse contributes ~25 directives × ~3 args ×
description / type metadata for free; new directives in
`directives.graphqls` light up arg-name completion, unknown-arg
diagnostics, and hover-from-docstring with no LSP-side change.

## File-level migration map

New files:

- `graphitron-lsp/.../parsing/SchemaCoordinate.java` — sealed interface
  + factories.
- `graphitron-lsp/.../parsing/LspVocabulary.java` — vocabulary record,
  startup population, `coordinateAt(file, pos)`, `deprecationOf(coord)`.
- `graphitron-lsp/.../parsing/Behavior.java` — sealed Behavior interface,
  hover hook.

Modified files (graphitron module):

- `RewriteSchemaLoader.java` — adds `directivesSdl()` accessor exposing
  the SDL text. The constant stays internal; consumers go through the
  accessor.

Modified files (graphitron-lsp module):

- `Workspace.java` — holds `LspVocabulary` alongside `CompletionData`.
- `Diagnostics.java` — Behavior-arm switch; gains generic unknown-directive
  / unknown-arg / required-arg checks that fall out of the registry.
- `ClassNameCompletions.java` — coordinate lookup replaces directive-name
  switch.
- `MethodCompletions.java` — same.
- `FieldCompletions.java`, `TableCompletions.java`,
  `ReferenceCompletions.java` — Behavior-arm dispatch replaces the
  per-directive arg-and-field walk.
- `Hovers.java` — Behavior-arm `hover()` hooks replace the per-directive
  switch; SDL-docstring fallback covers every coordinate the LSP knows
  about.
- `SdlAction.java` — `DeprecationTarget` collapses into
  `SchemaCoordinate`; downstream callers update to the unified type.
- `SdlActions.java` — drift checks read deprecation info off
  `LspVocabulary` rather than `DeprecationMarkers`.
- `DirectiveDefinitions.java` — back-compat shim during migration;
  deleted in the final phase.

Deleted (when shim is dropped):

- `Diagnostics.VALIDATE_METHOD` set.
- `DirectiveDefinitions.ENTRIES` hand-written list and the
  `DirectiveDef` / `ArgDef` / `InputTypeBinding` records (subsumed by
  `SchemaCoordinate` and the parsed registry).
- `DeprecationMarkers.java` and the two regex patterns.

## Phasing

Three phases, each green at HEAD; user-visible value lands in phase 1.

1. **Parse the SDL, populate the vocabulary, surface the free wins.**
   Add `LspVocabulary`, `SchemaCoordinate`, `Behavior`; populate from
   `directives.graphqls` at LSP startup; assert the structural invariant.
   Wire the generic capabilities listed under "Generic capabilities the
   parse unlocks": unknown-directive / unknown-arg diagnostics,
   required-arg validation, arg-name completion, hover-from-docstring.
   Existing per-directive consumers stay on `DirectiveDefinitions`; the
   new capabilities sit alongside them without conflict. Build green.

2. **Migrate consumers one by one.** `Diagnostics`, `ClassNameCompletions`,
   `MethodCompletions`, `FieldCompletions`, `TableCompletions`,
   `ReferenceCompletions`, `Hovers`. Each consumer reads the vocabulary
   instead of `DirectiveDefinitions`. The `@sourceRow` user-facing gap
   closes in this phase as a side effect: when `Diagnostics` and
   `ClassNameCompletions` migrate, both pick up the
   `@sourceRow(className:)` and `@sourceRow(method:)` coordinates the
   overlay declares.

3. **Delete the shims.** `DirectiveDefinitions.ENTRIES`,
   `DeprecationMarkers.java`, and the residual string-keyed paths come
   out. `SdlAction.DeprecationTarget` collapses into `SchemaCoordinate`;
   `SdlActions` drift checks rebase on the parsed registry. The pinning
   tests get rewritten as drift-detection tests against the parsed SDL.

## Open questions

- **GraalVM native-image.** R89 covers native-image build CI. Classpath
  resources work in native image when registered for inclusion;
  `directives.graphqls` will need the same registration the LSP's
  tree-sitter `.so` already has. Add it to the native-image config in
  the same phase that adds the SDL parse. (`RewriteSchemaLoader` already
  reads the resource at build time inside the producer module, so any
  reflection-config registration there is reused.)
- **Where does the registry live?** `LspVocabulary` holds it as a
  field; `Workspace` holds the vocabulary. The registry is read once
  at startup and never invalidated (the SDL ships with the LSP jar);
  `setCatalog` does not touch it. Confirm during implementation that no
  request path needs to mutate the registry (it shouldn't — it's
  shape, not state).
- **Validation diagnostic severities.** Generic-capability diagnostics
  (unknown directive, unknown arg, missing required arg) ship as
  warnings at first to avoid pre-existing schemas erupting. A follow-up
  bumps to error once the user-facing schemas catch up. Severity
  policy is implementation choice, not a Spec decision.

## Out of scope

- The `argMapping` content-syntax validator. `ArgMappingBinding` is a
  marker; the actual `"javaParam: graphqlArg, ..."` parser lives in a
  sibling item.
- Coordinates beyond the directive surface (e.g. `Query.user(id:)`,
  user-authored types). The sealed `SchemaCoordinate` admits them; this
  item doesn't add overlay entries for them.
- LSP definition / go-to-symbol / formatting capabilities. R90 covers
  some of those; this item only touches completion / diagnostics / hover.

## Wiring in the `dev` Mojo

`DevMojo` already constructs the `Workspace` with an initial catalog at
goal startup and refreshes the catalog from a classpath watcher
(`graphitron-maven-plugin/.../DevMojo.java:86`, `:188`). The
`LspVocabulary` is built in the same place, once, alongside the initial
catalog construction; `Workspace` accepts both via constructor and stores
the vocabulary as a final field (no setter — the registry is shape, not
state). `setCatalog` continues to only touch the catalog reference.

## Surfaced from

R110 In Review → Done approval (commit `5176f2f8` on the rewrite trunk;
see the R110 changelog entry's "Findings noted at approval" section). The
narrower item originally filed as "LSP directive registry sourced from
directives.graphqls" was rescoped during a follow-up design discussion to
this structurally-grounded version; the registry replacement is the first
phase of the migration above, not the goal.
