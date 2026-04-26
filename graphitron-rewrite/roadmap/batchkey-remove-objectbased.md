---
title: "`BatchKey.ObjectBased` removal"
status: Spec
priority: 3
---

# `BatchKey.ObjectBased`: remove the variant, reject DTO-parent service batching

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
- `ServiceCatalog.classifySourcesType` (`ServiceCatalog.java:391–396`) routes any
  non-`RowN`/`RecordN` `Class<?>` element type — both jOOQ `TableRecord` subclasses
  (whose PK can supply `RowKeyed` columns) and free-form DTOs with no jOOQ semantics —
  through a single arm that emits `ObjectBased(elementClass.getName())`. The two
  shapes used to live in separate arms; they were collapsed when both produced
  identical codegen.
- `GeneratorUtils.keyElementType` (`GeneratorUtils.java:153`) and
  `GeneratorUtils.buildKeyExtraction` (`GeneratorUtils.java:290–293`) have `ObjectBased`
  switch arms. No `ServiceTableField` rows-method body is emitted for them
  (`SplitRowsMethodEmitter.buildRowsMethod` throws `IllegalArgumentException` for
  `ServiceTableField`; only `Split*`/`Record*` variants are handled), so any classified
  `ObjectBased` `ServiceTableField` would fail at generation time.
- `GraphitronSchemaValidator.validateServiceTableField`
  (`GraphitronSchemaValidator.java:618–627`) has an early-return for `ObjectBased` that
  intentionally skips the "parent table must have PK" check. With no `ObjectBased`
  variant this escape hatch is unreachable.
- `BatchKeyField.java` no longer carries the "blocked on `ObjectBased`" javadoc note —
  that cleanup landed independently. Phase 3's `BatchKeyField` edit is a no-op today.

## Desired end state

- `BatchKey permits RowKeyed, RecordKeyed` — exhaustive switches narrow by one arm.
- `ServiceCatalog.classifySourcesType` splits the single `Class<?>` arm into two:
  - **`TableRecord` element type** → `RowKeyed(parentPkColumns)` (or `Optional.empty()`
    when `parentPkColumns.isEmpty()`, i.e. the parent has no `TableRef`). Matches the
    invariant that `BatchKey` column sets always come from `TableRef.primaryKeyColumns()`,
    never from reflection.
  - **Non-`TableRecord` element type** → `Optional.empty()`. The caller
    (`ServiceCatalog.reflectServiceMethod` / downstream `ServiceTableField` classifier)
    surfaces the failure as `UnclassifiedField`; `ValidateMojo` fails the build via the
    existing stubbed-variant plumbing. The error message names the field, the sources
    parameter, and the lifter-directive backlog item (see Phase 1 for the threading
    mechanism).
- `GeneratorUtils` `ObjectBased` switch arms deleted; the two remaining arms
  (`RowKeyed`, `RecordKeyed`) become exhaustive.
- `validateServiceTableField` escape hatch deleted; the PK-present check runs
  unconditionally once the field is classified (non-table-backed cases can never reach
  it, since they fail earlier with `UnclassifiedField`).

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
- `ServiceFieldValidationTest.OBJECT_BASED` (`ServiceFieldValidationTest.java:198–202`)
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
branch produces `RowKeyed` from the parent's PK columns; the non-`TableRecord` branch
returns `Optional.empty()` and is surfaced upstream as a classification failure with a
message that points at the lifter-directive backlog item.

### Changes

#### `ServiceCatalog.java` — `classifySourcesType` (single `Class<?>` arm at lines 391–396)

The signature is `classifySourcesType(Type paramType, List<ColumnRef> parentPkColumns)`
— there is no `parentTable` here; callers pass `List.of()` when no parent table context
is available (existing javadoc).

```java
// before
} else if (elementType instanceof Class<?> elementClass) {
    // Object-based parent: a jOOQ TableRecord subclass or a plain result-DTO class. Both
    // shapes emit identical codegen ({@code (FqClass) env.getSource()}), so they collapse
    // to the same BatchKey variant.
    return Optional.of(new BatchKey.ObjectBased(elementClass.getName()));
}

// after
} else if (elementType instanceof Class<?> elementClass
        && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
    if (parentPkColumns.isEmpty()) return Optional.empty();
    return Optional.of(new BatchKey.RowKeyed(parentPkColumns));
}
return Optional.empty();
```

