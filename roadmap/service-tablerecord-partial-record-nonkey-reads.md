---
id: R426
title: "TableRecord-sourced @service keys are partial records; service bodies reading non-key columns get silent nulls"
status: In Review
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-07-02
last-updated: 2026-07-03
---

# TableRecord-sourced @service keys are partial records; service bodies reading non-key columns get silent nulls

> Make the codegen honor the *already documented* contract of the typed-`TableRecord`
> source shape: when a table-backed parent carries a `BatchKeyField` child whose
> `sourceKey().wrap()` is `SourceKey.Wrap.TableRecord`, the parent `$fields` projection
> includes the full parent-table row (`table.fields()`), not just the selection plus key
> columns. The user manual (`handle-services.adoc`) and the in-tree
> `FilmService.titleTitlecase` already promise/assume "fully-populated parent records";
> the implementation delivers selection-dependent population, so this is a bug against the
> documented contract, not a missing doc. Fold R425's key-column collection and this
> full-row signal into one walk returning a sealed
> `RequiredProjection { FullParentRow | Columns }` (hence `depends-on: R425`). Pin at
> pipeline tier (full-row projection for a TableRecord-sourced service child;
> key-columns-only for a `Record1`-sourced sibling), at execution tier by unmasking the
> in-tree reproducer (`titleTitlecase` queried *without* selecting `title`), and at
> federation tier with an `_entities` fetch whose service body reads a non-key column.

## Problem

A `@service` child whose `Sources` parameter is typed-`TableRecord` (e.g. `Set<FilmRecord>`,
`SourceKey.Wrap.TableRecord`) receives keys built via
`((Record) env.getSource()).into(Tables.X)` (`GeneratorUtils.buildKeyExtraction`,
`generators/GeneratorUtils.java:492-524`). That record is *partial*: it carries only the
columns the parent `$fields` SELECT projected (the GraphQL selection plus the force-included
key columns; after R425 the key columns are guaranteed). A service body reading a non-key
column off that record (`film.getTitle()` when only `film_id` plus selected columns are
populated) gets `null` with no error, and whether it gets `null` depends on which *other*
fields the client happened to select. Same silent-null failure family as R425, and the same
worst-case trigger: a federation `_entities` fetch selects only the fields the router needs,
so a service child that worked in every hand-written test query silently degrades in
production.

### The contract is already written down, and the code breaks it

This item was filed as a "developer-side contract hazard" with documentation as a candidate
direction. Investigation shows the contract question is already settled, in the opposite
direction:

- `docs/manual/how-to/handle-services.adoc:208`: "`X extends TableRecord` →
  `MappedTableRecordKeyed`. The framework supplies fully-populated parent records (every
  column on the parent table), extracted via `env.getSource().into(Tables.FILM)`. Use this
  when the body needs columns beyond the parent's PK; `titleTitlecase` reads
  `film.getTitle()` straight off the record without a SELECT."
- `handle-services.adoc:226`: "The typed-`TableRecord` shape is the only one where the
  framework supplies fully-populated parent records."
- The in-tree `FilmService.titleTitlecase` (`graphitron-sakila-service`) reads `getTitle()`
  off the source record, and the execution test
  (`GraphQLQueryTest.films_titleTitlecase_resolvesViaServiceRecordFieldDataLoader_tableRecordSource`)
  asserts through it, its comment repeating the "without an extra fetch" promise. It passes
  only because the test query `{ films { title titleTitlecase } }` selects `title` alongside;
  drop `title` and the field resolves to a titlecased `null` today.
- The resolver's parent-table mismatch rejection
  (`ServiceDirectiveResolver.validateTableRecordSourceParentTable`) steers "use a
  Row1/Record1 source-shape if the typed record isn't needed": the typed-record arm's whole
  reason to exist is reading non-key parent columns without a re-SELECT. The `Row`/`Record`
  wraps already serve the key-only case, and their key types (`RowN`/`RecordN` over exactly
  the key columns) make non-key reads unrepresentable.

So the documented and intended semantics are "full parent row"; the implementation delivers
"whatever the selection projected". The fix is to make the projection honor the contract.

## Design

### Production change: full-row projection gated on the wrap axis

