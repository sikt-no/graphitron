---
id: R119
title: "LSP completion / diagnostics keyed by schema coordinates"
status: Spec
bucket: architecture
priority: 5
theme: lsp
depends-on: []
---

# LSP completion / diagnostics keyed by schema coordinates

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

### Behavior table

```java
public record LspVocabulary(
    Map<SchemaCoordinate, Behavior> behaviors,
    GraphQLSchema parsedDirectiveSchema
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
    /** Deprecated; emit a deprecation diagnostic on use. */
    record Deprecated(String replacementHint) implements Behavior {}
}
```

Each `Behavior` is a marker for "what completes / validates / hovers at this
coordinate." The actual completion / diagnostic / hover code lives in the
consumer files (`ClassNameCompletions`, `Diagnostics`, `Hovers`); the
behavior table is the dispatch index.

### Startup population

`graphitron-lsp/pom.xml:23-27` already declares a runtime dependency on
`graphitron`, so `directives.graphqls` is on the classpath at
`no/sikt/graphitron/rewrite/schema/directives.graphqls` and GraphQL-Java is
on the classpath transitively.

Startup sequence:

1. Read `directives.graphqls` as a classpath resource.
2. Parse with GraphQL-Java into a `GraphQLSchema`. (Use `SchemaParser`.)
3. Walk the parsed schema's directives, their arguments, and the input
   types those arguments reference. Emit `SchemaCoordinate` keys for each.
4. Apply a hand-written `Map<SchemaCoordinate, Behavior>` overlay declaring
   what each coordinate does. Failures here are **structural**: a coordinate
   in the overlay that doesn't resolve against the parsed schema fails the
   LSP startup with a message naming the unknown coordinate.
5. Hand the resulting `LspVocabulary` to `Workspace`.

The overlay is the only hand-maintained surface left; today's
`DirectiveDefinitions.ENTRIES` shrinks from ~50 lines of `(name, args, types)`
declarations to ~15 lines of coordinate → behavior mappings. Adding a new
directive arg to `directives.graphqls` only touches the overlay if the arg
needs LSP-side semantics; arg presence and type are picked up automatically.

### Provability

The structural guarantee:

