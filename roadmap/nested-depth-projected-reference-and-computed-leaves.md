---
id: R645
title: "Admit projected @reference and @externalField leaves at nested depth under NestingField"
status: In Progress
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Admit projected @reference and @externalField leaves at nested depth under NestingField

`GraphitronSchemaValidator.isNestedWireableLeaf` admits exactly four leaf shapes under a
`ChildField.NestingField`: `ColumnBackedField`, `TableField`, `NestingField`, and the
`SourceShape.Table` arm of `BatchedTableField`. Every other leaf is rejected by the sibling
`validateVariantIsSupportedAtNestedDepth` with
`Rejection.deferred("Field '<coord>': <VariantClass> is not yet supported under NestingField")`.
This item is about admitting two more: `ChildField.ColumnBackedReferenceField` (a scalar `@field` +
`@reference` projection) and `ChildField.ComputedField` (an `@externalField` expression leaf).

A spike settled the filing hypothesis, and it landed between the two answers on offer. The emitter
*is* already total for both leaves at nested depth, so no emit arm has to be written. But the gate
is not merely conservative either: it has been standing in for per-variant validation that never
runs at nested depth, so widening it on its own opens a silent-wrong-SQL path. Both halves ship
here, validator fix first. Details under "What the spike found".

## Why it matters: the downstream evidence

Measured on the fs-plattform `sis` subgraph mid-migration to Graphitron 10 (branch
`sis/upgrade-graphitron`) via `mcp__graphitron__diagnostics_aggregate`: **39 of its 72 deferred
errors are this one gate.**

| Sub-kind | Count | Example coordinate |
|---|---|---|
| `ColumnBackedReferenceField` under nesting | 23 | `Resultatfordeling.antallBestatt` |
| `ComputedField` under nesting | 12 | `ResultatAlleSprak.nb` |
| `ComputedField` under nesting, multi-parent | 4 | `EkskludertEmneIResultatsammendrag.emnenavn` |

The 23 are one uniform authoring shape across 11 wrapper types: a `@table`-less nesting type whose
scalar leaves each carry `@field` + `@reference` hopping to the *same* first table.

```graphql
type EmneIUtdanningsplanPlanlagtVekting {          # no @table
    verdi: BigDecimal @field(name: "VEKTINGSTALL_PLANLAGT")
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}])
    vektingstype: Vektingstype                      # object-typed: classifies ReferenceField, NO error
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}, {key: "…PLANLAGT__VEKTINGSTYPE__FK"}])
    vektingIStudiepoeng: BigDecimal @field(name: "VEKTING_I_STUDIEPOENG_PLANLAGT")
        @reference(path: [{table: "UTDANNINGSPLAN_EMNE_RESULTAT"}])
}
```

Note the asymmetry inside a single type: the *object*-typed `@reference` sibling is accepted, only
the *scalar* projection rejects. `VurderingsenhetTerminperiode` in the same schema does object-typed
`@reference` under a nesting type with no diagnostic.

A schema-side workaround exists for the 23 (hoist the shared first hop onto the parent field, give
the wrapper its own `@table`, drop the hop from each leaf; shape-preserving, and it emits one join
instead of N identical ones). That workaround is not a reason to skip this item: the 16
`ComputedField` errors have no clean schema-side equivalent, because `@externalField` on a leaf
inside an unbound wrapper is not a supported shape at all. The supported lift puts `@externalField`
on the *parent* field returning `Field<Record>`, which means reworking consumer Java, not just SDL.

## What the spike found

Verified on a throwaway working state, not by analogy to R23: predicate widened, a fixture nesting
type added under `Film` in `graphitron-sakila-example`'s `schema.graphqls`, full reactor build, and
an execution test against a live PostgreSQL. Reverted afterwards; nothing from the spike is in the
tree.

### The emitter is already total for both leaves

Widening `isNestedWireableLeaf` alone produced correct generated code, a compiling
`graphitron-sakila-example` (which compiles emitted sources at `<release>17</release>`), and a
green execution test covering both leaves, an aliased duplicate selection (`a: languageName`), and
a second level of nesting. No emitter, wiring or registration change was needed. The mechanism, so
the implementer restates it rather than rediscovers it:

- `ProjectionCommands.mintNestedUnit` mints a unit **per anchor** (`GeneratedUnits.nestingUnit`,
  addressed `<Anchor><Nested>`), and `ProjectionUnitRenderer`'s `CallWrap.Splice` arm passes the
  anchor's own `table` local straight into the nested unit's `$project(grouped, table, env)`. So
  the `ColumnBackedReferenceField`'s `SelectTerm.ScalarSubselect` correlates on the parent's table
  (`correlationWhere` renders against the `"table"` local) and the `ComputedField`'s
  `SelectTerm.HelperCall` hands that same table to the developer's method. Both are per-parent by
  construction, which is why the shared-parent question below is a separate one.