The non-`TableRecord` `Class<?>` case (free-form DTO) falls through to the trailing
`return Optional.empty();` already at the end of the method. The `TableRecord` case
with no parent PK (parent has no `TableRef`) likewise returns empty.

#### `ServiceCatalog.java` — `reflectServiceMethod` error message (around line 209–211)

Today, when `classifySourcesType` returns empty, the caller emits
*"parameter '…' has an unrecognized sources type: '…'"*. After this plan there are two
distinct empty-causes (truly unrecognized type versus non-`TableRecord` DTO, versus
`TableRecord` with no parent PK), and at least the DTO case wants a message that points
at the lifter directive.

Mechanism: replace `Optional<BatchKey>` with a small result type local to
`ServiceCatalog`:

```java
sealed interface SourcesClassification {
    record Classified(BatchKey key) implements SourcesClassification {}
    record Unrecognized() implements SourcesClassification {}
    record DtoSourcesUnsupported(String fqClassName) implements SourcesClassification {}
    record ParentLacksPrimaryKey() implements SourcesClassification {}
}
```

`reflectServiceMethod` switches on the result and produces a parameter-named message per
case; the `DtoSourcesUnsupported` arm names the rejected class and references the
`batchkey-lifter-directive.md` backlog item by title (path-relative wording, not a URL).
Existing call sites that consume `Optional<BatchKey>` (only `reflectServiceMethod` does)
adapt at the same time.

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

Delete the `ObjectBased` arms in `keyElementType` (line 153) and `buildKeyExtraction`
(lines 290–293). Surrounding switches become exhaustive over two variants.

#### `GraphitronSchemaValidator.java` (lines 618–627)

Delete the `ObjectBased` escape hatch in `validateServiceTableField`: the
`hasRowOrRecordKeyed` boolean and its `if (!hasRowOrRecordKeyed) return;` early-return
become dead code (every classified `Sourced` parameter is now `RowKeyed` or
`RecordKeyed`). Drop the variable and the early-return; the parent-table-PK check that
follows runs unconditionally.

### Success criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick` — no exhaustive-switch compile
      errors.
- [ ] `mvn test -pl graphitron-rewrite -Pquick`

---

## Phase 3 — Docs

### Changes

`BatchKeyField.java` already dropped the "blocked on `BatchKey.ObjectBased`" note in an
earlier cleanup; nothing to do there.

#### Roadmap

- This plan's item file is `roadmap/batchkey-remove-objectbased.md` (status: Spec).
- The Backlog already carries a [`BatchKey` lifter directive](batchkey-lifter-directive.md) entry.
- Delete any remaining *"`ObjectBased` batch loading is unimplemented"* Known Gap if
  still present.

### Success criteria

- [ ] Roadmap reads cleanly; no dangling references to `ObjectBased`.

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

#### `ServiceFieldValidationTest.java` (lines 198–202)

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

- `graphitron-test` compile must pass — catches any stray `BatchKey`
  switch that wasn't updated.
- Execution suite runs unchanged — there are no `ObjectBased` execution tests today.

---

## Open questions

None outstanding. The prior "TableRecord vs. reject" open question was resolved as
"TableRecord → `RowKeyed` from parent PK"; the prior "non-TableRecord Optional.empty()"
open question was resolved yes. The error-message threading mechanism (sealed
`SourcesClassification` result vs. simpler `Optional` + post-hoc `Class<?>` check in
`reflectServiceMethod`) is implementer's judgement; Phase 1 sketches the sealed-result
form because it keeps the failure cause typed at the boundary.

---

## References

- Sealed interface: `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`
- Classifier site: `ServiceCatalog.java:391–396` (single `Class<?>` arm); error-message threading at `ServiceCatalog.java:202–214`
- Validator site: `GraphitronSchemaValidator.java:618–627`
- Capability interface: `BatchKeyField.java` (already cleaned up; no edit required)
- Emit arms: `GeneratorUtils.java:153` + `GeneratorUtils.java:290–293`
- Record-fields path (unaffected): `FieldBuilder.deriveBatchKeyForResultType` (Phase 1 + 2 Done on trunk)
- Lifter directive backlog entry: [`batchkey-lifter-directive.md`](batchkey-lifter-directive.md)
