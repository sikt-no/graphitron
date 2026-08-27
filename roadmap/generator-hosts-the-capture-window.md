---
id: R864
title: "The generator hosts the capture window instead of taking a store"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The generator hosts the capture window instead of taking a store

The generator does not take a fact store as an input. It opens one, fills it, and runs the rest of
itself inside the window while it is open. `GraphQLRewriteGenerator.runPipeline` calls
`captureAndRead`, which calls `FactCapture.runAndRead` with a lambda, and validation and
`EmitPlan.produce` both run inside that lambda. So the arrow points the wrong way: capture is not a
step that produces a store for the generator, it is a scope that the generator's later phases are
nested inside.

The receiving half of the shape everyone would expect already exists elsewhere in the tree. Every
language server fact reader takes a `StoreHandle` as an ordinary parameter (`DeclarationFacts.of`,
`CatalogColumns.of`, `DirectiveSurface.load`), and `CapturedStore` in the test tier opens a store,
calls `FactCapture.capture` into it, and queries it afterwards. The build path is the one caller that
cannot be handed a store, because it is the caller that insists on making one.

## What this costs today

**The dev session opens the store twice and then has to guess.** `DevMojo` opens its own long-lived
`sessionStore` for the language server, the MCP server and the diagnostics writers. Each generator
pass inside that session opens a second store of its own, because `FactCapture.runInternal` is where
`GraphitronModelStore.openAt` gets called and no caller can substitute a handle. The session then
calls `Materializations.refreshAll` at start, and its own comment says why: the session cannot know
whether the pass that just ran captured into the same file. That is a defensive refresh caused
entirely by the ownership split, not by anything about the facts.

**Nothing can be re-run against rows that already exist.** There is no way to capture once and then
plan repeatedly, which is what anyone debugging the planner or a producer actually wants. Each
attempt recaptures, and on a large consumer schema that is the difference between an experiment and
an afternoon.

**`FactCapture`'s entry points grow with the generator's inputs.** The class publishes five
`run` / `runAndRead` / `runWithDetections` overloads and four `capture` overloads, and
`runAndRead` takes eleven arguments. `SubjectConfig` was extracted precisely to stop one family of
parameters accumulating there; the seven remaining positional arguments are the same pressure
arriving from the other direction, because every input the generator gains has to be threaded through
capture to reach the store.

## What actually blocks the inversion

One back-edge, and it is worth naming precisely because it is the reason the obvious refactor has not
already happened. Capture cannot run before the classification walk today, because
`FactCapture.detect` writes `walk_type_backing_class` from a `ClassifiedRun.Present` the generator
builds out of its walked model. That is a real dependency of capture on the generator, so the two
cannot simply be resequenced.

It is also a dependency that is already scheduled to die. That relation is a declared-transitional
shadow, held so the store-native backing derivation has something inside the store to diff against,
and its family header carries the removal criterion. So the question this item has to answer is one
of sequencing rather than of design: whether the inversion waits for that relation to drain, or
whether the walk-side write is lifted out of `detect` into its own step the caller sequences, which
would let the store become an input before the shadow retires.

## What changes when this lands

A caller opens a store and hands the generator a handle. `DevMojo` opens once instead of twice and
its defensive `refreshAll` becomes either unnecessary or provably necessary. A test or a debugging
session can capture once and plan many times against the same rows. `FactCapture` stops being the
place every generator input has to pass through on its way to a database, and stops needing a new
overload whenever the generator learns something new.

Nothing about what the store holds changes, and no generated output changes. This is about who owns
the store's lifetime, not about its contents.

