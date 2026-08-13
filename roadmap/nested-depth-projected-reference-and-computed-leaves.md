---
id: R645
title: "Admit projected @reference and @externalField leaves at nested depth under NestingField"
status: Ready
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

## Implementation

### 1. Run per-variant validation at nested depth

In `GraphitronSchemaValidator`, `walkNestedVariantsForImplementation` should route each
non-`NestingField` leaf through the same per-variant arm that `validateField`'s switch selects,
not just the two implementedness checks. Preferred shape: extract the switch body into a
`validateVariantSpecific(field, types, errors)` helper that both the top-level walk and the nested
walk call, so the two sites cannot drift again. A two-case special form for just the newly admitted
leaves would reintroduce exactly the shadowing this item exists to remove.

The fallout this raises was measured at the Spec review rather than left to the implementer;
see "Measured fallout" below. It is not empty, and it lands inside this item rather than splitting
out of it.

Two remaining decisions to record in the diff rather than let the refactor answer by accident:

- **The cross-cutting checks around the switch.** `validateField` also runs
  `validatePaginationRequiresOrdering`, `validateListRequiresOrdering` and
  `validateVariantIsImplemented`. `validateVariantIsImplemented` already runs in the nested walk, so
  do not double-report it. The two ordering checks produced zero fallout in the measurement, so the
  question is purely whether they *belong* at nested depth (they read
  `SqlGeneratingField.pagination()` / `orderBy()`, which nested `TableField` leaves do carry), not
  what they would break. Decide and say so.
- **The two guards ahead of the switch.** `validateField` opens with a site-level reentry
  implementedness guard (no current leaf can fire it) and an array-typed-DataLoader-key guard on
  `BatchKeyField`. The latter is live for a leaf already admitted at nested depth:
  `BatchedTableField` implements `BatchKeyField`, so a nested batched leaf keyed on an array column
  mis-batches silently today. Extracting only the switch body leaves that hole open. It produced no
  fallout, which means only that no fixture carries an array-typed key column. Either pull the guard
  into the shared helper or state why it stays behind.

#### Measured fallout

Method: `walkNestedVariantsForImplementation` routed each non-`NestingField` leaf through
`validateField`, predicate untouched, errors collected into a throwaway list so nothing failed;
`mvn test -pl :graphitron -DexcludedGroups=execution`, 3375 tests green; reverted. This covers every
schema the `graphitron` module's own tests validate. It does *not* cover `graphitron-sakila-example`'s
`schema.graphqls`, which is validated in that module's build; re-measure there before trusting the
count as total.

20 rows, two kinds, no third:

| Arm | Rows | Distinct coordinates | Verdict |
|---|---|---|---|
| `validateColumnBackedField` "@column is not valid on a non-table-backed type" | 18 | 8 | false positive, blocks step 1 |
| `validateColumnBackedReferenceField` `NodeIdEncodeKeys` deferral | 2 | 2 | expected; the predicted canary |

**The `ColumnBackedField` rows are the finding.** `validateColumnBackedField` gates on
`types.get(field.parentTypeName()) instanceof GraphitronType.TableBackedType`. A nested leaf's
`parentTypeName()` is the nesting type, which classifies as `NestingType`, so routing the plain
nested scalar (the most common nested leaf there is: `FilmDetails.title`, `TranslatedTexts.nb`)
through its own arm rejects it as invalid schema. The arm is correct at ordinary depth and wrong at
nested depth, because it reads table-boundness off the immediate parent type when the nesting type
inherits its table context from the anchor.

So step 1 cannot land as a pure extraction. The per-variant arms were written on the assumption that
the immediate parent type is the table-bound anchor, and the nested walk falsifies it. Fix inside
this item: the walk already holds the `NestingField`'s `ReturnTypeRef.TableBoundReturnType` at every
level, so thread the inherited table context down and have the arm check *that* rather than
`parentTypeName()`. A `nested` flag that skips the gate is sound too (a classified nested column leaf
only exists under a table-bound nesting return type, which is the guarantee the gate is reaching
for), and is the smaller diff; threading the anchor is the more honest check. Either is fine, pick one
and say why. What is not fine is splitting it out: it is caused by step 1, so step 1 does not ship
without it.

Two adjacent reads on the same pattern, neither triggered here, both worth a glance while in the
area: `validateServiceTableField` early-returns on a non-table parent (fails open at nested depth
rather than false-positiving) and `validateRecordReadField` would false-positive the same way. Both
leaves are outside the nested-wireable set, so they are notes for whoever widens next, not work here.

#### One more shadowed check, same family

