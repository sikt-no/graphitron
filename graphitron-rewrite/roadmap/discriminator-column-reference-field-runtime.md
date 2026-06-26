---
id: R388
title: "Discriminated-interface @reference field generates wrong SQL/fetcher at runtime"
status: Backlog
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
per-field `@reference` boilerplate and the `$fields(subjektTable)` wrongness) — is a separate, larger
feature worth its own roadmap item. R388 stays scoped to the near-term correctness fix.

How we ended up here: execution tests *do* cover discriminated interfaces and cross-table `@reference`
participant fields (`GraphQLQueryTest` Content/FilmContent/ShortContent, including `FilmContent.rating`).
But those fixtures use an FK target table (`film`) that does **not** re-declare the discriminator column,
and no fixture has a field that is simultaneously the discriminator/interface column and a `@reference`.
Closing the gap requires an execution fixture mirroring the `subjekt` + composite-FK shape (participant
table re-declaring the discriminator column, plus a participant-only field reached via the composite FK).

Scope: codegen qualification fix (defect 1) + validation/classifier guard (defect 2) + the execution
fixture that would have caught both.
