---
id: R916
title: "The dev session index and refresh loop re-reads only what changed"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---
# The dev session index and refresh loop re-reads only what changed

## Goal

A dev session's round costs work proportional to what the developer changed. Today every round, on
either the schema cadence or the classpath cadence, re-reads and re-parses the whole compile
classpath before it does anything else. When this lands, a `.graphqls` save re-parses nothing, a
one-file recompile re-parses one file, and the per-round cost of the census tracks the edit rather
than the size of the workspace.

## What a round costs today

Both watch handlers run the same whole-workspace pass. `DevMojo.regeneratePass` (a schema save) and
`DevMojo.rebuildCatalog` (a `.class` change) each construct a generator and run
`GraphQLRewriteGenerator.runPipeline`, which opens with two whole-classpath reads: the jOOQ catalog,
loaded reflectively, and the class census, from `CatalogBuilder.buildExternalReferences`. Those two
are hoisted so a pass pays for each exactly once, but a pass happens per round, and a schema save
cannot have invalidated either. `AbstractRewriteMojo.withCodegenScope` also builds a fresh
`URLClassLoader` per round.

`ClasspathScanner` reads every classfile in every non-`TRANSITIVE` entry: `Files.readAllBytes` per
file for a directory root, every `ZipEntry` for a jar. The store's existing retention does not reach
this. `StoreRefresh.prepare` computes the sources whose content hash still matches
`store_source.stamp` and pre-claims their classes so their rows are not re-inserted, which saves the
writes and not the reads: capture walks exactly as it would cold, and the rows it would have
re-inserted are dropped as duplicates. The knowledge also arrives too late to help, being computed
inside capture, after the scan has already run.

Measured on this repo:

| Measurement | Result |
|---|---|
| Census over a 25-entry, 28.2 MB classpath | 5,032 classes, 53,279 methods |
| First round (cold JIT) | 1,348 ms |
| Rounds 2 to 5, nothing changed between them | 383 to 476 ms |
| Stat-only walk of 1,421 reactor `.class` files | 2.9 ms |
| Read-all walk of the same files | 12.0 ms, before any parsing |

The census figure is an upper bound for a classpath that size: the harness presented every entry as
project-origin, while a real scan skips `TRANSITIVE` entries before opening them.

## The three populations

The classpath is not one thing. Its parts differ in how much they cost to read and in how often they
change, and the two run opposite to each other.

| Population | Share of bytes | Changes when | Detector |
|---|---|---|---|
| Release jars | most | never within a session | content hash |
| Snapshot jars | some | a sibling project is installed from another checkout | content hash |
| Reactor `target/classes` | little | every compile, so every `.java` save under a live dev loop | per-file size and modification time |

The design already half encodes this. `DevMojo.resolveClasspathRoots` filters the watch set to
directories, so jars are deliberately not watched at all, and `ClasspathSources` leaves a directory
root unstamped because it "changes on every compile, so hashing it would buy an invalidation that
always fires while paying for a full walk to decide that". What follows is that the population
treated as frozen is the one being re-parsed every round, and the population that genuinely churns is
cheap.

For a directory, modification time is not one detector among several, it is the only one that saves
anything: a hash requires the read the round is trying to avoid, while a stat does not. Per-file
grain is also what keeps the invalidation honest, firing for the files the compiler rewrote instead
of for the directory as a whole.

## Plan

### 1. Hold the census across rounds, in memory

The dev session is a long-lived JVM, so the census does not need re-deriving from anywhere. Keep it,
and invalidate it per entry: hash a jar to confirm it is unchanged, stat a directory's files to find
the ones the compiler rewrote, re-parse only those, and drop the entries whose files are gone. The
jOOQ catalog load takes the same treatment on the same signal.

This is where the round's cost goes, and it needs no schema change. `store_source`'s own rationale
sets the terms: the `(path, size, last-modified)` triple is "a heuristic, tolerable while a wrong
answer dies with the JVM and not tolerable once it survives a build". An in-process census is exactly
that case, so modification time is the right detector here and a content hash remains the right one
for anything persisted.

The census is plain data records rather than loaded classes, so holding it across rounds carries none
of the class-identity hazard that reusing the `URLClassLoader` would. The loader keeps being rebuilt
per round.

### 2. Give the persisted classpath facts per-file grain

A cold `graphitron:generate` has no in-memory census to keep, and its equivalent saving is to read
unchanged sources from the store rather than parse them. That needs the `jvm_` family to retain at
file grain instead of wholesale by classpath entry.

The store already has the shape. `java_file` is keyed on the file path, carries the stamp its rows
were read at, and is described as "the family's partition dimension and the grain its refresh runs
at"; `JavaSourceFacts` walks, rewrites what changed, and prunes the files the walk did not see
because they were deleted or renamed, scoped to the roots it covered. A `jvm_file` relation modelled
on it gives directory sources the same retention. Keying on the file rather than the class name is
what keeps it correct across the scan's nested-class exclusion, since a dropped `Foo$Bar.class` has
no `jvm_class` row to key on.

Whether this phase is worth building is an open question below, not a settled part of the plan.

### 3. Report the round's cost

Log per round what was re-parsed and what was reused, by population. Without it, a regression in the
invalidation is invisible: the loop still produces correct output, just slowly, which is the failure
mode this item exists to remove.

## Verification

Round-over-round timing in a live dev session: a first round, then a `.graphqls` save, then a
one-file recompile. The pass is a schema save that re-parses no classfiles and a one-file recompile
that re-parses one, with the census identical to what a cold round produces. That last part is the
one that matters, since the risk here is a stale census rather than a slow one, and it wants an
equality check against a freshly scanned census rather than a timing assertion alone.

## Open questions

* Whether reading a census from the store beats parsing the jars, which decides whether phase two is
  worth building. The `jvm_` tree runs to roughly 180,000 rows for a mid-size consumer, so this is
  not obviously a saving and should be measured before the phase is committed to.
* Whether the jOOQ catalog can be invalidated on the same signal as the census, or whether its
  reflective load has to follow the classloader's lifecycle instead.
* What a restored build cache does to modification times under `target/classes`. In-process use is
  covered by the carve-out above, but the answer decides whether phase two's persisted grain can
  ever read a stat rather than a hash.
* Whether the two processes in one checkout that R914 describes can share a warm census, or whether
  each keeps its own.

## Relationship to R914

R914 bounds the fact store: it stops the file growing without limit and stops a large store stalling
the run that opens it. This item is about the loop that fills the store, and the two meet at phase
two, which would put more load on exactly the store read path R914 found stalling. Phase one is
independent of both.
