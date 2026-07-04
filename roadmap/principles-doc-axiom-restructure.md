---
id: R434
title: "Restructure rewrite design principles around axioms with named enforcement"
status: Spec
bucket: docs
priority: 4
theme: docs
depends-on: [R433]
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

## Design decisions (2026-07-04, principles-architect consult folded in)

The consult confirmed the anatomy (per-principle named enforcement) and the promotion of the
cross-product thesis, and falsified four pieces of the original cut. All adopted:

1. **Five axioms, not principles-per-lesson.** Each axiom heading frames; corollary `===` sections
   carry the content. Every principle ends with an `*Enforced by:*` line naming what fails when it
   breaks: the compiler, a named meta-test, a build tier, or the honest gap label "review only".
   Review-only labels double as the meta-test gap list, which is *derived by reading the labels*,
   never stored as its own enumeration (the altitude rule applied to the doc itself).
2. **Walker-carrier is an exemplar, not a principle.** Its permanent kernel (typed carrier, typed
   `AuthorError` sub-seal with stable LSP codes) is the intersection of principles the doc already
   keeps; the distinctive part (translator over already-classified permits) is R222's own
   "transition technique, not prescription" with a scheduled death in R256/R333. It appears as a
   forward-noted sentence under the sealed-results corollary. R268's second-switch drift is NOT a
   walker smell; it is the canonical "parallel statement with no single enforcer" exemplar and
   homes under the enforcement axiom's headline.
3. **Additive-then-cutover leaves the principles doc.** It is change discipline, not design; both
   source items (R222, R431) call it a technique slices may discard. It moves to
   `roadmap/workflow.adoc` (small deliverable below); the principles preamble keeps a one-line
   pointer.
4. **Axiom 2 is stated at R333's altitude**, not R222's vocabulary: "assert what nothing else
   carries; derive what another axis or slot already forces; never store a derived fact." The
   derive-don't-store companion rule was previously homeless. The 45-permit cross-product stays as
   the historical smell, dispatch-axes as the worked example, R333 named as the current statement
   of the model. "Directives carry only what the SDL author needs to say" homes here as the
   authoring-surface instance of the cross-product smell (its input-wrapper smell IS a
   cross-product), not under the codec axiom.
5. **The enforcement axiom keeps its three directions as separate corollaries** (rejections:
   classifier → validator; acceptances: classifier → emitter; claims: documentation → test/type),
   each naming its own enforcer, because the direction tells a contributor which mechanism to
   reach for; a runtime cast "has an enforcer" but is explicitly not one in this doc's sense. The
   code-string-assertion ban stays visible in the body, not behind the testing.adoc xref.
