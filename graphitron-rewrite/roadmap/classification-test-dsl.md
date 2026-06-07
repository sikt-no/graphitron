---
id: R281
title: "Classification test DSL: @expectClassification spec-by-example"
status: Backlog
bucket: testing
priority: 4
theme: testing
depends-on: []
created: 2026-06-07
last-updated: 2026-06-07
---

# Classification test DSL: @expectClassification spec-by-example

Classification behaviour today is specified twice: as prose in `code-generation-triggers.adoc`
(the variant tables) and as ~398 enum rows in `GraphitronSchemaBuilderTest` (the executable truth
table). The two drift, and neither reads as a *specification by example*: you cannot look at one
artifact and see "this schema shape produces this verdict, and here is the rule in a sentence."
R8 (`docs-as-index-into-tests`, discarded into R279) wanted to make the doc a map into the tests;
this item proposes the cleaner collapse, make the test fixture itself the readable spec, by
embedding the expected classification in the SDL fixture as a directive.

## The shape

The corpus is **one or more annotated schemas** (SDL, plus reflection-backed Java fixtures for the
`@service` / `@tableMethod` examples). Each test case lives inside a *larger* schema rather than as
an isolated fragment, so it is classified in realistic context (reachability, participants, and FKs
resolve); using several schemas is fine and is how conflicting setups are kept apart (see open
questions). Every example parses, that is an invariant graphql-java gates, so a non-parsing example
does not exist. Each element whose classification is specified carries an inline test-only directive;
the description states the rule:

```graphql
type Query {
  """A root field with no directives on its arguments, returning a @table-bound type, is a QueryTableField."""
  films: [Film] @expectClassification(is: "QueryTableField")
}

type Film @table(name: "Film") @expectClassification(is: "TableType") { ... }
```

The harness parses each schema, runs the classifier, and asserts each annotated coordinate's
sealed-leaf verdict equals its `is:` value. The SDL is the example, the directive is the assertion,
the description is the spec sentence, the declarative form of R222's unit-test claim and idiomatic
with the existing `@capability` / `@exemplifies` annotation directives. Classifying a schema in one
run keeps its examples mutually consistent (FKs resolve, participants exist) and contains the
deliberately-unclassified examples (the harness classifies-and-inspects; it does not run the
validator-rejects path), so `@expectClassification(is: "UnclassifiedField")` is a first-class case.

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
  *becomes* the truth table and the enum rows retire. That migration (plus rewiring
  `VariantCoverageTest`) is the bulk of the cost. Slice accordingly (see Slicing below).
- **Coverage derived from the corpus.** `VariantCoverageTest.everySealedLeafHasAClassificationCase`
  becomes "every `FieldClassification` / `GraphitronType` sealed leaf is asserted by some fixture",
  with the covered set computed from the fixtures' `is:` values, harder to game than the enum scan.
