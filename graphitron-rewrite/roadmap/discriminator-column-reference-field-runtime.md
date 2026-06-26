---
id: R388
title: "Discriminated-interface @reference field generates wrong SQL/fetcher at runtime"
status: Spec
bucket: bug
priority: 2
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Discriminated-interface @reference field generates wrong SQL/fetcher at runtime

A single-table-inheritance interface (`@table(name: "subjekt") @discriminate(on: "SUBJEKT_TYPE")`)
with participant types whose fields reach a participant-specific table through a composite FK
(`@reference(path: [{key: "feide_applikasjon__fk_feide_applikasjon_subjekt"}])`) validates, generates,
and then fails at runtime because the generated code is wrong. Two distinct defects, neither caught by
any execution test:

1. **Unqualified discriminator column in the cross-table join (`TypeFetcherGenerator`).** The discriminator
   column is emitted as a bare `DSL.field(DSL.name("subjekt_type"))` both in the SELECT projection
   (`buildInterfaceFieldsList`, line ~1001) and in the LEFT JOIN ON-clause discriminator predicate
   (`buildCrossTableJoinChain`, lines ~1089-1090). When a participant's FK-target table re-declares the
   discriminator column (a composite FK whose columns include the discriminator), the bare reference is
   ambiguous and PostgreSQL rejects the query (`column reference "subjekt_type" is ambiguous`). This bites
   *any* `@reference` field on such a participant, including legitimate participant-only fields like `navn`,
   not just the pathological field below. Fix: qualify the discriminator column to the interface table
   (`tableLocal`), which is where it always lives.

2. **A discriminator-column field that also carries `@reference` is misclassified as a cross-table
   participant field.** `subjekt_type` is declared on the interface (so it is projected plainly at the
   interface level as the discriminator column) yet also carries `@reference`; `TypeBuilder.extractCrossTableFields`
   accepts it as a `CrossTableField` and `FieldBuilder` emits a `ParticipantColumnReferenceField`. The
   resulting fetcher reads the join-only alias `FeideApplikasjon_subjekt_type`, but in a non-inline-fragment
   query (`{ applikasjoner { id subjekt_type } }`) no join fires, the column is projected as plain
   `subjekt_type`, and the read finds nothing. Fix: reject at validation (preferred, matches the
   schema-validation theme) or have the classifier ignore `@reference` **only when the field's resolved
   column already exists on the interface/base table** (the discriminator column, or any base column). This
   narrow scope is important: a participant-only field like `navn` lives on the detail table and its
   `@reference` is load-bearing under the current model (see root cause below); it must stay valid. Only the
   base-table-column overlap (`subjekt_type`) is the contradiction.

## Root cause runs deeper than the two defects (joined-table inheritance is unsupported)

The developer did not write `@table(name: "subjekt")` + per-field `@reference` by choice — it is the only
shape that compiles. The genuine model here is **joined-table (class-table) inheritance**: a discriminated
base table (`subjekt` carrying `subjekt_type`) plus a per-concrete-type detail table (`feide_applikasjon`,
…) joined by a composite FK on `(subjekt_id, subjekt_type)`. graphitron has no path for that:

- The discriminated path (`TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher`) is hardwired
  single-table: it selects `FROM tableLocal` (the interface table) and projects **every** participant's
  `$fields(sel, tableLocal, env)` against that one shared table (line ~1005). `$fields`'s `table` parameter
  is typed as the participant's *own* jOOQ table class (`TypeClassGenerator` line ~220) and a plain column
  emits `fields.add(table.<COLUMN>)` (line ~277). So declaring `FeideApplikasjon @table(name:
  "feide_applikasjon")` makes its `$fields` parameter type `FeideApplikasjon`, which cannot accept the
  `Subjekt` argument the orchestrator passes → won't compile. Forcing `@table(name: "subjekt")` fixes the
  type but then `navn` emits `subjektTable.NAVN`, which `Subjekt` does not have → still won't compile. Hence
  `@reference` on every detail field, to divert it out of the `$fields` column path into the conditional
  LEFT-JOIN-alias machinery. The `fields.addAll(FeideApplikasjon.$fields(…, subjektTable, …))` for a subtype
  whose data does not live on `subjekt` is the visible symptom of the shared-table assumption.