- `ProjectionCommands.contributionFor`'s arms for both leaves ignore the `nested` flag entirely;
  only `ChildField.TableTargetField` forks on it.
- `FetcherEmitter.bind` routes both through `columnByAlias`, which reads `__rk_<resultKey>` off
  `env.getSource()` without consulting the parent table. That is the same parent-independence R23
  relied on for `TableField`, and it is why one shared `<Type>Fetchers` class serves every parent.
- `NestingReach.ownsFetchers` ("any classified field") and `FetcherRegistrationsEmitter.nestedBody`'s
  per-field walk are variant-agnostic, and `TypeFetcherGenerator` reaches the nested class through
  the same `generateTypeSpec` switch where both leaves already sit on no-op arms.

The emitted nested unit, for reference:

```java
public class FilmFilmProjectedLeaves {
    public static List<Field<?>> $project(Map<String, List<SelectedField>> grouped, Film table,
            DataFetchingEnvironment env) {
        // ...
            case "languageName" -> {
                Language l0 = Tables.LANGUAGE.as(table.getName() + "_l0");
                fields.add(DSL.field(DSL.select(l0.NAME).from(l0)
                    .where(l0.LANGUAGE_ID.eq(table.LANGUAGE_ID)).limit(1)).as("__rk_" + entry.getKey()));
            }
            case "isEnglish" ->
                fields.add(FilmExtensions.isEnglish(table).as("__rk_" + entry.getKey()));
```

So the predicate's javadoc claim that expanding it "requires the corresponding generator-side
change" does not hold for these two leaves, and that claim is why this item was filed as a
suspected emitter hole. Correcting it is part of the work.

### But the gate stands in for validation that never runs

`validateNestingField` descends through `walkNestedVariantsForImplementation`, which runs exactly
two checks per nested leaf: `validateVariantIsImplemented` and
`validateVariantIsSupportedAtNestedDepth`. The per-variant dispatch switch inside `validateField`
never runs at nested depth at all. So `validateColumnBackedReferenceField` (the `NodeIdEncodeKeys`
deferral, `validateReferencePath`, the 22-column `RecordN` cap) and `validateComputedField` (the
`@externalField`-carrying-a-`@reference`-path deferral) do not fire on a nested leaf. The blanket
nested-depth gate is the only thing keeping these leaves away from the emitter, and it is a
coarser instrument than the checks it shadows.

The spike demonstrated both failure modes:

- A nested `@externalField` carrying `@reference(path: [{key: "film_language_id_fkey"}])`, which is
  a clean `Rejection.deferred` at ordinary depth, **built green and emitted
  `FilmExtensions.isEnglish(table)` with the join path silently dropped**. Wrong SQL, no
  diagnostic, no failing build.
- A nested `ColumnBackedReferenceField` with `NodeIdEncodeKeys` compaction has no validator to stop
  it and reaches `ProjectionCommands.contributionFor`, whose arm throws
  `IllegalStateException("inline ColumnBackedReferenceField '...' with NodeIdEncodeKeys compaction
  must be rejected by the validator before production")`. A generator crash where a located
  rejection belongs.

That fixes the order of the work: the nested walk becomes variant-aware **before** the predicate
widens. The two are separable commits but not separable items, because the validator fix has no
independent motivation until a leaf with real per-variant checks is admitted at nested depth, and
the widening is unsafe without it.

## What landed

All of it, in `GraphitronSchemaValidator` plus tests at three tiers. Full reactor build green
(`mvn install -Plocal-db`), which covers the execution tier against a live PostgreSQL.

### Per-variant validation now runs at nested depth

`validateField`'s body became `validateVariantSpecific(field, schema, types, nestedAnchor, errors)`:
the two guards, the dispatch switch, and the three cross-cutting checks. Three sites call it, so
none can validate a leaf less than another does: the top-level field walk, the nested walk (renamed
`walkNestedVariants`, since it is no longer implementedness-only), and `validatePivotSpec`'s slot
walk. `validateVariantIsImplemented` moved inside the shared helper, so the nested walk no longer
calls it separately and nothing double-reports.

The decisions the spec asked to be recorded rather than answered by accident, each stated in the
helper's javadoc where the reader meets the code:

- **The cross-cutting checks belong at nested depth**, so they moved in whole.
  `validatePaginationRequiresOrdering` / `validateListRequiresOrdering` read
  `SqlGeneratingField.pagination()` / `orderBy()`, which nested `TableField` and `BatchedTableField`
  leaves genuinely carry; a paginated nested leaf with no ordering encodes a cursor over nothing
  exactly as at ordinary depth. The authoring surface exists there, so the check does.
