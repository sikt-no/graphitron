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
build tier, or the honest gap label "review only". The strategic principles (why Graphitron exists,
what it promises consumers) live at xref:../../graphitron-principles.adoc[graphitron-principles.md];
the typed-rejection narrative at xref:typed-rejection.adoc[Typed rejection]; how structural pivots
land (additive-then-cutover) is change discipline, in `roadmap/workflow.adoc`. Roadmap items R222
(`dimensional-model-pivot`) and R333 (`coordinate-lowers-to-datafetcher-queryparts`) name the
target internal architecture; where an exemplar below sits on a surface the pivot dissolves, a
forward note says so ; the principle itself survives the surface.

'''

== Decide once, at the parse boundary; carry the decision as a type

*Before implementing a generator body, ensure the model carries what the generator needs ;
pre-resolved, generation-ready.* `GraphitronSchemaBuilder` reads directives once and resolves
everything: table names, column references, method names, extraction strategies. Generators
receive a model in terms of "what to emit", not "what to interpret".

Signs a model type needs more pre-resolution:

- A generator switches on a raw string, or recomputes a derived name from a field name.
- The same multi-arm type switch recurs across multiple generators.
- Generation and calling are conflated in the same model type.
- A generator branches on a predicate over pre-resolved data (e.g. which side of a join holds the
  FK). The decision was not resolved, only its inputs were ; lift the fork into the model as a
  sealed sub-variant. Rule of thumb: if two consumers (generators, validators, resolvers,
  dispatchers) evaluate the same predicate over a model field, the branch belongs in the model.
  The same predicate evaluated by multiple consumers is a sign the resolver is under-specified,
  and an opportunity for one site to drift from another.

=== Sealed hierarchies over enums for typed information

When different variants of a concept carry different data, use a sealed interface ; not an enum
with a shared field set. An enum forces every variant to have the same shape; a sealed record
hierarchy gives each variant exactly the fields it needs.

`SourceKey.Reader` illustrates the pattern: each arm carries exactly the payload its rows-method
body needs to read parent-side input ; some arms carry a typed reference (an accessor ref, a
`@sourceRow` lifter ref, a declared `TableRecord` subclass), others carry nothing because the
surrounding record already holds their data. Read the type for the current arm set; per
"Principles are stated at altitude", this doc deliberately does not enumerate it. The compiler
enforces exhaustive switches ; when a new variant is added, every switch that doesn't handle it
becomes a compile error. (Forward note: R431 decomposes `SourceKey` onto the R333 model's facts;
the principle survives the decomposition ; each destination fact is itself a sealed type whose
arms carry exactly their own data.)

*Enforced by:* the compiler ; exhaustive switches over a sealed hierarchy break when a variant is
added.

=== The parse boundary is a containment invariant

Classification is not only "decide once"; it is a statement about *where* raw external types may
live at all.

Reading the reflection `java.lang.reflect.Type` tree is permitted only at builder-side classifiers
that convert reflection output into the typed model ; a deliberately small set of files,
discoverable by searching for the `Type`-tree reads themselves (`java.lang.reflect.Type`,
`getGenericReturnType`, `getGenericParameterTypes`) rather than enumerated here. A bare
`java.lang.reflect` import is not the discriminator: model records legitimately carry the resolved
`Method` and `Constructor` handles the classifiers produced. Each classifier converts raw
reflection output into typed carrier values (`MethodRef.Param`, `AccessorRef`,
`AccessorResolution`, and kin, each carrying a `ParamSource` where applicable). Everything
downstream ; validator, generator ; switches on the pre-classified values and never touches
reflection types.

The boundary lives at `JooqCatalog`: it is the canonical permitted holder of raw jOOQ types
(`Table<?>`, `ForeignKey<?,?>`) and the path classifier code goes through. The few deliberate
exceptions (validator candidate-hint enumeration, the LSP catalog snapshot) import `org.jooq` at
their own boundary and are discoverable by that import; builders otherwise consume the classified
output via `JooqCatalog` rather than holding raw types directly. If a generator needs information
not yet in a taxonomy record, the fix is to add a component and extract the value in the builder ;
not to reach past the taxonomy boundary.

`CallSiteExtraction` illustrates the principle for argument extraction: the builder decides once
(at classify time) which extraction strategy applies to each argument ; a direct strategy, or a
sealed sub-grouper for nested-input traversal and NodeId decode ; and stores that decision in
`CallParam.extraction` or `ParamSource.Arg.extraction`. The generator switches on the
pre-classified value and emits code directly.

*Enforced by:* review only, with grep-able discovery recipes (the reflection `Type`-tree reads;
the `org.jooq` imports). A meta-test pinning each containment set is a candidate roadmap item.

=== Narrow component types over broad interfaces

Field record components are declared with the narrowest type the classifier can guarantee rather
than the broad sealed-interface root. A field whose return type is always table-bound declares
`ReturnTypeRef.TableBoundReturnType` directly; a field whose return type is always polymorphic
declares `ReturnTypeRef.PolymorphicReturnType` directly.

This pushes classification certainty into the type system: code that receives a
`ServiceTableField` knows its `returnType` is `TableBoundReturnType` without a runtime check.

*Enforced by:* the compiler ; the narrowed component type is the contract.

=== Sub-taxonomies for resolution outcomes

Complex resolution outcomes get their own sealed type rather than being stored as raw strings:
`SourceKey.Wrap` is the key-shape sub-taxonomy, `TableRef` the resolved-table one, `ColumnRef` the
resolved-column one. The type of a field tells you exactly what states it can be in.

Each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries
that a sibling cannot ; otherwise it's probably a field on an existing record. At milestone
boundaries, audit which sub-taxonomies could collapse now that their forcing functions are
visible.

*Enforced by:* review only (the one-line justification note at proposal time; the
milestone-boundary collapse audit).

=== Builder-internal hierarchies are ephemeral; model hierarchies are carried

"Carry the decision as a type" answers *how*; this corollary answers *where the type lives*, and
the two answers are opposites for different jobs. A model-level hierarchy is carried all the way
to the generator. A builder-internal hierarchy exists to structure a complex multi-target
classification and is discarded before the model: generators never see it.

When a builder step classifies inputs into many variants that project into *different*
generation-ready outputs, introduce a builder-internal sealed hierarchy. It captures the full
classification, enables exhaustive projection into each target, and is discarded before reaching
the model. `ArgumentRef` (see xref:../reference/argument-resolution.adoc[argument-resolution.md])
classifies every GraphQL argument once into a variant (`ColumnArg`, `OrderByArg`,
`PaginationArgRef`, `TableInputArg`, etc.). Separate projection steps then switch on the
classified values to produce `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, and
`PaginationSpec` ; each projection is exhaustive and independent, and generators never see
`ArgumentRef` ; they see the projected results. The alternative ; multiple independent passes that
implicitly coordinate by skipping each other's arguments (e.g., `buildFilters()` skipping
pagination args using the same hardcoded names as `buildPaginationSpec()`) ; is fragile and makes
adding new argument types error-prone.

*Enforced by:* review only ; a generator referencing `ArgumentRef` (or any builder-internal type)
is the tell.

=== Builder-step results are sealed, not strings or out-params

Every builder-step lift returns a sealed `Resolved`; rejection is a typed variant, never a string
or out-param. The full narrative (sealed `Resolved` shape across the resolver siblings, the
`Rejection` taxonomy, the `BuildContext.candidateHint` contract) lives at
xref:typed-rejection.adoc[Typed rejection].

The walker slices apply the same rule one level up: a walker translating already-classified
permits returns a typed carrier (`WalkerResult.Ok | Err`), and its failures are typed
`AuthorError` sub-seal arms with stable LSP codes, never prose. (Forward note: the
walker-as-translator shape is R222's transition technique, and R333 relocates the carriers onto
the model's coordinate facts; the typed-result rule is what survives.)

*Enforced by:* the compiler on the sealed results; `SealedHierarchyDocCoverageTest` pins the
permit-to-doc mapping for the `Rejection` taxonomy.

== Orthogonal facts are independent axes

*Assert what nothing else carries; derive what another axis or slot already forces; never store a
derived fact.* When a concept's variants multiply, the usual cause is independent axes spliced
into one identifier: the permit set becomes the cross-product of its axes, and adding a value to
any axis multiplies the permits below it. Carry each axis as its own slot or sealed sub-interface;
let each dispatch site read the axis it forks on; compute cross-axis views at the read site
instead of storing them (a stored derived fact is a second copy of a decision, and second copies
drift). R222 (`dimensional-model-pivot`) is the roadmap-scale application ; the field-permit
taxonomy re-expressed as independent facts ; and R333 is the current statement of the target
model.

The DataLoader-backed source side is the worked example today: the per-row key shape, the body
input contract, the per-source row count, and the loader registration are independent axes, and
each dispatch site reads off whichever axis it forks on instead of reconstructing it from a
conflated permit. The xref:dispatch-axes.adoc[Dispatch axes] chapter narrates the split, the
cross-axis invariants the compact constructor pins, and the consumer-side dispatch shapes.
(Forward note: R431 relocates these axes onto the R333 model's facts, which sharpens rather than
retires the per-axis discipline.) The smell to watch for: a single shared accessor whose meaning
depends on the variant (e.g. "FK source columns" in one arm and "child target columns" in
another), a sealed permit name that splices two axes together, or a record component another
component already determines.

*Enforced by:* the compiler where cross-axis invariants live in compact constructors; review at
model-design time otherwise.

=== Capability interfaces and sealed switches serve different roles

When a generation pattern applies uniformly across multiple field variants, use an orthogonal
capability interface rather than an N-way `instanceof` chain. `SqlGeneratingField` and
`BatchKeyField` are established instances; `ServiceField` (R238) is the per-directive shape newer
slices follow. (Forward note: R222 retires `MethodBackedField` in favour of per-directive siblings
like `ServiceField`; the capability-vs-switch distinction below is untouched by that retirement.)

Capabilities express what is *uniformly true* across variants; sealed switches express what
*varies by identity*. Use a capability when the generator treats variants identically (iterate
`SqlGeneratingField.filters()` regardless of leaf type). Use a sealed switch when the generator
forks on identity (which `$fields` arm to emit, which rows-method signature to synthesise).
Capabilities don't eliminate exhaustiveness bookkeeping ; they relocate it.

*Enforced by:* the compiler ; capabilities relocate exhaustiveness bookkeeping, sealed switches
keep it.

=== Directives carry only what the SDL author needs to say

This is the authoring-surface instance of the cross-product smell: an input wrapper whose slots
are independently optional hands the author a permit cross-product to navigate, and the site
already determines most of the axes.

Directive arguments should be flat scalars whenever the directive site already disambiguates the
axis. An input-object wrapper is justified only when the directive carries several
genuinely-orthogonal pieces of information at once; the default is a single typed scalar
(`String!`, `Int!`, an enum), and reaching for an input wrapper is a deliberate decision rather
than a default.

`@field(name: String!)` is the worked example. The directive applies on four sites
(`FIELD_DEFINITION`, `INPUT_FIELD_DEFINITION`, `ARGUMENT_DEFINITION`, `ENUM_VALUE`); the site
itself tells the classifier which axis is being bound (column vs argument vs enum-value), so the
directive only carries the underlying name. There is no `@field(target: { axis, name })` wrapper,
because the axis is structural.

The smell to watch for is an input wrapper that most callsites fill in two-of-four slots on. SDL
authors end up typing a structured literal where a string would have served, and the directive's
failure-mode surface widens from "the named thing didn't resolve" to a cross-product of "field A
missing", "field B given but A wasn't", "A and B given but inconsistent". `ExternalCodeReference`
(`name`, `className`, `method`, `argMapping`) is the existing case that new directive surfaces
should not lean on; new directives default to `@field`'s shape.

*Enforced by:* review only, at directive-design time.

== One model, many views

*Code generation is the narrowest view of the classified model, not the model itself.* Other
consumers (the LSP completion and hover snapshot today) re-source from the same classified facts;
no consumer owns a private model. A view's coverage guarantee lives at its projection seam:
`CatalogBuilder.projectFieldClassification` is the live exemplar ; an exhaustive switch over the
field permits projecting LSP-renderable payload, so a new permit fails compilation until the view
covers it. The smell is a consumer-side shadow taxonomy: a second model maintained by hand to feed
a view, drifting from the real one silently. (Forward note: R333 widens the model these views
project from and names this rule its load-bearing requirement; the coverage switch moves with the
seam.)

*Enforced by:* the compile-checked projection switch at each view's seam.

== Boundaries decode and encode; the interior is typed

*Opaque wire formats (Relay NodeId base64 strings, Relay cursor strings, federation `_Any`
representations) decode at the DataFetcher boundary into typed column tuples; the projection layer
encodes back into wire format only at the same boundary; variants representing the wire shape
don't survive in the model.*

R50 is the worked example. The retired wire-shape carriers (nine "the model says this is a NodeId"
markers spread across `InputField`, `ChildField`, `BodyParam`, `LookupMapping`, and `ArgumentRef`;
see R50 in the changelog) each forced downstream emitters to call `NodeIdEncoder.hasIds(...)` or
similar wire-aware helpers across the boundary. The replacement:
`CallSiteExtraction.NodeIdDecodeKeys.{SkipMismatchedElement | ThrowOnMismatch}` lives at the
carrier slot where decode happens (input-fields, arg-level filters, lookup-key bindings);
`CallSiteCompaction.NodeIdEncodeKeys(HelperRef.Encode)` lives at the projection slot where encode
happens (column-shape carriers on output); `BodyParam.ColumnPredicate.{Eq | In | RowEq | RowIn}`
carries the predicate shape over decoded column tuples without needing to know the predicate came
from a wire-format input. Standard column predicates and lookup VALUES rows fall out for free.

The pattern matches Connection-cursor encode/decode (which already lives at
`ConnectionHelper.encodeCursor` / `decodeCursor` and never reached the model), and the federation
`_Any` rep flow (which reads the rep at `EntityFetcherDispatch.resolveByReps` and walks
alternatives over decoded values, never as opaque blobs).

The general rule: for any opaque wire format, classify the failure mode (skip vs throw) and the
direction (encode vs decode) at the boundary, never below it. A "this is a NodeId" or "this is a
base64 cursor" marker spreading through the model is the same family of smell as a parallel
runtime type system ; both are bypasses around classified information that the boundary already
carries.

*Enforced by:* review only; the R50 regression surface is pipeline-tier (a wire-shape carrier
reintroduced into the model has no typed home to land in).

=== Model metadata over parallel type systems

When the model already carries typed information, runtime data formats should derive from that
metadata rather than inventing a parallel type system.

`OrderByResult` pairs `List<SortField<?>>` with `List<Field<?>>` ; each cursor column's `DataType`
is already known. Cursor encode/decode should use `field.getDataType().convert()` for type-safe
round-tripping, and `DSL.noField(field)` for the no-cursor seek case. This eliminates the need for
a hand-rolled type-tag system (`i:`, `s:`, `l:`) in the cursor format ; the column metadata *is*
the type information.

The general principle: when the model has already classified and resolved type information at
build time, that same information should drive any runtime format that needs types. A parallel
type system in the runtime format is redundant and will diverge.

*Enforced by:* review only.

=== Wire boundaries are typed adapter / composer pairs

Where the generator emits a method that crosses the wire-format boundary, it emits it in pair with
a composer: the adapter takes `DataFetchingEnvironment` (or the wire-shape input) and produces
typed values; the composer takes those typed values and does the actual work. The two share name
and a table-anchor parameter, and the composer's signature is exactly the shape the adapter yields
after decoding. The boundary is the pair, not the adapter alone.

The worked example is the generated `QueryConditions.<method>(Table, DataFetchingEnvironment)`
plus the user-written `<X>Conditions.<method>(Table, ...)` it forwards to. The adapter decodes
`NodeId` strings, walks input maps, and hands typed jOOQ values across the boundary; the composer
takes those values (`Row<N><T1, ..., TN>`, `String`, `List<T>`, ...) and composes a `Condition`.
Same name, same table-first arg, two halves of one boundary.

The smell to watch for is asymmetric typing across the pair: most often the adapter erasing type
information the composer needs (e.g. an arity-erased `RowN` instead of the `Row<N><T1, ..., TN>`
the decoder actually produced). When that happens, the composer's signature stops documenting the
contract, and column-shape errors that should be compile failures become DSL-runtime surprises.
The fix is to honour the type the decoder produces, not to widen the composer to absorb the loss.
R79's switch from `RowN` to `Row<N><...>` is the application of this principle: the adapter side
of the boundary already had `Record<N><T1, ..., TN>` from the typed `instanceof` pattern; the
composer side just needed to type its argument the same way.

The principle generalises beyond `QueryConditions`: any `(env, ...) -> typed-args` adapter that
the generator emits in front of a user-written or emitter-written composer should land with the
same symmetric typing. Drift between the two is a smell that the adapter is hiding the boundary
rather than crossing it.

*Enforced by:* the `graphitron-sakila-example` compile where the pair's signatures meet; review
for the symmetry itself.

== Every invariant has an enforcer

*An invariant exists only while something fails when it breaks.* A classifier decision, an emitter
assumption, or a documentation claim that nothing mechanically pins is a false invariant waiting
to happen. The corollaries below are the three directions the rule flows ; rejections (classifier
to validator), acceptances (classifier to emitter), claims (documentation to test or type) ; and
the direction matters because each has a different enforcer; blurring them invites the wrong tool.
A runtime cast is not an enforcer in this doc's sense: it fails at request time, days after the
build passed. Enforcers run at build time or earlier.

The smell shared by all three directions: a parallel statement of the same fact with no single
enforcer ; a second dispatch set, a defensive cast, an unguarded census ; which drifts from the
real one silently. R268 is the worked example: an emit-side allow-list over the `ChildField`
taxonomy drifted from the arms the emitter actually implemented, producing both a latent
`IllegalStateException` and a false author-error rejection; the fix deleted the parallel set and
re-sourced the invariant from the fields' own resolution.

A review-only label anywhere in this document is an invitation: if the rule can be pinned
mechanically, filing that meta-test is roadmap material. The set of review-only principles is read
off the labels, never stored as its own list (a stored list would rot, per "Principles are stated
at altitude").

=== Rejections: validator mirrors classifier invariants

Every classifier decision that implies a generator branch must fail at validate time if that
branch is unimplemented. The validator reads the same dispatch sets the generator does, so an
unsupported classification surfaces as a build-time error rather than a runtime
`UnsupportedOperationException`. The dispatch state lives in `TypeFetcherGenerator` as a four-way
disjoint partition over every `GraphitronField` sealed leaf: `IMPLEMENTED_LEAVES` (real fetcher
arm), `PROJECTED_LEAVES` (emitted inline by `TypeClassGenerator.$fields`), `NOT_DISPATCHED_LEAVES`
(cannot reach the fetcher switch), and `STUBBED_VARIANTS.keySet()` (stub-emitting variants). The
partition is exhaustive and disjoint by construction;
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces both properties.
This closes the gap between "the schema classifies cleanly" and "the emitter has an arm for this
leaf". `ValidateMojo` consumes the stubbed-variant set and fails the build by default.

The rule extends beyond stubbed variants: when a classifier introduces a new invariant (e.g.
"`@asConnection` not allowed on inline `TableField`"), the validator should reject it by the same
mechanism the generator relies on ; no generator-side invariant goes unchecked at validate time.
This keeps "problems caught at build time" honest and the generator's builder-invariant
assumptions emitter-side safe.

*Enforced by:* `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` (partition
exhaustive and disjoint); `ValidateMojo` fails the build on stubbed variants.

=== Acceptances: classifier guarantees shape emitter assumptions

A classifier rejection becomes a build-time error via the validator; the reverse direction also
matters. A classifier acceptance can let an emitter assume narrower shapes, so the emitted code
reads as tight as if it were hand-written: no defensive casts, no wildcard locals, no `instanceof`
guards. The principle anchors on three layers:

. *Type-system narrowing at the producer.* The narrowness the consumer needs lives in the
  producer's signature ; a record component, a return type, a sealed sub-variant. Once carried in
  the type, the contract is mechanically enforced and the consumer compiles unchanged.
. *Pipeline-tier tests.* SDL → classified model → generated `TypeSpec` coverage pins the
  end-to-end shape; a regression that breaks the narrowed contract trips the pipeline test before
  the cross-module compile.
. *The `graphitron-sakila-example` compile as cross-module backstop.* `mvn compile -pl
  :graphitron-sakila-example -Plocal-db` against a real jOOQ catalog catches any
  classifier/emitter mismatch during the build, before any code reaches a consumer.

Compared with defensive runtime casts (which can throw `ClassCastException` on a real request,
days after the build passed) or `var`-typed locals fed into parameterised entry points (which
abandon the strict-shape guarantee entirely), the type-system-narrowed shape is the safest
expression of the contract.

Two worked examples illustrate the principle and the candidate type-system lifts that would carry
each contract structurally:

- *`@tableMethod` root fetcher.* `ServiceCatalog.reflectTableMethod` rejects developer methods
  whose return type is wider than the generated jOOQ table class, so the emitted fetcher declares
  the specific table type with no cast and feeds it directly into that type's `$fields(...)`. The
  candidate type-system lift (type-token threading through `MethodRef.StaticOnly` /
  `ReturnTypeRef.TableBoundReturnType`) is R240; pre-lift, the pipeline tests plus the
  sakila-example compile pin the shape.
- *`ColumnField` parent table.* The classifier produces a `ColumnField` only on a table-backed
  parent. The candidate lift (a non-null `parentTable` record component populated at construction,
  eliminating a threaded parameter and an `IllegalStateException` reachability arm) is R239.

When a contributor wants to record a producer-consumer linkage explicitly ; because the
type-system lift isn't viable for the key and the principle's three anchors don't visibly tie the
two sites together ; the recommended mechanism is a javadoc `{@link}` from the consumer to the
producer (and optionally back). `{@link}` is IDE-refactor-tracked (renaming the producer
auto-updates the link), carries no prose burden, and reuses the doc-tool the codebase already has.
No custom annotation, no audit infrastructure to maintain.

Rule: if you relax a producer's check body, audit every emitter site that consumes the
corresponding shape, in the same commit. The pipeline tests and the cross-module compile are the
safety net.

*Enforced by:* the type system where the contract is lifted; the pipeline tier and the
`graphitron-sakila-example` compile otherwise.

=== Behaviour is pinned at the pipeline tier and above

Behaviour is asserted at the SDL to classified model to generated `TypeSpec` pipeline layer, not
at the per-variant unit tier; new features earn a pipeline test first, and unit tests cover
structural invariants that pipeline coverage would make repetitive. Above the pipeline tier,
compilation of `graphitron-sakila-example` against a real jOOQ catalog pins type correctness, and
execution against real PostgreSQL pins behaviour. Code-string assertions on generated method
bodies are banned at every tier: they test implementation, not behaviour, and break on every
refactor ; the compile and execution tiers replace them. For canonical tier names, file locations,
the decision rubric, and the meta-annotations, see xref:../how-to/testing.adoc[Test-tier guide].

*Enforced by:* the tiers themselves, on every `-Plocal-db` build; the code-string ban is
review-enforced at test-review time.

=== Principles are stated at altitude

An unguarded inventory in this document ; an arm list, a file census, an occurrence count, a
compliance roster ; is the false-invariant family of "Documentation names only live tests/code"
applied to the document itself: it reads as authoritative and rots silently. A principle names the
rule, one canonical exemplar, and the smell. An inventory appears only when a named live test pins
it; the dispatch partition in "Rejections: validator mirrors classifier invariants" is the
pattern ; the four-way partition is safe to enumerate because
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces it. Counts, arm
enumerations, and audit rosters otherwise belong in guarded tests or generated reports, not here;
read the type for the current arm set.

*Enforced by:* review; the named-test requirement is the rule itself.

=== Documentation names only live tests/code

Two failure modes share one principle: trusted documentation that the code does not mechanically
pin.

The narrow failure mode: javadoc, plan prose, and README references that name a test, method, or
class must name one that exists today. A javadoc comment saying "enforced by
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`" when that method does not
exist is worse than no comment ; it's a false invariant that readers trust. When a plan's wording
anticipates a method, class, or test that the same plan will create, phrase it as "C3 adds `X`"
rather than "as asserted by `X`".

The broader failure mode: invariant claims that no live test, type, or assertion pins. A javadoc,
annotation `description`, or doc paragraph saying "the producer rejects X so the emitter may
assume Y" is exactly the same false-invariant family the narrow form names ; the symbols
referenced may exist today, but if X gets silently relaxed (a `ClassName.equals` widened to
`startsWith`, an `instanceof` arm broadened), the claim is silently false and readers still trust
it. The fix is the same: pin the invariant in the type system or in a test that fails when the
invariant fails, and let the documentation describe what's pinned rather than make a claim of its
own.

Reviewers check both forms explicitly during the Spec → Ready and In Review → Done transitions.

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

- *Explicit types, never `var`.* The reader of generated code does not have the generator's
  context; an explicit type at every local is documentation. (The same `var`-ban appears in
  "Acceptances: classifier guarantees shape emitter assumptions" for a different reason ; there it
  protects the strict-shape guarantee, here it protects the reader.)
- *Meaningful local names; short is good, throwaway is not.* `nodeId`, `key`, `sakKey` over `_s`,
  `_r`, `_nl`. Underscore-prefixed pattern-binding names carry no meaning and read as noise; a
  bare `_` is reserved in Java 21+ besides. Short names are welcome when they name the thing
  (`row`, `key`); cryptic ones are not.
- *Statement form over expression tricks.* Prefer named locals and `if`/`else` blocks over
  deeply-nested ternaries, and never reach for an expression-only contortion (the
  `((Supplier<X>) () -> { throw ...; }).get()` throw-inside-an-expression trick is the canonical
  offender) solely to keep emission inline. A developer cannot set a breakpoint inside a ternary
  arm or read a meaningful frame from a lambda-wrapped throw.
- *When the output must be an expression* (it is consumed as a method-call argument, a field
  initialiser, a stream lambda), lift it into a named private helper method per the
  <<Helper-locality>> convention so the call site stays an expression while the *body* is readable
  statement form. The helper name documents intent (`decodeSakKey(...)`), and the helper body gets
  explicit types, named locals, and ordinary control flow.
- *No `__`-prefixed Java identifiers in emitted code.* Emitted locals, lambda parameters, and
  method parameters use readable names (`row`, `byPk`, `fetched`, `violations`), never a
  `__`-prefixed default (`__r`, `__byPk`, `__fetched`). The generator emits every name in scope,
  including the method signature, so a collision is always knowable at generation time; the `__`
  prefix buys no safety that a readable name plus generation-time awareness does not already
  provide. Where an author-derived identifier (a GraphQL argument or input-component name) becomes
  a local, namespace it with a *readable, deterministic* prefix computed against the
  known-in-scope names (`arg_<name>`, `c_<name>`), never a blanket `__`. The `__`-prefix
  legitimately survives in two other shapes, both of which reach generated code as *string
  literals*, never as Java identifiers. First, *synthetic SQL column aliases* (`__sort__`,
  `__idx__`, `__rn__`, `__typename`, `__pkN__`), which live in the result-set column namespace
  alongside consumer-controlled table columns and wrap in `__` precisely to avoid colliding with a
  real column; by convention each is declared as a named constant carrying the collision rationale
  at its site (the meta-test does not pin the constant form, only the identifier-vs-literal
  boundary, so the constant discipline is a readability convention, not an enforced invariant).
  Second, *spec-defined external names we reference but do not own*: jOOQ's reflective
  `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` metadata constants, the Apollo-Federation
  `federation__*` / `link__*` SDL scalars, and the GraphQL introspection `__typename` meta-field
  (read off a federation representation map, distinct from the synthetic `__typename` SQL column
  that happens to share its spelling). The discriminator, and the one the no-regression meta-test
  keys on, is *Java identifier vs string literal in the emitted output*: a lazy dunder surfaces as
  a bare identifier and fails the test; every legitimate `__` name above surfaces as a string
  literal (or, for the jOOQ metadata, a reflective field-name argument) and is masked before the
  scan. The only `__`-led identifier the test allowlists is jOOQ's `__NODE_*`, on the rare path
  where the generator references that reflective constant by name rather than by value.

The smell to watch for: an emitter building a `CodeBlock` whose template spans several lines of
nested `? :`, casts to `Object` to dodge a parameterised `instanceof`, and binds `_x`-style
locals. That block is being written for the emitter author's convenience, not the consumer's; the
fix is a named helper with a statement body. (R260 cleaned up the NodeId-decode instance of this
smell; R334 tracks the `@condition` arg-extraction instance.)

*Enforced by:* `GeneratedSourcesLintTest.emittedSourcesDoNotUseVar` and
`GeneratedSourcesLintTest.emittedSourcesHaveNoDunderIdentifiers` (plus that class's sibling
lints); review for statement form and naming.

=== Generator Java 25; generated output and shipped runtime Java 17

Three categories, three floors. *Generator implementation* (almost everything in the reactor) may
freely use Java 25 features ; sealed classes, pattern matching, records, switch expressions, text
blocks, scoped values. *Generated source files* must be valid Java 17: consumers compile
Graphitron's output with their own toolchain, so nothing emitted may require 21+ ; no switch
patterns, no sequenced-collections API. *Hand-written runtime artifacts consumers depend on*
(`graphitron-jakarta-rest`, the reusable GraphQL-over-HTTP serving library, is the first instance)
must also target Java 17: a runtime jar on the consumer's classpath must not require newer
bytecode than the generated sources' floor. When adding code, ask which category it is in before
reaching for syntax.

*Enforced by:* the parent pom's `requireJavaVersion` enforcer (generator floor);
`graphitron-sakila-example` compiling with `<release>17</release>` (emitted-syntax ceiling);
`graphitron-jakarta-rest`'s own `<release>17</release>` main compile (runtime category).

'''

