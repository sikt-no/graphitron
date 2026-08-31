---
id: R865
title: "The generator creates its own fact store, and nothing but a run that consumes a store can fill one"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-31
---

# The generator creates its own fact store, and nothing but a run that consumes a store can fill one

> **Re-premised twice, and retitled twice. This note is the second, 2026-08-31, and it moves the
> item's centre.** What the user restated as the goal is one sentence: a person should be able to
> create a store without generating code, and the generator should be handed a store rather than
> creating one. Store creation is not the generator's to own, any more than it is the language
> server's or the MCP server's, and those two already work the way this item now asks the generator
> to work.
>
> That makes the ownership inversion this item's spine rather than a neighbouring item's
> consequence. It also ends the item's other half rather than relocating it: **there is no way to
> decline the materialization refresh in this item, at any layer.** No `<skipMaterialize>`, no
> cadence parameter on the capture entry point, no property. R876 is why, and the reasoning is under
> "No declinable refresh" below. The previous re-premise is kept under "What the first re-premising
> cost this item", because its accounting is still correct and a reviewer should not have to
> reconstruct it. Sections that these two readings retire say so where they stand.

Two facts about the tree today, and the second is the one that has moved to the front.

**Nothing fills a fact store except a run that consumes one.** Every entry point that captures is a
generation or a validation, so a person who wants the facts has to run the thing that reads them.
`mvn graphitron:validate` comes closest and is not close enough: it fills a store as a side effect
of asking questions of it, and it fails the build when the answers are bad, so it is not a command
that produces a store, it is a command that has an opinion and leaves a store behind.

**The generator creates the store it captures into.** `FactCapture.runInternal` is the only place
`GraphitronModelStore.openAt` is called on the build path, and no caller can substitute a store of
its own. The generator therefore owns a resource lifetime, a graph-ownership check, a retry policy
and a demote-to-memory fallback, none of which is generation. Compare the two other consumers:
`graphitron-lsp` and `graphitron-mcp` create nothing, they take a `StoreHandle` or a `StoreReader`
that `DevMojo` opened, and every fact reader in both modules has the shape `of(StoreHandle store,
...)`. The generator is the one consumer that mints its own.

The two facts are the same defect seen from either end. Because the generator owns store creation,
there is no store to hand a command that only wants to fill one; and because there is no such
command, the generator's ownership has never had to answer for itself.

## What the first re-premising cost this item

Filed against a refresh that appeared never to return. It returns. A capture of the consumer schema
was observed to finish at four hours and nineteen minutes, and the same store re-priced on the
shipping DDL refreshes in **43.0 seconds**, a figure R876 carries. R856 is dissolved into R876; the
evidence is in `roadmap/audits/2026-08-28-derived-read-cost-premise.md`, and R848 reached Done.

Four arguments died there and one was diminished. Naming them rather than leaving a reviewer to find
them.

**Dead: the refresh does not return.** It does.

**Dead: the measurement subtraction.** Running a goal with and without a skip flag was going to
produce the refresh's contribution to a capture, "a figure nobody can state today". It is stated:
the audit prices every position.

**Dead: R848 needs that number.** R848 is Done, having priced the register as a set without it.

**Dead: R856 needs a populated consumer store.** R856 is dissolved, and a populated consumer store
exists and is the audit's instrument.

**Diminished: the debugging hazard.** The exposure is 43 seconds rather than four hours, so a
capture killed mid-refresh is a design objection rather than an operational one, and it does not on
its own buy a parameter. The transaction-shape section below has since been found half wrong on its
own terms as well, and says so there.

**Untouched, and now the item's centre: the ownership defect and the missing command.** Neither was
ever an argument about the clock.

## Vocabulary

A **registration** is a row of `meta_materialize`: a derivation kept as a view under a `_live` name,
plus a table of the same shape under the canonical name that readers spell. The **refresh** is the
pass that empties and refills those tables.

**Store creation** is opening a `GraphitronModelStore` at a directory and deciding what to do when
that fails or when another checkout holds the graph: the open itself, the graph-ownership check, the
retry, and the fallback to a private in-memory store. It is one decision with four parts, and today
all four sit inside a capture call.

A **cadence** is which of the two refresh contracts a caller wants. `Materializations` already
distinguishes them and calls the difference "a real contract, not a convenience": a reader that
opens a store it did not write asks for current targets (`refreshAll`), and a run that captures does
not ask, because its own write implies currency.

## The transaction shape, which is now half true, and the half that survives

The complaint was that the refresh runs inside the capture's transaction, so a capture killed during
it commits **nothing**: not the refreshed tables, which is expected, but also not the SDL rows, the
catalog rows, the classpath census or the capture-cadence derivations. Everything the run
transcribed hostage to a later step that returns nothing to it.

