# Plan: Lift `@asConnection` rejection on `@splitQuery` fields

> **Status:** In Review
>
> §1 (SplitTableField + Connection) shipped at `3821842`. Awaiting reviewer
> sign-off to close. `@asConnection` + `@lookupKey` is permanently invalid (not
> a deferred scope), so the only possible §2 follow-up is dynamic `@orderBy` on
> Split+Connection, gated on whether any consumer needs it.

## Problem

The classifier rejects `@asConnection` on every `@splitQuery` arm today
(`FieldBuilder.java:252-256`, `:279-283`; both `SplitTableField` and
`SplitLookupTableField`), telling users the combination "is deferred to a
follow-up plan". That makes the most natural shape for a paginated child
collection ("batch-load a bounded page of children per parent") unreachable.
Consumers currently have to choose between a batched list (no pagination) or an
un-batched root-style connection (n+1 queries). This plan wires the window-function
approach the rejection message promised. Production-impact snapshot: 68 distinct
rejections, the top-ranked item in the rewrite-roadmap snapshot.

## Shape of the emitted SQL

For a connection-returning `@splitQuery` field with ORDER BY `o1, o2` over partition
key `parent_fk`, page size `N`, cursor `after`:

```sql
WITH ranked AS (
  SELECT terminal.*, parentInput."idx" AS "__idx__",
         ROW_NUMBER() OVER (PARTITION BY parentInput."idx"
                            ORDER BY terminal.o1, terminal.o2) AS "__rn__"
  FROM terminal
  JOIN parentInput
    ON terminal.fk = parentInput."parent_fk"
  WHERE <where>
    AND (terminal.o1, terminal.o2) > (:o1_after, :o2_after)  -- seek, if after supplied
)
SELECT <projection>, "__idx__"
FROM ranked
WHERE "__rn__" <= (N + 1)                                     -- over-fetch by 1
```

Reuses all four existing building blocks:
- `parentInput` VALUES derived-table: exact shape from `SplitRowsMethodEmitter.buildListMethod`
- `__idx__` scatter column: exact name + meaning from the non-connection path
- `ORDER BY` list: same columns used for cursor encoding in the root connection path
- `WHERE (ordering_cols) > (cursor_values)` seek: same jOOQ `.seek()` predicate
  semantics, but materialised as an explicit tuple comparison so it lives inside
  the windowed CTE rather than at the outer query level

For backward pagination (`last`/`before`), the emitter inverts the ORDER BY inside
`ROW_NUMBER()` (reuses `ConnectionHelper.reverseOrderBy`), fetches top N+1 per
partition, and the per-parent `ConnectionResult.trimmedResult()` re-reverses at
read time. Identical mechanics to the root connection fetcher.

## Batching key

DataLoader batches are keyed on `(parentKey, selectionSet, paginationArgs)` so every
member of a batch shares the same selection, page size, and cursor. Two concurrent
resolvers paginating two parents with different `after` cursors go to separate
batches; two resolvers asking for the first page of different parents batch
together (the common case). This collapses the per-row cursor-splicing problem
to a single shared `WHERE (o1, o2) > (:o1_after, :o2_after)` inside the windowed
CTE, which is what the SQL shape above already draws.

## Per-parent `ConnectionResult`

Today the split DataLoader's value type is `List<Record>` (list cardinality) or
`Record` (single). Connection cardinality changes it to `ConnectionResult` per key:

- DataLoader signature: `DataLoader<KeyType, ConnectionResult>`
- Scatter step produces `List<ConnectionResult>` indexed 1:1 with `keys`
- Each per-key `ConnectionResult` carries only that parent's trimmed + over-fetched
  slice, plus shared `(pageSize, afterCursor, beforeCursor, backward, orderByColumns)`.
  The args are the same across a batch, so they're extracted once in the rows
  method and wired into every slice.

The existing `edges/nodes/pageInfo` child resolvers in `ConnectionHelper` read off
`env.getSource()` as `ConnectionResult` and call `cr.trimmedResult()` / `cr.hasNextPage()`;
they don't care whether that `ConnectionResult` came from a root fetcher or a
Split one. Keeping the child contract `ConnectionResult` is what makes this clean.

## Cursor format

