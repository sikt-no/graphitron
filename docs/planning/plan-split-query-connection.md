# Plan: Lift `@asConnection` rejection on `@splitQuery` fields

> **Status:** Spec
>
> Design proposal for per-parent Relay pagination inside DataLoader batches via
> a `ROW_NUMBER() OVER (PARTITION BY <parent-fk>)` envelope. Lifts the four
> classifier rejections in `FieldBuilder` and the two validator rejections in
> `GraphitronSchemaValidator`. Needs reviewer sign-off before `[Ready]`.

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

**Classifier** in `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`:
- `:252-256`: delete the `hasSplitQuery && hasLookupKey && Connection` rejection;
  fall through to `SplitLookupTableField` construction
- `:279-283`: delete the `hasSplitQuery && Connection` rejection; fall through to
  `SplitTableField` construction

**Validator** in `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`:
- `:346-351`: `validateSplitLookupTableField` rejection on `Connection` wrapper must go
- `:340-343`: `validateSplitTableField` gains pagination-shape validation (e.g.
  OrderBy must be non-empty for a Connection, mirroring what cursor encoding
  requires today)

**Rows emitter** in `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`:
- New `buildConnectionMethod(...)` sibling to `buildListMethod` / `buildSingleMethod`.
  Reuses parent-input VALUES construction, alias generation, FK join emission, and
  the lookup-input join (for the `SplitLookupTableField` case). Adds the
  `ROW_NUMBER()` + CTE envelope and the `WHERE rn <= N+1` outer filter.
- Dispatch in `buildForSplitTable` / `buildForSplitLookupTable` picks the new
  method when `returnType().wrapper() instanceof FieldWrapper.Connection`.
- New `scatterConnectionByIdx(flat, keys.size(), pageSize, after, before, backward, orderByColumns)`
  helper emitted once per fetcher class alongside `scatterByIdx`. Returns
  `List<ConnectionResult>`.

**DataFetcher** in `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:
- `buildSplitQueryDataFetcher(:1051)` branches on wrapper: `Connection` means
  `DataLoader<KeyType, ConnectionResult>` and returns `CompletableFuture<ConnectionResult>`.
  Existing `List<Record>` / `Record` branches stay as-is.

**No new shared classes.** `ConnectionResult`, `ConnectionHelper.edges/nodes/pageInfo`,
`ConnectionHelper.encodeCursor/decodeCursor/reverseOrderBy`, and
`ConnectionHelper.PageRequest` are all reused.

## Deferred / non-goals

- **Single-cardinality `@splitQuery` + `@asConnection`**: nonsensical (a single
  result isn't paginable). Classifier keeps rejecting; message updated to drop
  the "follow-up plan" caveat.
- **`SplitLookupTableField` + `@asConnection`**: scope decision in §3 below.
  Semantics of "paginate the cartesian of per-parent × per-lookup-key" need a
  second design pass. First shipping phase targets `SplitTableField` only and
  keeps `SplitLookupTableField + Connection` rejected with an updated message.
- **Condition-join hops**: unsupportedReason already gates these; unchanged.
- **Custom pagination-arg names**: the plan uses `first/last/after/before`
  literal names in the emitted rows method (the same shortcut the root connection
  fetcher takes, per `buildQueryConnectionFetcher:548-551`, where
  `pagination().first().name()` is read but always matches the default today).
  If the audit item on custom names lands first, the split path inherits the
  outcome for free.

## Phases

**§1: SplitTableField + Connection** (the main item)
- Classifier: delete `FieldBuilder:279-283` rejection, update the message on the
  remaining single-cardinality arm.
- Validator: add OrderBy-non-empty check to `validateSplitTableField`.
- Emitter: `SplitRowsMethodEmitter.buildConnectionMethod` + `scatterConnectionByIdx`.
- DataFetcher: Connection arm in `buildSplitQueryDataFetcher`.
- Fixtures: add connection-returning split fixture (e.g. `Film.actors` as connection
  in sakila fixture; picks an existing many-many with a natural ordering).
- Execution test: two parents, forward/backward pagination, cursor round-trip.

**§2: SplitLookupTableField + Connection** (defer until §1 lands + reviewed)
- Requires a decision on per-lookup-key pagination semantics. Options:
  (a) paginate per `(parent, lookup-key)` pair (row_number partitions over the
  combined key); (b) paginate per parent, concatenating all lookup-key matches
  (row_number partitions over parent only); (c) reject until a consumer hits it.
- Emitter: `buildConnectionMethod` handles the `lookupMapping != null` branch; the
  lookup-input JOIN stays, partition clause varies by the option chosen.
- Classifier: delete `FieldBuilder:252-256` rejection.

**§3: Scope closure**
- Remove the "deferred to a follow-up plan" caveat from any remaining rejection
  messages (single-cardinality, condition-join).
- Roadmap: mark item Done, delete this file.

## Test coverage

- **Fixture expansion**: at least one SDL fixture in `graphitron-rewrite-test-fixtures`
  with `@splitQuery @asConnection` over a list relationship; variant coverage for
  `@orderBy(fixed:)` and `@orderBy(argument:)`.
- **Pipeline test**: assert classification produces `SplitTableField` with
  `Connection` wrapper (not `UnclassifiedField`); assert emission produces the
  expected method signatures.
- **Execution test**: real Postgres, two parents with overlapping child sets,
  forward `first: N`, `first: N, after: cursor`, backward `last: N, before: cursor`.
  Cursor stability under concurrent inserts out of scope (matches root connection
  coverage).
- **Lint ratchets**: existing three (`GeneratedSourcesLintTest`) already gate the
  generated bodies for `var`, full-qualified jOOQ tables, and entity-conditions
  imports. No new ratchet needed.

## Open questions

1. **OrderBy-required invariant**: root connections today don't *require* a
   non-empty ORDER BY (they fall through to `OrderBySpec.None`, empty orderBy,
   cursor encoding hashes an empty tuple, which works but gives stable-but-arbitrary
   pagination). Should Split+Connection require non-empty ORDER BY at validator
   time to force deterministic partitioning, or inherit the root fetcher's
   permissive stance? Recommend: **require it**, because partitions without a
   total order produce silently non-deterministic slicing, which is a worse
   failure mode than a build error.
2. **Limit N+1 vs N+2**: root fetcher over-fetches by 1 (`page.limit()` = pageSize+1).
   For backward pagination on partitions, does the same suffice, or does the
   re-reversal in `trimmedResult()` need a different guard? Recommend: same
   over-fetch; `trimmedResult` already handles the reversal symmetrically.
3. **Phase 2 scope gate**: if no in-tree consumer needs `@splitQuery @lookupKey`
   with a connection return, drop §2 from this plan entirely and leave the
   rejection with a permanent "not supported" message. Worth checking the
   `graphitron-rewrite-test-fixtures` schemas + any known downstream users before
   committing to §2 design.