**That is true of one of the two cadences a capture can take, and the tree has since grown the
other.** `FactCapture.capture` refreshes inside the transaction only when the store already holds a
graph. On a store that held none, the facts, the anchor row and the hand-written derivations commit
first and `Materializations.refreshAnalysing` runs afterwards, one committed transaction per
registration, with the source stamps following it. `reconciles` exists because of that split and its
javadoc states it. So the shape this item wanted already exists on the first-graph path, and the
objection is to the other path rather than to capture as such.

**One loose end a reviewer should not have to find.** The first re-premising explained fifteen empty
store files as fifteen people declining to wait, and that explanation was written without the
first-graph cadence in view. A first-graph capture killed during its refresh leaves committed graph
rows, so it cannot produce a file holding zero graphs; either those runs died inside the capture
transaction itself, or they predate the cadence. Nothing in this item's scope turns on which, and no
criterion below depends on it, but the sentence should not be repeated as though it were settled.

What survives is the part that was never about the clock: on the non-first-graph path, a step that
has nothing to do with transcription can still throw away transcription that succeeded. This item
does not move that refresh out of the transaction, and says why under "What is not being proposed".

## What changes when this lands

**Store creation moves out of the generator and becomes the caller's.** One fact-tier entry point
opens the store, runs the ownership check, and returns a sealed outcome rather than a handle plus a
log line. The mojos call it. `GraphQLRewriteGenerator` stops passing a `Path` into
`FactCapture.runAndRead` and starts being handed an open store, which is exactly the shape every
fact reader in `graphitron-lsp` and `graphitron-mcp` already has. Two production call sites read
`ctx.storeDirectory()` today and both are in the generator; after this, none is.

A branch disappears with them. Today the generator carries "and what if there is no store", because
`runInternal` falls back to a private in-memory store when the directory is null or the open
demotes. Once the caller supplies the store, a generation with no store is not a state the generator
can be in, and the fallback is one arm of the opener's outcome instead of a condition threaded
through a pipeline.

**`graphitron` gains a goal whose job is to fill a store and stop.** `mvn graphitron:capture` runs
schema loading, attribution, the classification walk and the capture loads, commits, and does
nothing else: no store-backed detections, no validation, no lint, no plan, no emission. The reason
it is a goal rather than an existing one repurposed is the opinion: `validate` fills a store on the
way to failing your build over your schema, and a command that produces an artifact must not refuse
to produce it because it disliked the input. What this buys is the thing the derived-read-cost audit
could not have: a store anyone can make on demand, rather than one kept from a run that happened to
leave it behind, which is why every figure in R876 rests on one file with a recorded SHA.

**The refresh does not change at all.** A capture the goal runs refreshes the registered targets
exactly as a generating run's capture does, on the same cadence, in the same transaction on the same
path. The store the goal produces is complete, and nothing in this item gives any caller a way to
ask for a store that is not.

**The dev session's second store loses its reason to exist.** `DevMojo` opens a long-lived
`sessionStore` for the language server, the MCP server and the diagnostics writers, and every
generator pass inside that session opens a second handle underneath it, because `runInternal` is the
only opener and no caller can substitute one. After this the session hands its own store to the
pass. This item does not remove the session's defensive `Materializations.refreshAll` at start,
which is R857's; it removes the reason that call cannot know the answer it is defending against.

## What this takes over from R864, and what R864 keeps

R864 moves the fact tier into `graphitron-model` as a module boundary, and listed the ownership
inversion among its consequences: "the generator takes a store", the dev session opening one store,
and the observation that inverting the ownership moves the graph-ownership check and the
retry-then-demote policy rather than just a lifetime. Those three are this item's now, and this item
delivers them without a module moving.

What R864 keeps is the boundary itself, which is the part javac enforces and this item does not
touch: after this item a planner can still import a crawler, and the only thing stopping it is
review. R864 gets cheaper rather than smaller, because the inversion it would have had to perform
mid-move is already done and the move is mechanical against it.

**Direction rather than an edit.** R864 is at Spec in another session's hands. Its body should be
trimmed to stop claiming the inversion before it goes to Ready, and its dependency list does not
need this item: the two are independent and either order works. Tell its author rather than editing
its front-matter from here.

## No declinable refresh

The previous two drafts carried a `<skipMaterialize>` parameter, first as the item's headline and
then as an open question. It is dropped outright, and so is the API-level cadence enum the last
draft proposed to keep in its place. Nothing in this item lets any caller obtain a store whose
registered targets are stale.

