---
id: R436
title: "Unsafe into() key extraction collides with multiset aliases and escapes error redaction"
status: Ready
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-07-06
last-updated: 2026-07-06
---

# Unsafe into() key extraction collides with multiset aliases and escapes error redaction

A `@service` split field on a `@table` parent derives its DataLoader key by
blind-converting the whole parent source record: `GeneratorUtils.buildKeyExtraction`'s
`SourceKey.Wrap.TableRecord` arm (GeneratorUtils.java:519-522) emits
`XRecord key = ((Record) env.getSource()).into(Tables.X)`. When a *sibling* object
field on the same parent is multiset-backed, its projection is aliased to the GraphQL
field name (`TypeFetcherGenerator`, `.as(fieldName)`), and if that alias case-insensitively
collides with a physical column on the parent table, jOOQ's `into(...)` maps the nested
`Result` value into the physical column's type and throws a `MappingException`. This
surfaced in a downstream consumer whose `Utdanningsmulighet` `@table` type selected
multiset-backed object fields `dager`/`tider` (aliases colliding with the physical
`UTDANNINGSMULIGHET.DAGER` `daterange` / `TIDER` `tstzrange` range columns) alongside a
`@service` split field `statushistorikk`; every parent node crashed resolving the split
field. The collision is aggravated by R426: `TypeClassGenerator.RequiredProjection.FullParentRow`
already widens the parent SELECT to `table.fields()` for the `TableRecord` wrap, so both the
physical range columns and the colliding multiset aliases coexist in the record `into()` walks.

Two distinct defects, both worth fixing here:

1. **Unsafe key derivation.** `into(Tables.X)` reifies *every* column on the parent record by
   name, not just the key columns the DataLoader actually needs. Any sibling projection whose
   alias shadows a physical column name (multiset object fields are the concrete trigger, but the
   hazard is general) poisons the conversion. Contrast the `Wrap.Record` arm (GeneratorUtils.java:509-517),
   which projects only `sourceKey.columns()` via `into(col, ...)` and is immune. The `TableRecord`
   arm should likewise read only the key/PK columns (or otherwise avoid a whole-record `into`),
   reconciled with R426's documented contract that this wrap hands the service body a
   fully-populated parent record.

2. **Escapes error redaction.** `keyExtraction` is emitted synchronously in the DataFetcher body
   *before* the DataLoader dispatch and its guard: `DataLoaderFetcherEmitter.build` places the
   extraction at DataLoaderFetcherEmitter.java:141, ahead of `loader.load(key, env)` (:142) and the
   `.thenApply(...).exceptionally(ErrorRouter...)` tail (:143). A throw out of `into()` therefore
   propagates straight out of `DataFetcher.get()` and the `.exceptionally` router that would
   `redact` it never runs. The raw jOOQ message, which includes a dump of the record's data, leaks
   into the API response, a privacy hole, and repeated across every parent node it bloats responses
   (the reported symptom: OTel gRPC export exceeding its 4 MiB limit).

Minimal repro to encode as a fixture: a `@table` type that selects a multiset-backed object field
whose alias collides with a physical column name, alongside a `@service` split field on the same
type. Any API consumer hitting that combination crashes today.

## Design

### Defect 1: reserved-alias the full-parent-row projection

