---
id: R281
title: "Classification test DSL: @classified spec-by-example"
status: Spec
bucket: testing
priority: 4
theme: testing
depends-on: []
created: 2026-06-07
last-updated: 2026-06-07
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
asserts the *dimensions* those names pack: a field's classification as `(source, action, target)`
rather than as one fused leaf. Authoring the corpus that way is dimensional discovery from the
reader's angle, the cheapest possible first-client check on R164's axis decomposition, run in
prose plus tests before any model surgery. If the rendered dimensional doc reads as clean orthogonal
rules, the axes are right; if it reads muddy, R164's decomposition is wrong and the corpus says so
before a line of the pivot is written. (Direct corroboration that the instrument is needed: R279
notes the legacy `code-generation-triggers` page already attempts a dimensional description, "scope
= (source context, target type) pair", and gets it subtly wrong, which R8 flagged as the doc's
actual defect. The corpus is what corrects it.)

The bridge from today's classifier to those dimensional assertions is a **throwaway leaf→tuple
adapter** (see [The leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism) below). R281
leads; the DataFetcher dimensional-slot slice (R282) consumes the vocabulary this corpus discovers.
The dependency runs driver → consumer, not the other way around: nothing here waits on R222.

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
  """A root field invoking a @service that returns a @table-bound type is sourced at the root,
     does a service call, and targets a table."""
  films: [Film] @classified(source: Root, action: Service, target: Table)
}

