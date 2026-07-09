---
id: R454
title: "Routine write result shapes: procedures, scalar/void routines, single-node Mutation @routine"
status: Backlog
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Routine write result shapes: procedures, scalar/void routines, single-node Mutation @routine

R451 ships the table-valued write slice: `@routine` plus at least one `@reference` hop on a
Mutation field, where the routine is a `VOLATILE` set-returning function and the response is
the post-commit chain re-read. Everything whose result does not arrive as a table stays
deferred here, with typed `Deferred`s pointing at this item's planSlug:

* **True procedures and scalar / void routines.** jOOQ exposes these through a different
  call surface than table-valued functions: static `Routines` methods taking a
  `Configuration`, with OUT parameters read through getters on a returned routine object
  rather than rows. `JooqCatalog` gained an exists-but-not-table-valued resolution arm in
  R451 so these names are distinguished from typos; actually resolving and calling them is
  this item's work, including how OUT parameters (if any) bind to the GraphQL return shape.
* **Single-node Mutation `@routine`** (no `@reference` hop). With no hop there is no
  post-commit table to anchor a re-read on, so the field's return must bind to something
  else: a void routine's acknowledgement shape, a scalar result, or OUT parameters. That
  story is inseparable from the procedure call surface above, which is why the single-node
  form is carried here rather than in R451.

Open questions for Spec: what return shapes are admitted for each routine kind (void,
scalar, OUT parameters); whether the R429-managed per-field transaction wraps the call the
same way R451's step 1 does; and whether the read-side scalar-function fork deferred at
R300 shares enough of the resolution work to be absorbed or stays separate.