The root problem is that the `TableRecord` key read resolves parent columns *by name* in a record
whose namespace is shared with client-driven projection aliases. Narrowing the read to key columns
is off the table: for `@service` fields the extracted key *is* the `Sources` value handed to the
developer's method, and R426's documented contract (`handle-services.adoc`, "fully-populated parent
records, every column on the parent table") requires the full row. The fix instead moves the
full-parent-row projection into a namespace no GraphQL selection can reach.

The codebase already has a settled synthetic-alias namespace for exactly this purpose: `__`-wrapped
names (`__sort__`, `__idx__`, `__rn__`, `__pkN__`), collision-proof because GraphQL reserves
leading-`__` names for introspection so no sibling projection alias (multiset object fields,
`.as(fieldName)` scalar aliases, interface-participant aliases) can ever produce one. Reuse that
convention rather than inventing a second namespace:

* `TypeClassGenerator`'s full-parent-row emit changes from
  `Collections.addAll(fields, table.fields())` to appending each physical column re-aliased under
  a reserved dunder scheme, e.g. a runtime loop
  `for (Field<?> f : table.fields()) fields.add(f.as("__src_" + f.getName() + "__"))`. The `table`
  local is the caller's aliased parent table, so values and converters are unchanged; only the
  projected name moves out of collision range.
* `GeneratorUtils.buildKeyExtraction`'s `SourceKey.Wrap.TableRecord` arm stops whole-record
  `into(Tables.X)` and reconstructs the typed record from the reserved names. Enumerate the parent
  table's columns at generation time and emit one typed `key.set(Tables.FILM.COL, source.get("__src_col__", <column's Java type>.class))`
  per column: explicit types, converter-aware, no wildcard-erasing `(Field<Object>)` cast in the
  emitted body (a runtime `Field<?>` loop with an unchecked cast is the shape the emitted-code
  principles push against; the projection side's runtime loop is fine, it mirrors today's
  `Collections.addAll`).
* The reserved scheme (prefix/suffix) is a single named constant carrying the collision rationale,
  referenced by both emit sites, with a javadoc `{@link}` tying the consumer (`buildKeyExtraction`)
  to the producer (the `$fields` emit) so the shared-secret coupling is visible. The dunder-lint
  pins (`DunderFreeEmissionPipelineTest`, `GeneratedSourcesLintTest`) gain the new allowlist entry.

Rejected alternatives, recorded so the reviewer doesn't re-derive them: pre-projecting via
`into(Tables.X.fields()).into(Tables.X)` still resolves by unqualified-name fallback under table
aliasing (the parent query aliases its tables, so identity/qualified matches fail) and inherits the
same first-match hazard; projecting a nested `DSL.row(table.fields())` under one reserved alias
risks Postgres composite-parse fragility for exotic column types (ranges are the incident's very
types); a generation-time validator rejecting *any* sibling-alias/physical-column collision (the
broad, whole-row case) punishes legitimate schemas like the incident's that work everywhere except
this seam — the narrow key/correlation-column validator adopted below is different: those schemas
are genuinely broken regardless of this fix.

**Consequence: `RequiredProjection` becomes a product, not a sum.** Today the sealed
`{ FullParentRow | Columns }` arms are genuinely mutually exclusive because base-named
`table.fields()` subsumes the columns list; the R426 comments call that "a type fact, not a dedup
accident". Under the reserved-alias scheme the full row no longer supplies base-named columns, so
the other force-projection consumers, `Wrap.Row`/`Wrap.Record` key reads (`get(Tables.X.COL)` /
`into(Tables.X.COL, ...)`) and `TableMethodField` correlation reads
(`parentRecord.get(DSL.name("<src>"), ...)`), still need the base-named force-include list even
when the full row is projected. The two facts are now co-present independent axes, and a sealed
two-arm sum with a non-absorbing combine has no honest shape (what would combining the arms
yield?). Reshape `RequiredProjection` into a product record carrying both facts (e.g.
`RequiredProjection(boolean reservedFullRow, List<ColumnRef> baseColumns)`);
`collectRequiredProjection` accumulates both, and the `$fields` emit appends both the
reserved-aliased full row and the base-named force-included columns. Key columns then appear twice
in the SELECT (once base-named, once reserved); that minor duplication is accepted. The R426
javadoc/comments asserting the absorbing "type fact" become false under this change and are
rewritten in the same commit.

**The residual key/correlation-column collision is validated at build time.** The base-named reads
above still resolve columns by name and would break when a sibling alias shadows a *key or
correlation column* specifically. Both name sets are known in the model at build time (sibling
GraphQL field names vs the force-included column names), so the collision is decidable at validate
time, and a schema containing it has legal queries that cannot succeed. Add a validator error
(case-insensitive match, per the incident) rejecting a sibling field whose name collides with a
`Wrap.Row`/`Wrap.Record` `SourceKey` column or a `TableMethodField` correlation column on the same
table-backed parent; the message names the colliding field and column and the remedy (rename the
GraphQL field). This is deliberately narrow: the broad whole-row collision case is *fixed* by the
reserved aliases, not rejected, so legitimate schemas like the incident's keep working. Defect 2's
guard remains the runtime backstop for anything the static check cannot see.

### Defect 2: route synchronous pre-dispatch throws through ErrorRouter

`DataLoaderFetcherEmitter.build` wraps the emitted body from the key extraction through the
dispatch-plus-async-tail in a `try`/`catch (Throwable e)` whose catch arm routes through the *same*
`ErrorRouter` disposition the async `.exceptionally` arm uses, wrapped for the async return type:
`return CompletableFuture.completedFuture(<routed DataFetcherResult>)`. The disposition is
deliberately defined once (R415: `ErrorRouterClassGenerator.noChannelRouterCall` shared across
sync catch arms and async tails), and the new catch arm must not become a third definition: thread
the router call (not the whole tail) from the caller to both arms so they cannot drift. That also
carries the channel-awareness requirement for free: a service fetcher with an error channel gets
its channel dispatch in the sync catch too, not a blanket redact, because the catch arm is built
from the same `errorChannel` the `.exceptionally` router call was.

One seam covers every DataLoader-registering fetcher: service (`buildServiceDataFetcher`), split
(`buildSplitQueryDataFetcher`, including the `buildKeyExtractionWithNullCheck` single-cardinality
shape whose early `return CompletableFuture.completedFuture(null)` stays legal inside the `try`),
and the record-parent key-extraction variants. The `preRegistrationPrelude` (R268 Outcome
narrowing) stays outside the guard: its early return is a deliberate control-flow arm, not a
failure path, and it precedes loader registration by design.

## Implementation

* `TypeClassGenerator`: `RequiredProjection` reshaped from the sealed sum to the product record;
  `collectRequiredProjection` accumulates both axes; `build$FieldsMethod` emits the
  reserved-aliased full-row append alongside the base-named column appends; the R426 "type fact"
  javadoc/comments rewritten to the two-axis shape.
* `GeneratorUtils.buildKeyExtraction`: `TableRecord` arm rewritten to generation-time per-column
  reconstruction from the reserved names; javadoc's wrap-axis table updated. Reserved alias scheme
  defined as one named constant reachable from both `TypeClassGenerator` and `GeneratorUtils`,
  with a `{@link}` from consumer to producer.
* Validator: the narrow sibling-alias vs key/correlation-column collision check, case-insensitive,
  erroring at generation time with field, column, and remedy in the message.
* `DataLoaderFetcherEmitter.build`: guard added; signature grows the catch-arm router call (or an
  equivalent carrier) threaded from both callers in `TypeFetcherGenerator`; `asyncWrapTail` (or
  its callers) refactored so the router call has one definition per fetcher, shared by the
  `.exceptionally` arm and the new catch arm.
* Lint pins: `DunderFreeEmissionPipelineTest` / `GeneratedSourcesLintTest` allowlists gain the new
  reserved alias.
* `handle-services.adoc:208` names the old mechanics (`extracted via env.getSource().into(Tables.FILM)`);
  update the phrasing to the new extraction shape. The user-facing contract (fully-populated
  records, full-row projection cost) is unchanged.

## Tests

Execution tier (`graphitron-sakila-example`) carries the behavioural proof for both defects:

* The repro from the incident, minimally: give a sakila `@table` type an inline object field whose
  GraphQL name collides case-insensitively with a physical column of a conversion-hostile type,
  alongside its existing `TableRecord`-wrap `@service` child. Concrete suggestion: `Film` gains
  `length: Language @reference(...)` (colliding with `FILM.LENGTH`, smallint; a nested `Result`
  cannot convert to it), queried together with `titleTitlecase`. Red pre-fix with the
  `MappingException` escape, green post-fix with both fields resolving. Reviewer note: `Film`
  already declares `length: Int @field(name: "LENGTH")`, so the object field cannot be *added*
  under that exact name; either replace the scalar or pick a differently-cased colliding name
  (the collision, like the validator, is case-insensitive). Implementer's choice.
* R426's existing contract tests (`titleTitlecase` reading a non-key column without selecting it;
  the federation `_entities` shape in `FederationEntitiesDispatchTest`) keep passing, pinning that
  the reserved-alias scheme still delivers the fully-populated record.
* Redaction: an induced synchronous key-extraction failure yields a redacted `DataFetcherResult`
  (correlation-id error only, no raw record dump, no escaped throw). Defect 1's fix removes the
  collision trigger, but the guard also covers the record-parent key extraction, which keys off a
  developer Java accessor: a fixture service returning a hand-rolled record whose accessor throws
  `RuntimeException` is a stable, schema-valid trigger. If the implementer finds that shape
  unreachable in practice, note the fallback taken on the item at In Review.

Compilation tier: the sakila-example compile is the backstop that the per-column reconstruction
statements and the reserved-alias projection line up against the real jOOQ catalog; no new test,
but the spec relies on it.

Pipeline tier (`ServiceProjectionPipelineTest`, helpers in `TypeSpecAssertions`), shape only:

* `appendsFullParentRow` updated to match the reserved-alias append shape; existing R426 group
  keeps passing against it.
* New case: parent with both a `TableRecord`-wrap service child *and* a `Wrap.Row` split sibling
  (or a `TableMethodField`), asserting the two-axis emit: reserved-aliased full row *and* the
  base-named force-included column both appended (no absorption).
* No body-string assertion on the try/catch guard: redaction behaviour is proven at the execution
  tier above, per the testing-tier principles.

Validation: a fixture with a sibling field shadowing a `Wrap.Row` key column asserting the new
validator error fires with the field/column/remedy message, in whichever tier hosts validator
coverage today.
