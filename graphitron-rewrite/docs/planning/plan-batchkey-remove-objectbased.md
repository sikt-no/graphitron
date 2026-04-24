# `BatchKey.ObjectBased` — remove the variant, reject DTO-parent service batching

> **Status:** Spec
>
> Collapses `BatchKey` to two variants (`RowKeyed`, `RecordKeyed`). The only remaining
> producer of `ObjectBased` is `ServiceCatalog.classifySourcesType` — a DTO parent
> handing a `List<Dto>` to an `@service` child. Split that arm: `TableRecord<?>`
> element types classify as `RowKeyed` from the parent table's PK; non-`TableRecord`
> element types return `Optional.empty()` → `UnclassifiedField`. A future directive-based
> lifter (backlog) will re-enable non-`TableRecord` DTOs by lifting them into `Row`/
> `Record` form — the sealed hierarchy stays at two variants.

## Overview

`BatchKey.ObjectBased` exists in the sealed hierarchy but no generator emits a working
rows-method for it. The design intent is that `BatchKey` only describes DataLoader
SOURCES parameters whose key is column-based — `RowN<…>` or `RecordN<…>`. DTO-parent
batching (free-form Java records / POJOs with no jOOQ semantics) is not supported today
and there is no legacy precedent to preserve. We collapse the hierarchy and reject the
non-`TableRecord` service-sources case at classification time; the existing
`UnclassifiedField` → `ValidateMojo` failure path gives the build-time error.

**Scope — one shape only.** Three DTO-parent shapes exist in principle:

1. **DTO → service** (`ServiceTableField`, `ServiceCatalog.classifySourcesType`).
   **This plan's subject.**
2. **DTO → `@splitQuery` + `@lookupKey`** (`RecordLookupTableField`).
3. **DTO → `@splitQuery` alone** (`RecordTableField`).

Shapes 2 and 3 do not go through `ServiceCatalog.classifySourcesType`. They derive
their `BatchKey` in `FieldBuilder.deriveBatchKeyForResultType` from FK metadata, which
produces `RowKeyed(fkJoin.sourceColumns())` uniformly for typed `ResultType` parents.
Record-fields Phase 1 and Phase 2 (Done on trunk) established this path. Neither path ever
reached `ObjectBased`. This plan does not touch them.

## Current state

- `BatchKey.java:27` permits three variants; `ObjectBased(String fqClassName)` is one.
- `ServiceCatalog.classifySourcesType` (`ServiceCatalog.java:334–339`) produces
  `ObjectBased` for both `TableRecord` element types (line 335–336 — misclassification:
  the `TableRecord` carries a jOOQ table whose PK can supply `RowKeyed` columns) and
  non-`TableRecord` element types (line 337–339 — free-form DTOs with no jOOQ
  semantics).
- `GeneratorUtils.keyElementType` (`GeneratorUtils.java:135`) and
  `GeneratorUtils.buildKeyExtraction` (`GeneratorUtils.java:242–245`) have `ObjectBased`
  switch arms. No rows-method body is emitted for them; any classified `ObjectBased`
  field fails at request time with `UnsupportedOperationException`.
- `GraphitronSchemaValidator.validateServiceTableField`
  (`GraphitronSchemaValidator.java:426–434`) has an early-return for `ObjectBased` that
  intentionally skips the "parent table must have PK" check. With no `ObjectBased`
  variant this escape hatch is unreachable.
- `BatchKeyField.java:10–11` names `ObjectBased` as the blocker for
  `RecordLookupTableField` implementing the capability. Stale after record-fields
  Phase 1 — that plan's `RowKeyed` path is the resolution, not an `ObjectBased` fix.

## Desired end state

- `BatchKey permits RowKeyed, RecordKeyed` — exhaustive switches narrow by one arm.
- `ServiceCatalog.classifySourcesType`:
  - **`TableRecord` element type** → `RowKeyed` constructed from the parent table's
    `primaryKeyColumns()`. Matches the invariant that `BatchKey` column sets always
    come from `TableRef.primaryKeyColumns()`, never from reflection.
  - **Non-`TableRecord` element type** → `Optional.empty()`. The caller
    (`ServiceCatalog.reflectServiceMethod` / downstream `ServiceTableField` classifier)
    surfaces the failure as `UnclassifiedField`; `ValidateMojo` fails the build via the
    existing stubbed-variant plumbing.
- `GeneratorUtils` `ObjectBased` switch arms deleted; the two remaining arms
  (`RowKeyed`, `RecordKeyed`) become exhaustive.
- `validateServiceTableField` escape hatch deleted; the PK-present check runs
  unconditionally once the field is classified (non-table-backed cases can never reach
  it, since they fail earlier with `UnclassifiedField`).
- `BatchKeyField.java` javadoc drops the "blocked on `ObjectBased`" note.