```java
for (var entry : overlay.entrySet()) {
    if (!resolvesAgainstSchema(entry.getKey(), parsedSchema)) {
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

## Coordinate vocabulary in scope

The full overlay after migration:

| Coordinate                                | Behavior                                                       |
|-------------------------------------------|----------------------------------------------------------------|
| `ExternalCodeReference.className`         | `ClassNameBinding`                                             |
| `ExternalCodeReference.name` (deprecated) | `Deprecated("use className:")`                                 |
| `ExternalCodeReference.method`            | `MethodNameBinding(ExternalCodeReference.className)`           |
| `ExternalCodeReference.argMapping`        | `ArgMappingBinding`                                            |
| `@sourceRow(className:)`                  | `ClassNameBinding`                                             |
| `@sourceRow(method:)`                     | `MethodNameBinding(@sourceRow(className:))`                    |
| `@table(name:)`                           | `CatalogTableBinding`                                          |
| `@field(name:)`                           | `CatalogColumnBinding`                                         |
| `ReferenceElement.key`                    | `CatalogFkBinding`                                             |
| `ReferenceElement.table`                  | `CatalogTableBinding`                                          |
| `ReferenceElement.condition`              | (no overlay; ECR field semantics ride on `ExternalCodeReference.*`) |

`@reference(path:)` is not a leaf coordinate; the cursor's *element-level*
coordinate inside the path resolves to one of the `ReferenceElement.*`
coordinates above, picked up via input-type traversal of the parsed schema.

## Consumer surface migration

The four files that read the registry today migrate to coordinate lookup:

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
schema's directive args + input-type field tree to walk from the directive
node down to the cursor's leaf field). Look up the behavior. If
`ClassNameBinding`, emit completions from `catalog.externalReferences()`.

Same code path now serves both `ExternalCodeReference.className` (inside
`@service(service: { className: ... })`) and `@sourceRow(className:)`
without a special-case parallel path — the user-facing gap R110 left in
place.

### `Hovers.java`

Today: per-directive hand-coded hover content.

After: hover content for any input-type field falls out of the SDL
docstrings on the parsed schema (graphql-java exposes `description` on
every element). Per-coordinate overlay can still override docstrings where
the LSP wants richer hover (e.g. live catalog data injected into a hover
on `@table(name:)`), but the default is "use the SDL docstring", and the
SDL becomes the single place to author this content.

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

## File-level migration map

New files:

- `graphitron-lsp/.../parsing/SchemaCoordinate.java` — sealed interface
  + factories.
- `graphitron-lsp/.../parsing/LspVocabulary.java` — vocabulary record,
  startup population.
- `graphitron-lsp/.../parsing/Behavior.java` — sealed Behavior interface.

Modified files:

- `Workspace.java` — holds `LspVocabulary` alongside `CompletionData`.
- `Diagnostics.java` — switch on Behavior; one validator method per arm.
- `ClassNameCompletions.java` — coordinate lookup replaces directive-name
  switch.
- `MethodCompletions.java` — same.
- `Hovers.java` — default hover content from SDL docstrings.
- `DirectiveDefinitions.java` — back-compat shim during migration; deleted
  in the final phase.

Deleted (when shim is dropped):

- `Diagnostics.VALIDATE_METHOD` set.
- `DirectiveDefinitions.ENTRIES` hand-written list.

## Phasing

Three phases, each green at HEAD:

1. **Parse the SDL, populate the vocabulary.** Add `LspVocabulary`,
   `SchemaCoordinate`, `Behavior`; populate from `directives.graphqls` at
   LSP startup; assert the structural invariant. Existing consumers
   unchanged. Build green.

2. **Migrate consumers one by one.** `Diagnostics`, `ClassNameCompletions`,
   `MethodCompletions`, `Hovers`. Each consumer reads the vocabulary
   instead of `DirectiveDefinitions`. The `@sourceRow` user-facing gap
   closes in this phase as a side effect: when `Diagnostics` and
   `ClassNameCompletions` migrate, both pick up the
   `@sourceRow(className:)` and `@sourceRow(method:)` coordinates the
   overlay declares.

3. **Delete the shim.** `DirectiveDefinitions.ENTRIES` and the residual
   string-keyed paths come out. The three pinning tests are rewritten as
   drift-detection tests against the parsed SDL.

## Open questions

- **GraphQL-Java surface.** Confirm `SchemaParser.parse(InputStream)`
  takes the `directives.graphqls` shape as-is (no enclosing schema; it's
  pure type / directive declarations). If the parser needs an enclosing
  `schema { ... }` block, either wrap at read time or use
  `Parser` (raw AST) instead of `SchemaParser`.
- **Resource-path stability.** `directives.graphqls` lives at
  `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`;
  its classpath path is `no/sikt/graphitron/rewrite/schema/directives.graphqls`.
  This path is not pinned anywhere today; it should become a constant on
  graphitron's side (`BuildContext.DIRECTIVES_RESOURCE_PATH = "..."`) so
  the LSP can reference it without copy-pasting.
- **GraalVM native-image.** R89 covers native-image build CI. Classpath
  resources work in native image when registered for inclusion;
  `directives.graphqls` will need the same registration the LSP's
  tree-sitter `.so` already has. Add it to the native-image config in
  the same phase that adds the SDL parse.

## Out of scope

- The `argMapping` content-syntax validator. `ArgMappingBinding` is a
  marker; the actual `"javaParam: graphqlArg, ..."` parser lives in a
  sibling item.
- Coordinates beyond the directive surface (e.g. `Query.user(id:)`,
  user-authored types). The sealed `SchemaCoordinate` admits them; this
  item doesn't add overlay entries for them.
- LSP definition / go-to-symbol / formatting capabilities. R90 covers
  some of those; this item only touches completion / diagnostics / hover.

## Surfaced from

R110 In Review → Done approval (commit `5176f2f8` on the rewrite trunk;
see the R110 changelog entry's "Findings noted at approval" section). The
narrower item originally filed as "LSP directive registry sourced from
directives.graphqls" was rescoped during a follow-up design discussion to
this structurally-grounded version; the registry replacement is the first
phase of the migration above, not the goal.