**R876 is the reason, and it is a stronger one than "no user needs it".** That item's whole finding
is that the register is a diagnostic rather than a deliverable: an expensive refresh is a modelling
defect, a relation capture never wrote or a join key that exists only as an expression, and where
the defect is fixed the registration has nothing left to buy. Its target is an empty register, and
it states the standard as a test rather than an aspiration: no relation in the consumer read set
refuses a five-second budget with nothing materialized. Against that standard, a switch that turns
the refresh off is the wrong instrument twice over. It treats the register as a cost to be declined
rather than a defect to be removed, and it takes the pressure off exactly the measurement R876 and
R899 need to stay uncomfortable. A refresh anybody wants to skip is a registration that should not
exist, and the fix belongs there.

**What that costs this item, stated rather than glossed.** The first draft's argument for the
parameter was real: `Materializations` distinguishes the writer cadence from the reader cadence and
calls the difference "a real contract, not a convenience", and capture welds the writer cadence in.
That weld survives this item. It is defensible where it stands, because capture writes and currency
is implied by its own write, and the caller that wanted the other contract is the reader that opens
a store it did not fill, which already has `refreshAll` and calls it. What would have made the weld
a defect is a caller who writes and does not want currency, and R876's reading is that no such
caller should exist.

**Consequence for R857.** It depends on this item for a correctness interaction: its rule says the
capture-cadence refresh "stays unconditional and records a claim per partition it refills", which
needed reconciling only with a capture path that could decline. No such path ships, so the rule
stays true as written and R857's dependency on this item can go. R857 is another session's item, so
this is a message to its author rather than an edit from here.

## What is not being proposed

This is not a fix for the refresh being slow. That axis is closed: R876 states it plainly, the pass
is 43.0 seconds, and work proposing to make a registration's refresh cheaper is work against a
43-second pass. Nor is it the transaction-boundary change the dissolved cut-set item tested and
recorded as a dead end, which split the capture into one transaction per registration to give the
planner statistics mid-pass and came back with a ratio of 0.82 against it. This item takes no
position on how many transactions a full capture uses, and it does not hoist the non-first-graph
refresh out of the capture transaction.

It is not the module boundary. Nothing moves between modules here, and after this item a planner
that imports a crawler still compiles. That is R864's, as stated above.

It is not a change to what capture writes, and it is not a way to write less. A store the new goal
produces holds the same rows a generating run's capture writes, refreshed targets included, because
a store missing a relation would not answer the question anyone opens it for. Whether a registration
should exist at all is R876's question and R899's after it, and the answer to an expensive refresh
is to retire the registration rather than to add a switch that skips it.

## The shape

**One fact-tier opener, returning a sealed outcome.** Store creation comes out of
`FactCapture.runInternal` and becomes an entry point whose whole job is to produce a store for a
graph: it opens at the directory, reports what the reaper released, decides whether this run may
write under its graph name, and returns which of those happened as a value. The arms are the states
`runInternal` currently expresses as a boolean plus a warning: a shared store this run owns, and a
private in-memory store with the reason it was demoted to one. `ownsGraph`'s javadoc says the check
"lives here, where the store is open and the row readable, rather than in the mojo, which never
reads the store", and that reasoning is why the check goes with the opener rather than to the
caller. The caller gets a decided value with its provenance, not a null and a log line to correlate.

The retry-then-demote policy goes with it. `captureWithRetry` distinguishes a lock timeout from a
capture bug and reports them differently, and it calls `reconciles` per attempt because the
first-graph cadence commits mid-capture. That is store-lifetime policy, and it belongs beside the
opener rather than inside a capture the caller now drives.

**The generator takes the store.** `captureAndRead` and `captureFacts` stop reading
`ctx.storeDirectory()` and take what the opener returned. `RewriteContext` keeps the directory,
because a path is configuration and the mojos still need it; what it stops doing is standing in for
a store the generator will mint later.

**A new goal, `mvn graphitron:capture`, that fills the store and stops.** The shape is already in
the tree: `ValidateMojo` is thirty-four lines whose body is
`runGenerator(GraphQLRewriteGenerator::validate)`, and `AbstractRewriteMojo.runGenerator` already
owns the context build, the codegen classloader scope and the error wrapping. `CaptureMojo` is that
shape against a capture entry point.

**The entry point is a projection, not a fifth pipeline.** `GraphQLRewriteGenerator`'s javadoc is
explicit that its four public entry points are four `Projection` values of one `runPipeline` body,
and that "a fifth entry point that grows a front half of its own is the regression this shape exists
to prevent". Capture-only is therefore a fifth projection. It costs more than the existing four
because validation and lint run on every pass today and this projection wants neither, so the
projection record grows the switches to say so. That is the honest cost of the goal and it is small,
but an implementer who reaches for a second body has built the thing that javadoc names.

