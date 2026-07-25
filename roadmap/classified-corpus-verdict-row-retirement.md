---
id: R539
title: "Finish retiring pure-verdict GraphitronSchemaBuilderTest rows onto the classified corpus"
status: Backlog
bucket: testing
theme: testing
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Finish retiring pure-verdict GraphitronSchemaBuilderTest rows onto the classified corpus

The `@classified` spec-by-example corpus shipped with an explicit purpose: replace the doc-prose-plus-truth-table double specification of classification behaviour with an annotated SDL corpus that *is* the readable spec. The corpus half landed and is load-bearing (`ClassifiedCorpus` carries 47 examples, `ClassifiedDslTest` pins every dimension arm or names it as a known gap, `ClassifiedDocTest` guards the `code-generation-triggers` page against drift). The retirement half stalled. `GraphitronSchemaBuilderTest` is still ~11,700 lines across 35 `ClassificationCase` enums holding ~400 constants, and the pure-verdict rows among them, the ones asserting only "this coordinate is an instance of this sealed leaf", remain the second specification the corpus was meant to absorb. Every one that survives is a row a contributor keeps in sync by hand when a leaf moves, with no reader benefit over the corpus fixture asserting the same verdict in SDL.

The blocker is that the deletion whitelist is no longer usable. `roadmap/audits/classification-test-dsl-inventory.md` bucketed all 407 then-current constants into PURE (35, retirement-eligible) / SLOT (170) / REJECTION (178) / INPUT (24) and set the rule "a row not listed here does not retire, full stop." That inventory now carries a superseded banner: it predates the classification reshapes that split and renamed leaves and the corpus recut onto the `(source, operation, target)` axes, so its row list matches neither the live enum nor the live corpus coordinates. The staleness audits have flagged it since 2026-07-10. What remains of the effort is a Claude skill (`classified-corpus`, encoding the per-verdict migration loop) pointing at an audit that tells its reader not to trust it, and no roadmap item tracking the work. That combination is why the grind stopped: any session picking it up must first redo the classification pass before it can delete a single row, and nothing said whose job that was.

Two things this item owes, in order. First, re-derive the bucketing against the current `GraphitronSchemaBuilderTest` enum and the current corpus, producing a fresh whitelist that supersedes the banner-flagged one (the PURE / SLOT / REJECTION / INPUT axes still look right; what changed is which rows land in which bucket). Second, run the migration loop to completion over that whitelist, per verdict: author or extend the corpus fixture, confirm the new coordinate classifies to the exact leaf the row asserts against the harness's per-coordinate leaf record rather than a green `VariantCoverageTest` (whose union net is one-way and will not catch a lost verdict), render the doc block, then delete the row. Whether the re-derivation lands as an updated audit file or moves into a build-checked form is a Spec-time decision; the current audit's staleness is itself the argument for preferring something the build can keep honest.

Scope boundary worth stating up front, since it is what keeps this item small: only pure-verdict rows retire. SLOT rows assert accessor detail that is the pipeline tier's job, REJECTION rows assert failure paths the corpus deliberately does not model, and INPUT rows cover input-side classification the corpus does not claim. Those three buckets stay in `GraphitronSchemaBuilderTest` and this item does not touch them. Nor does it change how classification works, add corpus examples for their own sake, or reopen the known-gap list in `ClassifiedDslTest`.
