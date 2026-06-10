---
id: R281
title: "Classification test DSL: @classified spec-by-example"
status: In Progress
bucket: testing
priority: 4
theme: testing
depends-on: []
created: 2026-06-07
last-updated: 2026-06-10
---

# Classification test DSL: @classified spec-by-example

Classification behaviour today is specified twice: as prose in `code-generation-triggers.adoc`
(the variant tables) and as ~405 enum rows in `GraphitronSchemaBuilderTest` (the executable truth
table). The two drift, and neither reads as a *specification by example*: you cannot look at one
artifact and see "this schema shape produces this verdict, and here is the rule in a sentence."
R8 (`docs-as-index-into-tests`, discarded as superseded; its doc-as-index intent now lives here)
wanted to make the doc a map into the tests; this item proposes the cleaner collapse, make the test
fixture itself the readable spec, by embedding the expected classification in the SDL fixture as a
directive.

**This item is also the design driver and executable acceptance spec for R222's dimensional field
pivot (Stage 3, the former R164).** The corpus does not assert today's cross-product permit names
(`QueryServiceTableField`, `RecordLookupTableField`, the ~45 of them R222 line 27 enumerates). It
asserts the *dimensions* those names fuse. A field's classification factors into two asserted
dimensions, a **producer** dimension (how the value is produced: a pipeline that either starts a new
SQL query or inlines into an existing one) and a **mapping** dimension (what domain object the value
*is*: a catalog table/column or a service record/field). Everything else, the source context, the
runtime fetcher/loader mechanism, the dispatch batching, the error channel, is **derived from those
two plus the field's schema position**, not asserted as separate axes (see
[The dimensional model](#the-dimensional-model)). The legacy permit names weld the derived facts into
the asserted ones: `QueryServiceTableField` packs a producer pipeline (`[Service, Query]`, a developer
method produces rows, then a follow-up query re-enters the catalog) and a mapping (`Table`) into one
token, and buries the fetcher mechanism (synchronous, root-dispatched) inside the same name. Authoring
the corpus in terms of the separated dimensions is dimensional discovery from the reader's angle, the
cheapest possible first-client check on R164's decomposition, run in prose plus tests before any
model surgery. If the rendered dimensional doc reads as clean orthogonal rules, the axes are right;
if it reads muddy, R164's decomposition is wrong and the corpus says so before a line of the pivot is
written. (Direct corroboration that the instrument is needed: R279 notes the legacy
`code-generation-triggers` page already attempts a dimensional description, "scope = (source context,
target type) pair", and gets it subtly wrong, which R8 flagged as the doc's actual defect. The corpus
is what corrects it.)

**Design history (2026-06-09).** The cut here was reached through sustained design dialogue. An
original two-axis cut (`queryBuilder × wiring`) and an intermediate three-axis model
(`producer × mapping × wiring`) were both explored and discarded: `wiring` proved fully derivable from
`producer` plus schema position, and the source-dependence facet it was briefly rescued into
(`context`) proved trivially positional. The pivot lands on `producer × mapping`, with the
fetcher/loader mechanism and `context` computed and only validated. See
[The dimensional model](#the-dimensional-model) for the full model.

The bridge from today's classifier to those dimensional assertions is a **throwaway leaf→tuple
adapter** (see [The leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism) below). R281
leads; the field dimensional-slot slices (R222's Stage 3 field-side pivot and its siblings, to be
filed) consume the vocabulary this corpus discovers. The dependency runs driver → consumer, not the other way around: nothing here waits on
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
  """A @service returning a @table-bound type produces rows from a developer method, then re-enters
     the catalog with a follow-up query to project the @table: its producer pipeline is
     [Service, Query], its mapping is the Table the projection lands on."""
  films(...): [Film] @classified(producer: [Service, Query], mapping: Table)

  """A field returning a @table-bound type by default starts its own SELECT and projects a @table:
     producer [Query], mapping Table."""
  allFilms: [Film] @classified(producer: [Query], mapping: Table)
}

