---
id: R62
title: "Lift @lookupKey partition onto TableInputArg"
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
---

# Lift @lookupKey partition onto TableInputArg

> Three consumers (one validator, two emitters) rebuild the same `Set<String>` of
> `@lookupKey` field names from `TableInputArg.fieldBindings()` to partition
> `TableInputArg.fields()` into "WHERE-clause inputs" and "SET-clause inputs".
> Lift the partition onto the model so each consumer reads the typed projection
> directly.

Background lives in commit `181c28f` ("R22: append architectural follow-ups
from post-shipping review"); this item carries that lift forward as standalone
work after R22's stub-lift phases shipped.

---

## Motivation

`MutationInputResolver.resolveInput` (`MutationInputResolver.java:281`),
`TypeFetcherGenerator.buildMutationUpdateFetcher` (`TypeFetcherGenerator.java:1469`),
and `TypeFetcherGenerator.buildMutationUpsertFetcher`
(`TypeFetcherGenerator.java:1523`) each contain a variant of:

```java
var lookupNames = tia.fieldBindings().stream()
    .map(b -> b.fieldName())
    .collect(Collectors.toSet());
```

followed by a partition over `tia.fields()` against `lookupNames`. The
classifier already knows which `InputField.ColumnField` is `@lookupKey`-bound
when it builds `fieldBindings` from `buildLookupBindings`; the model just
doesn't carry the partition forward, so each consumer reconstructs it.

Per *Generation-thinking*: "if two consumers evaluate the same predicate over a
model field, the branch belongs in the model." Three consumers evaluating the
same name-set partition is exactly that smell.

## Design

Add two projections to `ArgumentRef.InputTypeArg.TableInputArg`. The existing
record (current shape: `name, typeName, nonNull, list, inputTable,
fieldBindings, argCondition, fields`) gains two components; nothing existing is
removed:

```java
public record TableInputArg(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        TableRef inputTable,
        List<InputColumnBinding.MapBinding> fieldBindings,
        Optional<ArgConditionRef> argCondition,
        List<InputField> fields,
        // new:
        List<InputField.ColumnField> lookupKeyFields,
        List<InputField.ColumnField> setFields)
    implements InputTypeArg {}
```

- `lookupKeyFields()`: the subset of `fields()` whose `name()` matches some
  `fieldBindings().fieldName()`. The narrower element type
  (`InputField.ColumnField`, not `InputField`) is what the classifier already
  guarantees on the mutation arm via the structural rejection in
  `MutationInputResolver.resolveInput` (`MutationInputResolver.java:259-275`),
  which restricts DML mutation inputs to `Direct`-extracted `ColumnField`.
  Query-side TIAs that may carry other input-field arms simply contribute zero
  `ColumnField` entries to the lookup-key projection because `@lookupKey` only
  ever lands on a `ColumnField`. The narrow type expresses the classifier's
  guarantee, per *Narrow component types*.
- `setFields()`: the subset of `fields()` whose `name()` is **not** in any
  `fieldBindings()` entry, restricted to the `ColumnField` arm (same
  rationale as above).

Both projections are real components on the record, not derived accessors, and
populated at construction. To keep the model the single producer, expose a
static factory `TableInputArg.of(name, typeName, nonNull, list, inputTable,
fieldBindings, argCondition, fields)` that derives the two projections from
`fields` + `fieldBindings`; both construction sites (see *Implementation
sites* below) call `of(...)` rather than the canonical constructor. The
records remain immutable.

## Consumer rewrites

### `MutationInputResolver.resolveInput` (Invariant #4)

```java
// before
var lookupKeyNames = foundTia.fieldBindings().stream()
    .map(MapBinding::fieldName).collect(Collectors.toSet());
boolean hasNonLookupField = foundTia.fields().stream()
    .anyMatch(f -> !lookupKeyNames.contains(f.name()));
if (kind == DmlKind.UPDATE && !hasNonLookupField) {
    return new Resolved.Rejected(Rejection.structural(
        "@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"));
}

// after
if (kind == DmlKind.UPDATE && foundTia.setFields().isEmpty()) {
    return new Resolved.Rejected(Rejection.structural(
        "@mutation(typeName: UPDATE) has no non-@lookupKey fields to set"));
}
```

### `buildMutationUpdateFetcher` (SET clause)

```java
// before
var lookupNames = tia.fieldBindings().stream()
    .map(b -> b.fieldName()).collect(Collectors.toSet());
for (var inputField : tia.fields()) {
    var cf = (InputField.ColumnField) inputField;
    if (lookupNames.contains(cf.name())) continue;
    // .set(col, val)...
}

// after
for (var cf : tia.setFields()) {
    // .set(col, val)... ; cf is InputField.ColumnField, no cast
}
```

### `buildMutationUpsertFetcher` (SET clause + dispatch)

The col/val lists still walk `tia.fields()` (every field, `@lookupKey`
included, lands on the insert branch). The SET clause walks `tia.setFields()`.
The `.doUpdate()` vs `.doNothing()` dispatch reads `setFields().isEmpty()`
instead of comparing list sizes.

## Naming alternatives considered

- `dataFields` / `payloadFields`: ambiguous against the R12 payload concept.
- `nonLookupFields`: defines the projection by negation; reader has to chase
  `lookupKeyFields` to know what's left.
- `setFields`: maps directly to the SET clause it drives in UPDATE/UPSERT and
  is the term mutations.md (Phase 4/5) used throughout. Going with this.

## Alternatives considered (rejected)

These were the alternatives the post-R22 review listed; selecting (1) above
because (2) and (3) widen the surface change without measurable benefit at the
three current call sites.

2. *Linked binding on `InputField.ColumnField`.* Have each `ColumnField`
   carry an `Optional<MapBinding>` so the partition is reachable per-field
   via a typed accessor. Slightly larger surface (touches every site that
   constructs `ColumnField`) and removes the "two parallel lists indexed by
   name" shape across the model, but no current consumer needs the per-field
   accessor.
3. *Sealed split.* Sub-variant `LookupKeyColumnField` vs `RegularColumnField`
   under `InputField.ColumnField`. Most aligned with *Sealed hierarchies over
   enums* but the broadest refactor; today's three consumers don't justify
   the extra arm.

If a future call site needs per-field lookup-key reachability (rather than
list-level partitioning), revisit (2).

## Tests

Pure model refactor, not a behaviour change. Acceptance gates:

- Existing execution-tier PostgreSQL tests against UPDATE and UPSERT must pass
  unchanged (`updateFilm_updatesRowAndReturnsProjectedFilm`,
  `upsertFilm_updateBranch_writesAndReturnsProjectedFilm`,
  `upsertFilm_insertBranch_writesAndReturnsProjectedFilm`). Emitted SQL is
  byte-identical; only the model and emitter shape change.
- `GraphitronSchemaBuilderTest.UPDATE_ALL_FIELDS_LOOKUP_KEY_REJECTED`
  (the Invariant #4 case at `GraphitronSchemaBuilderTest.java:4887`) still
  rejects with the same message wording (the rejection site moves from
  "predicate at classify time" to "structural projection of the model";
  wording stays).
- Pipeline assertion: a happy-path UPDATE classification produces a
  `tia.setFields()` containing the expected `ColumnField` entries (in
  declaration order), and `tia.lookupKeyFields()` containing the
  `@lookupKey`-bound entries. Mirror assertions for UPSERT.
- `LoadBearingGuaranteeAuditTest` continues to find the
  `dml-mutation-shape-guarantees` producer/consumer pair. The check's
  `description` text loses the "skip-the-set-during-walk" phrasing in the
  UPDATE/UPSERT consumer reliesOn; update the strings in the same commit.

## Implementation sites

- `ArgumentRef.java` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/`):
  two new components, static factory `TableInputArg.of(...)`, accessors.
- `FieldBuilder.classifyArgument` (`FieldBuilder.java:698`): switch the
  `new TableInputArg(...)` call to `TableInputArg.of(...)`. The bindings still
  come from `enumMappingResolver.buildLookupBindings(...)` at line 697.
- `MutationInputResolver.resolveInput` (`MutationInputResolver.java:234`): the
  second `TableInputArg` construction site (the synthetic re-build for the
  mutation gate) switches to `TableInputArg.of(...)`. Invariant #4 check at
  line 281 reads `setFields().isEmpty()`.
- `TypeFetcherGenerator.buildMutationUpdateFetcher`: SET-clause walk
  rewrite.
- `TypeFetcherGenerator.buildMutationUpsertFetcher`: SET-clause walk +
  dispatch rewrite.
- `@DependsOnClassifierCheck` reliesOn strings on both fetchers.

## Non-goals

- Lifting `fieldBindings()` into a typed projection of typed bindings (the
  `MapBinding`/`RecordBinding` seal). Already done in R50.
- Promoting `ArgumentRef` into `model/`. Tracked separately (see the open
  decisions in the historical mutations.md entry, captured in
  `changelog.md`); this lift only adds two components to a record that
  already crosses the model/generator boundary.
- Validator-time gating of the partition shape. The non-empty `setFields`
  check stays in `MutationInputResolver` per Invariant #4; no extra gate is
  introduced.
