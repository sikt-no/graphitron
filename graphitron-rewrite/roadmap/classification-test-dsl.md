---
id: R281
title: "Classification test DSL: @classified spec-by-example"
status: Spec
bucket: testing
priority: 4
theme: testing
depends-on: []
created: 2026-06-07
last-updated: 2026-06-09
---

# Classification test DSL: @classified spec-by-example

Classification behaviour today is specified twice: as prose in `code-generation-triggers.adoc`
(the variant tables) and as ~398 enum rows in `GraphitronSchemaBuilderTest` (the executable truth
table). The two drift, and neither reads as a *specification by example*: you cannot look at one
artifact and see "this schema shape produces this verdict, and here is the rule in a sentence."
R8 (`docs-as-index-into-tests`, discarded as superseded; its doc-as-index intent now lives here)
wanted to make the doc a map into the tests; this item proposes the cleaner collapse, make the test
fixture itself the readable spec, by embedding the expected classification in the SDL fixture as a
directive.

**This item is also the design driver and executable acceptance spec for R222's dimensional field
pivot (Stage 3, the former R164).** The corpus does not assert today's cross-product permit names
(`QueryServiceTableField`, `RecordLookupTableField`, the ~45 of them R222 line 27 enumerates). It
asserts the *dimensions* those names fuse. A field's classification factors into two
orthogonal-but-co-occurring dimensions, a **queryBuilder** dimension (what query the field builds into
our jOOQ domain, the SQL scope it contributes to or opens) and a **wiring** dimension (where the value
comes from and how it is delivered to graphql-java, its runtime provenance). Other classification
inputs, source-context, result-target, cardinality, error channel, dispatch batching, end up captured
in slots *within* those two dimensions, not asserted as separate axes (see [The axes](#the-axes)). The
legacy permit names weld these together: `QueryServiceTableField` packs a wiring provenance (`Service`,
a developer method delivers the value) and a query consequence (`Table`, the result hands off into a
new query scope) into one token. Authoring the
corpus in terms of the separated dimensions is dimensional discovery from the reader's angle, the
cheapest possible first-client check on R164's decomposition, run in prose plus tests before any
model surgery. If the rendered dimensional doc reads as clean orthogonal rules, the axes are right;
if it reads muddy, R164's decomposition is wrong and the corpus says so before a line of the pivot is
written. (Direct corroboration that the instrument is needed: R279 notes the legacy
`code-generation-triggers` page already attempts a dimensional description, "scope = (source context,
target type) pair", and gets it subtly wrong, which R8 flagged as the doc's actual defect. The corpus
is what corrects it.)

**Update (working revision, 2026-06-09).** Through sustained design dialogue the original cut has been
reworked. An intermediate three-dimension model (`producer × mapping × wiring`) was explored and then
collapsed: `wiring` proved fully derivable, and the source-dependence facet it was rescued into
(`context`) proved trivially positional. The pivot **lands on two asserted dimensions,
`producer × mapping`**, with everything else (context, the fetcher/loader mechanism) computed and only
validated. See [Dimensional model (two-axis landing)](#dimensional-model-two-axis-landing-2026-06-09)
for the captured state; the superseded three-dimension exploration is retained just below it for
rollback history. This is a deliberate checkpoint ahead of the full rewrite; the directive, adapter,
minimal-pair, and slicing sections below still describe the original two-axis (`queryBuilder × wiring`)
form and have not yet been reconciled.

The bridge from today's classifier to those dimensional assertions is a **throwaway leaf→tuple
adapter** (see [The leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism) below). R281
leads; the field dimensional-slot slices (R282 and its siblings) consume the vocabulary this corpus
discovers. The dependency runs driver → consumer, not the other way around: nothing here waits on
R222.

## The shape

The corpus is **one or more annotated schemas** (SDL, plus reflection-backed Java fixtures for the
`@service` / `@tableMethod` examples). Each test case lives inside a *larger* schema rather than as
an isolated fragment, so it is classified in realistic context (reachability, participants, and FKs
resolve); using several schemas is fine and is how conflicting setups are kept apart (see
Mechanical decisions below). Every example parses, that is an invariant graphql-java gates, so a non-parsing example
does not exist. Each element whose classification is specified carries an inline test-only directive
naming its *dimensions*; the description states the rule:

```graphql
type Query {
  """A @service returning a @table-bound type is wired to a developer method AND hands the result off
     into a new query scope: its queryBuilder dimension is the handoff, its wiring is the service call."""
  films(...): [Film] @classified(queryBuilder: Handoff, wiring: Service)

  """A field returning a @table-bound type by default builds its own SELECT, delivered by a
     Graphitron-generated fetcher."""
  allFilms: [Film] @classified(queryBuilder: OwnSelect, wiring: Graphitron)
}

type Film @table(name: "Film") @classifiedType(as: TableType) { ... }
```

The harness parses each schema, runs today's classifier, maps the resulting sealed leaf (and its
slots) to its dimension tuple via the [leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism),
and asserts that tuple equals the directive's `(queryBuilder, wiring)`. The SDL is the example, the
directive is the assertion, the description is the spec sentence: the declarative form of R222's
unit-test claim, stated in the dimensional vocabulary R164 will adopt rather than in the fused leaf
name R164 will dissolve. Classifying a schema in one run keeps its examples mutually consistent (FKs
resolve, participants exist). The corpus asserts *successful* classification only; classification
failure (the `UnclassifiedField` / `UnclassifiedType` leaves, and the reason a coordinate fails to
classify) is out of scope here and gets a separate test mechanism, see [Out of scope](#out-of-scope)
and [Acceptance spec for the pivot](#r281-as-the-pivots-driver-and-acceptance-spec).

## Classification directives

Two test-only directives, split by what they classify, each carrying GraphQL enums so the assertion
is validated SDL-side (a typo is a parse error graphql-java rejects before the harness runs) and
autocompletes in an editor:

- `@classified(queryBuilder: QueryBuilderShape!, wiring: WiringShape!) on FIELD_DEFINITION` asserts the
  two dimensions an output-field coordinate classifies to: `queryBuilder` (the query the field builds,
  the SQL scope it contributes to or opens) and `wiring` (where its value comes from and how it is
  delivered to graphql-java). These two are the assertable axes; finer classification detail
  (source-context, result-target, cardinality, error channel, dispatch batching) is captured in slots
  *within* them, not as further directive arguments (see [The axes](#the-axes)). This is the prevalent
  directive (most annotated coordinates are output fields), so it is short and its argument names are
  the dimension names a reader reaches for when stating the rule.
- `@classifiedType(as: TypeVerdict!) on OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR`
  asserts the `GraphitronType` sealed leaf a type classifies to. Type classification is not yet
  dimensional (and may never need to be), so the type directive keeps the single-verdict `as:`
  shape; whether types eventually go dimensional is an open question this item does not force.

Input-field classification is out of scope (see [Out of scope](#out-of-scope)): an input field is
interpreted relative to the output field it feeds, a different game. So `@classified` sits only on
output `FIELD_DEFINITION` coordinates.

### Dimensional model (two-axis landing, 2026-06-09)

This is the model the pivot lands on, and what the full rewrite will fold through the directive,
adapter, minimal-pair, and slicing sections. A field's classification is asserted on **two** dimensions;
a third positional fact (`context`) and the runtime fetcher/loader mechanism are **derived, not
asserted**.

| Dimension | Answers | SQL/runtime analogue | Total? | Asserted? |
|---|---|---|---|---|
| `producer` | how is the value produced? | the `FROM` (new query) vs inlining into an existing one | partial (`∅`) | **yes** |
| `mapping` | what domain thing *is* the value? | the `SELECT` (projection) | total | **yes** |
| `context` | does the field resolve within a source? | root vs `env.getSource()` | total | no (positional) |

**`producer` (partial); a pipeline of length ≤ 2, each step `∅` or one of `{ Query, Service, Dml }`.**
The defining question is *does the field start a new SQL query, or inline into an existing one?*

- **`producer = ∅` means the field inlines into the existing query and correlates.** No new execution;
  it folds into the parent's query. `∅` does not mean "no SQL", a correlated subquery has its own
  `WHERE` / `ORDER BY` / `LIMIT`; it means "no *new* query." This is where correlation lives. The
  non-split table children (`TableField`, `LookupTableField`, `TableInterfaceField`, `TableMethodField`)
  inline, as do the column/nesting/passthrough carriers.
- **A step of `Query` starts a new SQL query.** A separate execution: the root query, a `@splitQuery`
  batch, a record-parent keyed load, a service re-query, a DML follow-up SELECT.
- **`Service` and `Dml` produce *rows* from outside the catalog** (a developer method, a write). They
  are row-sources, not queries.

**Why the pipeline, and why length ≤ 2.** A contiguous SQL query context inlines freely. `@service` and
`@externalField` (and a DML write) **terminate the current query context**, the value is now a Java
record / pojo / computed value, not SQL. To project a table-bound result below such a boundary you must
**re-enter SQL with a new `Query`**. So the second step is always `Query` ("get back into the catalog"),
and it appears exactly when a row-source feeds a table-bound output. The realized pipelines:

| pipeline | one DataFetcher | same composition, split parent → child |
|---|---|---|
| `[Query]` | `QueryTableField`, `SplitTableField`, record/lookup/poly new-query children | — |
| `∅` | inline table children, `ColumnField`, `NestingField`, `ConstructorField` | — |
| `[Service]` | `QueryServiceRecordField`, `ServiceRecordField` (terminal record/pojo) | — |
| `[Service, Query]` | `ServiceTableField`, `QueryServiceTableField`, `MutationServiceTableField` | `ServiceRecordField` → `RecordTableField` |
| `[Dml]` | DML → encoded ID (`DmlTableField` `Encoded*`) | — |
| `[Dml, Query]` | `DmlTableField` `Projected*` (write, then in-fetcher follow-up SELECT) | `MutationDmlRecordField` → `SingleRecordTableField` |

`@splitQuery`, the record-parent lift, `@tableMethod`'s dev table, lookup, polymorphic resolution, and
the seed source (root args vs parent key) are **slots** on a producer step, not producer values. The
fold-vs-batch and keyed-vs-correlated mechanism reads off these slots plus `context`; it is never an
asserted choice.

**`mapping` (total); what domain object the value is.** Over `domain := { catalog, service }`:

| | object | leaf |
|---|---|---|
| **catalog** | `Table` (+ `TableConnection` for pagination) | `Column` |
| **service** | `Record` / `Pojo` | `Field` |

`Table : Column :: Record : Field`. The catalog-vs-service split *is* the mirror/reflect distinction
(catalog mappings mirror a query result; service mappings reflect a Java object). `mapping` is tightly
coupled to `@classifiedType` but lives at *field* granularity (a scalar is `Column` under a table-backed
parent, `Field` under a pojo-backed parent); the duplication is **accepted for now**. Invariants:
`mapping = Table ⟺ table-bound`; `Record/Pojo ⟺ @record`; `Column/Field ⟺ scalar`. Cross-cutting:
**a `producer` step of `Query` (re-entering the catalog) co-occurs with `mapping = Table`** (a row-source
re-enters the catalog exactly when its output is table-bound), which is why the old `From*` family is
recovered as `(producer ends in Query) × (mapping = Table)` with its subtypes demoted to slots.

**`context` is derived, not asserted.** `context = None ⟺ root field`; everything else resolves within
`env.getSource()`. This is pure schema position, so verbalizing it in `@classified` would only restate
where the field sits. With `producer` redefined around new-query-vs-inline, `producer` already separates
the root/child twins that motivated a third axis (`QueryTableField = [Query]` vs `TableField = ∅`); the
only `(producer, mapping)`-equal root/child pairs that remain (the `[Service, Query] × Table` and
`[Service] × Record` twins) differ *only* by position, so deriving `context` recovers them with no loss.

**Derived runtime mechanism (validated, not asserted).** From `(producer, context, slots)`:
`Extract` (read off source) `⟺ producer = ∅`; a sync `GraphitronFetcher` `⟺` a root `Query`; a batched
`GraphitronLoader` `⟺` a `Query` child (keyed); dispatch `Grouped ⟺` keyed, else `Single`. The whole of
the old `wiring` axis is this projection.

**Open value-set questions for the rewrite:** collapse `Table`/`Record` (and `Column`/`Field`) to one
value plus a catalog/service backing slot?; `ReferencedColumn` as a value or `Column` + join-path slot
(lean: slot); keep `TableConnection` as a distinct value (lean: yes). And the one fact to confirm
against `TypeFetcherGenerator`: that non-split table children are genuinely emitted inline (correlated /
MULTISET) rather than as a separate per-parent sync query, if any run a separate query they are
`producer = Query`, not `∅`.

### Dimensional model (three-dimension exploration, superseded 2026-06-09)

> **Superseded.** This three-dimension `producer × mapping × wiring` model was an intermediate step. It
> is replaced by the [two-axis landing](#dimensional-model-two-axis-landing-2026-06-09) above (`wiring`
> collapsed to a derived projection; the rescued `context` facet proved positional and is derived too).
> Retained for rollback history only.

This subsection captures the model arrived at through design dialogue and **supersedes the two-axis
`queryBuilder × wiring` cut described in [The axes](#the-axes) below**. It is committed as an
intermediate checkpoint, a deliberate rollback point, ahead of a full rewrite that will fold it through
the directive, adapter, minimal-pair, and slicing sections (which still describe the two-axis form).

A field's classification factors into **three** dimensions, mapping onto how a field is resolved. The
**domain** they reach into has two halves: `domain := { catalog, service }`, the jOOQ catalog (tables,
columns) and the developer's service layer (Java pojos, records, and fields on them, returned from
`@service` / `@externalField`).

| Dimension | Answers | SQL/runtime analogue | Total? |
|---|---|---|---|
| `producer` | how is the value produced? | the `FROM` (+ non-SQL row sources) | **partial** |
| `mapping` | what domain thing *is* the value? | the `SELECT` (projection) | total |
| `wiring` | how does graphql-java get it? | the `DataFetcher` | total |

`producer` is the one partial axis (absent when a field rides an existing scope); `mapping` and `wiring`
are total.

**`producer` (partial) ; a pipeline over `{ Query, Service, Dml }`.** `Query` produces a *query*
(composable SQL that both reaches the rows and projects them); `Service` and `Dml` produce *rows*
(opaque records). A no-producer field rides the parent's scope (`ColumnField`, `NestingField`).
Row-producers *compose* a trailing `Query` exactly when the output is catalog-bound, that trailing
`Query` is "re-enter the catalog domain":

| pipeline | leaf |
|---|---|
| `[Query]` | root/child read, column term, lookup, union |
| `[Service]` | `@service` → pojo/record (terminal) |
| `[Service, Query]` | `@service` → `@table` (re-query the row) |
| `[Dml]` | write → id/scalar |
| `[Dml, Query]` | write → `@table` (commit, then project) |

Seed/correlation (root vs parent-keyed), `@tableMethod`'s dev table, `splitQuery`, `lookup`, polymorphic
resolution, and the record-parent lift are **slots** on a producer, not producer values.

**`mapping` (total) ; what domain object the value is.** Both halves, whole-object vs leaf in each:

| | object | leaf |
|---|---|---|
| **catalog** | `Table` (+ `TableConnection`) | `Column` |
| **service** | `Record` / `Pojo` | `Field` |

`Table : Column :: Record : Field`. The catalog-vs-service split *is* the mirror/reflect distinction
(catalog mappings mirror a query result; service mappings reflect a Java object), so mirror/reflect is
not a separate axis. `mapping` is tightly coupled to `@classifiedType` but lives at *field* granularity
(a scalar is `Column` under a table-backed parent, `Field` under a pojo-backed parent); the duplication
is **accepted for now**. Invariant: `mapping = Table ⟺ table-bound`, `Record/Pojo ⟺ @record`,
`Column/Field ⟺ scalar`. Open value-set questions for the rewrite: collapse `Table`/`Record` (and
`Column`/`Field`) to one value plus a catalog/service backing slot?; `ReferencedColumn` as a value or
`Column` + join-path slot (lean: slot); keep `TableConnection` (lean: yes, the connection envelope is a
distinct projection).

**`wiring` (total) ; the `DataFetcher`.** `Extract` (read off the parent record/result) vs `Generated`
(a generated fetcher/loader), × `{ SingleDispatch, GroupedDispatch }` (derived from source: root →
single, list-child → grouped). Kept the name `wiring` over `consumer` because the fetcher does more than
consume, it registers and drives the `DataLoader` for batching.

**Generating set (`producer` × `mapping`).** The ~27 `OutputField` leaves fall out of the cross:

| producer | mapping | leaf(s) |
|---|---|---|
| ∅ | `Column` | `ColumnField` (+ NodeId-encode slot) |
| ∅ | `Table` | `NestingField` (passthrough) |
| ∅ | `Field` | `RecordField`, `PropertyField`, `ComputedField` (`@externalField`) |
| `[Query]` | `Table` | `QueryTableField`, `TableField`, `SplitTableField`, lookups, polymorphic |
| `[Query]` | `TableConnection` | paginated query field |
| `[Service]` | `Record`/`Pojo` | `ServiceRecordField` |
| `[Service, Query]` | `Table` | `ServiceTableField` |
| `[Dml]` | `Column`/`Field` | `delete: ID` / `delete: Boolean` |
| `[Dml, Query]` | `Table` | `createFilm: Film` |

**Directive (to settle in the rewrite).** `@classified(queryBuilder:, wiring:)` becomes three arguments,
`producer`, `mapping`, `wiring`, with `producer` partial; slot detail (seed, lookup, splitQuery,
NodeId-encode, dev-table) rides below. The adapter maps each leaf to a `(producer, mapping, wiring)`
triple instead of a pair.

**Still to verify (full leaf sweep, deferred to the rewrite):** that `mapping` is total with at most the
one honest ∅ (the boolean-ack edge); that the old `From*` value is recovered from `(producer × mapping)`
with nothing left over; that `producer`'s ∅/`Query`/`Service`/`Dml` partition is clean; and that
`mapping` carries real content beyond `@classifiedType × producer-domain`.

### The axes

> **Superseded.** The original two-axis `queryBuilder × wiring` cut in this subsection is kept for
> reference and link stability; it is replaced by the
> [Dimensional model (two-axis landing)](#dimensional-model-two-axis-landing-2026-06-09) above and will
> be reconciled by the full rewrite.

The dimensions are not a fresh invention; they are the **mixin interfaces the model already carries**,
promoted to first-class. `SqlGeneratingField` (own SELECT), `ServiceField` / `MethodBackedField`
(service / developer-method fetch), `BatchKeyField` (DataLoader fetch, carrying the `SourceKey`),
`LookupField`, `WithErrorChannel`, `TableTargetField` (table result) are cross-cutting traits today;
the ~45 permits are their cross-product, and each permit name fuses several (R222 line 27:
`RecordLookupTableField` collapses four trait choices onto one identifier). The corpus un-fuses them.

Two dimensions carry most of the conflation and are the headline cut. Each is a small GraphQL enum:

| Dimension | What it answers | Values |
|---|---|---|
| `queryBuilder` | what query does the field build into our domain? (the SQL scope it contributes to or opens) | *provisional, settled in slice 1:* `None`, `InlinedTerm` (a column/expression in the parent's SELECT), `OwnSelect`, `Handoff` (a new managed scope opens on a produced record, the "record handoff"), `Union`, `Dml` |
| `wiring` | where does the value come from, and how is it delivered to graphql-java? | *settled:* `Extract` (already on the parent record, read it off), `Graphitron` (a query we generated drives the fetch), `Service` (a developer method drives the fetch) |

The decisive finding is that **queryBuilder and wiring co-occur on a single field; they are not a
pick-one choice.** The root `films` field above (leaf `QueryServiceTableField`) is the worked example:
a `@service` returning a `@table` type calls a developer method *and* triggers a record handoff (a new
Graphitron-managed query scope on the result, per `code-generation-triggers.adoc`'s scope-state
machine). So it is `queryBuilder: Handoff, wiring: Service` at once; the fused name welds the wiring
provenance (`Service`) to the query consequence (`Table` → handoff). Its sibling
`QueryServiceRecordField` is `queryBuilder: None, wiring: Service`: same call, no handoff, the result
terminates as a POJO. That pair isolates the queryBuilder axis at constant wiring, exactly the
distinction the single-token names hide.

**The `wiring` value set is settled: three provenance values.** `wiring` answers where the value comes
from, and the three answers are exhaustive: `Extract` (already materialized on the parent record, just
read it off), `Graphitron` (a query we generated drives the fetch, read or write), `Service` (a
developer method drives it). This collapses what an action-shaped reading would have split into six:
`@tableMethod` and `@externalField` are not their own provenance (the former is a `Graphitron` query
built around a developer-supplied table; the latter is an `Extract` of an expression spliced into our
SELECT), and `node` dispatch is a `Graphitron` query behind a routing prologue. `wiring` is *mostly
plumbing*: once the `queryBuilder` is known, the wiring is almost pure consequence, which is exactly
why it is worth separating out. Two facets ride *within* `wiring` as derived slots, not as further
values:

- **Dispatch (`SingleDispatch` / `GroupedDispatch`)** is derived from `source`: a root field with its
  own IO is always `SingleDispatch` (one resolution, nothing to batch); a child field with its own IO
  is always `GroupedDispatch` (under a parent list, so it must DataLoader-batch or it is N+1). Crossed
  with the wiring value it reproduces the generator's actual emit helpers exactly:

  | wiring × dispatch | emit helper |
  |---|---|
  | `Graphitron` × `SingleDispatch` | `GraphitronFetcher` (e.g. `buildQueryTableFetcher`) |
  | `Graphitron` × `GroupedDispatch` | `GraphitronLoader` (`buildSplitQueryDataFetcher`) |
  | `Service` × `SingleDispatch` | root passthrough (`buildServiceFetcherCommon`) |
  | `Service` × `GroupedDispatch` | `buildServiceDataFetcher` + `buildServiceRowsMethod` |
  | `Extract` | LightDataFetcher / property read |

- **Extract mode (mirror / reflect)** is derived from the producer: a value carried by a SELECT we
  emit is read by *mirroring* the `queryBuilder`'s projection (a real column, or a spliced
  `@externalField` expression); a value that is a property of a service return is read by *reflection*;
  a passthrough is the degenerate identity (the whole parent record, no field-read).

Input cardinality (single-row vs bulk-row, the `BulkDmlRecord` case) is *not* a `wiring` facet, it is a
`queryBuilder` slot on the DML step (single VALUES vs multi VALUES); a bulk DML is `SingleDispatch`
with a bulk input, not a grouped dispatch.

`target` (the result shape: table-record / record / scalar / polymorphic) and `source` (the parent
context: root / single-parent-keyed / list child) are real classification *inputs*, but neither is an
assertable axis. `target` is what often *triggers* a `queryBuilder` value (a `@table` result triggers
the handoff) and is captured in slots on both dimensions; `source` drives the dispatch facet (root →
`SingleDispatch`, list child → `GroupedDispatch`) and the `SourceKey` slot beneath `wiring`.
Cardinality and the error channel are the same: slot detail, not directive arguments. So the directive
names exactly two axes, and the finer dimensions ride in slots beneath them. The root
`QueryServiceTableField` (synchronous direct call) and the child `ChildField.ServiceTableField`
(DataLoader-backed) share `wiring: Service`, differing only in the derived dispatch facet
(`SingleDispatch` vs `GroupedDispatch`) and the `SourceKey` slot, not the verdict.

**The two axes and the `wiring` value set are fixed; the `queryBuilder` value set is the slice-1
deliverable.** What this Spec settles is the *mechanism* (a two-axis directive, the `QueryBuilderShape`
/ `WiringShape` enums, the adapter, its totality check), the *cut* (queryBuilder × wiring, primary and
co-occurring), and the *whole `wiring` axis* (three provenance values plus the derived dispatch and
extract-mode facets above). What slice 1 closes is the exact value set of `QueryBuilderShape`: the
leaves dictate which `queryBuilder` values are real, surfaced by driving the adapter to totality across
`OutputField` (see [Validating the axis set](#validating-the-axis-set)). Renaming or merging a value is
then a cheap assertion-value edit, far cheaper than the alternative the old leaf-name framing forced
(rewriting every assertion when R164 collapses the leaves). Downstream items bind only after slice 1
closes the value sets: R279's merge gate adopts the corpus at phase-3 completion; R282 and its siblings
consume the vocabulary slice 1 settled.

R281 *discovers and proposes* the dimensional vocabulary; R164 makes its model the source of truth
once it lands, and is likely to adopt these names because this corpus drove their discovery.

**On distinguishing the two directives by capitalization (`@classified` vs `@Classified`): recommend
against.** Two directives differing only in the case of their first letter is a scan-and-typo footgun,
and a leading-uppercase directive breaks the lowercase-leading convention every other GraphQL
directive follows (`@deprecated`, `@skip`, and graphitron's own `@table` / `@field` / `@service` /
`@node`), so `@Classified` reads like a type reference rather than a directive. A distinct,
self-describing name carries the field/type split without that risk; types are far less numerous than
fields, so the extra characters in `@classifiedType` cost nothing while the prevalent `@classified`
stays short.

`TypeVerdict` enumerates the leaves of `GraphitronType` minus the failure leaf `UnclassifiedType`. A
meta-test asserts its constant set equals that leaf set, so adding a sealed type leaf without a
matching enum constant fails the build, the validator-mirrors-classifier discipline turned on the
type half of the DSL.

## The leaf→tuple adapter (the driver mechanism)

The corpus asserts dimensions, but today's classifier still produces fused leaves. One small,
deliberately throwaway component bridges them: an adapter that maps each classified `OutputField`
(its leaf, plus the slots where a dimension lives below the leaf name) to its dimension tuple.

```
QueryServiceTableField             -> (queryBuilder: Handoff,   wiring: Service)
QueryServiceRecordField            -> (queryBuilder: None,      wiring: Service)
ChildField.TableField              -> (queryBuilder: OwnSelect, wiring: Extract)      // inlined correlated subquery
ChildField.SplitTableField         -> (queryBuilder: OwnSelect, wiring: Graphitron)   // + GroupedDispatch (DataLoader)
ChildField.RecordLookupTableField  -> (queryBuilder: OwnSelect, wiring: Graphitron)   // + source / lookup slots
...
```

The harness classifies a coordinate, applies the adapter, and compares the tuple to the directive.
**Built to full coverage, this adapter *is* R164's leaf↔dimension truth table**, written in
prose-and-tests before R164 touches the model. That is the whole point: the *enumeration* half of the
pivot (which `queryBuilder` values are real, given the settled `wiring` set, and a total leaf→tuple map
across the leaf set) gets done and checked here, cheaply.

Be precise about what this validates and what it defers. The adapter is checked against every leaf the
classifier *produces*; the verdict-correctness check below anchors each leaf's `(queryBuilder, wiring)`
on the existing `TypeFetcherGenerator` dispatch partition (the implemented / projected / ... leaf
groups), not on an author's hunch. Grounding each dimension value in a generator branch is R164 /
R282's burden; this item settles the *decomposition*, R282 settles the *grounding*.

The tuple is the primary fingerprint, not the complete emit key. The finer classification dimensions,
source-context, result-target, cardinality, the error channel, dispatch batching, live in slots within
the two dimensions (R222 models several as their own carriers: `SourceKey`, `Pagination`, the
`ErrorChannel` family). The corpus reads them as slots rather than folding them into the
`queryBuilder` / `wiring` enums, so two leaves that differ only in a slot share one tuple; the
assertion is the two-axis verdict, not the full slot payload.

When R164 lands, the field carries its dimensions as slots directly (R222's `QueryBuilder` /
`DataFetcherBuilder`). The adapter is deleted, the harness reads the slots instead of mapping a leaf,
and **the corpus assertions do not change.** Their continued green is the proof that R164's
decomposition was behaviour-preserving. The doomed cross-product leaf names then live only in the
adapter for the transition, and nowhere in the corpus or the rendered docs.

### Validating the axis set

The adapter is the instrument, and slice 1 drives it to two properties (corpus coverage adds a third,
value-exercise; see [Design forks](#design-forks-to-settle-at-spec)):

- **Totality.** Every concrete `OutputField` leaf (the `QueryField` / `MutationField` / `ChildField`
  leaves) maps to a tuple; a leaf with no mapping fails the build. This is the
  validator-mirrors-classifier discipline (adding a leaf forces an adapter row). The leaf set is
  narrower than the `GraphitronField`-wide `TypeFetcherGenerator` dispatch partition, which also covers
  `InputField` and the `UnclassifiedField` sibling; both are outside `OutputField` and out of scope, so
  there is no failure leaf to exclude.
- **Verdict correctness.** Each leaf's `(queryBuilder, wiring)` matches how the generator actually
  treats it, cross-checked against the existing capability memberships (`SqlGeneratingField` ⇒ a
  SQL-scope `queryBuilder`; `ServiceField` ⇒ `wiring: Service`; `BatchKeyField` / `MethodBackedField`)
  and the `TypeFetcherGenerator` dispatch partition, not on an author's hunch. Two leaves *sharing* a
  tuple is expected when they differ only in slot detail: the four root sync service permits share
  `buildServiceFetcherCommon`, so `QueryServiceTableField` and `MutationServiceTableField` legitimately
  collapse; `ChildField.SplitTableField` and `ChildField.RecordTableField` share `(OwnSelect,
  Graphitron)` and differ only in the `SourceKey` slot.

The instrument for stating both the axes and the slots is the **minimal pair**: two leaves differing
in exactly one dimension. They double as the corpus's canonical documentation examples (isolating one
variable is how you both prove a dimension and teach it):

| Dimension | Minimal pair | `(queryBuilder, wiring)` | Differs in |
|---|---|---|---|
| `queryBuilder` (asserted) | `QueryServiceTableField` vs `QueryServiceRecordField` | `(Handoff, Service)` vs `(None, Service)` | the asserted `queryBuilder` verdict: `@table` hands off into a new scope vs terminates as a POJO |
| `wiring` (asserted) | `ChildField.TableField` vs `ChildField.SplitTableField` | `(OwnSelect, Extract)` vs `(OwnSelect, Graphitron)` | the asserted `wiring` verdict; `@splitQuery` is what flips the provenance from inlined-`Extract` to a generated `Graphitron` fetch, not a "modifier" |
| source (slot) | `ChildField.SplitTableField` vs `ChildField.RecordTableField` | `(OwnSelect, Graphitron)` for both | the `SourceKey` slot only, same asserted verdict |
| lookup (slot) | `ChildField.TableField` vs `ChildField.LookupTableField` | `(OwnSelect, Extract)` for both | the lookup slot only, same asserted verdict |

The first two pairs prove queryBuilder and wiring are independent (`ChildField.TableField` /
`ChildField.SplitTableField` share a queryBuilder, split on wiring) and co-occurring
(`QueryServiceTableField` / `QueryServiceRecordField` share a wiring, split on queryBuilder). The last
two, sharing their asserted verdict, are the converse evidence: source and lookup live in slots the
directive does not assert. The matrix study behind this Spec found no leaf
pair the generator forks on that shares both its `(queryBuilder, wiring)` verdict and every slot, which
is what makes two axes sufficient; if slice 1 ever surfaces one, that is the signal to revisit the cut.

## Rendering: queries as views over the corpus

Prose does not embed schema; it embeds a **query** naming the fields it wants to show, or a
**fragment** whose `on Type` condition names a type directly (the type-display case, no extra
convention needed). The renderer resolves that selection against the corpus, takes the touched
coordinates, and regenerates minimal SDL for that closure (graphql-java `SchemaPrinter` over the
projected subgraph).
One mechanism does three jobs at once:

- *Import what's relevant* (the earlier requirement): the query's selection set is the projection,
  reusing GraphQL's own selection mechanism instead of bespoke include-tags.
- *Strip the test directives*: regenerated SDL emits only real schema, so internal directives are
  simply not printed. The render-strip is not a separate filter; it falls out of regeneration.
- *Bound the snippet honestly*: the closure to emit is exactly what R279's completeness rule says
  the classification closes over, the node, its directives, its parent's, and its target's directives
  and participants. Honour that closure and the displayed excerpt actually classifies to the asserted
  dimensions; the downward-context examples (a record child field needing its `@service` ancestor's
  backing class) stay honest because the query selects through the ancestor.

So a rendered example is a faithful *excerpt* of a coordinate classified in full-corpus context, not
necessarily a standalone repro; honouring the closure rule is what keeps the excerpt from lying.

## Design forks to settle at Spec

- **Own item, built against today's classifier (not an R279 slice, not an R222 consumer).** The
  directive asserts dimensions derived (via the adapter) from verdicts that exist now, so this depends
  on neither R279's inversion nor R222's pivot; it *drives* the latter. Landing it first makes it the
  readable behavioural net both ride, which *strengthens* R279's merge-gate posture (its primary tier
  becomes this corpus) and hands R164 its acceptance spec. R279 is a consumer, R282 is a consumer,
  R281 is the owner.
- **Replace the enum truth table, do not run beside it.** Adding the DSL on top of the ~398 enum
  rows recreates the exact duplication R8 fought; the value lands only when the fixture corpus
  *becomes* the truth table and the migrated enum rows retire. Scope note: the output-field and type
  rows migrate; input-field classification rows stay in the enum table as their own game (out of
  scope here). That migration (plus rewiring `VariantCoverageTest`) is the bulk of the cost. Slice
  accordingly (see Slicing below).
- **Coverage derived from the corpus, against the adapter not the leaf set.**
  `VariantCoverageTest.everySealedLeafHasAClassificationCase` walks the `GraphitronField` /
  `GraphitronType` roots today. The corpus-backed coverage generalises to three obligations the
  meta-test enforces together (see [Validating the axis set](#validating-the-axis-set)): (1) the
  leaf→tuple adapter is *total* over `OutputField` (every leaf maps to a tuple); (2) every value of
  both enums (`QueryBuilderShape`, `WiringShape`) is exercised by some fixture; (3) `TypeVerdict`'s constants
  equal `GraphitronType`'s non-failure leaf set, asserted by some fixture. So the unit of coverage on
  the field side shifts from "every leaf has a case" to "every dimension value has a case and every
  leaf has an adapter row", which is strictly stronger: it forces the dimensional decomposition to be
  complete, not just the leaf enumeration. `InputField` leaves and the failure leaves stay covered by
  their existing mechanism (see Out of scope).
- **Directive mechanics.** Both directives are test-only: declared in the test schema, ignored by the
  classifier, read only by the harness, and must never leak into the auto-injected
  `directives.graphqls`. See [Classification directives](#classification-directives) for the
  dimensional shape and the naming decision. Asserting the *rejection code* (the reason a coordinate
  fails to classify) is deliberately out of scope for this mechanism (see
  [Out of scope](#out-of-scope)): failure is a separate test type, and post-R222 it becomes a
  `WalkerResult.Err` assertion rather than a positive dimension tuple.
- **Doc renders from fixtures.** R281 owns the documentation deliverable outright (it ate R279's
  former slice 0): the `code-generation-triggers` page is reworked here, rendering its taxonomy from
  the fixtures (variant -> asserting fixture -> its description), the truest form of "doc as a map
  into the tests."

## Mechanical decisions (settled at Spec)

- **Internal-vs-real directive partition.** The projector needs an explicit set of test/enrichment
  directives to omit (`@classified`, `@classifiedType`, and any future enrichment directive);
  `@table` / `@service` / `@node` etc. are the rule being demonstrated and must render.
- **Partitioning across schemas (add a schema when a shape conflicts).** A single schema cannot hold
  two shapes of the same concept (a `Film` with `@table` and a `Film` without, to show two verdicts).
  Such conflicting examples live in separate schemas; the rule is simply "add another corpus schema
  when a new example would conflict with an existing name." There is no up-front partitioning taxonomy
  to design. The convention (how broadly each schema scopes, keeping type names prose-readable since
  the query-view renders real names) is discovered empirically as the thin slice and the documentation
  migration pull examples in.
- **Companion Java mechanism (AsciiDoc `include` regions).** Reflection-backed examples (`@service` /
  `@tableMethod`) need real, compiled Java the classifier reflects against and that prose can show
  without copy-paste drift. Contributor docs are AsciiDoc, so the mechanism is AsciiDoctor
  `include::Fixture.java[tags=r]` over `// tag::r[]` regions in the fixture source. (JEP 413's
  `@snippet` tag is the Javadoc-native equivalent; graphitron's docs are not Javadoc, so reach for it
  only if any of this also surfaces there.) Either way the honesty guarantee comes from the fixtures
  being real test sources the build compiles and the classifier reflects against, not from the snippet
  tool, which only governs how prose references them. Note the asymmetry with SDL: SDL examples are
  *projected* (the query-as-view regenerates minimal SDL), whereas Java fixtures are *excerpted
  verbatim* by region, you can project a schema subgraph but you show Java as-written. Reuse
  `GraphitronSchemaBuilderTest`'s fixtures where possible.

## Out of scope

- **Input-field classification.** An input field is interpreted relative to the output field it
  feeds, a separate concern with its own rules. `@classified` sits only on output `FIELD_DEFINITION`
  coordinates; `InputField` leaves stay covered by the existing `GraphitronSchemaBuilderTest` enum
  cases, not this corpus.
- **Classification failure.** The `UnclassifiedField` / `UnclassifiedType` leaves and the *reason* a
  coordinate fails to classify (the rejection code) are not asserted by `@classified` /
  `@classifiedType`. Failure-path testing is a separate mechanism, deferred; post-R222 it becomes a
  `WalkerResult.Err` assertion. Keeping it out is what lets the corpus survive R222 untouched (see
  [Acceptance spec for the pivot](#r281-as-the-pivots-driver-and-acceptance-spec)).
- **Generation / `TypeSpec` assertions.** This DSL asserts SDL -> classified model only. The
  SDL -> classified model -> generated `TypeSpec` pipeline tests stay where they are; the corpus is
  the classification half, not the emission half.
- **The capability catalog and `@capability` directive** (R112 / R115) are unrelated; this item does
  not touch the capability namespace.
- **R279's driver restructure.** The corpus and renderer build against today's classifier. R279's
  inversion and orphan pruning are its own work; R281 owns only the documentation deliverable and the
  test DSL.
- **Legacy modules.** Untouched, per the repo-wide scope rule.

## Slicing (recommended phasing)

The corpus is the source of truth and the prose is a view over it, so prose genuinely follows the
corpus (you cannot reference examples that do not exist). But the test side should not land as a
single big-bang conversion:

1. **Thin vertical slice, end to end, that nails the value sets.** The `@classified` /
   `@classifiedType` directives (with the `QueryBuilderShape` / `WiringShape` and `TypeVerdict` enums) +
   the leaf→tuple adapter + harness + the coverage/validation meta-test (adapter totality, verdict
   correctness, value exercise, `TypeVerdict` mirror) + a handful of exemplar examples in a small
   corpus,
   running *alongside* the existing enum truth table (transitional coexistence, not the permanent
   duplication the fork above rejects), plus a prototype of the query-as-view renderer
   (query/fragment -> projected SDL, internal directives stripped) over those few examples. This
   slice's primary deliverable is the *validated `queryBuilder` value set* (`wiring` is already
   settled): it drives the adapter to totality across
   `OutputField` (see [Validating the axis set](#validating-the-axis-set)), which settles which
   `queryBuilder` values are real, the vocabulary R164 inherits. Secondarily it surfaces
   the authoring
   constraints (real names must read well since they render verbatim; an example must be selectable
   via a query/fragment) *before* the expensive grind. The closure rule already pins most of what
   projects, so the renderer prototype is cheap insurance, not a hard gate.
2. **Documentation-first; migrate while documenting.** Drive the migration from the prose: writing
   the documentation (the `code-generation-triggers` absorption R281 took over from R279's former
   slice 0) pulls each example it needs into the corpus, authored with `@classified` /
   `@classifiedType` and rendered via the query-as-view, and the corresponding enum row is deleted as
   the example lands.
   Duplication with the enum table is therefore transient per example, and the table shrinks as the
   doc grows. Every migrated example arrives with a documentation home and a written rationale, and
   the order is pedagogical rather than enum-declaration order. This phase also folds in the stale
   `code-generation-triggers.md` -> `.adoc` Javadoc-reference cleanup in `GraphitronSchemaBuilderTest`
   / `LookupMapping` that the staleness audit flagged, which rode with the old slice 0.
3. **Coverage sweep, then enum retirement.** Documentation is curated: it covers the explainable
   majority, not necessarily every one of the ~398 enum cases in the truth table. The coverage
   meta-test surfaces
   the untested long tail; migrate those as corpus entries (tested, not necessarily prose-featured),
   then rewire `VariantCoverageTest`'s output-field and type coverage onto the corpus and delete the
   migrated enum rows. The input-field classification rows remain (their own game, out of scope), so
   the enum table shrinks to that residue rather than emptying. So every doc example is a corpus
   example, but not every corpus example is in the doc (the swept tail).
   **Retire only the *successful*-classification rows.** The corpus asserts the happy path only, so
   only the enum rows encoding successful output-field/type verdicts move onto it. Rows that encode a
   *rejection* outcome (`UnclassifiedField` / `UnclassifiedType` and their reasons) must not retire
   until the separate failure-path mechanism that replaces them has landed; otherwise this sweep
   silently narrows the rejection half of validator-mirrors-classifier, which is exactly the
   same-commit-replacement constraint R279 imposes on its slices. Until that mechanism exists, the
   rejection rows stay in the enum table alongside the input-field residue.
   Completion here (full successful output-field and type corpus coverage) is the milestone that lets
   R279's merge gate adopt the corpus as its primary tier for the acceptance direction.

## R281 as the pivot's driver and acceptance spec

R222 (`dimensional-model-pivot`, Spec) is the umbrella that dissolves the ~45 cross-product field
permits into dimensional slots; its Stage 3 (R164's content) is the field-side pivot specifically,
landing `DataFetcherBuilder` / `QueryBuilder` / `ValidationBuilder` dimensional slots per sub-seal.
R281 is not a downstream consumer of that work waiting for the walker to land. It runs first and
**drives** it, in three concrete ways:

- **R281 discovers and validates the axis vocabulary.** Stage 3 needs to know what dimensions the
  leaves decompose into before it can build slots. R281's corpus + adapter answer that empirically:
  the adapter, driven to totality, *is* the leaf↔dimension truth table
  (see [The leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism)), and the dimension enums
  are the proposed slot vocabulary, including the load-bearing discovery that queryBuilder and wiring
  are separate co-occurring dimensions (the `QueryServiceTableField` handoff). R164 is likely to adopt
  these names because this corpus is where they were found and stress-tested.
- **R281 is Stage 3's executable acceptance spec for the *decomposition*.** The corpus asserts the
  two-axis verdict, not leaf names. When the pivot lands and the field carries its dimensions as slots
  directly (`QueryBuilder` / `DataFetcherBuilder`), the adapter is deleted and the harness reads the
  slots; **every corpus assertion stays byte-identical, proving the `(queryBuilder, wiring)`
  decomposition was preserved.** It does *not* prove slot-level emit, a `SourceKey`-arm change would keep the corpus
  green, so that stays the pipeline / `TypeSpec` tier's job. R282's merge gate is therefore *both*
  tiers green: this corpus for the decomposition, the pipeline tier for the emit.
- **R281 keeps the field-taxonomy docs pivot-proof.** Because the rendered `code-generation-triggers`
  *field* taxonomy is expressed in axes and never in cross-product leaf names, that half of the
  documentation does not get rewritten when the leaves dissolve. The doomed names live only in the
  throwaway adapter during the transition. The claim is scoped to the field side on purpose: the type
  half still asserts `GraphitronType` leaf names via `@classifiedType`, the same leaf-name coupling
  R281 designs away for fields. That asymmetry is accepted because no type-side dimensional pivot is in
  flight; if one ever is, the type corpus inherits the field side's rewrite-on-collapse fragility and
  would want the same dimensional treatment.

The success/failure split is the one R222 constraint R281 honours rather than drives: R222's Stage 4
retires `UnclassifiedType` / `UnclassifiedField` into `WalkerResult.Err`. The corpus asserts only
successful classification, so anchoring failure here would couple it to exactly the part R222 removes;
the `TypeVerdict` meta-test excludes the failure leaf for the same reason, and that exclusion is the
seam where the future `WalkerResult.Err` test type takes over.

### The consuming slice (R282)

The field-side pivot itself is filed separately as **R282** (`datafetcher-field-dimensional-slots`),
R222's Stage 3 spin-out. R282 *consumes* this corpus as its acceptance spec; the dependency edge runs
R281 → R282, driver to consumer. Its scope is the field's **wiring** dimension and the
`DataFetcherBuilder` that reads it, co-modelling the `queryBuilder` slot where the two fuse on one field
(the service→`@table` handoff), collapsing `QueryServiceTableField` / `MutationServiceTableField` /
`MutationServiceRecordField` / `ChildField.ServiceTableField` / `ChildField.ServiceRecordField` and
the wider `QueryField` / `MutationField` / `ChildField` permit set (R222 line 27) into dimensional
slots per emit-relevant identity. The `QueryBuilder` and `ValidationBuilder` consumers are sibling
Stage 3 slices the same corpus drives; the Stage 1 foundation (`ServiceField` / `ServiceMethodCall`)
has already landed. R282 is to be filed as a Backlog item via the roadmap tool; this item does not
block on it.

## Relationship to R279

R279 (`field-first-classification-driver`) is a consumer and the motivating context.
**R281 has eaten R279's former slice 0**: the entire documentation deliverable (the
`code-generation-triggers` absorption, R8's doc-as-index intent, the stale-ref cleanup) lives here,
and R279 is now purely the driver-restructure code work. The coupling that remains: R279's slices 3
and 6 (the inversion and orphan pruning) require corresponding updates to this doc when they land,
and R279's merge gate adopts the corpus as its primary tier only at phase 3 completion (full
coverage, enum table retired). No code dependency in the other direction: the corpus and adapter
build against the current classifier, so R281 can land independently and ahead of R279's inversion.