type Film @table(name: "Film") @classifiedType(as: TableType) { ... }
```

The harness parses each schema, runs today's classifier, maps the resulting sealed leaf (and its
slots) to its dimension tuple via the [leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism),
and asserts that tuple equals the directive's `(producer, mapping)`. The SDL is the example, the
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

- `@classified(producer: [ProducerStep!]!, mapping: Mapping!) on FIELD_DEFINITION` asserts the two
  dimensions an output-field coordinate classifies to: `producer` (how the value is produced, a
  pipeline given as a GraphQL list of steps, each `Query` / `Service` / `Dml`, with the empty list
  `[]` meaning "inline into the existing query, no new execution") and `mapping` (what domain object
  the value is: `Table` / `TableConnection` / `Column` / `Record` / `Field`). These two are the
  assertable axes; everything finer (source-context, the fetcher/loader mechanism, dispatch batching,
  cardinality, error channel) is **derived** from them plus the field's schema position, captured in
  slots rather than as further directive arguments (see [The dimensional model](#the-dimensional-model)).
  This is the prevalent directive (most annotated coordinates are output fields), so it is short and
  its argument names are the dimension names a reader reaches for when stating the rule.
- `@classifiedType(as: TypeVerdict!) on OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR`
  asserts the `GraphitronType` sealed leaf a type classifies to. Type classification is not yet
  dimensional (and may never need to be), so the type directive keeps the single-verdict `as:`
  shape; whether types eventually go dimensional is an open question this item does not force.

Input-field classification is out of scope (see [Out of scope](#out-of-scope)): an input field is
interpreted relative to the output field it feeds, a different game. So `@classified` sits only on
output `FIELD_DEFINITION` coordinates.

### The dimensional model

This is the model the pivot lands on, and the vocabulary the rest of this Spec (the directive, adapter,
minimal pairs, and slicing) is stated in. A field's classification is asserted on **two** dimensions;
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
  `WHERE` / `ORDER BY` / `LIMIT`; it means "no *new* query." This is where correlation lives. A `@table`
  child reachable by FK correlation from a query-scope parent classifies here, so all the non-split
  table children, `TableField`, `LookupTableField`, `TableInterfaceField`, `TableMethodField`, are `∅`,
  as are the column/nesting/passthrough carriers. (`TableField` / `LookupTableField` are also *emitted*
  inline today, a `DSL.multiset(...)` correlated subquery; `TableInterfaceField` / `TableMethodField`
  are correctly classified `∅` but the current generator mis-emits them as a per-parent query, a defect
  tracked separately, see the [current-vs-correct note](#current-vs-correct) below.)
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
| `[Query]` | `QueryTableField`, `SplitTableField`, record/lookup re-query children (`RecordTableField`, `RecordLookupTableField`) | — |
| `∅` | `TableField`, `LookupTableField`, `TableInterfaceField`, `TableMethodField` (all inline correlate), `ColumnField`, `NestingField`, `ConstructorField` | — |
| `[Service]` | `QueryServiceRecordField`, `ServiceRecordField` (terminal record/pojo) | — |
| `[Service, Query]` | `ServiceTableField`, `QueryServiceTableField`, `MutationServiceTableField` | `ServiceRecordField` → `RecordTableField` |
| `[Dml]` | DML → encoded ID (`DmlTableField` `Encoded*`) | — |
| `[Dml, Query]` | `DmlTableField` `Projected*` (write, then in-fetcher follow-up SELECT) | `MutationDmlRecordField` → `SingleRecordTableField` |

`@splitQuery`, the record-parent lift, `@tableMethod`'s dev table, lookup, polymorphic resolution, and
the seed source (root args vs parent key) are **slots** on a producer step, not producer values. The
fold-vs-batch and keyed-vs-correlated mechanism reads off these slots plus `context`; it is never an
asserted choice. A child `[Query]` is always keyed and batches through a DataLoader (otherwise it is
N+1, which is never correct); correlation (`∅`) is how a child avoids a separate query in the first
place.

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
the discarded `wiring` axis is this projection; crossed with `context` it reproduces the generator's
actual emit helpers exactly:

| producer × context | emit helper |
|---|---|
| root `[Query]` | `GraphitronFetcher` (e.g. `buildQueryTableFetcher`) |
| child `[Query]` (keyed) | `GraphitronLoader` (`buildSplitQueryDataFetcher`) |
| root `[Service…]` | service passthrough (`buildServiceFetcherCommon`) |
| child `[Service…]` | `buildServiceDataFetcher` + `buildServiceRowsMethod` |
| `∅` (any context) | `Extract` (LightDataFetcher / property read) |

The mirror/reflect split of `Extract` (read a column the SELECT projected vs reflect a property off a
service return) is itself derived from `mapping` (`Column` vs `Field`), not a separate choice.

**Open value-set questions (slice 1 closes these):** collapse `Table`/`Record` (and `Column`/`Field`)
to one value plus a catalog/service backing slot?; `ReferencedColumn` as a value or `Column` +
join-path slot (lean: slot); keep `TableConnection` as a distinct value (lean: yes).

<a id="current-vs-correct"></a>
**Current implementation vs. what's correct (the corpus asserts *correct*); resolves the slice-1
inline-vs-query unknown.** A reading of `TypeFetcherGenerator` shows the current generator does not
match this classification everywhere, and keeping the two apart is the point. `TableField` /
`LookupTableField` are emitted correctly: a `DSL.multiset(...)` correlated subquery folded into the
parent SELECT (`InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter`; no fetcher method is
emitted). `TableInterfaceField` and `TableMethodField` are *classified* `∅`, a polymorphic interface
target and a `@tableMethod`-supplied table are both inline-able by FK correlation, but the current
generator mis-emits each as its own per-parent
`dsl.select(...).from(...).where(parent-correlation).fetch()` (`buildTableInterfaceFieldFetcher` /
`buildChildTableMethodFetcher`): a synchronous per-parent query, i.e. N+1. N+1 is never correct, so
that emission is a generator **defect** (filed as **R288**), not a `[Query]` classification. The corpus
asserts the correct verdict `∅` for both; the gap to the current generator is the bug, tracked and
fixed there, not classified.

This is the methodological split the corpus exists to enforce: **current implementation** (what the
generator emits now) ≠ **what's possible** (whether the inline path has been built yet) ≠ **what's
correct** (the field's essential data dependency). `@classified` asserts the last. The generator's
dispatch partition is *evidence* for a verdict, never the ground truth; where it encodes a known
defect, the corpus states the correct dimension and the defect is filed, not blessed as a value.

### Grounding in the model's traits

The dimensions are not a fresh invention; they are the **mixin interfaces the model already carries**,
promoted to first-class. `SqlGeneratingField` (touches the catalog), `ServiceField` / `MethodBackedField`
(service / developer-method fetch), `BatchKeyField` (DataLoader fetch, carrying the `SourceKey`),
`LookupField`, `WithErrorChannel`, `TableTargetField` (table result) are cross-cutting traits today;
the ~45 permits are their cross-product, and each permit name fuses several (R222 line 27:
`RecordLookupTableField` collapses four trait choices onto one identifier). The corpus un-fuses them,
and each trait maps onto the two asserted dimensions or onto a derived slot:

- `TableTargetField` (a `@table`-bound result) `⟺ mapping = Table`; `@record` `⟺ mapping = Record`; a
  scalar `⟺ Column` / `Field`.
- `SqlGeneratingField` `⟺` the `producer` touches the catalog, either inline (`∅`, a correlated
  subquery) or as a fresh `[Query]` step.
- `ServiceField` / `MethodBackedField` `⟺` the `producer` contains a `Service` step; a DML write `⟺` a
  `Dml` step.
- `BatchKeyField` (the `SourceKey`), `LookupField`, `WithErrorChannel`, `@splitQuery`, polymorphic
  resolution, the NodeId-encode, and `@tableMethod`'s dev table are **slots**, not asserted values;
  the derived fetcher mechanism reads them (see [the dimensional model](#the-dimensional-model)).

R281 *discovers and proposes* the dimensional vocabulary; R164 makes its model the source of truth
once it lands, and is likely to adopt these names because this corpus drove their discovery. Renaming
or merging a value is a cheap assertion-value edit, far cheaper than the alternative the old leaf-name
framing forced (rewriting every assertion when R164 collapses the leaves). Downstream items bind only
after slice 1 closes the value sets: R279's merge gate adopts the corpus at phase-3 completion; the
field-side pivot slice and its siblings consume the vocabulary slice 1 settled.

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
QueryServiceTableField             -> (producer: [Service, Query], mapping: Table)
QueryServiceRecordField            -> (producer: [Service],        mapping: Record)
ChildField.TableField              -> (producer: [],               mapping: Table)   // inlined correlated subquery
ChildField.SplitTableField         -> (producer: [Query],          mapping: Table)   // @splitQuery: a new keyed query
ChildField.RecordLookupTableField  -> (producer: [Query],          mapping: Table)   // + lookup slot
ChildField.ColumnField             -> (producer: [],               mapping: Column)
...
```