**Migration note (user-facing).** Any DTO flowing through a DataLoader path — service
sources today, `@splitQuery` sources for record-fields — must be backed by a jOOQ
`TableRecord<?>` whose populated key columns come from the underlying table. Free-form
DTOs / POJOs are not supported until the `BatchKey` lifter directive (backlog) lands.
The build-time error message names the field and points at the lifter backlog item.

## Non-goals

- **`BatchKey` lifter directive.** Mechanism (directive, interface, fluent builder —
  TBD) for a developer to supply a DTO → `RowN`/`RecordN` conversion so free-form
  DTOs re-enter the column-keyed DataLoader path. Sits on the backlog as Unplanned;
  the sealed hierarchy stays at two variants regardless.
- **Changes to record-fields Phase 1/2.** Their `BatchKey` derivation already uses
  `RowKeyed` + FK metadata; this plan does not touch
  `FieldBuilder.deriveBatchKeyForResultType`, `SplitRowsMethodEmitter`, or
  `GeneratorUtils.buildRecordKeyExtraction`.

## Key discoveries

- The only emit sites that reference `ObjectBased` are `GeneratorUtils.keyElementType`
  and `GeneratorUtils.buildKeyExtraction`. Both are small switch arms.
- Only one production classifier produces `ObjectBased` today:
  `ServiceCatalog.classifySourcesType`. No other model-construction site references it.
- `ServiceFieldValidationTest.OBJECT_BASED` (`ServiceFieldValidationTest.java:196–200`)
  is the only test asserting current `ObjectBased` behaviour; it asserts "no errors"
  for a PK-less parent. Rewritten to assert the rejection path.
- Row equality for `DSL.row(…)` is value-based via `AbstractRow.equals` →
  `FieldsImpl.equals` → `AbstractParam.equals`. Verified empirically
  (`DSL.row(123).equals(DSL.row(123))` → `true`, `HashMap`/`HashSet` dedup functional).
  This matters for DataLoader key dedup; not a blocker, but recorded here because prior
  drafts of this plan incorrectly asserted `Row` lacked Java equality.

## Implementation approach

Four phases. Phase 1 splits the classifier arm — the primary behaviour change. Phase 2
deletes the sealed variant. Phase 3 tidies docs. Phase 4 covers tests.

---

## Phase 1 — Classifier: split the `ObjectBased` arm

### Overview

`ServiceCatalog.classifySourcesType` stops producing `ObjectBased`. The `TableRecord`
branch produces `RowKeyed` from the parent `TableRef.primaryKeyColumns()`; the
non-`TableRecord` branch returns `Optional.empty()`.

### Changes

#### `ServiceCatalog.java` (lines 334–339)

```java
// before
} else if (elementType instanceof Class<?> elementClass
        && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
    return Optional.of(new BatchKey.ObjectBased(elementClass.getName()));
} else if (elementType instanceof Class<?> elementClass) {
    return Optional.of(new BatchKey.ObjectBased(elementClass.getName()));
}

// after
} else if (elementType instanceof Class<?> elementClass
        && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
    if (parentTable == null) return Optional.empty();
    return Optional.of(new BatchKey.RowKeyed(parentTable.primaryKeyColumns()));
}
return Optional.empty();
```

`parentTable` is the `TableRef` of the parent type, already resolved by the caller. If
the parent is not table-backed (DTO parent with no `TableRef`), the first branch falls
through to `Optional.empty()` — the `UnclassifiedField` path catches it with the
standard message.

`Optional.empty()` surfaces upstream as classification failure. Confirm the existing
error message names the field and the sources parameter; otherwise thread a reason
through `ServiceCatalog.reflectServiceMethod`.

### Success criteria

- [ ] `mvn test -pl graphitron-rewrite -Pquick`
- [ ] Pipeline / unit test coverage (Phase 4) for: (a) `TableRecord` element + table
      parent → `RowKeyed` classification succeeds; (b) non-`TableRecord` element →
      classification fails with a DataLoader-key-required message.

---

## Phase 2 — Sealed hierarchy: delete `BatchKey.ObjectBased`

### Overview

Remove the `ObjectBased` record and all `case BatchKey.ObjectBased` arms. Exhaustive
switches over `BatchKey` narrow by one arm; the compiler finds every site.

### Changes

#### `BatchKey.java`

```java
// before
public sealed interface BatchKey permits BatchKey.RowKeyed, BatchKey.RecordKeyed, BatchKey.ObjectBased
// after
public sealed interface BatchKey permits BatchKey.RowKeyed, BatchKey.RecordKeyed
```