On a table-backed parent (`TypeClassGenerator.generate()`'s `TableType`/`NodeType` walk),
when any child field, recursing through `ChildField.NestingField` exactly as
`collectRequiredProjectionColumns` does, is a `BatchKeyField` whose `sourceKey().wrap()` is
`SourceKey.Wrap.TableRecord`, the generated `$fields` method appends the whole parent row:

```java
java.util.Collections.addAll(fields, table.fields());
```

Mechanism notes:

- `$fields(sel, table, env)` already receives the (possibly aliased) concrete jOOQ table as
  the `table` parameter, and accumulates into a `LinkedHashSet` deduped by jOOQ Field
  identity (`build$FieldsMethod`, `TypeClassGenerator.java:216-244`). Appending
  `table.fields()` is therefore alias-correct by construction: the aliased instance's
  fields carry their base column names, which is the same name-matching `into(Tables.X)`
  reads by, so the two sites agree by column name. No per-column `ColumnRef` list needs to
  be threaded through the model: the model keeps carrying key columns on `SourceKey`, and
  the *generator* widens the projection at the emit site (jOOQ's `Table` already knows its
  own columns, and the alias lives on the `table` parameter, not in the model).
- **Single walk, sealed outcome** (per the principles-architect consult): the projection
  requirement of a table-parent `BatchKeyField` is one of two mutually exclusive outcomes,
  *these specific columns* or *the whole parent row*. Rather than a
  `(Stream<ColumnRef>, boolean)` pair, where the `true` case makes the column list
  redundant and a second parallel `NestingField` walk folds the same tree, fold R425's
  column collection and this item's full-row signal into one traversal returning a sealed
  `RequiredProjection { FullParentRow | Columns(List<ColumnRef>) }` with an absorbing
  combine (`FullParentRow` dominates). `build$FieldsMethod` switches once: `FullParentRow`
  emits the `table.fields()` append and skips the per-column loop; `Columns` keeps the
  existing loop. "Full row subsumes columns" becomes a type fact, not a dedup accident.
- The key extraction is untouched: `into(Tables.X)` keeps building the DataLoader key from
  the parent row, which is now guaranteed complete.
- Gate on the **wrap**, not on the sealed variants: `buildKeyExtraction` already forks the
  key *read* on the wrap, and `Wrap.TableRecord` is the arm whose read (`into(Tables.X)`)
  touches arbitrary parent columns by name, so full-row projection is correct for *any*
  table-parent field carrying that wrap, independent of which variant carries it. Today
  only the two `@service` shapes (`ServiceTableField`, `ServiceRecordField`) can carry it
  (Split* shapes are always `Wrap.Row`); gating on the wrap means a future `BatchKeyField`
  acquiring the wrap gets the right projection for free, whereas gating on the variants
  would re-encode a distinction the wrap already carries and create exactly that drift
  trap. The wrap check sits *after* R425's record-parent guard-throw inside the same
  `BatchKeyField` arm, so only table-parent fields reach it (the call-site soundness
  invariant R425 documents).

### Cost and its boundary

Full-row projection over-fetches when the parent table is wide and the client's selection is
narrow. That cost is opt-in via the developer's declared parameter type, which is exactly how
the docs frame the arm ("the most powerful"); the `Row`/`RecordN` source shapes remain the
frugal choice and the mismatch rejection already points at them. Make the cost explicit with
one sentence in `handle-services.adoc`'s typed-record bullet: choosing the typed-record shape
opts the parent SELECT into projecting the full row whenever the field is selected.

### Why "make reality match the docs" and not the reverse

Beyond the doc promise itself, two principles settle the tiebreak. *Separate business logic
from API code*: the service body is business logic and the generated fetcher is the API
layer; a body reading `film.getTitle()` against the documented contract is correct business
logic that the API layer silently starves, and the docs-first alternatives would make the
business layer responsible for knowing the API layer's projection internals. *Classifier
guarantees shape emitter assumptions*: the classifier's own rejection message steers
developers to the typed-record shape precisely when non-key reads are needed; a classifier
acceptance that differentiates a shape obliges the generated code to deliver that shape.
Note also `handle-services.adoc:210` already carries a nuanced caveat whose table-parent
half ("if the parent is a synthesised connection, every column is present") is *false*
today; this fix reconciles the doc's internal contradiction rather than merely satisfying
`:208`/`:226`.

### Rejected alternatives

- **Docs-only** (document "only key columns are guaranteed"): the docs promise the opposite,
  full population is the arm's only differentiation from `Wrap.Record`, and the in-tree
  `titleTitlecase` would become a documented misuse. Rewriting the contract underneath
  existing users is worse than implementing it.
- **Narrow the extraction to key columns** (`.into(keyCols).into(Tables.X)`), making the
  partiality uniform and deterministic: deterministic failure beats data-dependent failure,
  but it makes the documented contract permanently false and breaks the arm's documented use
  case; honoring the contract beats both.
- **Validation-time detection of non-key reads**: the service body is opaque compiled code;
  bytecode analysis is out of all proportion to the problem, and the problem disappears once
  the record is fully populated.

## Tests

Per the tier rubric (pipeline pins shape, execution pins behaviour; body-string assertions
banned):

- **Pipeline-tier** (`graphitron`, alongside the R425 projection tests): SDL plus a
  typed-`TableRecord` `@service` child on a table type with no other force-projecting
  children; assert the generated type class projects the full parent row (new
  `TypeSpecAssertions` helper, sibling of `appendsRequiredColumn`, asserting the
  `table.fields()` append). Contrast test on the same shape with a `Record1`-sourced service
  child (`titleUppercase` shape): key columns only, no full-row append; this pins that the
  widening is wrap-gated. A nested variant (TableRecord-sourced service child under a
  `NestingField`) pins the recursion *and* that the projected fields are the outer parent
  table's (`emitSelectionSwitch` threads `tableArg` unchanged into nested depths, so the
  nested child's `into(Tables.X)` reads against the outer table).
- **Execution-tier** (`graphitron-sakila-example`): the reproducer is already in-tree, just
  masked by its own selection. Add
  `films_titleTitlecase_withoutSelectingTitle_readsNonKeyColumnOffSourceRecord`: query
  `{ films { titleTitlecase } }` (no `title`, no `id`) and assert the titlecased values
  against real PostgreSQL. Red today (`getTitle()` is `null`, the body titlecases `null` to
  `null`), green after. Keep the existing masked test; update its comment to note which test
  pins the full-population contract. `Film`'s `cast`/`castByKey` siblings force-project only
  `FILM_ID`, so this test is not masked the way R425's key-column tests were. This test is
  also what pins the manual's "fully-populated parent records" claim (documentation names
  only live behaviour: the doc sentence must have a test that fails if it regresses).