The harness classifies a coordinate, applies the adapter, and compares the tuple to the directive.
**Built to full coverage, this adapter *is* R164's leaf↔dimension truth table**, written in
prose-and-tests before R164 touches the model. That is the whole point: the *enumeration* half of the
pivot (which `producer` pipelines and `mapping` values are real, and a total leaf→tuple map across the
leaf set) gets done and checked here, cheaply.

Be precise about what this validates and what it defers. The adapter is checked against every leaf the
classifier *produces*; the verdict-correctness check below anchors each leaf's `(producer, mapping)`
on the existing `TypeFetcherGenerator` dispatch partition (the implemented / projected / ... leaf
groups), not on an author's hunch. Grounding each dimension value in a generator branch is R164 /
the field-side pivot slice's burden; this item settles the *decomposition*, that slice settles the *grounding*.

The tuple is the primary fingerprint, not the complete emit key. The derived facts, source-context,
the fetcher/loader mechanism, dispatch batching, the error channel, live in slots beside the two
asserted dimensions (R222 models several as their own carriers: `SourceKey`, `Pagination`, the
`ErrorChannel` family). The corpus reads them as slots rather than folding them into the `producer` /
`mapping` enums, so two leaves that differ only in a slot share one tuple; the assertion is the
two-axis verdict, not the full slot payload.

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
- **Verdict correctness.** Each leaf's `(producer, mapping)` is the *correct* verdict for that leaf. The
  existing capability memberships (`SqlGeneratingField` ⇒ a `producer` that touches the catalog;
  `ServiceField` ⇒ a `Service` step; `TableTargetField` ⇒ `mapping = Table`; `BatchKeyField` /
  `MethodBackedField` feed the slots) and the `TypeFetcherGenerator` dispatch partition are *evidence*
  for the verdict, not an author's hunch, but not the ground truth either: where the generator's
  emission is a known defect the corpus asserts the correct verdict and the defect is filed (see the
  [current-vs-correct note](#current-vs-correct), e.g. `TableInterfaceField` / `TableMethodField` are
  `∅` though the generator currently emits a per-parent query). Two leaves *sharing* a tuple is expected
  when they differ only in slot detail: `QueryServiceTableField` and `MutationServiceTableField` both
  classify `([Service, Query], Table)` and differ only in root context; `ChildField.SplitTableField`
  and `ChildField.RecordTableField` share `([Query], Table)` and differ only in the `SourceKey` slot.

The instrument for stating both the axes and the slots is the **minimal pair**: two leaves differing
in exactly one dimension. They double as the corpus's canonical documentation examples (isolating one
variable is how you both prove a dimension and teach it):