Same encoding as root connections (`ConnectionHelper.encodeCursor(record, orderByColumns)`):
the cursor carries the seek columns' values, not the row-number ordinal. This
preserves cursor stability under page-size changes and concurrent inserts within
the partition, matching the root-connection semantics. `decodeCursor` runs once
in the rows method (shared across the batch) and the decoded seek values are
applied inside the windowed CTE.

## Touch points

**§1 (shipped at `3821842`):**
- `FieldBuilder:279-283` Connection rejection on `SplitTableField` path deleted.
- `GraphitronSchemaValidator.validateSplitTableField` gained two checks for
  Split+Connection: (a) orderBy non-empty, (b) orderBy not `Argument`. See
  "Deferred / non-goals" below for why Argument is out of §1 scope.
- `SplitRowsMethodEmitter.buildConnectionMethod` (new) emits the
  parentInput VALUES + FK chain + ROW_NUMBER envelope; dispatch arm in
  `buildForSplitTable`. `buildScatterConnectionByIdxHelper` emits a scatter
  that returns `List<ConnectionResult>`; helper emission gated on
  "any connection-returning SplitTableField in the class".
- `TypeFetcherGenerator.buildSplitQueryDataFetcher` branches on wrapper:
  Connection means `DataLoader<KeyType, ConnectionResult>` and returns
  `CompletableFuture<ConnectionResult>`.
- `ConnectionResultClassGenerator`: storage type narrowed from `Result<Record>`
  to `List<Record>` so the scatter can pass per-parent `ArrayList` sublists
  without synthesizing a jOOQ Result. Root emission unaffected because
  `Result<Record>` extends `List<Record>`.
- `SplitRowsMethodEmitter.buildRuntimeStub` handles the Connection wrapper's
  `List<ConnectionResult>` return type alongside the existing List/Single cases.

**Permanent rejections (no further work):**
- `FieldBuilder:252-257` and `:266-271`: `@asConnection` + `@lookupKey` rejected
  at classifier time as an invalid schema (not a deferred feature). `@lookupKey`
  establishes a positional input-list↔output-list correspondence, which
  pagination would break. Holds whether or not `@splitQuery` is present.
  Message instructs the schema author to drop one directive.
- `GraphitronSchemaValidator` rejections of `Connection` on `LookupTableField` /
  `SplitLookupTableField` stay as defence-in-depth.

**No new shared classes.** `ConnectionHelper.edges/nodes/pageInfo`,
`ConnectionHelper.encodeCursor/decodeCursor/reverseOrderBy`, and
`ConnectionHelper.PageRequest` are all reused.

## Deferred / non-goals