- **Federation execution-tier** (`FederationEntitiesDispatchTest` sibling, building on
  R425's fixture): a representations-driven `_entities` fetch selecting *only* a
  TableRecord-sourced service child whose body reads a *non-key* column (the
  `titleTitlecase` shape). R425's federation test pins only the key-column null; this is
  the same "router selects the child but nothing else" production shape applied to non-key
  reads, the highest-value residual surface.
- **Compilation-tier**: the sakila-example compile picks up the new emission via the
  existing fixtures.

## Implementation sites

- `generators/TypeClassGenerator.java`: fold `collectRequiredProjectionColumns` into the
  single-walk sealed `RequiredProjection` collector, switch on it in `build$FieldsMethod`
  (full-row append vs. the existing per-column loop); extend the force-include taxonomy
  comments (`:98-108`, `:234-238`, already touched by R425) with the wrap-gated full-row
  case.
- `graphitron/src/test/...`: pipeline tests plus the `TypeSpecAssertions` helper.
- `graphitron-sakila-example`: the unmasking execution test; comment update on the masked
  sibling.
- `docs/manual/how-to/handle-services.adoc`: the contract prose at `:182` currently hedges
  ("the framework hasn't fetched them yet on the typed-record arm, though the typed-record
  arm is special-cased, see below"); after the fix state it plainly. Add the one-sentence
  projection-cost note to the `:208` bullet. The `:210` caveat (a parent that is itself a
  hand-rolled service record is only as populated as the service made it) stays: it
  describes the record-parent path this item does not touch.

## Sequencing

Depends on R425: the fix lands as a refinement of the `BatchKeyField` capability arm R425
introduces in `collectRequiredProjectionColumns`, and R425's guard is what keeps
record-parent implementers out of this walk. Implement after R425 reaches Done (or stack on
its branch if scheduled together).

## Non-goals

- Record-parent shapes (`RecordTableField`, `RecordLookupTableField`,
  `RecordTableMethodField`) and service-returned parents: no `$fields` SELECT exists to
  widen; population stays the producing service's responsibility, per the existing
  `handle-services.adoc:210` caveat.
- The `Row`/`Record` source shapes: their key types make non-key reads unrepresentable; no
  change.
- `SingleRecordIdField` (also `Wrap.TableRecord`, but reads the producer's returned record,
  not a `$fields` row, and is not a `BatchKeyField`): out of scope.
- No change to `buildKeyExtraction` or any DataLoader mechanics.

Surfaced by the R425 principles-architect consult; see that item's spec for the
projection-side (key-column) analysis.