| Dimension | Minimal pair | `(producer, mapping)` | Differs in |
|---|---|---|---|
| `producer` (asserted) | `ChildField.TableField` vs `ChildField.SplitTableField` | `([], Table)` vs `([Query], Table)` | the asserted `producer` verdict; `@splitQuery` flips an inline correlated subquery (`∅`) into a new keyed query, not a "modifier" |
| `mapping` (asserted) | `ChildField.ColumnField` vs `ChildField.RecordField` | `([], Column)` vs `([], Field)` | the asserted `mapping` verdict: a catalog column (mirror a SELECT) vs a service field (reflect a property), producer `∅` for both |
| source (slot) | `ChildField.SplitTableField` vs `ChildField.RecordTableField` | `([Query], Table)` for both | the `SourceKey` slot only, same asserted verdict |
| lookup (slot) | `ChildField.TableField` vs `ChildField.LookupTableField` | `([], Table)` for both | the lookup slot only, same asserted verdict |

The first two pairs isolate each asserted axis: `ChildField.TableField` / `ChildField.SplitTableField`
hold `mapping = Table` and split on `producer`, while `ChildField.ColumnField` / `ChildField.RecordField`
hold `producer = ∅` and split on `mapping`. (The two axes are partially correlated, a trailing `Query`
step co-occurs with `mapping = Table`, so a clean minimal pair varies one axis without crossing that
boundary.) The last two pairs, sharing their asserted verdict, are the converse evidence: source and
lookup live in slots the directive does not assert. The matrix study behind this Spec found no leaf
pair the generator forks on that shares both its `(producer, mapping)` verdict and every slot, which is
what makes two axes sufficient; if slice 1 ever surfaces one, that is the signal to revisit the cut.

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
  becomes this corpus) and hands R164 its acceptance spec. R279 is a consumer, the field-side pivot slice is a consumer,
  R281 is the owner.
