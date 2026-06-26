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
   schema-validation theme) or have the classifier ignore `@reference` when the field resolves to a column
   already present on the interface table / the discriminator column.

How we ended up here: execution tests *do* cover discriminated interfaces and cross-table `@reference`
participant fields (`GraphQLQueryTest` Content/FilmContent/ShortContent, including `FilmContent.rating`).
But those fixtures use an FK target table (`film`) that does **not** re-declare the discriminator column,
and no fixture has a field that is simultaneously the discriminator/interface column and a `@reference`.
Closing the gap requires an execution fixture mirroring the `subjekt` + composite-FK shape (participant
table re-declaring the discriminator column, plus a participant-only field reached via the composite FK).

Scope: codegen qualification fix (defect 1) + validation/classifier guard (defect 2) + the execution
fixture that would have caught both.
