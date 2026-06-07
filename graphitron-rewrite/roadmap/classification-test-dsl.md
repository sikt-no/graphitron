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

A test-only directive carries the expectation on the schema element it classifies, with the rule
stated in the GraphQL description:

```graphql
type Query {
  """A root field with no directives on its arguments, returning a @table-bound type, is a QueryTableField."""
  films: [Film] @expectClassification(is: "QueryTableField")
}

type Film @table(name: "Film") @expectClassification(is: "TableType") { ... }
```

The harness parses the fixture, runs the classifier, and asserts each annotated coordinate's
sealed-leaf verdict equals the `is:` value. The SDL is the example, the directive is the
assertion, the description is the spec sentence. This is the declarative form of R222's
unit-test claim ("parse a fragment, run the producer, assert on the sealed result") and is
idiomatic with the existing `@capability` / `@exemplifies` SDL-annotation directives that tooling
already reads without runtime meaning.

## Design forks to settle at Spec

- **Own item, built against today's classifier (not an R279 slice).** The directive asserts
  verdicts that exist now, so this does not depend on R279's inversion. Landing it first makes it
  the readable behavioural net the inversion rides, which *strengthens* R279's merge-gate posture
  (its primary tier becomes this corpus). R279 is the first consumer, not the owner.
- **Replace the enum truth table, do not run beside it.** Adding the DSL on top of the ~398 enum
  rows recreates the exact duplication R8 fought; the value lands only when the fixture corpus
  *becomes* the truth table and the enum rows retire. That migration (plus rewiring
  `VariantCoverageTest`) is the bulk of the cost. Slice accordingly: harness + directive + a few
  exemplar fixtures + a coverage meta-test first; bulk migration as a named follow-on.
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

## Relationship to R279

R279 (`field-first-classification-driver`) is the first consumer and the motivating context.
Cross-reference both ways once this reaches Spec: R279's merge-gate posture and slice 0 (doc
absorption) both lean on this corpus. No code dependency in the other direction, this can land
independently and against the current classifier.
