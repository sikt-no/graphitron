---
id: R242
title: DML payload positional input/output alignment
status: Spec
depends-on: [collapse-singlerecordtablefield-into-recordtablefield]
created: 2026-05-26
last-updated: 2026-06-15
---

# DML payload positional input/output alignment

Payload-returning bulk DML carriers (DELETE / INSERT / UPDATE / UPSERT) must
emit data-field lists that are positionally aligned with the mutation's input
list: input index `i` maps to output index `i`, and positions where no row
was produced must be representable as `null` (DELETE: the row didn't exist /
wasn't deleted; INSERT/UPDATE/UPSERT: the corresponding "no result for this
input" case, exact taxonomy to be settled in Spec). Today the DELETE `Id`-arm
emitter (`FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`,
`FetcherEmitter.java:944`) iterates the `DELETE … RETURNING` `Result<Record>`
directly and appends one entry per RETURNING row, so a miss simply shortens
the output list, with no positional correspondence to the input. The
classifier compounds the mismatch by rejecting the `[ID]` (list-of-nullable)
wrapper that this contract requires and admitting only `[ID!]` / `[ID!]!`
(`BuildContext.java:617-625`, now scoped to `CarrierFamily.DML`); the
diagnostic that pins the wrong contract ("every element of a successful
DELETE response is the encoded PK of an actually-deleted row, so the slot
cannot be null") is the surface symptom that originally surfaced this bug.

**Substrate correction (R305).** This item was originally drafted around a
single mechanism, a `VALUES`-join against each verb's `RETURNING` result,
for all four verbs. That premise is wrong for the three verbs whose payload
is a `@table`: INSERT / UPDATE / UPSERT do not read their payload off
`RETURNING`, they **re-fetch** it with a follow-up SELECT outside the DML
transaction (`SingleRecordTableField`, `ChildField.java:105-113`). R305
(Ready) reclassifies that re-fetch as a source-keyed Lookup that already
emits the idx-ordered `VALUES(idx, key)`-join with `ORDER BY input.idx`, so
the input/output **ordering** half is delivered by R305. R242 therefore no
longer owns a `RETURNING` mechanism for those verbs; it owns the
**positional-`null` layer on top of R305's re-fetch Lookup**, plus the two
arms that genuinely cannot re-fetch and so stay `RETURNING`-native: the
DELETE `Id` arm (the row is gone) and the server-generated-PK INSERT ID echo
(no client key exists to re-fetch on).

RLS reframes the design: even on verbs where the SQL succeeds for every input
row, a row-level-security policy can filter the row out of the result the
carrier reads (the re-fetch SELECT for INSERT / UPDATE / UPSERT, or
`RETURNING` for DELETE). So positional `null` is not just the "DELETE PK
didn't exist" case; it can happen on any verb whenever the policy hides the
produced row from the caller's role. The data-field wrapper rule has to admit
this for all four verbs.

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
   by RLS from the result the carrier reads (the re-fetch SELECT, or
   `RETURNING` on DELETE; any verb). The contract is binary, not
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

The positional mechanism is an idx-ordered `VALUES (idx, key1, key2, …)`
derived table, `ORDER BY input.idx` on the outer select. This is the
federation `_entities` dispatch pattern: `idx` is the positional scatter
key, the `key*` columns identify an input row to its produced row. **What
the `VALUES` table joins against differs by verb**, and R305 already owns
one of the two substrates:

- **INSERT / UPDATE / UPSERT (`@table` payload), join against the re-fetch
  SELECT.** R305 already emits this `VALUES(idx, key)`-join for the re-fetch
  Lookup (`LookupValuesJoinEmitter.java:378-383`; `ORDER BY input.idx` at
  `:422`), so R242 authors no fresh join here. The catch: R305's join is an
  **inner** join driven by the catalog table (`.from(table).join(input)`),
  which preserves "same rows" but **drops** any input row whose produced row
  is missing (UPDATE-by-PK against an absent PK) or RLS-filtered. R242's
  delta is to make the correspondence total: drive the join from `input`
  (`LEFT JOIN`), read the projected columns nullable, emit `null` at all-null
  slots, and assert output size equals input size.
- **DELETE (`Id` arm), join against `RETURNING`.** The row is gone, so there
  is no re-fetch SELECT to join against; the substrate stays `RETURNING`.
  `LEFT JOIN` the input-PK `VALUES` table against the `DELETE … RETURNING`
  result, `ORDER BY idx`. This is the one arm where the original
  "`VALUES`-join against `RETURNING`" framing survives intact, replacing the
  current append-per-row iteration at `FetcherEmitter.java:944`.
- **INSERT, server-generated PK with no client-side identity.** Neither path
  works: there is no client key to re-fetch on, and SQL cannot carry `idx`
  from `INSERT … SELECT` source rows into `RETURNING`. Fall back to per-row
  INSERT as a single JDBC batch (`PreparedStatement.addBatch` +
  `executeBatch` with `RETURN_GENERATED_KEYS`), inside one transaction; the
  i-th `addBatch` slot scatters to output position `i` by construction.

Per-verb join key: DELETE-by-PK and UPDATE-by-PK key on the input `<pk>`;
UPSERT keys on the conflict `<uk>` (the input necessarily carries it, else
`ON CONFLICT` has no target); client-identity INSERT keys on the supplied
`<pk>` / `<uk>`.

The INSERT dispatch ("does this INSERT have client-side identity?") is a
classify-time decision against the `@input` resolution: if the input record
carries any column that is a PK or part of a UNIQUE index on the target
table, take the re-fetch `VALUES`-join path; otherwise per-row batched.
Multi-row order-preservation is **not** assumed even in the non-RLS happy
path; PostgreSQL preserves it in practice, but the SQL spec does not promise
it, and the carrier contract is too load-bearing to rest on de-facto
behavior.

### Design fork: where the `LEFT JOIN` null-padding lives

R305's lookup emitter inner-joins, so *every* inline lookup currently
shortens on a missing key, not just DML payloads. Two ways to reach the
positional `null`, to settle with R305's owner before implementing because
it decides whether R242 edits `LookupValuesJoinEmitter` or forks it:

1. **Null-pad in the Lookup family.** Flip `LookupValuesJoinEmitter` to drive
   from `input` with a `LEFT JOIN` and nullable reads, so a missing key
   renders `null` for every lookup. Cleanest if a missing `@lookupKey`
   result *should* be a positional `null` (the federation `_entities`
   contract arguably already wants this), but it changes lookup behavior
   well beyond DML payloads.
2. **Null-pad only on the DML-payload carrier.** Keep the shared inner-join
   emitter and add a payload-carrier-specific left-join variant. Narrower
   blast radius, at the cost of a second join shape in the lookup family.

Recommendation: option 1 if R305's owner agrees the inner-join shortening is
a latent lookup bug rather than intended; option 2 otherwise.

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

1. **Model (wrapper-shape admission).** Lift the wrapper-shape admission rule
   into a shared `DmlPayloadListWrapper` predicate in `BuildContext`, used by
   every element-arm classifier. Invert the `[ID]` reject at
   `BuildContext.java:617-625` (today scoped to `CarrierFamily.DML`) so
   nullable wrappers admit; mirror the same admission on the `@table`-element
   arm and on the non-DELETE DML kind classifiers. Diagnostic wording
   converges to one helper. This is the classify-time half and is
   substrate-independent: the same whether the payload arrives via re-fetch
   (R305) or `RETURNING` (DELETE).
2. **DELETE Id arm emit rewrite (`RETURNING`-native).** Replace the
   append-per-row iteration in
   `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`
   (`FetcherEmitter.java:944`) with a `LEFT JOIN` of the input-PK `VALUES`
   table against the `DELETE … RETURNING` result. Source becomes the
   pre-built ordered `Result<Record>` (each row carries idx + encoded PK
   columns, or all-nulls for a PK that matched no deleted row); the fetcher
   reads PK columns nullable and emits `null` at any all-null slot. Encoder
   is invoked only on non-null slots. This arm cannot route through R305's
   re-fetch Lookup: the deleted row is gone, so `RETURNING` is the only
   post-image.
3. **DELETE Table arm emit rewrite.** ~~Same `VALUES`-JOIN substrate for
   `buildSingleRecordTableFromReturningFetcherValue`; the synthesized
   per-row `Record` is `null` at miss positions; per-field
   `ColumnFetcher`s already null-propagate, so no per-field changes are
   needed beyond admitting `null` source rows.~~ **Obsolete (R287):** the
   DELETE -> `@table` carrier and `buildSingleRecordTableFromReturningFetcherValue`
   were removed (DELETE cannot project a `@table`, the row is gone); only
   the Id arm (step 2) remains for DELETE.
4. **UPDATE / UPSERT null-padding on R305's re-fetch Lookup.** These verbs'
   `@table` payloads already classify as re-fetch Lookups under R305, so the
   `VALUES`-join and ordering exist. R242's work is the null-padding delta of
   the design fork above (left-join + nullable reads + size-equality
   assertion) plus the wrapper admission from step 1. Join key is the input
   PK (UPDATE) or the conflict UK (UPSERT), discovered from `@upsertWith` /
   the input-resolution machinery; both are the same keys R305 already feeds
   the Lookup.
5. **INSERT admission and identity dispatch.** At classify time, examine the
   input's resolved column set: if any PK/UNIQUE column is supplied, classify
   as `InsertIdentityKind.ClientSupplied(keyColumns)`; otherwise
   `InsertIdentityKind.ServerGenerated`. `ClientSupplied` routes through
   R305's re-fetch Lookup keyed on the supplied identity, null-padded per the
   design fork (as UPDATE / UPSERT). `ServerGenerated` cannot re-fetch (no
   client key) and cannot carry `idx` through `INSERT … SELECT`, so it routes
   to `buildBulkInsertPerRowBatchedFetcher` (JDBC `addBatch` +
   `RETURN_GENERATED_KEYS`, scatter by batch slot) for the ID echo.
6. **`@service` size-check wrapper.** New
   `FetcherEmitter.wrapServiceProducerForPositional` helper emitted at the
   service-producer call site; takes the producer's `List<XRecord>` (or
   `Result<…>`) result and the input size, validates equality, propagates
   `null` entries unchanged.
7. **Tests** (every tier; rules of `rewrite-design-principles.adoc`):
   - *Unit:* wrapper-shape admission/rejection across all four verbs and
     both element arms (`ID`-element and `@table`-element); the DELETE-Id
     `RETURNING` `LEFT JOIN` emission shape pinned; the left-join null-pad
     delta over R305's re-fetch join pinned; `@service` size-check exception
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
     - input-order preservation through the `VALUES`-join (R305's join for
       the re-fetch verbs; the DELETE-Id `RETURNING` join for DELETE).
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
  lands, the conflict UK is in the input by construction and R305's re-fetch
  `VALUES`-join path covers it; R242 leaves the door open.
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

- **R305** (`collapse-singlerecordtablefield-into-recordtablefield`, Ready)
  is the substrate this item now builds on. R305 reclassifies the
  INSERT / UPDATE / UPSERT `@table` re-fetch as a source-keyed Lookup that
  emits the idx-ordered `VALUES(idx, key)`-join (`LookupValuesJoinEmitter`),
  delivering input/output ordering and preserving "same rows". R242 layers
  the missing-slot `null` / size-equality contract on top (the design fork
  above) and depends on R305 landing first; it does not re-emit a
  `RETURNING` join for those three verbs.
- **R156** introduced the DELETE payload-returning carrier and the
  PK-echo-of-actually-deleted-rows semantics that R242 revises.
  `buildSingleRecordIdFromReturningFetcherValue` was introduced there.
  (R287 removed the sibling `buildSingleRecordTableFromReturningFetcherValue`
  and the DELETE -> `@table` carrier; only the Id arm survives for DELETE.)
- **R141** was the original input-order-preservation pattern (the
  PK-keyed-map Java re-walk); R305 supersedes it with the SQL `VALUES`-join,
  and R242 inherits that substrate rather than the Java re-walk. The
  DELETE-Id arm still needs its own `RETURNING` `VALUES`-join (no re-fetch
  is possible once the row is deleted).
- **R158** admitted `@service`-backed producers on the single-record
  carrier data field; R242 adds the runtime size-check at every
  `@service` producer call site emitted on a payload-returning carrier.
- **R308** (`service-list-payload-arrival`) builds the list-arrival
  (`Source{Many}`) `@service` carrier arm on R305's framework. R242's
  `@service` size-check rides whichever carrier arm the producer lands on;
  the two items share R305's substrate and do not otherwise overlap.
- **R12** is the errors-channel producer; R242 keeps it orthogonal,
  ensuring the wrapper-shape rules do not preclude composition.
- **R63** (`dml-dialect-requirement-on-model`) owns dialect-capability
  gating; R242 stays on Postgres-only execution-tier coverage today and
  does not duplicate that surface.
