# Plan: `SplitTableField` under `NestingField`

> **Status:** Done
>
> Lift the validator rejection that blocks `@splitQuery` fields inside plain-object
> `NestingField` wrappers. Scope: `SplitTableField` and `SplitLookupTableField`.
> Production-impact snapshot: 12 distinct rejections, roadmap Priority #1.

## Problem

`GraphitronSchemaValidator.validateVariantIsSupportedAtNestedDepth` (`:440-443`)
rejects every non-`NESTED_WIREABLE_LEAVES` variant at nested depth with a pointer
to roadmap `#8` (Non-table / scalar / reference child leaves). `SplitTableField`
and `SplitLookupTableField` are caught by this gate today, even though they are
`BatchKeyField` leaves, not non-table leaves — the `#8` pointer is misleading.

A nested split field is a natural shape: `type Film { info: FilmInfo }` where
`FilmInfo` is a plain wrapper with a `cast: [Actor] @splitQuery` child. The
`NestingField` wiring already passes the outer parent `Record` through
(`TypeFetcherGenerator.buildWiringEntry` at `:1296`), so key extraction off
`env.getSource()` works unchanged at nested depth. What is missing is a home
for the emitted rows method + fetcher method + scatter helper.

## Where the split methods live today

For a top-level `@table` parent, `TypeFetcherGenerator.generate` emits a
`<TypeName>Fetchers` class holding every field's data fetcher plus the
`rows<Name>` method, DataLoader registration, and scatter helpers
(`TypeFetcherGenerator:400-430` for helper gating). Classes are generated for
`TableType`, `NodeType`, `RootType`, and `ResultType` — never for plain-object
types reached via `NestingField`.

For nested types, `GraphitronWiringClassGenerator` (`:108-117`) builds one
`TypeRuntimeWiring` per plain-object target and calls
`TypeFetcherGenerator.buildWiringEntry` with `className=null`. Every nested-
wireable arm today resolves inline without referencing a class: scalars through
`ColumnFetcher`, inline tables through the outer Record's multiset column,
`NestingField` through a passthrough lambda. Split fields cannot fit this
inline-only model because their DataLoader registration + rows-method call
cannot reasonably live in a wiring lambda.

## Design

Generate a narrow `<NestedTypeName>Fetchers` class for each plain-object nesting
target that contains at least one `BatchKeyField` leaf. The class carries only
the Split methods (data fetcher + rows method + scatter helpers); other leaves
continue to wire inline exactly as today. `GraphitronWiringClassGenerator`
passes the nested type's Fetchers class name to `buildWiringEntry`, and the
existing `$L::$L` fallback wires the Split field correctly.

DataLoader path-scoping (`TypeFetcherGenerator.buildDataLoaderName` at `:1156`)
uses `env.getExecutionStepInfo().getPath().getKeysOnly()`, which naturally
disambiguates `/filmById/info/cast` from `/filmById/cast`; no changes needed
for nested-depth batching.

BatchKey-column projection: `TypeClassGenerator.$fields` collects Split*
`RowKeyed` columns from the top-level field list at
`TypeClassGenerator.java:94-103`, but does not recurse into
`NestingField.nestedFields()`. For nested Split fields, the outer parent's
SELECT still needs to carry those columns so key extraction reads non-null
values off `env.getSource()`. The `emitSelectionSwitch` walk at `:271-274`
already recurses, so once the column-collection walker matches it, nested
Split BatchKey columns flow through.

## Touch points

**Validator** in `GraphitronSchemaValidator.java`:
- `:427-433`: add `ChildField.SplitTableField.class` and
  `ChildField.SplitLookupTableField.class` to `NESTED_WIREABLE_LEAVES`.
- `:424` (comment above `NESTED_WIREABLE_LEAVES`): update the note that says
  "Expanding this set is a one-line edit paired with adding a
  className-independent arm to `buildWiringEntry`" — this plan uses a
  Fetchers-class approach instead, so the comment is stale after §1.
- No change to the `:443` rejection message; it continues to catch genuinely
  unsupported leaves with the existing `#8` pointer.

**Fetcher class generation** in `TypeFetcherGenerator.java`:
- `:67-76` (`generate(schema)`): after the existing stream over `schema.types()`,
  add a separate walk: for every `TableBackedType` root, recurse into its
  `NestingField` descendants via `NestingField.nestedFields()`, collect each
  nested type name that contains at least one `BatchKeyField` leaf, and
  generate a Fetchers class for it. Plain-object nesting types are not entries
  in `schema.types()`, so they cannot be reached by extending the existing
  filter; the walk is a second pipeline added after it.
- `:238-253` (`generateTypeSpec`): call directly from the new walk, passing
  the `NestingField.nestedFields()` list pre-filtered to `BatchKeyField`
  variants as the `fields` argument, and `NestingField.returnType().table()`
  (the enclosing `@table` parent's table, threaded through `NestingField`
  classification in `FieldBuilder.java:339`) as `parentTable`. Do not go
  through `generateForType`, which reads `schema.fieldsOf` (empty for
  non-classified nesting types). No algorithmic change to `generateTypeSpec`
  itself — the current body already reads parent table from the argument.
- New arm (or gate) to suppress non-Split method emission for nested-type
  Fetchers classes: inline leaves continue to wire via `buildWiringEntry`, so
  emitting their data fetcher methods would be dead code. Simplest: filter
  `fields` to only `BatchKeyField` variants before the switch in
  `generateTypeSpec` when emitting for a nested plain-object type.