- The multi-table path (`MultiTablePolymorphicEmitter`, for non-discriminated `QueryInterfaceField` /
  `QueryUnionField`) *does* thread each participant's own table into `$fields(env.getSelectionSet(), t, env)`
  via per-typename stage-2 dispatch — it is the closest existing template — but it assumes wholly
  independent PK-bearing tables UNION'd together, with no shared discriminated base. It does not cover the
  discriminated + joined-detail shape.

So defects 1 and 2 are the runtime breakage *of the workaround*; the qualification fix + classifier guard
+ execution fixture make the workaround correct and runnable, which unblocks the consumer today. The larger
item — first-class discriminated joined-table inheritance (a participant declares its own `@table`, the
emitter joins it to the discriminated base and projects via the participant's own table/alias, dropping the
per-field `@reference` boilerplate and the `$fields(subjektTable)` wrongness) — is tracked as **R389** and
is out of scope here. R388 stays scoped to the near-term correctness fix.

## Spec / implementation plan

Reviewed against the design principles by the `principles-architect` (read-only consult). Key rulings
folded in below: defect 2's validator rejection is **mandatory** (not an either/or with classifier-skip)
per "validator mirrors classifier invariants"; defect 1's qualification has no compile-tier guard (both
forms type-check) so the new execution fixture is **load-bearing**, not optional.

### Workstream A — defect 1: qualify the discriminator column to the base table (`TypeFetcherGenerator`)

The discriminator column always lives on the interface/base table, and `tableLocal` is bound to an
unaliased `Tables.X` instance (confirmed via `GeneratorUtils.declareTableLocal`), so
`tableLocal.field(DSL.name(col))` renders `"base"."col"` and matches the `FROM` clause. Three emission
sites, all currently bare `DSL.field(DSL.name(discriminatorColumn))`:

1. **SELECT projection** — `buildInterfaceFieldsList`, line ~1001. Change to
   `fields.add($L.field($T.name($S)))` with `tableLocal` as the table local (it is already a parameter).
2. **LEFT JOIN ON-clause predicate** — `buildCrossTableJoinChain`, lines ~1089-1090. Change the
   `.and(DSL.field(DSL.name(col)).eq(value))` term to `.and($L.field($T.name($S)).eq($S))` with `tableLocal`.
3. **WHERE discriminator filter** — `buildDiscriminatorFilter`, lines ~1112-1113. Currently
   `condition.and(DSL.field(DSL.name(col)).in(...))`; this is ambiguous too once any participant join is
   present (the participant's detail table re-declares the discriminator column). Thread `tableLocal` into
   `buildDiscriminatorFilter` (add the parameter; both call sites — `buildQueryTableInterfaceFieldFetcher`
   line ~845 and `buildTableInterfaceFieldFetcher` line ~919 — already have `tableLocal` in scope) and emit
   `condition.and($L.field($T.name($S)).in($L))`.

### Workstream B — defect 2: reject the discriminator-column-with-`@reference` contradiction (validation)

A participant field carrying `@reference` whose resolved column (`@field(name:)`, falling back to the
field name) already exists on the **interface/base table** is a contradiction: the column is read directly
from the discriminated base table, so a cross-table `@reference` is meaningless. Resolve the "column lives
on the base table" predicate **once** (avoid the drift risk of `catalog.findColumn(base, col)` being
recomputed in two consumers):