== Emitter Conventions

=== Return types

DataFetchers return `Result<Record>` ; no DTOs, no TypeMappers. GraphQL-Java traverses records
using the registered field DataFetchers. Exception: Connection fields return `ConnectionResult`, a
generated carrier wrapping `Result<Record>` + pagination context.

=== Selection-aware queries

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

=== Error quality

`BuildContext.candidateHint(attempt, candidates)` sorts candidates by Levenshtein distance. The
Levenshtein-suggestion contract has consolidated onto `BuildContext` and `Rejection` (the
rejection-construction sites), with classifier-side callers thinning out as rejections are
produced through the typed sealed-result path. When adding new jOOQ existence checks in the
validator or builder, follow the same pattern ; pass the relevant candidate list from
`JooqCatalog` to `candidateHint`, or produce the rejection through `Rejection.unknownName(...)` so
the candidate list rides on the typed result.

=== Column value binding: `DSL.val(rawValue, col.getDataType())`

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
=== DTO-parent batching

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

=== Helper-locality

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

'''

== Constraints

Facts with existing enforcers, recorded here so the axioms above stay principles:

- The repo root `pom.xml` is a single self-contained Maven reactor: `mvn install` on a clean local
  repo builds every module (the root pom's `<modules>` list is the live inventory) with no
  dependency outside the pinned third-party set. The legacy `graphitron-parent` generator is
  retired; `graphitron-maven-plugin` is the consumer entry point.
````

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
   technique, not prescription" with a scheduled death in R256/R333. It rides as a forward-noted
   paragraph under the sealed-results corollary. R268's second-switch drift is the enforcement
   axiom's headline smell, not a walker smell.
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
2. Diff the live `rewrite-design-principles.adoc` at cutover time against its state when this spec
   was drafted (last changed by the R433 rework, commit `d905fac`); fold any interim changes into
   v2 consciously or record why they are dropped.
3. Verify none of R433's fixes regress in v2: the corrected `Type`-tree discovery recipe, the
   `<modules>` pointer, no unguarded counts, no `TextMapLookup`.
4. Reference sweep over the source tree (not `docs/target/`, not historical records in
   `roadmap/changelog.md` / `roadmap/audits/`): retarget the filename and any section-anchor
   citations in `docs/architecture/index.adoc`, `explanation/index.adoc`,
   `explanation/dispatch-axes.adoc`, `explanation/typed-rejection.adoc`, `how-to/testing.adoc`,
   `reference/argument-resolution.adoc`, `reference/code-generation-triggers.adoc`,
   `manual/reference/directives/asConnection.adoc`, `CLAUDE.md`,
   `.claude/agents/principles-architect.md`, `.claude/skills/reviewer-prompt/SKILL.md`,
   `.claude/skills/srp/SKILL.md`, plus live roadmap items that cite the doc or its sections by
   name.
5. Full `mvn install -Plocal-db` green (docs render; `check-adoc-tables`; doc-coverage tests).

## Open questions for Spec review

- The Emitter Conventions catalogue stays in-doc (drafted) versus moving to its own reference
  page. In-doc chosen to limit churn and keep the consumer-artifact axiom's catalogue adjacent; a
  later item can still extract it.
- Name: *Graphitron Development Principles* / `development-principles.adoc` is drafted;
  `engineering-principles` and `technical-principles` are live alternatives if preferred.
- Axiom sections that state a rule directly (orthogonal axes; one model, many views; boundaries)
  carry their own `*Enforced by:*` lines; the decide-once, enforcement, and consumer-artifact
  axioms delegate to their corollaries. Reviewer should check this reads consistently rather than
  accidentally.
