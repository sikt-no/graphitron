---
id: R17
title: Annotated walkthrough of a generated file
status: Backlog
bucket: cleanup
priority: 7
theme: docs
depends-on: []
---

# Annotated walkthrough of a generated file

Today's docs cover the input side (schema → classification → variant) and
the model side (sealed hierarchy, capability interfaces, design principles)
but a contributor reading them never sees a complete generated file
explained section by section. The mental model "this is what the output
looks like" gets reconstructed from grepping
`graphitron-sakila-example/target/generated-sources/graphitron/`.

## Scope

Add `docs/architecture/explanation/generated-output-walkthrough.adoc` (the
explanation quadrant of the Diataxis layout introduced by R182) containing a
frozen snippet of one small generated file, annotated section by section.

Candidate fixtures (pick one — small enough to fit in a doc, real enough to
illustrate the spine):

- A `*Fetchers` class for a basic `@table` type (e.g. Sakila `Language`):
  the simplest end-to-end shape, covering the `graphitronContext` helper,
  one table-field DataLoader fetcher, and the `wiring()`-equivalent
  registrations.
- A `*Type` class with `$fields(sel, table, env)` showing selection-aware
  column projection.
- A `*Conditions` class showing a generated WHERE predicate and a
  user-`@condition` method call.

Annotations should explain "this section corresponds to variant X / generator
method Y" rather than restate what the code does.

## Maintenance

Generated code drifts. Two ways to keep this honest:

- Mark the snippet "frozen at commit `<sha>`" and link to the live generator
  test that produces equivalent output today (the pipeline tests in
  `graphitron`, or the compiled output in `graphitron-sakila-example`).
- Or pin the exact bytes via a fixture path and a small ratchet test that
  compares the doc snippet against the live generator output.

The pin-via-test approach is more work but prevents silent drift; the
frozen-with-sha approach is lighter and probably sufficient for an
on-ramp doc that is consulted once.

## Coordinates with

- The doc-as-index work (formerly `docs-as-index-into-tests`, R8, retired
  into R279 and now owned by R281's classification corpus) is about pointing
  the classification reference
  (`docs/architecture/reference/code-generation-triggers.adoc`) at tests;
  this is about pointing the on-ramp doc at one frozen example. Different
  doc, different audience.
- The pipeline tour shipped by `rewrite-docs-entrypoint` (R28, Done) lives at
  `docs/architecture/explanation/pipeline-overview.adoc`; this walkthrough is
  the natural "click into the output side" follow-up from there.