- **Single-cardinality `@splitQuery` + `@asConnection`**: nonsensical (a single
  result isn't paginable). Classifier keeps rejecting; message updated to drop
  the "follow-up plan" caveat.
- **`@asConnection` + `@lookupKey` (any form)**: invalid schema, rejected at
  classifier time. `@lookupKey` contracts a 1:1 positional mapping between the
  input key list and the output list; pagination would break that contract.
  Not a "deferred" generator gap, it is permanently incompatible. The rejection
  message instructs the schema author to drop one directive.
- **Dynamic `@orderBy` on `SplitTableField` + `@asConnection`**: rejected at
  validator time. The existing `<fieldName>OrderBy(env)` helper emitted by
  `TypeFetcherGenerator.buildOrderByHelperMethod` hard-codes the canonical
  `tableLocal` alias (e.g. `actor`), but Split emission uses the FK-chain terminal
  alias (e.g. `actorsConnection_a1`). Reuse would return an `OrderByResult`
  referencing the wrong jOOQ table instance, which jOOQ would flag at SQL
  generation. Lifting the gate needs either a Split-specific OrderBy helper that
  accepts the terminal-aliased Table, or a broader refactor threading the alias
  through all OrderBy helpers. Possible §2, gated on whether any consumer asks
  for it.
- **Condition-join hops**: unsupportedReason already gates these; unchanged.
- **Custom pagination-arg names**: the plan uses `first/last/after/before`
  literal names in the emitted rows method (the same shortcut the root connection
  fetcher takes, per `buildQueryConnectionFetcher:548-551`, where
  `pagination().first().name()` is read but always matches the default today).
  If the audit item on custom names lands first, the split path inherits the
  outcome for free.

## Phases

**§1: SplitTableField + Connection** (the main item): shipped at `3821842`.
Fixture: `Film.actorsConnection` over the film_actor junction with
`@defaultOrder(primaryKey: true)`. Three execution tests cover two-parent batching
(one rows round-trip), forward after-cursor paging (per-partition seek), and
backward last-page (reversed ordering + client-side re-reverse). Learnings:

- jOOQ's `.seek(Field<?>...)` on the inner Select composes the tuple comparison
  inside the windowed CTE's `WHERE`; `DSL.noField(col)` from `decodeCursor`
  collapses to no-op when no cursor is supplied, so the single rows method
  handles both first-page and after-cursor paths without a runtime branch. The
  `WHERE (ordering_cols) > (cursor_values)` shape drawn in "Shape of the emitted
  SQL" above is what jOOQ emits; we never had to build it by hand.
- The `<fieldName>OrderBy(env)` helper for dynamic ordering bakes in the
  canonical `tableLocal` alias, not the Split path's FK-chain terminal alias;
  §1 scope rejects `OrderBySpec.Argument` at validator time and defers the
  alias-threading refactor to §2.
- `ConnectionResult`'s `result` field narrowed from `Result<Record>` to
  `List<Record>` so the scatter can pass per-parent `ArrayList` sublists without
  synthesizing a jOOQ Result. Root connection emission still compiles because
  `Result<Record>` is-a `List<Record>`.

**§2 (conditional): dynamic `@orderBy` on SplitTableField + Connection.**
Gated on a consumer request. Needs the `<fieldName>OrderBy(env)` helper to
accept the FK-chain terminal-aliased Table instead of baking in the canonical
`tableLocal` alias. Either a Split-specific helper variant or a cross-cutting
refactor that threads the alias through every OrderBy helper. If no consumer
hits it, keep the validator rejection and close this plan at §1.

**§3: Scope closure.** Delete this file once §1 is approved, assuming §2 is not
pursued. If §2 lands, its own closure commit handles the file deletion.

## Test coverage

§1 shipped with:
- Pipeline tests (`GraphitronSchemaBuilderTest`): classification cases
  `AS_CONNECTION_SPLIT_CLASSIFIED` and
  `CONNECTION_WITH_DEFAULT_ORDER_INDEX_SPLIT_CLASSIFIED` assert the
  previously-rejected shapes now produce `SplitTableField` with
  `FieldWrapper.Connection`. `AS_CONNECTION_SPLIT_LOOKUP_REJECTED` pins the §2
  rejection message.
- Execution tests (`GraphQLQueryTest`): three cases covering two-parent
  batching into a single rows invocation, forward `first: N, after: cursor`
  (per-partition seek), and backward `last: N` (reversed ordering, client-side
  re-reverse).
- Lint ratchets: existing three (`GeneratedSourcesLintTest`) catch `var`,
  full-qualified jOOQ tables, and graphql-java imports on entity-scoped
  `*Conditions` classes. The new emitter avoids all three.

§2, if pursued, needs a pipeline + execution test for dynamic-orderBy on
Split+Connection. Classifier case
`AS_CONNECTION_LOOKUP_REJECTED` in `GraphitronSchemaBuilderTest` already pins
the `@asConnection` + `@lookupKey` rejection as a permanent invariant.

## Open questions

1. **§2 trigger**: does any in-tree or downstream schema want dynamic `@orderBy`
   on `@splitQuery @asConnection`? If yes, §2 is worth doing and the alias-threading
   refactor is its focus. If no, §1 closes the plan.

### Decided during §1

- **OrderBy-required invariant**: require non-empty ORDER BY at validator time
  for Split+Connection. Validator now emits
  "@splitQuery connections require a non-empty ORDER BY (add @defaultOrder,
  @orderBy, or a primary key on the target table)" when orderBy is `None` or
  empty `Fixed`. Partitions without a total order would produce silently
  non-deterministic slicing, which is a worse failure mode than a build error.
- **Over-fetch size**: same `pageSize + 1` as the root connection fetcher.
  `ConnectionResult.trimmedResult()` already handles the backward reversal
  symmetrically, so §1 reuses `page.limit()` unchanged.