- Delete the `ObjectBased` record (lines 81–94).
- Delete the `ObjectBased` bullet in the interface javadoc (lines 19–21).
- Delete the javadoc example for `ObjectBased(...)` (lines 39–41).
- Update the `keyColumns` paragraph (lines 23–25): drop the "parent type with no primary
  key" escape clause. Replace with: *"`keyColumns` always comes from the parent type's
  `TableRef.primaryKeyColumns()` — never from reflection. Parent types without a
  `TableRef` cannot produce a `BatchKey` and fail classification upstream."*

#### `GeneratorUtils.java`

Delete the `ObjectBased` arms in `keyElementType` (line 135) and `buildKeyExtraction`
(lines 242–245). Surrounding switches become exhaustive over two variants.

#### `GraphitronSchemaValidator.java` (lines 426–434)

Delete the `ObjectBased` escape hatch in `validateServiceTableField`. The
`hasRowOrRecordKeyed` boolean is now trivially true for every classified `Sourced`
parameter; the block collapses to an unconditional parent-table-PK check.

### Success criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick` — no exhaustive-switch compile
      errors.
- [ ] `mvn test -pl graphitron-rewrite -Pquick`

---

## Phase 3 — Docs

### Changes

#### `BatchKeyField.java`

Delete lines 10–11 (the "blocked on `BatchKey.ObjectBased`" note for
`RecordLookupTableField`). Add `RecordTableField` to the implementer list (shipped on
trunk via record-fields Phase 1) and note that `RecordLookupTableField` will join once
Phase 2 of record-fields lands — no remaining hierarchy-level blocker.

#### `docs/planning/rewrite-roadmap.md`

- The Backlog item *"`BatchKey.ObjectBased` generator path decision"* is already moved
  to Active Draft (this plan).
- The Backlog already carries an Unplanned *"`BatchKey` lifter directive"* entry.
- Delete any remaining *"`ObjectBased` batch loading is unimplemented"* Known Gap if
  still present.

### Success criteria

- [ ] Roadmap reads cleanly; no dangling references to `ObjectBased`.
- [ ] `BatchKeyField` javadoc matches the post-delete reality.

---

## Phase 4 — Tests

### Overview

Classifier-level tests for the two paths and a validator-level pipeline test for the
rejection.

### Changes

#### `ServiceCatalog` classification tests

- `List<FilmRecord>` element on a `@table(Film)` parent → `RowKeyed` with `Film.film_id`
  (regression covering Phase 1's "TableRecord → RowKeyed" split).
- `List<FilmDto>` element (plain class) → `Optional.empty()` from
  `classifySourcesType`; reflected upstream as classification failure.

#### Pipeline test

One case covering the end-to-end rejection: an SDL with a `@ResultType` parent + a
service-backed child whose `List<Sources>` element is a bare DTO class. Assert the
resulting `ValidationError` names the field and the DataLoader-key requirement.

#### `ServiceFieldValidationTest.java` (lines 196–200)

Rewrite the `OBJECT_BASED` case: previously asserted "no errors"; now asserts the
classification-failure path. Rename to `DTO_SOURCES_REJECTED` for clarity.

### Success criteria

- [ ] All new cases pass.
- [ ] `ServiceFieldValidationTest` passes with rewritten `DTO_SOURCES_REJECTED` case.
- [ ] `mvn test -pl graphitron-rewrite -Pquick` — zero failures.
- [ ] `(cd graphitron-rewrite && mvn install -Plocal-db)`
      — no regressions on execution suite (no `ObjectBased` execution coverage today).

---

## Testing Strategy

### Unit tests

- `ServiceCatalog.classifySourcesType` — `TableRecord` element with table parent →
  `RowKeyed`; non-`TableRecord` element → `Optional.empty()`.

### Pipeline tests

- DTO-parent service sources produces a classification error that names the field.
- `TableRecord` parent + `TableRecord` sources still validates cleanly (regression
  against the Phase 2 removal of the `ObjectBased` escape hatch in
  `validateServiceTableField`).

### Compilation + execution

- `graphitron-rewrite-test` compile must pass — catches any stray `BatchKey`
  switch that wasn't updated.
- Execution suite runs unchanged — there are no `ObjectBased` execution tests today.

---

## Open questions

None outstanding. The prior "TableRecord vs. reject" open question was resolved as
"TableRecord → `RowKeyed` from parent PK"; the prior "non-TableRecord Optional.empty()"
open question was resolved yes. Error-message wording is a doc polish and lands with
Phase 1.

---

## References

- Sealed interface: `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`
- Classifier site: `ServiceCatalog.java:334–339`
- Validator site: `GraphitronSchemaValidator.java:426–434`
- Capability interface: `BatchKeyField.java`
- Emit arms: `GeneratorUtils.java:135` + `GeneratorUtils.java:242–245`
- Record-fields path (unaffected): `FieldBuilder.deriveBatchKeyForResultType` (Phase 1 + 2 Done on trunk)
- Lifter directive backlog entry: `rewrite-roadmap.md` → "`BatchKey` lifter directive"