- Compute it in `TypeBuilder.extractCrossTableFields` (TypeBuilder.java ~789-814) alongside the existing
  silent self-join skip at line 801 — that skip is the same family of contradiction (a `@reference` field
  that is not actually cross-table). Where line 801 skips `fk.targetTable().denotesSameTableAs(interfaceTable)`,
  add the sibling check: the field's resolved column exists on the interface table. Carry that fact so the
  validator reads it rather than recomputing.
- **Validator (mandatory):** in `validateTableInterfaceType` (GraphitronSchemaValidator.java ~326, which
  has the interface `TableRef` in scope), emit a `Rejection.invalidSchema` AUTHOR_ERROR with file:line for
  any such participant field, message naming the field, the offending column, and the base table, plus a
  `candidateHint` over the detail-table columns (error-quality convention). The classifier narrowing is then
  safe belt-and-suspenders; the validator is the gate. A participant-only field like `navn` (column lives
  only on the detail table, not on the base) is **not** matched and stays valid.

### Workstream C — execution fixture (load-bearing for defect 1) + validation test (defect 2)

Add a discriminated joined-inheritance fixture to the shared Sakila test DB
(`graphitron-sakila-db/src/main/resources/init.sql`, alongside the existing `content` discriminated
fixture). Use a clearly-scoped table-name prefix so it reads as an isolated fixture and is not picked up by
any existing `@table` example schema. Shape (mirrors the `subjekt` case):

- a base table with a PK and a discriminator column, plus a `UNIQUE(pk, discriminator)` constraint so the
  detail tables can carry a composite FK that **re-declares the discriminator column**;
- one or two detail tables, each with a composite FK `(pk, discriminator)` back to the base (re-declaring
  the discriminator column — the dimension no current fixture has) and a detail-only column;
- seed rows for each discriminator value.

GraphQL fixture (in the example schema), authored the **corrected** way (no `@reference` on the
discriminator field — that authoring is now a validation error):

- interface `@table(base) @discriminate(on: DISCRIMINATOR)` exposing the discriminator as a plain field;
- participant types `@table(base) @discriminator(value:)`, each with a detail-only field carrying
  `@reference(path: [<composite FK>])`.

Tests:

- **Execution** (`GraphQLQueryTest`, `@ExecutionTier`): (a) `{ <interface> { <id> <discriminator> } }`
  returns the correct per-row discriminator (failure mode 1 region); (b)
  `{ <interface> { __typename ... on <Participant> { <detailField> } } }` runs without an
  ambiguous-column error and returns the joined detail value (non-null for matching discriminator rows,
  null otherwise) — this is the **defect-1 regression test**, and the only mechanical guard for the
  qualification fix; assert the SQL via the `SQL_LOG` listener carries qualified discriminator references.
- **Validation** (pipeline/validation tier): a fixture with `@reference` on the discriminator field is
  rejected with the workstream-B message — the **defect-2 regression test**.

### Acceptance criteria

- Both queries from the bug report run green against PostgreSQL through `GraphQLQueryTest`.
- Generated SQL qualifies the discriminator column at all three sites (SELECT, ON-clause, WHERE filter).
- Authoring `@reference` on a discriminator/base-table column fails validation with a clear file:line
  message and a candidate hint; participant-only `@reference` fields (e.g. `navn`) remain valid.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green (full pipeline → compile → execute).

How we ended up here: execution tests *do* cover discriminated interfaces and cross-table `@reference`
participant fields (`GraphQLQueryTest` Content/FilmContent/ShortContent, including `FilmContent.rating`).
But those fixtures use an FK target table (`film`) that does **not** re-declare the discriminator column,
and no fixture has a field that is simultaneously the discriminator/interface column and a `@reference`.
Closing the gap requires an execution fixture mirroring the `subjekt` + composite-FK shape (participant
table re-declaring the discriminator column, plus a participant-only field reached via the composite FK).

Scope: codegen qualification fix (defect 1) + validation/classifier guard (defect 2) + the execution
fixture that would have caught both.