## The measurement this was going to buy, and no longer does

This section used to hold a table of two commands differing in one flag, whose difference was the
refresh's contribution to a capture: "a figure nobody can state today". It is stated. The audit
prices every position of the pass and R848 reached Done without needing the subtraction. Kept as a
heading rather than deleted, so a reader who remembers the argument can see that it was retired
rather than quietly dropped.

## Three seams in the code

**1. Store creation leaves `FactCapture.runInternal`.** This is the seam the rest hang off, and it
is the one that is not mechanical: the open, the reaper report, the ownership check, the retry pair
and the in-memory fallback are five behaviours currently expressed as control flow inside one
private method, and they come out as an opener with a sealed result. Every existing behaviour has to
survive the move, the demotion warning included, because a build beside a dev session that silently
did nothing is what that warning exists to prevent.

**2. The walk-side write has to come out of `detect`.** `FactCapture.detect` currently does two
unrelated things in one arm: it writes `walk_type_backing_class` from the run's `ClassifiedRun`, and
it runs the store-backed detections. A capture-only run wants the first and not the second, so
today "capture faithfully but detect nothing" is not reachable. Lifting `TypeBackingClassRows.write`
into its own step the caller sequences is a small change, and it is a piece of the edge R870
removes, so this item pays down part of that one rather than working around it.

A capture-only run therefore still walks and still writes those rows. That is deliberate: the
artifact this goal exists to produce is *the store a real capture writes*, and a store missing a
relation would not answer the question anyone opens it for.

If R870 lands first this seam disappears rather than changing: it deletes `walk_type_backing_class`
outright, having established that the comparison the relation served reads the walk in memory and
needs no store-side copy, and `detect` becomes detections-only with nothing left to separate. R870
is small and unblocked, so that is the likely order. Plan this seam as work, but check whether it is
already done before starting it, and if it is, delete the seam rather than reinstating a write to
have something to lift.

**3. `packagesRequired()` returns `false`, as it does for `validate`.** The sentinel only substitutes
when the parameter is absent, so a consumer with `<jooqPackage>` configured gets a full catalog
crawl. A capture run that fell back on the sentinel writes no `sql_` rows at all, which makes the
store useless for timing views that join the catalog, so the goal logs a warning when it substitutes
rather than leaving that silent.

## What the inversion must not break

Store creation is four decisions, not one open, and each is a property some run depends on. An
implementer moving them should be able to point at where each one now lives:

* **A second checkout does not thrash a shared partition.** The ownership check refuses a graph
  whose recorded base directory is somebody else's, and the run continues against a private store.
* **A contended write demotes rather than fails.** A lock timeout is reported as a demotion, and a
  failure twice in a row is reported as a probable capture bug, which are deliberately different
  messages.
* **A demotion is audible.** `DEMOTED_TO_MEMORY` is the one demotion no other layer reports, and a
  silent one makes a build beside a dev session look like a build that did nothing.
* **The reaper's release is reported once.** Whichever opener runs first says what the sweep freed,
  which on a build is the generator's open today and the mojo's after this.

The dev session's own store is the one place where the inversion changes who opens what rather than
where the code lives, so the session's existing budgets and readers have to keep pointing at the
same store the pass captured into.

## How we will know it is delivered

The first two criteria are the item. The rest pin the properties the inversion is most able to break
quietly.

* **`mvn graphitron:capture` on `graphitron-sakila-example` produces a store, and nothing else.** No
  emitted file, no validation report, no plan. Reopen the store and find graphs and fields non-zero,
  which is what says the goal produced the artifact rather than an empty file.
* **The goal produces a store for a schema `validate` would reject.** Point it at a fixture whose
  schema fails validation, and find a store holding that schema's captured rows and the stage
  verdicts that refused it. This is what separates the goal from `mvn graphitron:validate`, which
  fills a store and then fails the build.
* **No production code outside the mojos opens a store.** A test asserts it, in the shape
  `graphitron-mcp`'s `StoreClientBoundaryTest` already uses for a guard list: after this item the
  generator's main sources name no store opener.
* A test drives a generation against a store the caller opened and asserts the pass captured into
  *that* store, which is the claim "the generator takes a store" reduces to and the one a green
  build would otherwise not answer.
* A test covers the demotion arm: a store directory the run does not own yields the demoted outcome
  with its reason, the generation still completes, and the shared file is untouched.
* A test captures one fixture graph twice, once through the goal's projection and once through an
  ordinary generating pass, and asserts the two stores hold the same rows in every relation capture
  writes. That is what says the goal's artifact is the real one rather than a subset of it.


