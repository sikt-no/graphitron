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

## The shape

The corpus is **one or more annotated schemas** (SDL, plus reflection-backed Java fixtures for the
`@service` / `@tableMethod` examples). Each test case lives inside a *larger* schema rather than as
an isolated fragment, so it is classified in realistic context (reachability, participants, and FKs
resolve); using several schemas is fine and is how conflicting setups are kept apart (see
Mechanical decisions below). Every example parses, that is an invariant graphql-java gates, so a non-parsing example
does not exist. Each element whose classification is specified carries an inline test-only directive
naming its verdict; the description states the rule:

```graphql
type Query {
  """A root field with no directives on its arguments, returning a @table-bound type, is a QueryTableField."""
  films: [Film] @classified(as: QueryTableField)
}

type Film @table(name: "Film") @classifiedType(as: TableType) { ... }
```

The harness parses each schema, runs the classifier, and asserts each annotated coordinate's
sealed-leaf verdict equals its `as:` value. The SDL is the example, the directive is the assertion,
the description is the spec sentence: the declarative form of R222's unit-test claim. Classifying a
schema in one run keeps its examples mutually consistent (FKs resolve, participants exist). The
corpus asserts *successful* classification only; classification failure (the `UnclassifiedField` /
`UnclassifiedType` leaves, and the reason a coordinate fails to classify) is out of scope here and
gets a separate test mechanism, see [Out of scope](#out-of-scope) and
[Relationship to R222](#relationship-to-r222).

## Classification directives

Two test-only directives, split by what they classify, each carrying a GraphQL enum so the verdict is
validated SDL-side (a typo is a parse error graphql-java rejects before the harness runs) and
autocompletes in an editor:

- `@classified(as: FieldVerdict!) on FIELD_DEFINITION` asserts the `OutputField` sealed leaf an
  output-field coordinate classifies to. This is the prevalent directive (most annotated coordinates
  are output fields), so it is short.
- `@classifiedType(as: TypeVerdict!) on OBJECT | INTERFACE | UNION | INPUT_OBJECT | ENUM | SCALAR`
  asserts the `GraphitronType` sealed leaf a type classifies to.

Input-field classification is out of scope (see [Out of scope](#out-of-scope)): an input field is
interpreted relative to the output field it feeds, a different game. So `@classified` sits only on
output `FIELD_DEFINITION` coordinates, and `FieldVerdict` mirrors `OutputField`, not the full
`GraphitronField` hierarchy.

`FieldVerdict` enumerates the sealed leaves of `OutputField` (the `RootField` and `ChildField`
families); `TypeVerdict` enumerates the leaves of `GraphitronType` minus the failure leaf
`UnclassifiedType`. A meta-test asserts each enum's constant set equals its hierarchy's leaf set:
adding a sealed leaf without a matching enum constant fails the build. This is the
validator-mirrors-classifier discipline turned on the test DSL itself, and it is what makes "coverage
derived from the corpus" (below) harder to game than the old reflective enum scan, the enum *is* the
leaf set by construction. With input fields out, output-field leaf simple names are unique (the
`CompositeColumnField` / `CompositeColumnReferenceField` collisions live on the `InputField` side),
so the enum constants are the plain leaf simple names, no disambiguation needed.

**On distinguishing the two directives by capitalization (`@classified` vs `@Classified`): recommend
against.** Two directives differing only in the case of their first letter is a scan-and-typo footgun,
and a leading-uppercase directive breaks the lowercase-leading convention every other GraphQL
directive follows (`@deprecated`, `@skip`, and graphitron's own `@table` / `@field` / `@service` /
`@node`), so `@Classified` reads like a type reference rather than a directive. A distinct,
self-describing name carries the field/type split without that risk; types are far less numerous than
fields, so the extra characters in `@classifiedType` cost nothing while the prevalent `@classified`
stays short.

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
  the verdict closes over, the node, its directives, its parent's, and its target's directives and
  participants. Honour that closure and the displayed excerpt actually classifies to the asserted
  verdict; the downward-context examples (a record child field needing its `@service` ancestor's
  backing class) stay honest because the query selects through the ancestor.

So a rendered example is a faithful *excerpt* of a coordinate classified in full-corpus context, not
necessarily a standalone repro; honouring the closure rule is what keeps the excerpt from lying.

## Design forks to settle at Spec

- **Own item, built against today's classifier (not an R279 slice).** The directive asserts
  verdicts that exist now, so this does not depend on R279's inversion. Landing it first makes it
  the readable behavioural net the inversion rides, which *strengthens* R279's merge-gate posture
  (its primary tier becomes this corpus). R279 is the first consumer, not the owner.
- **Replace the enum truth table, do not run beside it.** Adding the DSL on top of the ~398 enum
  rows recreates the exact duplication R8 fought; the value lands only when the fixture corpus
  *becomes* the truth table and the migrated enum rows retire. Scope note: the output-field and type
  rows migrate; input-field classification rows stay in the enum table as their own game (out of
  scope here). That migration (plus rewiring `VariantCoverageTest`) is the bulk of the cost. Slice
  accordingly (see Slicing below).
- **Coverage derived from the corpus.** `VariantCoverageTest.everySealedLeafHasAClassificationCase`
  walks the `GraphitronField` / `GraphitronType` roots today; the corpus-backed coverage is "every
  `OutputField` / `GraphitronType` sealed leaf (excluding `UnclassifiedType`) is asserted by some
  fixture", with the covered set computed from the fixtures' `as:` values resolved to leaf classes.
  `InputField` leaves and the failure leaves stay covered by their existing mechanism (see Out of
  scope), so only the output-field and type portion moves onto the corpus. The enum-to-leaf meta-test
  above is what makes the migrated portion harder to game than the old reflective enum scan.
- **Directive mechanics.** Both directives are test-only: declared in the test schema, ignored by the
  classifier, read only by the harness, and must never leak into the auto-injected
  `directives.graphqls`. See [Classification directives](#classification-directives) for the
  enum-typed shape and the naming decision. Asserting the *rejection code* (the reason a coordinate
  fails to classify) is deliberately out of scope for this mechanism (see
  [Out of scope](#out-of-scope)): failure is a separate test type, and post-R222 it becomes a
  `WalkerResult.Err` assertion rather than an `as:`-style leaf.
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
  [Relationship to R222](#relationship-to-r222)).
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

1. **Thin vertical slice, end to end.** The `@classified` / `@classifiedType` directives (with their
   `FieldVerdict` / `TypeVerdict` enums and the enum-to-leaf meta-test) + harness + coverage
   meta-test + a handful of exemplar examples in a small corpus, running *alongside* the existing
   enum truth table (transitional coexistence, not the permanent duplication the fork above rejects),
   plus a prototype of the query-as-view renderer (query/fragment -> projected SDL, internal
   directives stripped) over those few examples. This proves both sides at small scale and, more
   importantly, surfaces the authoring constraints (real names must read well since they render
   verbatim; an example must be selectable via a query/fragment) *before* the expensive grind. The
   closure rule already pins most of what projects, so the renderer prototype is cheap insurance,
   not a hard gate.
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
   Completion here (full output-field and type corpus coverage) is the milestone that lets R279's merge
   gate adopt the corpus as its primary tier.

## Relationship to R279

R279 (`field-first-classification-driver`) is the first consumer and the motivating context.
**R281 has eaten R279's former slice 0**: the entire documentation deliverable (the
`code-generation-triggers` absorption, R8's doc-as-index intent, the stale-ref cleanup) lives here,
and R279 is now purely the driver-restructure code work. The coupling that remains: R279's slices 3
and 6 (the inversion and orphan pruning) require corresponding updates to this doc when they land,
and R279's merge gate adopts the corpus as its primary tier only at phase 3 completion (full
coverage, enum table retired). No code dependency in the other direction: the corpus and renderer
build against the current classifier, so R281 can land independently and ahead of R279's inversion.

## Relationship to R222

R222 (`dimensional-model-pivot`, Spec) restructures classification into a `Walker<S, C>` producing a
sealed `WalkerResult<C>`, and its Stage 4 retires `UnclassifiedType` / `UnclassifiedField` into
`WalkerResult.Err`, narrowing `GraphitronSchemaValidator`. Two consequences shaped this spec:

- **The success-verdict taxonomy survives; the failure representation does not.** R222 changes how
  classification is *driven* and how *failure* is represented, but the positive leaves of
  `OutputField` / `GraphitronType` persist as carrier types. A corpus that asserts only successful
  verdicts via `@classified` / `@classifiedType` is therefore forward-compatible with R222: it does
  not get rebuilt when the walker lands.
- **So failure is deliberately out of scope.** Anchoring `UnclassifiedField` / `UnclassifiedType` or
  rejection codes through this mechanism would couple the corpus to exactly the part R222 removes. The
  enum-to-leaf meta-test excludes the two failure leaves for the same reason, and that exclusion is
  the natural seam where the future `WalkerResult.Err` test type takes over.

R281 builds on today's classifier and lands independently of and ahead of R222; the forward
compatibility above is a design constraint honoured now, not a dependency.
