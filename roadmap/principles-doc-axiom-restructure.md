---
id: R434
title: "Restructure rewrite design principles around axioms with named enforcement"
status: Spec
bucket: docs
priority: 4
theme: docs
depends-on: [principles-doc-altitude-trim]
created: 2026-07-04
last-updated: 2026-07-04
---

# Restructure rewrite design principles around axioms with named enforcement

`docs/architecture/explanation/rewrite-design-principles.adoc` grew by appending a heading per
lesson learned and now carries 28 flat peer sections: an axiom like "classification belongs at the
parse boundary" has the same weight as "use two-arg `DSL.val`", the type-system family is stated
five times at different zoom levels, and the doc's actual central principle (R222's thesis:
orthogonal facts are slots, never permit cross-products) exists only as a preamble pointer while
five of its weaker cousins have their own sections. Meanwhile two patterns that are now load-bearing
across shipped items are absent entirely: the walker-carrier pattern (R238/R244/R246/R256: typed
call carrier + `AuthorError` sub-seal with stable LSP codes + collect-Err-exclude-field) and the
additive-then-cutover discipline for structural pivots (stated only inside R431/R222 item files).
The restructure: a small axiom set with corollaries, every principle carrying a uniform anatomy
(rule, exemplar, smell, and a named *enforcement*: compiler / meta-test / build tier / review-only,
extending R433's altitude discriminator from inventories to the principles themselves), descriptive
sections demoted to a constraints postscript, and the test-tier trio collapsed onto the
`testing.adoc` pointer the way typed-rejection already collapsed. Review-only enforcement labels
double as a visible gap list for future meta-test items. The spec carries the complete replacement
doc text so review happens on the actual result while the live doc stays untouched until cutover;
cutover includes an xref sweep of citing docs and item files (section anchors change).

## The document, version 2

The complete v2 text, self-contained: **what is not below is not carried forward.** Cutover writes
this to `docs/architecture/explanation/development-principles.adoc` (the project is no longer "the
rewrite"; "Development" avoids colliding with the strategic `graphitron-principles.md`) and deletes
`rewrite-design-principles.adoc`. Design decisions and cutover mechanics follow the text.

````asciidoc
= Graphitron Development Principles

How Graphitron is built: six axioms, each with corollary principles, and every principle ends with
an *Enforced by* line naming what fails when it is broken ; the compiler, a named meta-test, a
build tier, or the honest gap label "review only". The axioms share one root, the insight R222 and
R333 install as the target architecture: the classified model is a normalized fact base ;
classification asserts each fact once at the parse boundary, everything downstream (generation,
validation, the LSP) is a derived view over the same facts, and wire formats are its I/O. The
strategic principles live at xref:../../graphitron-principles.adoc[graphitron-principles.md]; the
typed-rejection narrative at xref:typed-rejection.adoc[Typed rejection]; additive-then-cutover
change discipline in `roadmap/workflow.adoc`.

'''

== Decide once, at the parse boundary; carry the decision as a type

*Before implementing a feature, ensure the model carries what the feature needs ; pre-resolved,
ready to consume.* `GraphitronSchemaBuilder` reads directives once and resolves everything: table
names, column references, method names, extraction strategies. Every consumer receives the model
in terms of "what to do", never "what to interpret" ; a generator sees "what to emit", the
validator "what to check", the LSP "what to show".

Signs a model type needs more pre-resolution:

- A generator switches on a raw string, or recomputes a derived name from a field name.
- The same multi-arm type switch recurs across multiple generators.
- Generation and calling are conflated in the same model type.
- A generator branches on a predicate over pre-resolved data ; the decision was not resolved,
  only its inputs were. Rule of thumb: if two consumers evaluate the same predicate over a model
  field, the branch belongs in the model, and the duplicate sites are an opportunity to drift.

=== Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface, not an enum
with a shared field set: a sealed record hierarchy gives each variant exactly the fields it needs,
and every switch that misses a new variant becomes a compile error. `CallSiteExtraction` is the
exemplar ; some arms carry nothing, others exactly the references their extraction strategy needs,
payloads no enum constant could hold; read the type for the current arm set, this doc deliberately
does not enumerate it.

*Enforced by:* the compiler ; exhaustive switches over a sealed hierarchy break when a variant is
added.

=== The parse boundary is a containment invariant

Classification is not only "decide once"; it is a statement about *where* raw external types may
live at all.

Reading the reflection `java.lang.reflect.Type` tree is permitted only at builder-side classifiers
that convert reflection output into typed carrier values ; a deliberately small set of files,
discoverable by searching for the `Type`-tree reads themselves (`java.lang.reflect.Type`,
`getGenericReturnType`, `getGenericParameterTypes`), not by the `java.lang.reflect` import (model
records legitimately carry resolved `Method` and `Constructor` handles). Everything downstream ;
validator, generator ; switches on pre-classified values and never touches reflection types. The
jOOQ twin: `JooqCatalog` is the canonical holder of raw jOOQ types (`Table<?>`,
`ForeignKey<?,?>`); the few deliberate exceptions (validator candidate-hint enumeration, the LSP
catalog snapshot) import `org.jooq` at their own boundary and are discoverable by that import. If
a generator needs information not yet in a taxonomy record, add a component and extract the value
in the builder ; never reach past the boundary.

*Enforced by:* review only, via the grep-able discovery recipes above; a meta-test pinning each
containment set is a candidate roadmap item.

=== Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee, not
the broad sealed-interface root: a field whose return type is always table-bound declares
`ReturnTypeRef.TableBoundReturnType` directly, so consumers know it without a runtime check.

*Enforced by:* the compiler ; the narrowed component type is the contract.

=== Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than raw strings: `TableRef` is the
resolved-table sub-taxonomy, `ColumnRef` the resolved-column one. Each new sub-taxonomy proposal
comes with a one-line note on what distinct information it carries that a sibling cannot ;
otherwise it's probably a field on an existing record. At milestone boundaries, audit which
sub-taxonomies could collapse.

*Enforced by:* review only (the one-line justification note at proposal time; the
milestone-boundary collapse audit).

=== Builder-internal hierarchies are ephemeral; model hierarchies are carried

"Carry the decision as a type" answers *how*; this corollary answers *where the type lives*. A
model-level hierarchy is carried all the way to the generator; a builder-internal hierarchy
structures a complex multi-target classification and is discarded before the model ; generators
never see it. `ArgumentRef` is the exemplar
(xref:../reference/argument-resolution.adoc[argument-resolution.md]): every GraphQL argument is
classified once, then exhaustive projections produce each generation-ready output. The smell is
the alternative: multiple independent passes that implicitly coordinate by skipping each other's
arguments.

*Enforced by:* review only ; a generator referencing `ArgumentRef` (or any builder-internal type)
is the tell.

=== Builder-step results are sealed, not strings or out-params

Every builder-step lift returns a sealed `Resolved`; rejection is a typed variant with a stable
LSP code, never a string or out-param. The full narrative lives at
xref:typed-rejection.adoc[Typed rejection]. In fact-base terms, rejections are facts too: located
violations asserted once and rendered into views ; the build log, the LSP ; never prose composed
at the detection site.

*Enforced by:* the compiler on the sealed results; `SealedHierarchyDocCoverageTest` pins the
permit-to-doc mapping for the `Rejection` taxonomy.

== Orthogonal facts are independent axes

*Assert what nothing else carries; derive what another axis or slot already forces; never store a
derived fact.* When a concept's variants multiply, the usual cause is independent axes spliced
into one identifier: the permit set becomes the cross-product of its axes, and adding a value to
any axis multiplies the permits below it. Carry each axis as its own slot or sealed
sub-interface, and compute cross-axis views at the read site ; a spliced identifier or a stored
derived fact is denormalization, a second copy of a decision that will drift. R222
(`dimensional-model-pivot`) is the roadmap-scale application; R333 is the current statement of the
target model.

The DataLoader-backed source side is the worked example: key shape, body input contract, row
count, and loader registration are independent axes, and each dispatch site reads off whichever
axis it forks on (xref:dispatch-axes.adoc[Dispatch axes] narrates the split). The smell to watch
for: a single shared accessor whose meaning depends on the variant, a sealed permit name that
splices two axes together, or a record component another component already determines.

*Enforced by:* the compiler where cross-axis invariants live in compact constructors; review at
model-design time otherwise.

=== Capability interfaces and sealed switches serve different roles

Capabilities express what is *uniformly true* across variants; sealed switches express what
*varies by identity*. When a generation pattern applies uniformly, use an orthogonal capability
interface (`SqlGeneratingField`, `BatchKeyField`, `ServiceField`) rather than an N-way
`instanceof` chain; when the generator forks on identity, use a sealed switch. Capabilities don't
eliminate exhaustiveness bookkeeping ; they relocate it.

*Enforced by:* the compiler ; capabilities relocate exhaustiveness bookkeeping, sealed switches
keep it.

=== Directives carry only what the SDL author needs to say

The authoring-surface instance of the cross-product smell. Directive arguments are flat scalars
whenever the directive site already disambiguates the axis; an input-object wrapper is justified
only for several genuinely-orthogonal pieces of information at once. `@field(name: String!)` is
the worked example: the site (field, input field, argument, enum value) tells the classifier which
axis is bound, so the directive carries only the name ; the axis is structural. The smell: an
input wrapper most callsites fill in two-of-four slots on, whose failure surface widens from "the
named thing didn't resolve" to a cross-product of missing/inconsistent-slot errors
(`ExternalCodeReference` is the existing case new directives should not lean on).

*Enforced by:* review only, at directive-design time.

== One model, many views

*Code generation is the narrowest view of the classified model, not the model itself.* Other
consumers (the LSP snapshot today) re-source from the same classified facts; no consumer owns a
private model, and a view's coverage guarantee lives at its projection seam.
`CatalogBuilder.projectFieldClassification` is the exemplar: an exhaustive switch over the field
permits, so a new permit fails compilation until the view covers it; the coverage switch moves
with the seam. The smell is a consumer-side shadow taxonomy ; a second model maintained by hand to
feed a view.

*Enforced by:* the compile-checked projection switch at each view's seam.

== Boundaries decode and encode; the interior is typed

*Opaque wire formats (Relay NodeId base64 strings, Relay cursor strings, federation `_Any`
representations) decode at the DataFetcher boundary into typed column tuples; the projection layer
encodes back into wire format only at the same boundary; variants representing the wire shape
don't survive in the model.*

For any opaque wire format, classify the failure mode (skip vs throw) and the direction (encode
vs decode) at the boundary, never below it. R50 is the worked example: nine "the model says this
is a NodeId" markers were retired in favour of decode and encode facts carried at the boundary
slots where each happens (see R50 in the changelog for the carrier-by-carrier story); cursor and
federation `_Any` handling already followed the pattern. The smell: a "this is a NodeId" or "this
is a base64 cursor" marker spreading through the model ; a bypass around classified information
the boundary already carries.

*Enforced by:* review only; the R50 regression surface is pipeline-tier (a wire-shape carrier
reintroduced into the model has no typed home to land in).

=== Model metadata over parallel type systems

When the model already carries typed information, runtime data formats derive from that metadata
rather than inventing a parallel type system. The cursor format is the exemplar: each cursor
column's jOOQ `DataType` is already known, so encode/decode goes through
`field.getDataType().convert()` instead of a hand-rolled type-tag system ; the column metadata
*is* the type information. A parallel type system in a runtime format is redundant and will
diverge.

*Enforced by:* review only.

=== Wire boundaries are typed adapter / composer pairs

Where the generator emits a method that crosses the wire-format boundary, it emits it in pair
with a composer: the adapter decodes the wire shape into typed values, the composer does the work,
and the composer's signature is exactly the shape the adapter yields ; the boundary is the pair,
not the adapter alone. The generated `QueryConditions.<method>(Table, env)` forwarding to the
user-written `<X>Conditions.<method>(Table, ...)` is the worked example. The smell is asymmetric
typing across the pair ; most often the adapter erasing type information the composer needs (an
arity-erased `RowN` where the decoder produced `Row<N><T1, ..., TN>`; R79 is the shipped fix),
turning would-be compile failures into DSL-runtime surprises. Honour the type the decoder
produces; don't widen the composer to absorb the loss.

*Enforced by:* the `graphitron-sakila-example` compile where the pair's signatures meet; review
for the symmetry itself.

== Every invariant has an enforcer

*An invariant exists only while something fails when it breaks.* The corollaries below are the
three directions the rule flows ; rejections (classifier to validator), acceptances (classifier to
emitter), claims (documentation to test or type) ; and the direction matters because each has a
different enforcer. Enforcers run at build time or earlier; a runtime cast is not one ; it fails
on a real request, days after the build passed. The shared smell: a parallel statement of the same
fact with no single enforcer (a second dispatch set, a defensive cast, an unguarded census), which
drifts silently ; R268 is the worked example, an emit-side allow-list that drifted from the arms
the emitter implemented until it was deleted and the invariant re-sourced. A review-only label
anywhere in this document is an invitation: filing the meta-test that pins it is roadmap material.
The set of review-only principles is read off the labels, never stored as its own list.

=== Rejections: validator mirrors classifier invariants

Every classifier decision that implies a generator branch must fail at validate time if that
branch is unimplemented: the validator reads the same dispatch sets the generator does, so an
unsupported classification is a build-time error, not a runtime
`UnsupportedOperationException`. The dispatch state is a four-way disjoint partition over every
`GraphitronField` sealed leaf in `TypeFetcherGenerator` (`IMPLEMENTED_LEAVES`, `PROJECTED_LEAVES`,
`NOT_DISPATCHED_LEAVES`, `STUBBED_VARIANTS.keySet()`; safe to enumerate because the test below
pins it), and `ValidateMojo` fails the build on stubbed variants. The rule extends to every new
classifier invariant: no generator-side invariant goes unchecked at validate time.

*Enforced by:* `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` (partition
exhaustive and disjoint); `ValidateMojo` fails the build on stubbed variants.

=== Acceptances: classifier guarantees shape emitter assumptions

The reverse direction: a classifier acceptance lets an emitter assume narrower shapes, so emitted
code reads as tight as hand-written ; no defensive casts, no wildcard locals, no `instanceof`
guards. The contract anchors on three layers: type-system narrowing at the producer (a record
component, a return type, a sealed sub-variant ; once in the type, mechanically enforced),
pipeline-tier tests pinning the end-to-end shape, and the `graphitron-sakila-example` compile as
cross-module backstop. A defensive runtime cast or a `var`-typed local abandons the guarantee ;
it fails on a real request, days after the build passed. Candidate type-system lifts that would
carry current prose contracts structurally are R240 (`@tableMethod` type-token threading) and
R239 (`ColumnField.parentTable`). To record a producer-consumer linkage the type system can't
carry, use a javadoc `{@link}` from consumer to producer ; IDE-refactor-tracked, no audit
infrastructure. Rule: if you relax a producer's check body, audit every emitter site that
consumes the corresponding shape, in the same commit.

*Enforced by:* the type system where the contract is lifted; the pipeline tier and the
`graphitron-sakila-example` compile otherwise.

=== Behaviour is pinned at the pipeline tier and above

Behaviour is asserted at the SDL to classified model to generated `TypeSpec` pipeline layer; new
features earn a pipeline test first. Above it, the `graphitron-sakila-example` compile pins type
correctness and PostgreSQL execution pins behaviour. Code-string assertions on generated method
bodies are banned at every tier: they test implementation, not behaviour, and break on every
refactor ; the compile and execution tiers replace them. Tier names, locations, and the decision
rubric: xref:../how-to/testing.adoc[Test-tier guide].

*Enforced by:* the tiers themselves, on every `-Plocal-db` build; the code-string ban is
review-enforced at test-review time.

=== Principles are stated at altitude

An unguarded inventory in this document ; an arm list, a file census, an occurrence count, a
compliance roster ; reads as authoritative and rots silently. A principle names the rule, one
canonical exemplar, and the smell; an inventory appears only when a named live test pins it (the
dispatch partition under "Rejections" is the pattern). Counts and arm lists otherwise belong in
guarded tests or generated reports; read the type for the current arm set. Exemplars are chosen on
stable surfaces: an exemplar that needs a forward note about its own demolition is the wrong
exemplar.

*Enforced by:* review; the named-test requirement is the rule itself.

=== Documentation names only live tests/code

Two failure modes share one principle: trusted documentation the code does not mechanically pin.
The narrow form: any named test, method, or class must exist today ; a javadoc citing a
nonexistent test is worse than no comment, and a plan that anticipates a symbol it will create
says "C3 adds `X`", never "as asserted by `X`". The broad form: an invariant claim ("the producer
rejects X so the emitter may assume Y") whose symbols exist but which no test or type pins; when X
is silently relaxed the claim is silently false. The fix is the same for both: pin the invariant
mechanically and let documentation describe what's pinned rather than make claims of its own.

*Enforced by:* the reviewer gates (Spec to Ready, In Review to Done); doc-coverage meta-tests
where the surface is closed (`SealedHierarchyDocCoverageTest` and its siblings).

== Generated code is a consumer artifact

*Emitted code is read, breakpointed, and stack-traced by developers who have never seen the
generator, and it compiles on the consumer's toolchain.* Optimise emitted code for the consumer's
legibility, not emitter-side brevity; and keep everything that ships to consumers valid Java 17,
whatever Java 25 features the generator itself uses. This is the readability-and-portability
companion to "Acceptances: classifier guarantees shape emitter assumptions": that corollary keeps
emitted code tight (no defensive casts, no wildcard locals); this axiom keeps it legible and
consumable.

=== Readability rules

A generated method that throws on a real request must produce a stack frame, a line, and locals
that a developer who has never seen the generator can reason about. Concrete rules for any code a
generator emits:

- *Explicit types, never `var`* ; the reader does not have the generator's context.
- *Meaningful local names* (`nodeId`, `row`, `key`), never throwaway pattern-binding noise
  (`_s`, `_r`).
- *Statement form over expression tricks.* No deeply-nested ternaries, no
  throw-inside-an-expression contortions; a developer cannot breakpoint a ternary arm. When the
  output must be an expression, lift the body into a named private helper (per
  xref:../reference/emitter-conventions.adoc#helper-locality[Helper-locality]) so the call site
  stays an expression and the body is readable statements.
- *No `__`-prefixed Java identifiers in emitted code.* Every name in scope is knowable at
  generation time, so a readable deterministic prefix (`arg_<name>`) always beats a blanket `__`.
  The `__`-prefix legitimately survives only as *string literals* (synthetic SQL column aliases
  like `__sort__`; spec-defined external names like jOOQ's `__NODE_*` and federation scalars);
  the identifier-vs-literal discriminator and its full semantics live on
  `GeneratedSourcesLintTest`.

The smell: an emitter building a `CodeBlock` of nested `? :` with `Object` casts and `_x` locals ;
written for the emitter author's convenience, not the consumer's. (R260 cleaned up the
NodeId-decode instance; R334 tracks the `@condition` arg-extraction instance.)

*Enforced by:* `GeneratedSourcesLintTest.emittedSourcesDoNotUseVar` and
`GeneratedSourcesLintTest.emittedSourcesHaveNoDunderIdentifiers` (plus that class's sibling
lints); review for statement form and naming.

=== Generator Java 25; generated output and shipped runtime Java 17

Three categories, three floors. *Generator implementation* may freely use Java 25 features.
*Generated source files* must be valid Java 17: consumers compile Graphitron's output with their
own toolchain, so nothing emitted may require 21+ ; no switch patterns, no sequenced-collections
API. *Hand-written runtime artifacts consumers depend on* (`graphitron-jakarta-rest` is the first
instance) must also target Java 17 ; a runtime jar on the consumer's classpath keeps the same
floor as the generated sources. When adding code, ask which category it is in before reaching for
syntax.

*Enforced by:* the parent pom's `requireJavaVersion` enforcer (generator floor);
`graphitron-sakila-example` compiling with `<release>17</release>` (emitted-syntax ceiling);
`graphitron-jakarta-rest`'s own `<release>17</release>` main compile (runtime category).

The emitter-conventions catalogue (return types, selection-aware queries, error quality,
`DSL.val` column binding, DTO-parent batching, helper-locality) is reference material for emitter
authors: xref:../reference/emitter-conventions.adoc[Emitter conventions].

'''

== Constraints

Facts with existing enforcers, recorded here so the axioms above stay principles:

- The repo root `pom.xml` is a single self-contained Maven reactor: `mvn install` on a clean local
  repo builds every module (the root pom's `<modules>` list is the live inventory) with no
  dependency outside the pinned third-party set. The legacy `graphitron-parent` generator is
  retired; `graphitron-maven-plugin` is the consumer entry point.
- This document is a shared context cost: agents and reviewers load it on every design consult, so
  it budgets itself at 3,500 words. An addition that pushes past the cap must displace something.
  *Enforced by:* `DocSizeBudgetTest.developmentPrinciplesStaysUnderBudget`.
````

## Extracted: docs/architecture/reference/emitter-conventions.adoc (new page)

The conventions catalogue leaves the principles doc for its own reference page; its audience is
someone writing an emitter, not every design consult. Complete page text:

````asciidoc
= Emitter Conventions

Conventions for the code the generator emits; the reference companion to the "Generated code is a
consumer artifact" axiom in
xref:../explanation/development-principles.adoc[Graphitron Development Principles].

== Return types

DataFetchers return `Result<Record>` ; no DTOs, no TypeMappers. GraphQL-Java traverses records
using the registered field DataFetchers. Exception: Connection fields return `ConnectionResult`, a
generated carrier wrapping `Result<Record>` + pagination context.

== Selection-aware queries

`DataFetchingFieldSelectionSet` and `SelectedField` are threaded through all table method
signatures, structurally committing to selection-aware queries:

- *Top-level queries*: call `Type.$fields(sel, table, env)` for the column list, then
  `dsl.select(fields).from(table)...`
- *Inline nesting*: use jOOQ `multiset(select(columns).from(CHILD).where(...)).as("alias")`
  returning `Field<?>` (type-erased). Use type erasure at every helper method boundary ; jOOQ
  generic types compound badly with nesting depth, causing slow compile times.
- *`@splitQuery`*: separate DataLoader; parent fetches FK/PK columns, child batches by those keys.

Selection-driven queries produce different SQL per request, preventing cached query-plan reuse.
This is an acceptable trade-off for wide tables with large optional columns; for narrow tables
(≤ 10 columns) where most fields are always requested, `TABLE.*` is simpler and the dynamic-column
overhead exceeds the benefit.

== Error quality

`BuildContext.candidateHint(attempt, candidates)` sorts candidates by Levenshtein distance. The
Levenshtein-suggestion contract has consolidated onto `BuildContext` and `Rejection` (the
rejection-construction sites), with classifier-side callers thinning out as rejections are
produced through the typed sealed-result path. When adding new jOOQ existence checks in the
validator or builder, follow the same pattern ; pass the relevant candidate list from
`JooqCatalog` to `candidateHint`, or produce the rejection through `Rejection.unknownName(...)` so
the candidate list rides on the typed result.

== Column value binding: `DSL.val(rawValue, col.getDataType())`

When emitting code that binds a raw GraphQL input value (from an input map or
`env.getArgument(...)`) to a specific jOOQ column, always use the two-argument form:

[source,java]
----
DSL.val(rawValue, table.COL.getDataType())
----

Do *not* use the one-argument form with a Java-side cast (`DSL.val((JavaType) rawValue)`):

- GraphQL-Java delivers enum values as `String` ; a Java cast to the jOOQ enum class throws
  `ClassCastException` at runtime.
- GraphQL-Java delivers `ID` scalars as `String` ; a cast to `Long` (or any numeric PK type)
  also throws.
- The one-argument form ignores the column's registered jOOQ `Converter` entirely.

The two-argument form hands `rawValue` to the column's `DataType` and its registered `Converter`
at bind time. No SQL `CAST` is rendered; the coercion is purely Java-side, inside jOOQ.

*`CallSiteExtraction` solves a different problem.* Its value-coercion strategies exist to produce
a typed Java value for a *condition/ordering method parameter* ; code paths where a
developer-written method expects the column's Java type, not a jOOQ `Field<T>`. For inline jOOQ
DSL expressions (INSERT `values(...)`, UPDATE `set(...)`, DELETE/UPDATE `where(...)` predicates),
`DSL.val(rawValue, col.getDataType())` does the coercion inside jOOQ without any Java-side step,
and no `CallSiteExtraction` switch is needed.

Precedent: `LookupValuesJoinEmitter.addRowBuildingCore` (search for `DSL.val` with two arguments).

For DTO-parent batching ; where the parent's backing class is a plain POJO or Java record without
a jOOQ FK to the child's `@table` ; see <<dto-parent-batching,DTO-parent batching>> below; the
lifter contract is how the schema author hands the framework a typed key when the catalog can't
supply one.

[#dto-parent-batching]
== DTO-parent batching

When a record-backed parent's backing class (reflected from its producer) is not a jOOQ
`TableRecord`, the catalog cannot supply the FK columns the column-keyed DataLoader path needs to
batch a child `@table` field. The `@sourceRow` directive closes that gap: the schema author
supplies a static Java method that lifts a `RowN<...>` batch key out of the parent DTO, plus the
`targetColumns` (column names on the child table) the key positions match. The classifier reflects
on the lifter once at build time, validates the per-position column-class match, and produces a
`SourceKey` whose `Reader` is `SourceRowsCall(lifter)` and whose `Wrap` is `Row`, carrying a
`JoinStep.LiftedHop` (target table + slot list, single-hop by construction) in the `path` and a
`LifterRef` (declaring class + method name) on the reader. The lifted slots are
`JoinSlot.LifterSlot` permits, each folding source-side and target-side onto a single `ColumnRef`
by construction (DataLoader key tuple IS target-column tuple as a type fact, not a prose
precondition). The emitter feeds that into the existing `SplitRowsMethodEmitter.buildListMethod`
path with no identity branching: target accessors come from `WithTarget.slots()`, key extraction
from the lifter call. The lifter is the *single place* where the DTO → key mapping lives; if a
schema author needs a different key, they author a new lifter method, not a new emitter arm.

[#helper-locality]
== Helper-locality

Emitted helper methods that bind column references to a specific aliased jOOQ `Table` instance
always take the `Table` as a parameter ; never declare it locally. Callers from different paths
(root fetcher, inline subquery, Split-rows method) need to pass distinct aliases for the same
target table; a locally-declared `Table` forces the wrong alias on every caller but the one the
helper was first written for.

Pattern (canonical example, `<fieldName>OrderBy` emitted by
`TypeFetcherGenerator.buildOrderByHelperMethod`):

[source,java]
----
private static OrderByResult <fieldName>OrderBy(DataFetchingEnvironment env, <Table> table) { ... }
----

Each call site supplies the alias appropriate to its scope: the root fetcher passes its declared
`<entity>Table`; a Split-rows method passes its FK-chain terminal alias; an inline subquery passes
its correlated alias. The helper's column references resolve through the parameter.

The rule does not apply to helpers that are not Table-bound (e.g. cursor encode/decode helpers
operating on `Field<?>` / `SortField<?>`).
````

## Extracted: `GeneratedSourcesLintTest` javadoc (dunder semantics)

The `__` rule's full semantics move next to their enforcer, as javadoc on
`GeneratedSourcesLintTest.emittedSourcesHaveNoDunderIdentifiers` (merging with whatever that
javadoc already explains; the implementer reconciles). Content to carry:

The generator emits every name in scope, including the method signature, so a collision is always
knowable at generation time; the `__` prefix buys no safety that a readable name plus
generation-time awareness does not provide. Author-derived identifiers namespace with a readable,
deterministic prefix (`arg_<name>`, `c_<name>`), never a blanket `__`. The `__`-prefix
legitimately survives in two shapes, both reaching generated code as string literals, never as
Java identifiers: synthetic SQL column aliases (`__sort__`, `__idx__`, `__rn__`, `__typename`,
`__pkN__`; by convention each is declared as a named constant carrying the collision rationale,
which this test does not pin ; only the identifier-vs-literal boundary), and spec-defined external
names we reference but do not own (jOOQ's reflective `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`, the
Apollo-Federation `federation__*` / `link__*` SDL scalars, and the GraphQL introspection
`__typename` meta-field, distinct from the synthetic SQL alias sharing its spelling). The
discriminator the test keys on is Java identifier vs string literal in the emitted output: a lazy
dunder surfaces as a bare identifier and fails; every legitimate `__` name surfaces as a string
literal (or a reflective field-name argument) and is masked before the scan. The only `__`-led
identifier allowlisted is jOOQ's `__NODE_*`, on the rare path where the generator references the
reflective constant by name.

## Dropped, with reasons

Cut without a new home; recorded because v2 carries nothing forward implicitly:

- The `CallSiteExtraction` illustration paragraph (parse-boundary section): a second exemplar
  restating decide-once; the strategy set is documented on `CallSiteExtraction`'s javadoc and the
  `code-generation-triggers.adoc` reference row.
- The R50 carrier-by-carrier replacement walkthrough: the changelog's R50 entry carries it; the
  principle keeps the rule, the pointer, and the smell.
- The adapter/composer long narrative (four paragraphs): the compressed rule keeps the pair
  contract, the asymmetric-typing smell, and the R79 citation; the shipped story is in the
  changelog.
- The classifier-guarantees expansions (three-anchor elaborations, worked-example detail): R240
  and R239 item files carry the lift designs; the compressed corollary keeps all three anchors and
  both citations.
- All forward notes and the transitional exemplars that required them (`SourceKey.Reader`,
  `SourceKey.Wrap`, the R431 decomposition notes, the `MethodBackedField` retirement note, the
  walker-carrier sentence): replaced by stable exemplars per the altitude principle's new
  stable-exemplar rule; R222/R333/R431 carry their own transition narratives.
- Assorted sentence-level restatements throughout (each principle previously explained itself two
  to three times; v2 states each rule once).

## Design decisions

From the 2026-07-04 principles-architect consult and the user's Spec review, in force above:

1. **Six axioms** (user review promoted "One model, many views" from corollary to axiom): three
   about the model (decide once / orthogonal axes / one model many views), then the edge
   (boundaries decode and encode), honesty (every invariant has an enforcer), and output
   (generated code is a consumer artifact). Corollary `===` sections carry the concrete rules;
   every principle ends with an `*Enforced by:*` line (compiler / named meta-test / build tier /
   the honest gap label "review only"). The review-only gap list is read off the labels, never
   stored.
2. **Rename** (user review): the project is graphitron now, so v2 is *Graphitron Development
   Principles* at `docs/architecture/explanation/development-principles.adoc`; "Development"
   avoids colliding with the strategic `graphitron-principles.md`. Runners-up considered:
   `engineering-principles`, `technical-principles`.
3. **v2 is self-contained** (user review): all text inlined, no splice markers; what is not in the
   document above is not carried forward. Anything landing on the live doc between this spec and
   cutover is diffed and consciously folded or dropped.
4. **Walker-carrier is an exemplar, not a principle** (consult): its permanent kernel (typed
   carrier, typed `AuthorError` sub-seal with stable LSP codes) is the intersection of principles
   the doc keeps; the distinctive translator-over-permits part is R222's own "transition
   technique, not prescription" with a scheduled death in R256/R333. Initially it rode as a
   forward-noted sentence under the sealed-results corollary; the stable-exemplar rule (decision
   12) removed it entirely ; that corollary and typed-rejection carry the kernel. R268's
   second-switch drift is the enforcement axiom's headline smell, not a walker smell.
5. **Additive-then-cutover leaves the principles doc** (consult): change discipline, not design;
   both source items (R222, R431) call it a technique slices may discard. It moves to
   `roadmap/workflow.adoc` (deliverable below); the ingress keeps a pointer clause.
6. **The axes axiom is stated at R333's altitude** (consult), not R222's vocabulary: assert what
   nothing else carries; derive what another axis or slot already forces; never store a derived
   fact (previously homeless). "Directives carry only what the SDL author needs to say" homes here
   as the authoring-surface instance of the cross-product smell, not under the codec axiom.
7. **The enforcement axiom keeps its three directions as separate corollaries** (consult):
   rejections (classifier to validator), acceptances (classifier to emitter), claims
   (documentation to test or type) ; the direction tells a contributor which mechanism to reach
   for, and a runtime cast is explicitly not an enforcer. The code-string-assertion ban stays in
   the body, not behind the testing.adoc xref.
8. **Axiom 1 keeps two corollaries with teeth** (consult): the parse boundary as a *containment*
   invariant with R433's corrected discovery recipes, and the ephemeral-vs-carried distinction for
   builder-internal hierarchies.
9. **Stale process-state names fixed in v2**: the live doc's "Draft → Approved and Pending Review
   → Done" (pre-current-workflow vocabulary) becomes "Spec → Ready and In Review → Done".
10. **Drafted against R433's landed text** (`depends-on: [principles-doc-altitude-trim]`); v2
    embeds R433's corrected recipes and altitude trims, and cutover verifies none regress.
11. **The document has a size budget with an enforcer** (user review, 2026-07-04): the doc is
    loaded by the principles-architect and reviewer agents on every design consult, so per-read
    cost and signal density matter more than completeness. v2 lands at roughly 3,200 words, about
    half the pre-compression draft; the cap is 3,500 words, enforced by a new
    `DocSizeBudgetTest.developmentPrinciplesStaysUnderBudget` (sibling of the doc-coverage tests
    in `graphitron-sakila-example`'s internal test package), so future accretion forces a
    displacement conversation instead of silent growth. Per-principle budget: rule, exemplar
    pointer, smell, enforcement line ; narratives live with their enforcer or their audience. The
    Emitter Conventions catalogue moves to a new reference page (resolving the earlier open
    question toward extraction), the `__` rule's full semantics move to
    `GeneratedSourcesLintTest`'s javadoc, and everything cut without a destination is recorded in
    "Dropped, with reasons".
12. **Stable exemplars, and the fact-base frame** (user review, 2026-07-05). Two connected calls.
    First: an exemplar that needs a forward note about its own demolition is the wrong exemplar ;
    the rule is now written into "Principles are stated at altitude", and every forward note is
    gone from v2: `SourceKey.Reader` gave way to `CallSiteExtraction` (sealed hierarchies),
    `SourceKey.Wrap` left the sub-taxonomy exemplars, `MethodBackedField` left the capability
    list, and the walker sentence left sealed-results. The pivot items themselves carry the
    transitional narratives; the principles only name surfaces expected to outlive them. Second:
    R333's deeper insight colors the whole doc from the ingress ; the classified model is a
    normalized fact base (classification asserts facts once, everything downstream is a derived
    view, wire formats are its I/O), which is the shared root of the first three axioms, makes
    the cross-product/stored-derived-fact smell literally denormalization, and recasts rejections
    as located-violation facts rendered into views (folded into the sealed-results corollary).

## Deliverable 2: workflow.adoc addition

Append to `roadmap/workflow.adoc` (after the plan-shape bullets), receiving the technique the
principles doc no longer carries:

````asciidoc
== Structural pivots land additive-then-cutover

When an item replaces a widely-pinned type or seam (R222's technique, restated by R431): introduce
the decomposed replacement alongside the old surface, dual-source, migrate consumers arm by arm
behind the compiler, then delete the old surface. The execution-tier acceptance holds at every
intermediate commit, not just the endpoint; a big-bang edit on a widely-pinned type does not
survive a trunk-based, concurrently-edited repo. This is a technique, not a prescription: slices
may refine or discard it when the surface is narrowly pinned.
````

## Cutover

1. Write the v2 text above, verbatim, to `docs/architecture/explanation/development-principles.adoc`;
   delete `docs/architecture/explanation/rewrite-design-principles.adoc`.
2. Create `docs/architecture/reference/emitter-conventions.adoc` from its extracted chapter above;
   register both pages wherever the docs nav indexes them (`explanation/index.adoc`, reference
   index if one exists).
3. Fold the dunder-semantics content into `GeneratedSourcesLintTest`'s javadoc per its extracted
   chapter, reconciling with the javadoc already present.
4. Add `DocSizeBudgetTest.developmentPrinciplesStaysUnderBudget` (internal test package of
   `graphitron-sakila-example`, next to the doc-coverage tests): fails when
   `development-principles.adoc` exceeds 3,500 words, with a message pointing at the Constraints
   budget entry.
5. Diff the live `rewrite-design-principles.adoc` at cutover time against its state when this spec
   was drafted (last changed by the R433 rework, commit `d905fac`); fold any interim changes into
   v2 consciously or record why they are dropped.
6. Verify none of R433's fixes regress in v2: the corrected `Type`-tree discovery recipe, the
   `<modules>` pointer, no unguarded counts, no `TextMapLookup`.
7. Reference sweep over the source tree (not `docs/target/`, not historical records in
   `roadmap/changelog.md` / `roadmap/audits/`): retarget the filename and any section-anchor
   citations in `docs/architecture/index.adoc`, `explanation/index.adoc`,
   `explanation/dispatch-axes.adoc`, `explanation/typed-rejection.adoc`, `how-to/testing.adoc`,
   `reference/argument-resolution.adoc`, `reference/code-generation-triggers.adoc`,
   `manual/reference/directives/asConnection.adoc`, `CLAUDE.md`,
   `.claude/agents/principles-architect.md`, `.claude/skills/reviewer-prompt/SKILL.md`,
   `.claude/skills/srp/SKILL.md`, plus live roadmap items that cite the doc or its sections by
   name. Conventions citations retarget to the new reference page.
8. Full `mvn install -Plocal-db` green (docs render; `check-adoc-tables`; doc-coverage tests; the
   new budget test).

## Open questions for Spec review

- Name: *Graphitron Development Principles* / `development-principles.adoc` is drafted;
  `engineering-principles` and `technical-principles` are live alternatives if preferred.
- The 3,500-word cap and the word-count mechanism (vs a char cap) are first guesses; the reviewer
  may prefer a different threshold or measure.
- Axiom sections that state a rule directly (orthogonal axes; one model, many views; boundaries)
  carry their own `*Enforced by:*` lines; the decide-once, enforcement, and consumer-artifact
  axioms delegate to their corollaries. Reviewer should check this reads consistently rather than
  accidentally.
