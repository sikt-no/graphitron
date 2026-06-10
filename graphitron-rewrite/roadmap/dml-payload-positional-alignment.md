---
id: R242
title: DML payload positional input/output alignment
status: Spec
depends-on: []
created: 2026-05-26
last-updated: 2026-05-26
---

# DML payload positional input/output alignment

Payload-returning bulk DML carriers (DELETE / INSERT / UPDATE / UPSERT) must
emit data-field lists that are positionally aligned with the mutation's input
list: input index `i` maps to output index `i`, and positions where no row
was produced must be representable as `null` (DELETE: the row didn't exist /
wasn't deleted; INSERT/UPDATE/UPSERT: the corresponding "no result for this
input" case, exact taxonomy to be settled in Spec). Today the DELETE `Id`-arm
emitter (`FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`, lines
561-610) iterates the `DELETE … RETURNING` `Result<Record>` directly and
appends one entry per RETURNING row, so a miss simply shortens the output
list, with no positional correspondence to the input. The classifier
compounds the mismatch by rejecting the `[ID]` (list-of-nullable) wrapper
that this contract requires and admitting only `[ID!]` / `[ID!]!`
(`BuildContext.java:680-699`); the diagnostic that pins the wrong contract
("every element of a successful DELETE response is the encoded PK of an
actually-deleted row, so the slot cannot be null") is the surface symptom
that originally surfaced this bug. The same gap exists on the DELETE
`Table`-arm synthesized-Record path and across all four DML verbs.

RLS reframes the design: even on verbs where the SQL succeeds for every input
row, a row-level-security policy can filter the row out of `RETURNING`. So
positional `null` is not just the "DELETE PK didn't exist" case; it can
happen on any verb whenever the policy hides the produced row from the
caller's role. The data-field wrapper rule has to admit this for all four
verbs.

---

## Contract

Across DELETE / INSERT / UPDATE / UPSERT, on both payload-carrier arms
(`Id` and `Table`) and both producer kinds (DML and `@service`):

1. **Positional 1:1 with the input list.** Output index `i` corresponds to
   input index `i`. The output list size equals the input list size; no
   silent shortening.
2. **Nullable elements only.** List wrappers admit `[Type]` and `[ID]` only.
   `[Type!]` and `[ID!]` reject at classify time with a diagnostic pointing
   at the nullable form. Singleton `Type` / `Type!` and `ID` / `ID!`
   continue to admit on the singleton verbs; an RLS-filtered single row
   surfaces as `null`, and `Type!` bubbles to the field per standard
   GraphQL semantics (intended, not a footgun).
3. **Missing slot semantics.** A `null` at position `i` means "the database
   did not produce a row for this input." That can be: the targeted row did
   not exist (DELETE-by-PK, UPDATE-by-PK), or the produced row was filtered
   from `RETURNING` by RLS (any verb). The contract is binary, not
   reason-coded; callers who need reasons compose with the R12 errors
   channel (out of scope for R242).

### Per-verb failure-model summary

| Verb            | Without RLS                     | With RLS              | Wrapper admitted |
|-----------------|---------------------------------|-----------------------|------------------|
| DELETE-by-PK    | miss when PK absent             | + RLS filter          | `[Type]` / `[ID]` |
| UPDATE-by-PK    | miss when PK absent             | + RLS filter          | `[Type]` / `[ID]` |
| INSERT          | all-or-nothing (txn rollback)   | RLS filter only       | `[Type]` / `[ID]` |
| UPSERT          | all-or-nothing                  | RLS filter only       | `[Type]` / `[ID]` |

UPDATE-by-PK against a missing row is symmetric to DELETE-by-PK: the
position renders `null`, not an error. Per-row failure reasons (RLS vs.
missing-row vs. constraint) belong on the R12 errors channel; R242 only
guarantees the slot is representable.

---

## Identity-match strategy

Preferred mechanism for every shape: `VALUES (idx, key1, key2, …)` derived
table joined against the verb's `RETURNING` result, with `ORDER BY idx` on
the outer select. This is the federation `_entities` dispatch pattern
adapted to DML; `idx` is the positional scatter key, the `key*` columns are
whatever identifies an input row to its produced row.

Per-verb identity column(s):

- **DELETE-by-PK:** input carries PK → `LEFT JOIN del ON del.<pk> = input.<pk>`.
- **UPDATE-by-PK:** input carries PK → `LEFT JOIN upd ON upd.<pk> = input.<pk>`.
- **UPSERT:** input necessarily carries the conflict UK (otherwise
  `ON CONFLICT` has no target) → `LEFT JOIN ups ON ups.<uk> = input.<uk>`.
- **INSERT, input carries PK or UK:** `LEFT JOIN ins ON ins.<key> = input.<key>`.
- **INSERT, server-generated PK with no client-side identity:**
  `VALUES`-JOIN does not work because SQL has no way to carry `idx` from
  `INSERT … SELECT` source rows into the `RETURNING` output. Fall back to
  per-row INSERT executed as a single JDBC batch (`PreparedStatement.addBatch` +
  `executeBatch` with `RETURN_GENERATED_KEYS`), inside one transaction.
  The i-th `addBatch` slot scatters to output position `i` by construction.

The dispatch ("does this INSERT have client-side identity?") is a
classify-time decision against the `@input` resolution: if the input record
carries any column that is a PK or part of a UNIQUE index on the target
table, take the single-statement + `VALUES`-JOIN path; otherwise per-row
batched. Multi-row INSERT order-preservation is **not** assumed even in the
non-RLS happy path; PostgreSQL preserves it in practice, but the SQL spec
does not promise it, and the carrier contract is too load-bearing to rest
on de-facto behavior.

### `@service` producer alignment

A `@service`-backed mutation returns `List<XRecord>` by its own
implementation. Graphitron cannot reach inside to enforce positional
alignment, so the emitted fetcher wraps the returned list with a runtime
size-check against the input list size; mismatch throws an
`IllegalStateException` naming the producer method, the expected size, and
the actual size. Documented in the directive reference as "the service must
return one record per input element in input order; positions where no row
was produced must be `null`."

---

## Implementation phases

1. **Model.** Lift the wrapper-shape admission rule into a shared
   `DmlPayloadListWrapper` predicate in `BuildContext`, used by every
   element-arm classifier. Replace the `[ID!]` admit / `[ID]` reject pair
   at `BuildContext.java:680-699` with the inverted rule; mirror the same
   admission on the `Table` arm (`DataElement.Table`) and on non-DELETE
   DML kind classifiers. Diagnostic wording converges to one helper.
2. **DELETE Id arm emit rewrite.** Replace
   `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (lines
   561-610) with the `VALUES`-JOIN scatter emission. Source becomes the
   pre-built result of the join (an ordered `Result<Record>` where each
   row carries idx + encoded PK columns or all-nulls for misses); the
   fetcher reads PK columns nullable and emits `null` at any all-null
   slot. Encoder is invoked only on non-null slots.
3. **DELETE Table arm emit rewrite.** ~~Same `VALUES`-JOIN substrate for
   `buildSingleRecordTableFromReturningFetcherValue`; the synthesized
   per-row `Record` is `null` at miss positions; per-field
   `ColumnFetcher`s already null-propagate, so no per-field changes are
   needed beyond admitting `null` source rows.~~ **Obsolete (R287):** the
   DELETE -> `@table` carrier and `buildSingleRecordTableFromReturningFetcherValue`
   were removed (DELETE cannot project a `@table`, the row is gone); only
   the Id arm (step 2) remains for DELETE.
4. **UPDATE / UPSERT verb-arm admission.** Lift the carrier-shape permits
   from `MutationUpdateTableField` / `MutationUpsertTableField` (and their
   bulk siblings) to admit payload-returning carriers symmetric to the
   DELETE permits R156 introduced. Identity column is the input PK
   (UPDATE) or the conflict UK (UPSERT) discovered from
   `@upsertWith` / the input-resolution machinery.
5. **INSERT verb-arm admission and dispatch.** Lift carrier-shape permits
   for `MutationInsertTableField` / `MutationBulkInsertTableField`. At
   classify time, examine the input's resolved column set: if any
   PK/UNIQUE column is supplied, classify as
   `InsertIdentityKind.ClientSupplied(keyColumns)`; otherwise
   `InsertIdentityKind.ServerGenerated`. The two kinds dispatch to two
   sibling emitter helpers: `buildBulkInsertScatterFetcher` (single
   statement + `VALUES`-JOIN) and `buildBulkInsertPerRowBatchedFetcher`
   (JDBC `addBatch` + scatter by batch slot).
6. **`@service` size-check wrapper.** New
   `FetcherEmitter.wrapServiceProducerForPositional` helper emitted at the
   service-producer call site; takes the producer's `List<XRecord>` (or
   `Result<…>`) result and the input size, validates equality, propagates
   `null` entries unchanged.
7. **Tests** (every tier; rules of `rewrite-design-principles.adoc`):
   - *Unit:* wrapper-shape admission/rejection across all four verbs and
     both arms; `VALUES`-JOIN SQL emission shape pinned; `idx`-keyed
     scatter result shape pinned; `@service` size-check exception
     payload pinned.
   - *Pipeline:* per-verb classifier admission cells with `[Type]` /
     `[Type!]` / `Type!` / wrong-element-type matrices; `InsertIdentityKind`
     dispatch coverage (PK-in-input, UK-in-input, neither); `@service`
     producer registration with the new wrapper.
   - *Compilation:* `graphitron-sakila-example` adds one payload-carrier
     mutation per verb (Film for DELETE / UPDATE / INSERT-with-PK,
     Actor for UPSERT, and one INSERT case on a table with a SERIAL PK
     and no other identity to exercise the per-row batched path).
   - *Execution:* native-Postgres end-to-end per verb:
     - input-order preservation through `VALUES`-JOIN.
     - positional `null` at the miss slot (DELETE/UPDATE missing PK).
     - RLS-filtered row renders `null` at its slot (one execution test
       creates an RLS policy that hides a specific row from the test role
       and asserts the slot is `null`, others are populated).
     - per-row batched INSERT preserves order; failure on any row rolls
       back the whole batch and surfaces a clear exception.
     - `@service` returning wrong-size list throws the documented
       `IllegalStateException` with the producer method name in the message.

---

## Out of scope (called out, not regressed)

- `[Type!]` / `[ID!]` opt-in admission (e.g. on tables with no RLS policy).
  May be revisited as a separate Backlog item if a concrete user case
  emerges; until then the wrapper rule stays uniformly nullable.
- `ON CONFLICT DO NOTHING` semantics on INSERT (currently not expressible
  on the model; will be a separate Backlog item when added). When it
  lands, the conflict UK is in the input by construction and the existing
  `VALUES`-JOIN path covers it; R242 leaves the door open.
- The R12 errors-channel composition for per-row reason codes. R242 makes
  the slot `null`-representable; R12 already composes structurally with
  the carrier types.
- Dialect-capability gating for `RETURNING`. The existing
  `dml-dialect-requirement-on-model` (R63) work owns that surface; the
  per-row INSERT fallback in step 5 is dialect-neutral.
- Cross-arm consolidation of the `SingleRecordIdFieldFromReturning`
  permit. (R287 removed the sibling `SingleRecordTableFieldFromReturning`:
  DELETE cannot project a `@table`, the row is gone after the statement.)

---

## Cross-references

- **R156** introduced the DELETE payload-returning carrier and the
  PK-echo-of-actually-deleted-rows semantics that R242 revises.
  `buildSingleRecordIdFromReturningFetcherValue` was introduced there.
  (R287 removed the sibling `buildSingleRecordTableFromReturningFetcherValue`
  and the DELETE -> `@table` carrier; only the Id arm survives for DELETE.)
- **R141** is the closest existing input-order-preservation pattern
  (PK-keyed-map walk); R242 generalizes to `VALUES`-JOIN so all four
  verbs can share one mechanism, and so `idx` can be the scatter key on
  INSERT where the input has no PK.
- **R158** admitted `@service`-backed producers on the single-record
  carrier data field; R242 adds the runtime size-check at every
  `@service` producer call site emitted on a payload-returning carrier.
- **R12** is the errors-channel producer; R242 keeps it orthogonal,
  ensuring the wrapper-shape rules do not preclude composition.
- **R63** (`dml-dialect-requirement-on-model`) owns dialect-capability
  gating; R242 stays on Postgres-only execution-tier coverage today and
  does not duplicate that surface.