`walkNestedVariantsForImplementation` recurses into a nested `NestingField`'s `nestedFields()`
without ever calling `validateNestingField` on the nesting field itself, so the list-cardinality
rejection ("list cardinality on a plain-object nesting field is not supported") fires only at the
top level. A list-shaped nesting type nested inside another nesting type is unchecked today. That is
the same shadowing this item exists to remove, and the routing shape prescribed above ("each
non-`NestingField` leaf") preserves it. Close it here: apply the nesting field's own non-walk checks
at each level, taking care not to re-enter the walk and double-report the subtree. Add a unit case
alongside the existing `Film.tags` one, at depth two.

`validatePivotSpec`'s slot walk is a third site running implementedness alone. `PivotSlotField`'s
arm is empty so there is no fallout, but "so the two sites cannot drift again" is really three; route
it through the shared helper too, or note the exemption.

### 2. Widen `isNestedWireableLeaf`

Add `ChildField.ColumnBackedReferenceField` and `ChildField.ComputedField` arms returning `true`.

Do **not** gate the `ColumnBackedReferenceField` arm on `CallSiteCompaction.Direct`. Step 1 makes
`validateColumnBackedReferenceField` fire at nested depth, and it rejects `NodeIdEncodeKeys` on its
own account with a message that names the actual missing capability ("requires JOIN-with-projection
emission"). A compaction gate in the predicate would re-shadow that with the vaguer "is not yet
supported under NestingField", which is the shape of mistake this item is unwinding.

### 3. Repair the predicate's javadoc

Replace the "Expanding this predicate requires the corresponding generator-side change" sentence
with what the spike established: the projected leaves reach nested depth through a per-anchor
projection unit and a parent-independent alias read, so admitting one is a validator question,
while the class-backed and record-sourced leaves are the ones that need emit arms. Keep the
`BatchedTableField` source-shape paragraph, which is still exactly right and is the reason the
predicate is a predicate rather than a class set.

## Tests

**Pipeline tier** (`GraphitronSchemaBuilderTest`, following R23's pattern): a fixture that
classifies a nesting type carrying both leaves and validates clean.

**Unit tier** (`NestingFieldValidationTest`). The two existing cases do not invert, and that is the
point. `DEFERRED_NESTED_COMPOSITE_REFERENCE` and `DEFERRED_NESTED_COMPOSITE_INSIDE_NESTED_NESTING`
both build a `NodeIdEncodeKeys` carrier, so their expected message moves from
"ColumnBackedReferenceField is not yet supported under NestingField" to the
`validateColumnBackedReferenceField` deferral, not to "no error". The measurement confirmed the
replacement text for both coordinates ("ColumnBackedReferenceField NodeIdEncodeKeys (rooted-at-parent
NodeId reference) not yet implemented", then the JOIN-with-projection clause), and that nothing else
fired on those two fixtures. Those two assertions are the canary for step 1: had only step 2 landed they would have gone green with no error and the
generator would then crash on the same fixture. Call that out in the diff so a reviewer reads the
change as a shadowing fix rather than a message tweak. Add alongside them:

- a `Direct`-compaction `ColumnBackedReferenceField` under a nesting type, expecting no error;
- a `ComputedField` under a nesting type, expecting no error;
- a `ComputedField` with a non-empty `joinPath` under a nesting type, expecting the
  `validateComputedField` lift-form deferral. This is the case the spike watched emit silently
  wrong SQL, so it is the highest-value regression guard in the file.

**Execution tier** (`graphitron-sakila-example`): a nesting type on `Film` carrying a `@field` +
`@reference` scalar and an `@externalField` leaf, queried alongside the same two leaves declared
flat on `Film`, asserting the nested and flat values agree. Include an aliased duplicate selection
(`a: languageName`) to pin the `__rk_<resultKey>` read, and one level of nesting inside nesting.
All three passed in the spike, so this tier pins verified behaviour rather than exploring.

**Census.** `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` should need no
edit. `ColumnBackedReferenceField` sits in `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` and
outside `TypeFetcherGenerator.IMPLEMENTED_LEAVES`, landing in the derived projected bucket where
the already-admitted `TableField` sits; `ComputedField` is in both sets, one of the dual-arm kinds
the census pins explicitly (its `generateTypeSpec` arm is a no-op, since `IMPLEMENTED_LEAVES`
membership means "no `stub(f)` call", not "emits a method"). Neither set keys on nested-depth
wireability. This reading was taken from source and the spike build ran `-Pquick`, which skips
tests, so confirm it with a real run rather than inheriting the claim.

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