- **Directive mechanics.** `@expectClassification` is test-only: declared in the test schema,
  ignored by the classifier, read only by the harness, and must never leak into the auto-injected
  `directives.graphqls`. It asserts the sealed leaf name; a second argument should be able to
  assert the rejection code for the `UnclassifiedField` / `UnclassifiedType` cases (and later
  R222's `WalkerResult.Err`), since the *reason* a thing fails to classify is part of the spec.
- **Doc renders from fixtures (the R279 slice-0 payoff).** Once the corpus exists, R279's absorbed
  `code-generation-triggers` page can render its taxonomy from the fixtures (variant → asserting
  fixture → its description), the truest form of "doc as a map into the tests." This is the
  coupling point with R279 slice 0; the two should reference each other.

## Open design questions (mechanical, for Spec)

- **Internal-vs-real directive partition.** The projector needs an explicit set of test/enrichment
  directives to omit (`@expectClassification`, and any future enrichment directive); `@table` /
  `@service` / `@node` etc. are the rule being demonstrated and must render.
- **Partitioning across schemas.** A single schema cannot hold two shapes of the same concept (a
  `Film` with `@table` and a `Film` without, to show two verdicts); since multiple corpora are
  allowed, such conflicting examples live in separate schemas. This is a partitioning decision, not a
  scaling wall. Open: the partitioning convention (per broad area, per conflicting-shape cluster) and
  keeping type names prose-readable, since the query-view renders real names.
- **Companion Java mechanism (direction found).** Reflection-backed examples (`@service` /
  `@tableMethod`) need real, compiled Java the classifier reflects against and that prose can show
  without copy-paste drift. The JVM-native facility is **JEP 413's `@snippet` tag** (Java 18+):
  external snippet files in a `snippet-files/` directory (or on `--snippet-path`), referenced by
  named region (`// @start region="r"` ... `// @end` in the source, pulled via
  `{@snippet class="Fixture" region="r"}`). But `@snippet` renders into *Javadoc*, and graphitron's
  contributor docs are *AsciiDoc*; the direct equivalent already in the toolchain is AsciiDoctor
  `include::Fixture.java[tags=r]` over `// tag::r[]` regions. Either way the honesty guarantee comes
  from the fixtures being real test sources the build compiles and the classifier reflects against,
  not from the snippet tool, which only governs how prose references them. Note the asymmetry with
  SDL: SDL examples are *projected* (the query-as-view regenerates minimal SDL), whereas Java
  fixtures are *excerpted verbatim* by region, you can project a schema subgraph but you show Java
  as-written. Decision for Spec: AsciiDoc `include` regions (matches the AsciiDoc docs) vs `@snippet`
  (if any of this also surfaces in Javadoc); reuse `GraphitronSchemaBuilderTest`'s fixtures where possible.

## Slicing (recommended phasing)

The corpus is the source of truth and the prose is a view over it, so prose genuinely follows the
corpus (you cannot reference examples that do not exist). But the test side should not land as a
single big-bang conversion:

1. **Thin vertical slice, end to end.** The `@expectClassification` directive + harness + coverage
   meta-test + a handful of exemplar examples in a small corpus, running *alongside* the existing
   enum truth table (transitional coexistence, not the permanent duplication the fork above rejects),
   plus a prototype of the query-as-view renderer (query/fragment -> projected SDL, internal
   directives stripped) over those few examples. This proves both sides at small scale and, more
   importantly, surfaces the authoring constraints (real names must read well since they render
   verbatim; an example must be selectable via a query/fragment) *before* the expensive grind. The
   closure rule already pins most of what projects, so the renderer prototype is cheap insurance,
   not a hard gate.
2. **Documentation-first; migrate while documenting.** Drive the migration from the prose: writing
   the documentation (this *is* R279 slice 0's `code-generation-triggers` absorption) pulls each
   example it needs into the corpus, authored with `@expectClassification` and rendered via the
   query-as-view, and the corresponding enum row is deleted as the example lands. Duplication with
   the enum table is therefore transient per example, and the table shrinks as the doc grows. Every
   migrated example arrives with a documentation home and a written rationale, and the order is
   pedagogical rather than enum-declaration order.
3. **Coverage sweep, then enum retirement.** Documentation is curated: it covers the explainable
   majority, not necessarily every one of the ~398 sealed leaves. The coverage meta-test surfaces
   the untested long tail; migrate those as corpus entries (tested, not necessarily prose-featured),
   then rewire `VariantCoverageTest` onto the corpus and delete the now-empty enum table. So every
   doc example is a corpus example, but not every corpus example is in the doc (the swept tail).
   Completion here (full corpus coverage, enum table retired) is the milestone that lets R279's merge
   gate adopt the corpus as its primary tier.

## Relationship to R279

R279 (`field-first-classification-driver`) is the first consumer and the motivating context.
Under the documentation-first phasing above, R281's phase 2 *is* R279 slice 0: writing the absorbed
`code-generation-triggers` prose is what pulls the migration, so the two are one interleaved effort
rather than sequential items, and R279 slice 0 depends on R281's thin slice (harness + renderer)
landing first. R279's merge gate adopts the corpus as its primary tier only at phase 3 completion
(full coverage, enum table retired). Cross-reference both ways once this reaches Spec. No code
dependency in the other direction: the corpus and renderer build against the current classifier.
