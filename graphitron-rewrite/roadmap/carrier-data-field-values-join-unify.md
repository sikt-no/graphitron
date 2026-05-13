---
id: R158
title: "Carrier data-field follow-up SELECT: Collection source + VALUES-idx JOIN (unify with bulk-DML)"
status: Backlog
bucket: structural
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Carrier data-field follow-up SELECT: Collection source + VALUES-idx JOIN (unify with bulk-DML)

The `SingleRecordTableField/MANY` fetcher emitted by `FetcherEmitter.buildSingleRecordTableFetcherValue` (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java:251) pins its upstream producer to a jOOQ `Result<RecordN<PK>>`. That contract is satisfied by `MutationBulkDmlRecordField`'s `.returningResult(PK)` shape but excludes any other producer that arrives with the PK columns already in hand; in particular, a `@service` mutation feeding a payload-carrier with a `@table`-element data field returns the developer-declared `List<XRecord>` verbatim, so the cast `(Result<RecordN<...>>) env.getSource()` fails at runtime with `ArrayList cannot be cast to org.jooq.Result`. Reproducer: `OpprettRegelverksamlingPayload` + `opprettRegelverksamling` `@service` returning `List<RegelverksamlingRecord>`.

Spec direction (to be confirmed during Backlog to Spec):

- **Widen the downstream contract.** Cast `env.getSource()` to `Collection<? extends Record>` (MANY) and `Record` (ONE); read PK columns via `Record.get(<PK field>)`. `Collection` is the least committal interface that covers the producers we care about: jOOQ's `Result` (via `List`), the `ArrayList` a `@service` mutation typically returns, and a `HashSet` (or other unordered `Collection`) when a service author has a reason to hand back an unordered set. Whether `HashSet` is semantically appropriate for a given carrier (i.e. whether order is required by the downstream consumer) is a validation-stage concern, not a fetcher-emit concern; the emit is uniform across ordered and unordered producers. R96 is preserved (no `@record` needed on the payload).
- **Push order-preservation into SQL via the VALUES-idx JOIN idiom already used by `@lookupKey`.** Walk the producer's `Collection<? extends Record>` once to build `Row<N+1>[]` of `(idx, pk1, ..., pkN)` in iteration order; `DSL.values(rows).as("<carrier>Input", "idx", pk1_sql, ...)` derives the keyset; `SELECT ... FROM <carrier_table> JOIN input ON pk_eq ORDER BY input.field("idx")` returns rows in the producer's iteration order. For `List` / `Result` producers iteration order is insertion order; for `HashSet` producers iteration order is whatever the hash structure yields (deterministic within a single fetch, not across fetches). Retire the R141 PK-keyed-map Java-side reorder (and its load-bearing "Postgres scan order then re-key in Java" comment). Empty-source short-circuit mirrors the existing lookup empty-rows arm. Reuse (or closely parallel) the helpers in `InlineLookupTableFieldEmitter` / `LookupValuesJoinEmitter`.
- **Fold R141's bulk-DML path onto the same idiom.** The aim is unification, not a second variant. `MutationBulkDmlRecordField` continues to emit `Result<RecordN<PK>>`; the downstream fetcher reads PKs the same way regardless of producer kind. The R141 order-preservation property `output.data[i] = input[i]` becomes "iteration order of the producer-supplied `Collection<? extends Record>` is the order the data field renders," upheld in SQL by `ORDER BY input.idx` rather than in Java by a second walk of the PK-keyed map. The bulk-DML case continues to produce a `Result` (insertion-ordered), so its end-to-end order story is unchanged; the migration is purely on the downstream emit.
- **Carrier walk stays untouched.** `BuildContext.tryResolveSingleRecordCarrier` is producer-kind-agnostic; the fix lives in the emitter, not the classifier. `ChildField.SingleRecordTableField` keeps its current permit shape; no new sibling.

Spec must address: ONE-arm shape (no VALUES table needed; single `Record.get(<PK field>)` per column, then a `SELECT ... WHERE pk_eq` returning one row); composite-PK keying inside the VALUES table (already handled by the lookup-table emitter); empty-source short-circuit invariants; the load-bearing classifier-check key for "downstream contract is `Collection<? extends Record>`" so the validator pins the widened contract; whether to surface a validation-time signal (warning or rejection) when a `@service` producer's reflected return type is an unordered `Collection` and the carrier's SDL semantics suggest order matters, or to leave that purely to the service author; whether to remove `MutationBulkDmlRecordField`'s `Result`-typed return in favour of a more general `Collection<? extends Record>` so the unification reaches all the way back to the producing fetcher's signature.
