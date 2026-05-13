---
id: R156
title: "LSP: autocomplete and diagnostics for @field on @record-bound types"
status: Spec
bucket: lsp
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# LSP: autocomplete and diagnostics for @field on @record-bound types

The LSP has no record-component awareness today. `@field(name: "X")` only
resolves and validates against a jOOQ table when the enclosing type carries
`@table(name: "...")`; if the enclosing type carries
`@record(record: {className: "com.example.Foo"})` instead, the column
completion (`FieldCompletions.generate`) silently returns `List.of()` and
the diagnostic (`Diagnostics.validateCatalogColumn`) silently returns. The
schema author gets neither suggestions nor a "field name does not match a
component on `Foo`" error. This affects both
`OBJECT`- and `INPUT_OBJECT`-typed `@record` parents (the directive applies
on both per `directives.graphqls:290`); the asymmetry the user reported on
input types is a special case of "no record-component awareness anywhere."

The gap is structural, not a missing branch: there's no
`Behavior.RecordComponentBinding` arm in
`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/Behavior.java`,
`CompletionData.ExternalReference`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CompletionData.java:139-144`)
carries only `name`, `className`, `description`, and `methods` (no record
components), and `ClasspathScanner.readIfCandidate`
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/ClasspathScanner.java:92-120`)
doesn't read the `Record` class-file attribute. The fix adds the missing
data on the catalog side, the missing dispatch arm on the LSP side, and
wires the existing `@field(name:)` consumer to fall back from `@table`
lookup to `@record` lookup.

## Interaction with R96 (deprecate-record-directive)

R96 Phase 1 removes `@record` on `INPUT_OBJECT` entirely (gated on R94),
and Phase 3 narrows it on `OBJECT` to *polymorphic-return-only*. After R96
ships:

- The input-side LSP work in R156 becomes dead code (no input type carries
  `@record` anymore). Authors of input types use `@table` (already
  works) or R94's graphitron-internal records (not author-visible, not an
  LSP surface).
- The output-side LSP work persists in narrowed form: `@field(name:)` on
  `@record(record:)`-bound `OBJECT` is still a real coordinate, used for
  polymorphic-return payload classes.

R156 should land *before* R96 Phase 1 to be useful on the input side, or
should drop the input scope and target only the output scope (Java-record
payload classes for polymorphic returns). Picking the timing is part of
the Spec → Ready handoff; the implementation is the same either way.

## Implementation shape

### 1. Catalog: read record components

Extend `ClasspathScanner.readIfCandidate` to detect record classes
(`cm.findAttribute(Attributes.record())` returns an `Optional<RecordAttribute>`;
present iff the class is a record) and read each `RecordComponentInfo` into a
new `CompletionData.RecordComponent(name, type, description)` shape. Each
`ExternalReference` gains a `List<RecordComponent> recordComponents` field
(empty list for non-records). The `type` rendering matches
`ClasspathScanner.displayName` so component types display the same way method
return types do.

### 2. LSP: new `Behavior` arm

Add `Behavior.RecordComponentBinding()` (sibling to `CatalogColumnBinding`,
same shape — the candidate set is derived from the enclosing type's
`@record` className, not from any sibling coordinate).

### 3. LSP: dispatch — keep one coordinate, branch by enclosing type

`@field(name:)` already resolves to `Behavior.CatalogColumnBinding` (the
overlay entry at `LspVocabulary.java:714`). Don't split the overlay; the
overlay maps coordinates to behavior structurally, not contextually.
Instead, in the three consumer sites
(`FieldCompletions.generate`, `Diagnostics.validateCatalogColumn`,
`Hovers.columnHover`), extend the enclosing-type lookup to:

1. Resolve `TypeContext.enclosingTypeDefinition(directive.outer())`.
2. If the type carries `@table(name:)`, behave as today (catalog column
   completion / diagnostic / hover).
3. Otherwise, if the type carries `@record(record: {className:})`, look up
   the className in `CompletionData.externalReferences()` and use that
   entry's `recordComponents` as the candidate set / validation domain /
   hover content.
4. If neither, return empty.

Add a `TypeContext.recordClassNameOf(typeDef, source)` helper that mirrors
`TypeContext.tableNameOf` but reads `@record(record: {className: ...})`.
Both `@record` and `@table` may co-occur on the same `OBJECT` (the
`@table + @record` shadow rule that R96 Phase 1 removes); preserve current
shadow precedence by checking `@table` first.

### 4. Hover content

`columnHover` renders the column with its `graphqlType` + nullable note.
The record-component hover renders the component's Java type (e.g.
`Integer filmId`) — there's no jOOQ-level "graphqlType" derivation for a
Java record component, just the declared component type. Matches the
texture of `MethodCompletions.formatSignature`.

### 5. Tests

- `FieldCompletionsTest`: positive test on
  `type Foo @record(record: {className: "com.example.FilmCard"}) { bar: Int @field(name: "") }`
  with a catalog containing `FilmCard` as a record (components
  `filmId`, `title`). Mirror for `input FooInput @record(...)`. Negative
  test: enclosing `@record` references an unknown className → empty
  completions.
- `DiagnosticsTest`: `@field(name: "ghost")` on a `@record`-bound type
  produces an "Unknown component" error; `@field(name: "filmId")` produces
  no error. Both for input and output enclosing types.
- `HoversTest`: hover on `@field(name: "filmId")` inside a `@record`-bound
  type renders the component's Java type.
- `ClasspathScannerTest`: a record class on the classpath surfaces its
  components on the matching `ExternalReference`; a non-record class still
  surfaces an empty `recordComponents` list.

### 6. Out of scope

- `@enum(enum: {className:})`-bound types — the LSP has no
  "enum constants of a class" notion either, but enums don't carry
  `@field(name:)` so the same gap doesn't bite. Separate item if it ever
  matters.
- jOOQ-generated record components. `@table` already covers this via the
  column path; the record-component path is purely for developer-authored
  Java records via `@record`.
- Hover/diagnostic on `@reference(...)` keys inside a `@record`-bound
  type. `@reference` is FK-shaped and there's no FK metadata on a
  developer-authored record; reject as inapplicable.

## Risk

- **Short-lived for input scope.** Per the R96 interaction above, the
  input-side LSP work is gone within one or two roadmap items. If R94/R96
  are imminent, drop input scope and target output only.
- **Component-type display fidelity.** The class-file `Record` attribute
  stores component types as JVM descriptors (`Ljava/lang/Integer;`,
  `I`, `Ljava/util/List;`). `ClasspathScanner.displayName` already converts
  these for method types; reuse it. Generic type parameters on components
  (`List<Foo>`) are erased in the descriptor — the hover shows `List`,
  not `List<Foo>`. Matches the existing method-parameter rendering, so
  no new fidelity gap.