- `:452` (`buildWiringMethod` emission): `generateTypeSpec` today always emits
  a per-class `buildWiring` method listing every entry in `fields`. For nested
  plain-object types, wiring is already built by
  `GraphitronWiringClassGenerator.nestedTypeWirings` (`:108-117`), so emitting
  a second `buildWiring` on the nested Fetchers class would produce an
  unreferenced `TypeRuntimeWiring`. Suppress it: add a flag or separate entry
  point that skips the `buildWiringMethod` call for nested invocations.

**Wiring** in `GraphitronWiringClassGenerator.java`:
- `:108-117` (nested-type wiring loop): pass
  `nestedTypeName + "Fetchers"` as the `className` arg to `buildWiringEntry`
  when the nested type has any Split field; otherwise keep `className=null`
  to preserve current behaviour for pure-inline nested types.

**`TypeClassGenerator`** in `TypeClassGenerator.java`:
- `:94-103` (`requiredProjectionColumns` collection): replace the flat
  `schema.fieldsOf(typeName)` scan with a recursive walker that also descends
  into `NestingField.nestedFields()`. Nested Split BatchKey columns collected
  this way land in the outer parent's SELECT.

**Roadmap** (`docs/planning/rewrite-roadmap.md`):
- L47 snapshot row and L60 Priority #1 bullet transition `[Backlog] → [Spec/Ready/Done]`
  as the plan advances.
- Priority #1's closing sentence ("update the rejection pointer (or remove it)")
  resolves as a no-op: SplitTableField/SplitLookupTableField pass the gate after
  this plan, and the pointer stays accurate for the remaining non-Split leaves
  (`MultitableReferenceField`, `ComputedField`, etc.) that still close via
  roadmap `#8`.

## Phases

**§1: Mechanism.** `NESTED_WIREABLE_LEAVES` extension; nested-type Fetchers class
emission gated on "any BatchKeyField leaf"; wiring-generator className
threading; `requiredProjectionColumns` recursion into `NestingField.nestedFields()`.
Ship with a `SplitTableField`-only fixture to keep §1 focused.

**§2: `SplitLookupTableField` arm.** Adding the composite-keyed variant. The
emitter dispatch in `SplitRowsMethodEmitter.buildForSplitLookupTable` already
handles the lookup-input join; verify it works unchanged at nested depth.
Expected to be a trivial extension once §1 lands, but gated separately so the
fixture + execution test can be added without bloating §1.

**§3: Scope closure.** Delete this file on reviewer approval.

## Fixtures

Extend `graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls`:

```graphql
type FilmInfo {
    releaseYear: Int    @field(name: "RELEASE_YEAR")
    meta:        FilmMeta
    # Nested-depth @splitQuery: per-Film DataLoader batch reaches actors via
    # film_actor junction, same path as Film.actors at non-nested depth.
    cast: [Actor!]! @splitQuery @reference(path: [
        {key: "film_actor_film_id_fkey"},
        {key: "film_actor_actor_id_fkey"}
    ])
}
```

§2 fixture (once §1 lands): add a nested `SplitLookupTableField` under a
different nesting type so the two variants have independent coverage.

## Test coverage

**Pipeline** (`SplitTableFieldPipelineTest`): assert that nesting a
`SplitTableField` under a `NestingField` produces a `FilmInfoFetchers` class
carrying the rows method + data fetcher + scatter helper, and that the
outer `FilmFetchers` continues to contain only top-level Split methods.

**Classifier** (`GraphitronSchemaBuilderTest`): add
`NESTED_SPLIT_TABLE_FIELD_CLASSIFIED` asserting the nested `cast` classifies as
`SplitTableField` with `parentTypeName = "FilmInfo"` and `batchKey =
RowKeyed([FILM.FILM_ID])`.

**Projection** (`TypeClassGeneratorTest`): assert that `Film.$fields` includes
`FILM.FILM_ID` in its selectFields whenever `info.cast` is requested — the
recursive collector working end-to-end.

**Execution** (`GraphQLQueryTest`): query
`{ filmById(film_id: ["1", "2"]) { info { cast { actorId } } } }`, assert two
parents batch into one `rowsCast` round-trip (QUERY_COUNT == 2), and per-parent
actors resolve correctly (film 1 → [1, 2]; film 2 → [1, 3]).

## Deferred / non-goals

- **Multi-parent `NestingField` sharing of a nested type with Split fields.**
  The existing "Multi-parent NestingField sharing — TableField arm" plan
  (Active) does not cover Split-containing nested types. If a nested type
  carries Split fields, each parent's classification produces a parent-specific
  `BatchKey` in the nested field; sharing semantics need design work. Rejected
  at classifier time today via the multi-parent compatibility check at
  `GraphitronSchemaValidator:481-560`; keep that rejection.
- **Condition-join step inside a nested Split path.** Still gated by
  `SplitRowsMethodEmitter.unsupportedReason` regardless of nesting depth;
  closes with Classification vocabulary follow-ups §5.

## Open questions

1. **Nested-type Fetchers class naming.** Resolved: use `<NestedTypeName>Fetchers`
   (matches top-level convention). No validator check needed — GraphQL already
   enforces unique type names across the schema, so two types (one `@table`,
   one plain-object) cannot share a name and cannot collide on
   `<Name>Fetchers.java` in the `rewrite.fetchers` output package.
2. **Class-emission gate: "any `BatchKeyField` leaf" vs "any non-inline leaf".**
   The former is surgical; the latter is more forward-compatible if more nested
   leaves ever need class-scoped methods. Recommend: start with `BatchKeyField`
   and broaden if needed.