- **Replace the enum truth table, do not run beside it.** Adding the DSL on top of the ~405 enum
  rows recreates the exact duplication R8 fought; the value lands only when the fixture corpus
  *becomes* the truth table and the migrated enum rows retire. Scope note: the output-field and type
  rows migrate; input-field classification rows stay in the enum table as their own game (out of
  scope here). Slice accordingly (see Slicing below). (2026-06-10 recalibration: the rows eligible
  for retirement are the ~42 pure-verdict rows, not the full table; the slot-asserting and rejection
  rows stay by design, so the table shrinks modestly rather than emptying. See
  [Pre-migration hardening](#pre-migration-hardening).)
- **Coverage derived from the corpus, against the adapter not the leaf set.**
  `VariantCoverageTest.everySealedLeafHasAClassificationCase` walks the `GraphitronField` /
  `GraphitronType` roots today. The corpus-backed coverage generalises to three obligations the
  meta-test enforces together (see [Validating the axis set](#validating-the-axis-set)): (1) the
  leaf→tuple adapter is *total* over `OutputField` (every leaf maps to a tuple); (2) every value of
  both enums (`ProducerStep`, `Mapping`) is exercised by some fixture; (3) `TypeVerdict`'s constants
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
   `@classifiedType` directives (with the `ProducerStep` / `Mapping` and `TypeVerdict` enums) +
   the leaf→tuple adapter + harness + the coverage/validation meta-test (adapter totality, verdict
   correctness, value exercise, `TypeVerdict` mirror) + a handful of exemplar examples in a small
   corpus,
   running *alongside* the existing enum truth table (transitional coexistence, not the permanent
   duplication the fork above rejects), plus a prototype of the query-as-view renderer
   (query/fragment -> projected SDL, internal directives stripped) over those few examples. This
   slice's primary deliverable is the *validated `producer` pipeline set and `mapping` value set*: it
   drives the adapter to totality across
   `OutputField` (see [Validating the axis set](#validating-the-axis-set)), which settles which
   `producer` / `mapping` values are real and resolves the open value-set questions (collapse
   `Table`/`Record`?; `ReferencedColumn` as value or slot?), the vocabulary R164 inherits. Secondarily
   it surfaces the authoring
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
   majority, not necessarily every one of the ~405 enum cases in the truth table. The coverage
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

<a id="pre-migration-hardening"></a>
### Pre-migration hardening (gate before the slice-2/3 grind, added 2026-06-10)

A review of slice 1 plus the first two slice-2 migrations recalibrated the migration's size and
surfaced gaps to close before grinding the remaining verdicts. These land first, as their own
commits; no further enum row retires until items 1 and 2 are done, and no mutation- or type-side
*doc* example lands until item 3 is done.

**Recalibration: the retirement pool is ~42 rows, not ~405.** Of the 405 enum constants in
`GraphitronSchemaBuilderTest`, roughly 176 involve rejection outcomes (out of scope, stay), roughly
187 assert slot detail (stay; slots are the pipeline tier's job), and only roughly 42 are pure
`isInstanceOf` verdicts eligible for retirement (some of those are input-field rows, which also
stay; the inventory below settles the exact list). Consequence for the endgame: the enum table does
not shrink to a thin residue. It remains the slot/rejection/input truth table, while the corpus
becomes the verdict truth table and the doc's source. The item's value is unchanged (the dimensional
vocabulary, the pivot's acceptance spec, the rendered doc); the deletion count is just smaller than
the original framing suggested.

1. **Commit the retirement inventory.** Enumerate the pure-verdict candidate rows (by enum,
   constant, and leaf) as a checklist in `roadmap/audits/classification-test-dsl-inventory.md`. The grind
   spans sessions; the checklist is what makes over-deletion and under-coverage reviewable instead
   of trusted, fixes the pedagogical order up front, and bounds what may delete at all (a row not on
   the list does not retire). Each migration commit ticks its row off.
2. **Close the corpus-pickup gap in the retirement loop.** `VariantCoverageTest`'s union net is
   one-way: it never fails when a deleted row's leaf is still covered by a *different* enum row, so
   a green run is not evidence the corpus picked the verdict up; and nothing automated detects
   deleting a slot-asserting row whose leaf happens to be corpus-covered. Mitigation, encoded in the
   `classified-corpus` skill: the row being retired must name a leaf the new corpus example's
   coordinate demonstrably classifies to (the harness records the leaf per coordinate), and the
   inventory checklist is the deletion whitelist.
3. **Extend the renderer for the mutation and type halves.** Two gaps block honest doc rendering
   beyond the field/catalog side: (a) input types reachable from a kept field's arguments are not
   expanded, so a rendered `createFilm(in: FilmInput!)` would reference a `FilmInput` the excerpt
   never shows, violating the closure-honesty rule; (b) the fragment (`on Type`) selection form this
   Spec promises for type display is unimplemented and untested (the lone type example rode a query
   that happens to touch `Film`; `ErrorType`, `EnumType`, and the input-type family have no query
   path to them). Until both land, mutation and type verdicts migrate corpus-only (no `query`, no
   doc block), an explicit outcome of the per-verdict loop. **(Landed 2026-06-10.)** The renderer's
   `QueryTraverser` walk was replaced with a schema-resolving selection walk that handles all three
   selection forms (fields, inline fragments, named-fragment spreads) and bare top-level
   `fragment on Type` documents, and grew two closure expansions: the input-object closure from a kept
   field's argument types (recursing nested input objects) and abstract-output-type emission for
   unions / interfaces. `QueryViewRendererTest` pins both over the real corpus-only fixtures (`dml`,
   `mutation-roots`, `union`, `relay-node`, `error-type`, `split-lookup`), with the field/catalog-side
   output preserved byte-for-byte (the slice-1 `ClassifiedDocTest` page blocks are unchanged). Scalars
   and enums stay unexpanded by design. Graduating specific corpus-only verdicts to rendered doc
   examples is now unblocked but remains a separate, deliberately sparing pedagogical call.
4. **Minor sweeps (ride along with items 1-3).** Fix the stale `ClassifiedDsl` Javadoc reference
   (`ClassifiedHarness#typeVerdictMirrorMismatch()` does not exist; the real pair is
   `typeVerdictEnumConstants()` plus the mirror test in `ClassifiedDslTest`). Assert simple-name
   uniqueness across `GraphitronType`'s sealed leaves: the `TypeVerdict` mirror compares simple
   names, so a future nested leaf reusing a name like `Backed` would silently conflate. Correct the
   page's stale transitional-table names (`PlainObjectType` is now `NestingType`; `PojoResultType`'s
   sole concrete leaf is `Backed`). Memoizing `ClassifiedHarness.classify` per example is noted but
   deferred until the corpus is large enough for the rebuild-per-test cost to matter.

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
  are the proposed slot vocabulary, including the load-bearing discovery that `producer` is a pipeline
  (a row-source terminates the SQL context, so a table-bound result re-enters with a trailing `Query`)
  and that the whole `wiring`/fetcher mechanism is derivable from `producer` plus schema position
  rather than a separate axis. R164 is likely to adopt these names because this corpus is where they
  were found and stress-tested.
- **R281 is Stage 3's executable acceptance spec for the *decomposition*.** The corpus asserts the
  two-axis verdict, not leaf names. When the pivot lands and the field carries its dimensions as slots
  directly (`QueryBuilder` / `DataFetcherBuilder`), the adapter is deleted and the harness reads the
  slots; **every corpus assertion stays byte-identical, proving the `(producer, mapping)`
  decomposition was preserved.** It does *not* prove slot-level emit, a `SourceKey`-arm change would keep the corpus
  green, so that stays the pipeline / `TypeSpec` tier's job. The field-side pivot slice's merge gate is therefore *both*
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

### The consuming slice (R222 Stage 3, to be filed)

The field-side pivot itself is R222's Stage 3 spin-out (`datafetcher-field-dimensional-slots`), to be
filed separately as its own Backlog item. It *consumes* this corpus as its acceptance spec; the
dependency edge runs R281 → that slice, driver to consumer. Its scope is the field's **producer** dimension (the row-source /
re-query pipeline) and the `DataFetcherBuilder` the derived fetcher mechanism drives off it, modelling
the `SourceKey` and dispatch slots where `producer` meets schema position (the service→`@table`
re-query is the load-bearing case: a `[Service, Query]` pipeline), collapsing `QueryServiceTableField`
/ `MutationServiceTableField` / `MutationServiceRecordField` / `ChildField.ServiceTableField` /
`ChildField.ServiceRecordField` and the wider `QueryField` / `MutationField` / `ChildField` permit set
(R222 line 27) into dimensional slots per emit-relevant identity. The `QueryBuilder` and `ValidationBuilder` consumers are sibling
Stage 3 slices the same corpus drives; the Stage 1 foundation (`ServiceField` / `ServiceMethodCall`)
has already landed. That slice is to be filed via the roadmap tool; this item does not
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
