---
id: R840
title: "The corpus becomes a folder of self-describing, fact-first SDL documents"
status: Backlog
bucket: architecture
priority: 4
theme: testing
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The corpus becomes a folder of self-describing, fact-first SDL documents

The spec-by-example corpus is the right idea in the wrong container, asserting in a dying
vocabulary. The corpus (`ClassifiedCorpus`) holds 56 annotated fixture schemas that are
simultaneously tests (`ClassifiedDslTest`) and the source of the worked examples on
`reference/code-generation-triggers.adoc`. Two defects, one per half of that sentence.

The container is Java. Every new example is an edit to a hand-maintained Java list, featuring it
on the page is a manual paste loop (run the drift test, copy the block from the failure message,
paste), and the example's own story lives in three parallel habitats: a Java comment nothing
renders, `#` comments in the projection query, and hand prose on the page. The failure criterion
this item exists to close: Java gets written when the vocabulary changes, never when the corpus
grows. If adding a schema document forces a `.java` edit, the vocabulary has leaked into the
container.

The assertion vocabulary is the transitional walk's. `@classified` / `@classifiedType` declare
the walk's dimensional tuple, `ClassifiedHarness` runs "today's classifier" through
`GraphitronSchemaBuilder` (on R682's terminal-deletion list), and the test prelude's enums are
hand-copied mirrors of the walk's sealed enums. R682 already schedules the recast ("onto store
relations and emitted output, or retire with the walk") and names the classified-corpus programme
as the mechanism it follows; what it does not yet have is the successor form.

## The direction (for the Spec to own)

R814's outcome block demonstrated the successor's oracle pair end to end, with no classifier API
anywhere: capture the fixture into a fact store and read the verdict rows
(`intent_resolved_field_claim`), run generation and read the emitted unit and method names. A
fact-first corpus generalises that pair into the assertion model:

- **The container is a folder of self-describing SDL documents.** Each document carries the
  fixture, its projection operation, its purpose as descriptions (capture already transcribes
  descriptions into `graphql_` rows, so the prose becomes store-queryable data for free), and its
  assertions as directives. A loader walks the folder with an anti-vacuity floor; every reader
  reads the loaded corpus, and nothing re-lists the directory to answer "what exists". Documents
  are executed, never surveyed: each is loaded, classified, captured, generated, and rendered,
  which is the line separating this from the fixture-folder grep that once produced a false
  published claim (R346's decision 2).
- **Assertions declare expected facts.** A coordinate declares its expected verdict rows (which
  `intent_` relation, which classifier value, which tier) instead of the walk tuple; the harness
  becomes capture-then-compare. The assertion value spaces derive from the store's own closed
  vocabularies rather than a hand-maintained prelude, so even vocabulary growth is DDL, not Java.
- **The rendered documentation is the approval file.** The emitted-names half asserts output
  identity at the names-and-signatures ceiling, and the drift-guarded outcome block already is a
  checked-in expectation in the `ApprovalQueryExampleTest` sense, living on the doc page. This
  item completes the collapse: per-example sections render into staging (generated and never
  committed, the shape that has never drifted and needs no verify guard), the authored page keeps
  only narrative and teaching order via includes, and a document on no page fails the build. Doc
  and test stop being connected artifacts and become the same artifact.

## Constraints and boundaries

- **Axis by axis, behind R682.** Not every tuple axis has a fact spelling yet (the operation
  member arms, target shape, arrival shape); per R814's rule, a verdict with no spelling in a
  surviving vocabulary marks a missing relation, R682's to land. This corpus is the consumer that
  forces each re-keying, and it will also reveal which axes were walk-internal detail never worth
  pinning. `@commits` straddles the command tier R682 reshapes and gets the same scrutiny rather
  than a mechanical port.
- **Ownership boundary with R682.** R682 owns the walk's deletion and the re-key of the
  completeness gates (`VariantCoverageTest`'s corpus obligation must survive on surviving
  vocabularies); this item owns the container, the assertion form, and the doc collapse. Neither
  blocks the other.
- **Register rule.** Descriptions in a consumer-facing schema render into introspection for API
  clients; fixture-purpose prose lives in test documents only.
- **The measurable acceptance form.** The diff for a new worked example is one new schema
  document plus at most one include line placing it on the page, and `git log` can prove it.

The doctrine this instances is a candidate sentence for `development-principles.adoc`: prose is
data with a stated consumer set; both schema languages offer the same ladder (throwaway comment,
attached prose, typed structure) and every claim lives on the highest rung it can reach; capture
makes the SDL-homed rungs queryable on the SQL side; and spec files are executed, never surveyed.