6. **New corollary: one model, many views** (R333's load-bearing requirement, previously absent):
   code generation is the narrowest view of the classified model; consumers re-source from the
   facts; no consumer owns a private model; a view's coverage guarantee is a compile-checked
   projection switch at its seam (`CatalogBuilder.projectFieldClassification` is the live exemplar,
   verified: exhaustive switch, new permit fails compile until mapped).
7. **Axiom 1 keeps two named corollaries with teeth**: the parse boundary as a *containment*
   invariant (with R433's corrected discovery recipes), and the ephemeral-vs-carried distinction
   for builder-internal hierarchies ("carry the decision as a type" must not flatten "carry it to
   the generator" and "discard it before the model" into one verb).
8. **Drafted against R433's landed text** (`depends-on: [R433]`); the replacement below embeds
   R433's corrected recipes and altitude trims. Cutover verifies none of R433's fixes regress.
9. **The Emitter Conventions catalogue carries over verbatim** and is spliced at cutover rather
   than duplicated in this spec: it is unchanged by this item, and a second copy would race
   concurrent edits to the live doc. Everything that changes is written out in full below.

## Replacement text

The complete new `rewrite-design-principles.adoc`. Sections marked `[carried verbatim: ...]` splice
the named block from the live doc unchanged at cutover; all other text is the actual proposed
wording, reviewed here while the live doc stays untouched.

````asciidoc
= Rewrite Design Principles

Technical and architectural principles that govern the rewrite pipeline. For Graphitron's
strategic/philosophical principles, see xref:../../graphitron-principles.adoc[graphitron-principles.md].

These principles govern both the current code and the structural pivot in flight: roadmap items
R222 (`dimensional-model-pivot`) and R333 (`coordinate-lowers-to-datafetcher-queryparts`) name the
target internal architecture. Where an exemplar below sits on a surface those items dissolve, a
forward note says so; the principle itself survives the surface.

The document is organised as five axioms; corollary subsections carry the concrete rules. Every
principle ends with an *Enforced by* line naming what fails when it is broken: the compiler, a
named meta-test, a build tier, or ; the honest label for a gap ; review only. A review-only label
is an invitation: if the rule can be pinned mechanically, filing that meta-test is roadmap
material. The set of review-only principles is deliberately not enumerated anywhere; read the
labels (a stored list would rot, per "Principles are stated at altitude").

The typed-rejection narrative (the sealed `Resolved` shape across the resolver siblings, the
`Rejection` taxonomy, the Levenshtein-ranked candidate hint contract) is consolidated at
xref:typed-rejection.adoc[Typed rejection]. How structural pivots *land* (additive-then-cutover)
is change discipline, not design; see `roadmap/workflow.adoc`.

'''

== Decide once, at the parse boundary; carry the decision as a type

*Before implementing a generator body, ensure the model carries what the generator needs ;
pre-resolved, generation-ready.* `GraphitronSchemaBuilder` reads directives once and resolves
everything: table names, column references, method names, extraction strategies. Generators
receive a model in terms of "what to emit", not "what to interpret".

[carried verbatim: the "Signs a model type needs more pre-resolution" bullet list from
§ Generation-thinking]

=== Sealed hierarchies over enums for typed information

[carried verbatim: § "Sealed hierarchies over enums for typed information", first paragraph and
the `SourceKey.Reader` exemplar paragraph with its R431 forward note]

*Enforced by:* the compiler ; exhaustive switches over a sealed hierarchy break when a variant is
added.

=== The parse boundary is a containment invariant

Classification is not only "decide once"; it is a statement about *where* raw external types may
live at all.

[carried verbatim: § "Classification belongs at the parse boundary", all three paragraphs: the
reflection `Type`-tree containment with its discovery recipe, the `JooqCatalog` containment with
its `org.jooq` import recipe, and the `CallSiteExtraction` illustration]

*Enforced by:* review only, with grep-able discovery recipes (the reflection `Type`-tree reads;
the `org.jooq` imports). A meta-test pinning each containment set is a candidate roadmap item.

=== Narrow component types over broad interfaces

[carried verbatim: § "Narrow component types over broad interfaces", both paragraphs]

*Enforced by:* the compiler ; the narrowed component type is the contract.

=== Sub-taxonomies for resolution outcomes

[carried verbatim: § "Sub-taxonomies for resolution outcomes", both paragraphs]

*Enforced by:* review only (the one-line justification note at proposal time; the
milestone-boundary collapse audit).

=== Builder-internal hierarchies are ephemeral; model hierarchies are carried

"Carry the decision as a type" answers *how*; this corollary answers *where the type lives*, and
the two answers are opposites for different jobs. A model-level hierarchy is carried all the way
to the generator. A builder-internal hierarchy exists to structure a complex multi-target
classification and is discarded before the model: generators never see it.

[carried verbatim: § "Builder-internal sealed hierarchies for multi-target classification", the
`ArgumentRef` exemplar paragraph and the key-distinction paragraph]

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

[carried verbatim: the axes paragraph from § "Sealed hierarchies over enums" (the
DataLoader-backed worked example with the dispatch-axes xref, the R431 forward note, and the
god-accessor / spliced-permit smell), extended with one smell clause: "or a record component
another component already determines".]

*Enforced by:* the compiler where cross-axis invariants live in compact constructors; review at
model-design time otherwise.

=== Capability interfaces and sealed switches serve different roles

[carried verbatim: § "Capability interfaces and sealed switches serve different roles", both
paragraphs including the R222 `MethodBackedField` forward note]

*Enforced by:* the compiler ; capabilities relocate exhaustiveness bookkeeping, sealed switches
keep it.

=== Directives carry only what the SDL author needs to say

This is the authoring-surface instance of the cross-product smell: an input wrapper whose slots
are independently optional hands the author a permit cross-product to navigate, and the site
already determines most of the axes.

[carried verbatim: § "Directives carry only what the SDL author needs to say", all three
paragraphs (`@field(name:)` worked example, `ExternalCodeReference` counter-example)]

*Enforced by:* review only, at directive-design time.

== Boundaries decode and encode; the interior is typed

*Opaque wire formats (Relay NodeId base64 strings, Relay cursor strings, federation `_Any`
representations) decode at the DataFetcher boundary into typed column tuples; the projection layer
encodes back into wire format only at the same boundary; variants representing the wire shape
don't survive in the model.*

[carried verbatim: § "Wire-format encoding is a boundary concern, never a model concern", the R50
worked-example paragraph, the connection-cursor / `_Any` pattern paragraph, and the general-rule
paragraph (skip-vs-throw and direction classified at the boundary; the "this is a NodeId" marker
smell)]

*Enforced by:* review only; the R50 regression surface is pipeline-tier (a wire-shape carrier
reintroduced into the model has no typed home to land in).

=== Model metadata over parallel type systems

[carried verbatim: § "Model metadata over parallel type systems", all three paragraphs
(`OrderByResult` / cursor type-tag exemplar and the general principle)]

*Enforced by:* review only.

=== Wire boundaries are typed adapter / composer pairs

[carried verbatim: § "Wire boundaries are typed adapter / composer pairs", all three paragraphs
(`QueryConditions` worked example, the asymmetric-typing smell with R79, the generalisation)]

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

=== Rejections: validator mirrors classifier invariants

[carried verbatim: § "Validator mirrors classifier invariants", both paragraphs (four-way dispatch
partition, `ValidateMojo`, and the extension rule)]

*Enforced by:* `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` (partition
exhaustive and disjoint); `ValidateMojo` fails the build on stubbed variants.

=== Acceptances: classifier guarantees shape emitter assumptions

[carried verbatim: § "Classifier guarantees shape emitter assumptions", all paragraphs: the
three-anchor list, the two compressed worked examples (R240, R239), the `{@link}` linkage
paragraph, and the relax-a-producer audit rule]

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

=== One model, many views

Code generation is the narrowest view of the classified model, not the model itself. Other
consumers (the LSP completion and hover snapshot today) re-source from the same classified facts;
no consumer owns a private model. A view's coverage guarantee lives at its projection seam:
`CatalogBuilder.projectFieldClassification` is the live exemplar ; an exhaustive switch over the
field permits projecting LSP-renderable payload, so a new permit fails compilation until the view
covers it. The smell is a consumer-side shadow taxonomy: a second model maintained by hand to feed
a view, drifting from the real one silently. (Forward note: R333 widens the model these views
project from and names this rule its load-bearing requirement; the coverage switch moves with the
seam.)

*Enforced by:* the compile-checked projection switch at each view's seam.

=== Principles are stated at altitude

[carried verbatim: § "Principles are stated at altitude", whole section]

*Enforced by:* review; the named-test requirement is the rule itself.

=== Documentation names only live tests/code

[carried verbatim: § "Documentation names only live tests/code", all three paragraphs and the
reviewer-gate sentence]

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

[carried verbatim: § "Generated code is read and debugged", the full concrete-rules bullet list
(explicit types; meaningful locals; statement form; expression-position helpers; the `__`
mega-bullet) and the closing smell paragraph with its R260/R334 citation]

*Enforced by:* `GeneratedSourcesLintTest.emittedSourcesDoNotUseVar` and
`GeneratedSourcesLintTest.emittedSourcesHaveNoDunderIdentifiers` (plus that class's sibling
lints); review for statement form and naming.

=== Generator Java 25; generated output and shipped runtime Java 17

Three categories, three floors. *Generator implementation* (almost everything in the reactor) may
freely use Java 25. *Generated source files* must be valid Java 17: consumers compile Graphitron's
output with their own toolchain. *Hand-written runtime artifacts consumers depend on*
(`graphitron-jakarta-rest` is the first instance) must also target Java 17, for the same reason.
When adding code, ask which category it is in before reaching for syntax.

*Enforced by:* the parent pom's `requireJavaVersion` enforcer (generator floor);
`graphitron-sakila-example` compiling with `<release>17</release>` (emitted-syntax ceiling);
`graphitron-jakarta-rest`'s own `<release>17</release>` main compile (runtime category).

'''

== Emitter Conventions

[carried verbatim: the entire § "Emitter Conventions" half: Return types / Selection-aware
queries / Error quality / Column value binding `DSL.val` / DTO-parent batching / Helper-locality,
unchanged]

'''

== Constraints

Facts with existing enforcers, recorded here so the axioms above stay principles:

- The repo root `pom.xml` is a single self-contained Maven reactor: `mvn install` on a clean local
  repo builds every module (the root pom's `<modules>` list is the live inventory) with no
  dependency outside the pinned third-party set. The legacy `graphitron-parent` generator is
  retired; `graphitron-maven-plugin` is the consumer entry point.
````

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

1. Splice the `[carried verbatim: ...]` blocks from the live doc at their marked positions;
   everything else is replaced by the text above. Re-check at cutover time that the live doc has
   not changed under this spec (R433 is In Review on the same file); if it has, re-derive the
   verbatim splices from the then-current text.
2. Verify none of R433's fixes regress: the corrected `Type`-tree discovery recipe, the
   `<modules>` pointer, no unguarded counts, no `TextMapLookup`.
3. Xref sweep over the source tree (not `docs/target/`, not historical records in
   `roadmap/changelog.md` / `roadmap/audits/`): `docs/architecture/index.adoc`,
   `explanation/index.adoc`, `explanation/dispatch-axes.adoc`, `explanation/typed-rejection.adoc`,
   `how-to/testing.adoc`, `reference/argument-resolution.adoc`,
   `reference/code-generation-triggers.adoc`, `manual/reference/directives/asConnection.adoc`,
   `.claude/agents/principles-architect.md`, `.claude/skills/reviewer-prompt/SKILL.md`,
   `.claude/skills/srp/SKILL.md`, plus live roadmap items that cite sections by name. File-level
   xrefs survive; section-anchor citations get retargeted to the new headings.
4. Full `mvn install -Plocal-db` green (docs render; `check-adoc-tables`; doc-coverage tests).

## Open questions for Spec review

- "One model, many views" is drafted as a corollary of the enforcement axiom; the alternative is a
  sixth top-level axiom. Corollary chosen because its enforcement story (compile-checked
  projection switch) is the axiom's anatomy at its purest, but reviewer may disagree on rank.
- The Emitter Conventions catalogue stays in-doc (drafted) versus moving to its own reference
  page. In-doc chosen to limit churn and keep axiom 5's catalogue adjacent; a later item can still
  extract it.
- Axiom headline sections that state a rule directly (axioms 2, 3, 4) carry their own
  `*Enforced by:*` lines; axioms 1 and 5 delegate to their corollaries. Reviewer should check this
  reads consistently rather than accidentally.