- **Both guards moved in too.** The array-typed DataLoader-key guard was the live hole the spec
  predicted: `BatchedTableField` implements `BatchKeyField` and its Table-sourced arm is already
  admitted under a nesting field, so a nested batched leaf keyed on an array column mis-batched
  silently. The reentry guard fires on no current leaf at any depth; running it uniformly is what
  keeps that true as arms land, which is the guard's job.
- **The `ColumnBackedField` false positive: the anchor is threaded, not a `nested` flag.**
  `validateVariantSpecific` takes the enclosing `NestingField`'s
  `ReturnTypeRef.TableBoundReturnType` (null at ordinary depth) and hands it to
  `validateColumnBackedField`, which skips the parent-type gate when it is present. The two options
  are equivalent in effect, since `TableBoundReturnType` is table-bound by construction; the anchor
  wins because it states *why* at the call site and because it is the object the next arm to be
  widened will want. `validateServiceTableField` (fails open) and `validateRecordReadField` (would
  false-positive the same way) are untouched, per the spec: both their leaves are outside the
  nested-wireable set, so they stay notes for whoever widens next.
- **The shadowed nesting-field check is closed.** `validateNestingField` split into
  `validateNestingFieldShape` (the list-cardinality rejection) plus the walk; the walk applies the
  shape check at every level and does *not* route nesting fields through the dispatch switch, which
  would re-enter the walk and double-report the subtree.

Measured fallout on the real run: exactly the two predicted `NodeIdEncodeKeys` canaries in
`NestingFieldValidationTest`, nothing else, in the `graphitron` module (3377 tests) or in
`graphitron-sakila-example`. The spec's 18 `ColumnBackedField` rows never materialised because the
anchor fix landed in the same commit.

### `isNestedWireableLeaf` widened, javadoc repaired

`ColumnBackedReferenceField` and `ComputedField` arms added, the former ungated on compaction (a
comment at the arm says why). The javadoc's "expanding this predicate requires the corresponding
generator-side change" sentence is replaced by what the spike established: the projected leaves
reach nested depth through a per-anchor projection unit and a parent-independent alias read, so
admitting one is a validator question; the class-backed and record-sourced leaves are the ones that
need emit arms. The `BatchedTableField` source-shape paragraph is kept.

### Tests

- **Unit** (`NestingFieldValidationTest`): the two `NodeIdEncodeKeys` cases keep asserting an error,
  with the message moved to `validateColumnBackedReferenceField`'s own deferral; a comment at the
  cases spells out that they are the canary for the validator half, not a message tweak. Added: a
  `Direct` reference leaf and a `ComputedField` under a nesting type (no error), a `ComputedField`
  carrying a `joinPath` (the lift-form deferral, the case the spike watched emit silently wrong
  SQL), and a list-shaped nesting type at depth two.
- **Pipeline** (`GraphitronSchemaBuilderTest.nestedProjectedLeaves_referenceAndExternalField_classifyAndValidateClean`):
  a nesting type carrying both leaves, asserting the classification and that validation is clean.
- **Execution** (`GraphQLQueryTest.nestingField_projectedLeaves_agreeWithTheirFlatSiblings`, over
  the new `Film.projected`): both leaves nested, asserted equal to their flat `Film` siblings, with
  an aliased duplicate selection and a second level of nesting.
- **Census**: `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` needed no
  edit, confirmed by a real test run rather than inherited from the `-Pquick` spike.

## Scoping decisions, resolved

**One item, not two.** The evidence converged rather than diverging: both leaves need zero emitter
work, and both are blocked by the same shadowed validation. Splitting would either duplicate the
validator fix or serialise two halves of one diff.

**Multi-parent stays out.** The four multi-parent `ComputedField` errors measured downstream come
from `compareNestedFieldsShape`'s catch-all via `validateNestingParentCompat`, a genuinely separate
gate that needs its own argument. My reading is that R23's argument does carry for both leaves
(per-anchor projection units, parent-independent alias read), but it is not this item's to make.
R323 holds the cross-link; it now also carries the closure of its `LookupTableField` question,
which was answered by attrition (R432 folded the leaf away, both survivors are already admitted at
nested depth).

**A gap found on the way, filed separately.** `ServiceCatalog.reflectExternalField` documents "one
parameter assignable from the parent's jOOQ `Table<?>` class" and then only checks
`Table.class.isAssignableFrom(p.getType())`; the `parentTableClass` argument is passed and never
read. Two parents on different tables sharing one `@externalField` declaration classify clean and
emit a helper call with the wrong table type, breaking compilation of generated code. Pre-existing
and reachable at ordinary depth today, so it does not block this item, but a shared nesting type
turns it into one SDL declaration served by two parents. Filed as R646
(`roadmap/externalfield-parent-table-assignability.md`); it should be closed before multi-parent
`ComputedField` is admitted anywhere.