type Film @table(name: "Film") @classifiedType(as: TableType) { ... }
```

The harness parses each schema, runs today's classifier, maps the resulting sealed leaf to its
dimension tuple via the [leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism), and
asserts that tuple equals the directive's `(source, action, target)`. The SDL is the example, the
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

- `@classified(source: SourceAxis!, action: ActionAxis!, target: TargetAxis!) on FIELD_DEFINITION`
  asserts the dimension tuple an output-field coordinate classifies to. This is the prevalent
  directive (most annotated coordinates are output fields), so it is short and its argument names
  are the axis names a reader reaches for when stating the rule.
- `@classifiedType(as: TypeVerdict!) on OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR`
  asserts the `GraphitronType` sealed leaf a type classifies to. Type classification is not yet
  dimensional (and may never need to be), so the type directive keeps the single-verdict `as:`
  shape; whether types eventually go dimensional is an open question this item does not force.

Input-field classification is out of scope (see [Out of scope](#out-of-scope)): an input field is
interpreted relative to the output field it feeds, a different game. So `@classified` sits only on
output `FIELD_DEFINITION` coordinates.

### The axes

`@classified`'s three arguments are the axes a cross-product permit name packs (R222 line 27: "where
source comes from, what the fetcher does, the field's output shape"). Each is a small GraphQL enum:

| Enum | Provisional values |
|---|---|
| `SourceAxis` | `Root` (a `Query` / `Mutation` field), `OnlyChild` (a single-parent-keyed child), `ListChild` (a list-parent child) |
| `ActionAxis` | `None` (no I/O), `Service` (a `@service` invocation), `Generated` (generated jOOQ) |
| `TargetAxis` | `Table` (a `@table`-bound type), `Record` (a `@record` projection), `Outcome` (a mutation result / payload) |

**The axis *set* is a slice-1 deliverable, not Spec-frozen.** Both the *number* of axes and their
values are the phase-1 starting point, and the corpus's job is to confirm or break them (see
[Validating the axis set](#validating-the-axis-set)). What this Spec settles is the *mechanism* (a
dimensional directive, per-axis enums, the adapter, and the totality/no-collapse falsifier); what
slice 1 settles is the resulting axis set. So the three-argument signature above is illustrative of
the shape, not a frozen arity: phase 1 may well add a fourth axis (the likely `Query`-vs-`Mutation`
collision, see [Validating the axis set](#validating-the-axis-set)), which is a directive-argument,
enum, adapter-column, and re-annotation change, not the one-line edit a value *rename* is. Downstream
items bind to the corpus only after slice 1 closes the axis set: R279's merge gate adopts it at
phase-3 completion, and R282 consumes the vocabulary slice 1 settled, not the provisional three.

R281 *discovers and proposes* the axis vocabulary; R164 makes its model the source of truth once it
lands, and is likely to adopt these names because this corpus drove their discovery. Even the
costlier add-an-axis case is far cheaper than the alternative the old leaf-name framing would have
forced: rewriting every assertion when R164 collapses the leaves.

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
deliberately throwaway component bridges them: an adapter that maps each `OutputField` sealed leaf to
its `(source, action, target)` tuple.

```
QueryServiceTableField     -> (Root,      Service, Table)
RecordLookupTableField     -> (OnlyChild, Generated, Table)
...
```

The harness classifies a coordinate, applies the adapter to the resulting leaf, and compares the
tuple to the directive. **Built to full coverage, this adapter *is* R164's leaf↔dimension truth
table**, written in prose-and-tests before R164 touches the model. That is the whole point: the
*enumeration* half of the pivot (which axes the leaves decompose into, and whether that decomposition
is total and collision-free across the real leaf set) gets done and checked here, cheaply.

Be precise about what this validates and what it defers. The adapter is checked against every leaf
the classifier *names*, not against every emit-behaviour it produces; "two leaves emit differently"
(the no-collapse premise below) is, at this layer, the adapter author's claim, not a test that reads
the emitters. So the corpus's green proves the adapter *agrees with the classifier's leaf
enumeration*, not that each axis value corresponds to a real generator branch. Grounding each axis
value in an emitter fork is R164/R282's burden, not discharged here; where it helps, the no-collapse
check can be anchored on the existing `TypeFetcherGenerator` dispatch partition (the
implemented/projected/... leaf groups) so "emit differently" reduces to "land in distinguishable
dispatch arms" rather than an annotation. This item settles the axis *decomposition*; R282 settles
the axis *grounding*.

When R164 lands, the field carries `source()` / `action()` / `target()` slots directly. The adapter
is deleted, the harness reads the slots instead of mapping a leaf, and **the corpus assertions do not
change.** Their continued green is the proof that R164's decomposition was behaviour-preserving. The
doomed cross-product leaf names then live only in the adapter for the duration of the transition, and
nowhere in the corpus or the rendered docs, so the documentation never has to be rewritten when the
leaves dissolve.

### Validating the axis set

The adapter is the instrument that tests the hypothesis, via two structural checks the meta-test
enforces over it (corpus coverage adds a third, axis-value exercise; see
[Design forks](#design-forks-to-settle-at-spec)):

- **Totality.** Every `OutputField` sealed leaf maps to a tuple; a leaf with no mapping fails the
  build. This is the validator-mirrors-classifier discipline (adding a leaf forces an adapter row),
  and it is what makes corpus-derived coverage harder to game than the old reflective enum scan.
- **No spurious collapse.** Two distinct leaves that emit differently must not map to the same tuple.
  If they do, the three axes are *incomplete*, an axis is missing, and the adapter cannot be written
  as a function without losing information. This is the discovery the driver exists to force. The
  first such collision is likely `QueryServiceTableField` vs `MutationServiceTableField`: both are
  `(Root, Service, Table)` under the three provisional axes, yet they emit differently (DML / error
  channel on the mutation side). That collision is the corpus telling us a fourth axis (operation, or
  cardinality, or both) is real before R164 has to discover it the expensive way. Resolving it is
  phase-1 work, not a defect in the plan; the plan is the thing that surfaces it.

So the axis vocabulary in the table above is where phase 1 *starts*, and the adapter's two checks are
how phase 1 *finishes*: with an axis set rich enough that the leaf→tuple map is total and collision-
free across the whole `OutputField` hierarchy.

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
  leaf→tuple adapter is *total* over `OutputField` (every leaf maps to a tuple) and collision-free;
  (2) every value of every axis enum is exercised by some fixture; (3) `TypeVerdict`'s constants
  equal `GraphitronType`'s non-failure leaf set, asserted by some fixture. So the unit of coverage on
  the field side shifts from "every leaf has a case" to "every axis value has a case and every leaf
  has an adapter row", which is strictly stronger: it forces the dimensional decomposition to be
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

1. **Thin vertical slice, end to end, that nails the axes.** The `@classified` / `@classifiedType`
   directives (with the `SourceAxis` / `ActionAxis` / `TargetAxis` and `TypeVerdict` enums) + the
   leaf→tuple adapter + harness + the coverage/validation meta-test (adapter totality, no-collapse,
   axis-value exercise, `TypeVerdict` mirror) + a handful of exemplar examples in a small corpus,
   running *alongside* the existing enum truth table (transitional coexistence, not the permanent
   duplication the fork above rejects), plus a prototype of the query-as-view renderer
   (query/fragment -> projected SDL, internal directives stripped) over those few examples. This
   slice's primary deliverable is the *validated axis set*: it must drive the adapter to totality and
   collision-freedom across `OutputField`, which is what forces any missing axis (the likely
   `Query`-vs-`Mutation` collision, see [Validating the axis set](#validating-the-axis-set)) out into
   the open and settles the final axis vocabulary R164 inherits. Secondarily it surfaces the authoring
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
  the adapter, driven to totality and collision-freedom, *is* the leaf↔dimension truth table
  (see [The leaf→tuple adapter](#the-leaftuple-adapter-the-driver-mechanism)), and the axis enums are
  the proposed slot vocabulary. R164 is likely to adopt these names because this corpus is where they
  were found and stress-tested.
- **R281 is Stage 3's executable acceptance spec.** The corpus asserts dimensions, not leaf names.
  When the pivot lands and the field carries `source()` / `action()` / `target()` slots directly, the
  adapter is deleted and the harness reads the slots; **every corpus assertion stays byte-identical,
  and its continued green is the proof the pivot was behaviour-preserving.** This is the merge gate
  for the field-side pivot, written before the pivot.
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
R281 → R282, driver to consumer. Its scope is the `DataFetcherBuilder` dimension only (source ×
action × target), collapsing `QueryServiceTableField` / `MutationServiceTableField` /
`MutationServiceRecordField` / `ChildField.ServiceTableField` / `ChildField.ServiceRecordField` and
the wider `QueryField` / `MutationField` / `ChildField` permit set (R222 line 27) into one record per
emit-relevant identity carrying dimension properties. Sibling dimensions `QueryBuilder` /
`ValidationBuilder` are separate future Stage 3 slices; the Stage 1 foundation
(`ServiceField` / `ServiceMethodCall`) has already landed. R282 is to be filed as a Backlog item via
the roadmap tool; this item does not block on it.

## Relationship to R279

R279 (`field-first-classification-driver`) is a consumer and the motivating context.
**R281 has eaten R279's former slice 0**: the entire documentation deliverable (the
`code-generation-triggers` absorption, R8's doc-as-index intent, the stale-ref cleanup) lives here,
and R279 is now purely the driver-restructure code work. The coupling that remains: R279's slices 3
and 6 (the inversion and orphan pruning) require corresponding updates to this doc when they land,
and R279's merge gate adopts the corpus as its primary tier only at phase 3 completion (full
coverage, enum table retired). No code dependency in the other direction: the corpus and adapter
build against the current classifier, so R281 can land independently and ahead of R279's inversion.
