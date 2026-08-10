---
id: R620
title: "The dev loop reads the whole classpath twice per pass"
status: Backlog
bucket: architecture
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# The dev loop reads the whole classpath twice per pass

`ClasspathScanner` opens every entry on the compile classpath, walks it, and parses each class file
for its public methods, parameters, record components, and `GraphQLScalarType` constants. Its sole
production caller is `CatalogBuilder.buildExternalReferences`, and two consumers call that: the
capture load, through `GraphQLRewriteGenerator.captureFacts`, and the LSP catalog, through
`CatalogBuilder.build`. Either entry point on its own scans once. `DevMojo` calls both, so the dev
loop scans twice: at startup (`runGeneratorPass` then `buildOutputQuietly`) and again on every schema
save (`runGeneratorPass` then `buildOutput`, both inside one `withCodegenScope` lambda in
`DevMojo.regenerate`).

The second read has no new input to read. Both calls in a pass share one `RewriteContext`, so
`classpathRoots()` is the same list, in the same process, milliseconds apart. Nothing between them
changes the consumer's classpath: the one event that does, a consumer `.class` changing, is watched
separately and handled by `DevMojo.rebuildCatalog`, which opens its own scope and so should keep
scanning fresh. The scope is built and torn down per pass, which makes it the natural place to
memoise: a census cached on the pass's context needs no invalidation protocol, because the context
does not outlive the pass.

One thing in between is not nothing, and this item exists to decide it rather than to assume it away.
`regenerate` runs `incrementalCompiler.recompile` between the two scans, writing freshly compiled
generated classes into the exclusive output directory, and the census excludes only the jOOQ
generated package, so today's second scan can see classes the first could not. Whether any consumer
depends on that is the question to answer first; if one does, the fix is to memoise per scope and
invalidate on the recompile rather than to drop the second read.

Measure before building anything. There is no timing instrumentation anywhere in the capture or
catalog packages, so the scan's cost is currently unknown at both call sites. The nearest recorded
figures measure neighbours: the census at 28,556 classes and 205,262 methods, with the *insert*
brought from 23 s to 13 s by a per-relation bind batch, and store persistence taking about a third
off the sakila-example module build. If a measurement puts read-and-parse low enough that halving it
is imperceptible in the loop a developer actually feels, discard this item and record the number, so
the next person reads a measurement instead of re-deriving the estimate.

## The retained-partition skip is not the way in

This item was filed as that skip, carried out of the fact-store delivery as a change to the scan's
*caller* rather than to the store, and refocused once the code disagreed. Recorded here so it is not
re-derived. The warm store already avoids re-inserting an unchanged jar's rows: `StoreRefresh`
retains a partition when `store_source` recorded a content hash for the entry and the entry still
hashes to that, enforcing it by seeding `FactSink.claim` so capture walks exactly as it would cold
and the duplicate rows drop where duplicates always drop. Suppressing the *read* behind that
retention is not reachable from the caller, for three reasons:

- **The retain decision is derived from the scan's output.** `StoreRefresh.freshSources` iterates
  `namedSources(extensions)`, the `ExternalReference.sourceName` values the scan produced. A
  partition cannot be known to be retained without having been scanned. Reaching the decision
  earlier means feeding `StoreRefresh` the entry list from `RewriteContext.classpathRoots()`, which
  changes its contract, not its caller's argument.
- **The store is not open when the scan runs.** The stamp comparison needs `store_source` rows, and
  the store opens inside `FactCapture.run`, strictly after `buildExternalReferences` returned. Both
  `StoreRefresh` and `ClasspathSources` are package-private to the capture package.
- **Keeping the census whole while skipping the read means reading the store back.** A retained
  jar's classes exist only in the `jvm_` family, so serving the LSP from retained rows would be the
  store's first production read, against `FactCapture`'s stated property that nothing reads the store
  yet and consumers migrate onto it one at a time. Partial is not an option: the LSP diagnostics
  guard only the fully-empty census, so a list missing a retained jar's classes reports the author's
  valid class name as unknown, which is the jar-resident red-squiggle bug the jar-widening fixed.

R612 already rejected the shape this pushes toward, a store-first channel between two components of
one run, and two of its arguments transfer: it puts the store on the critical path of a build that
today cannot fail on store trouble, and under read-your-own-writes it makes the agreement anchor
vacuous, the pipeline's inputs being derived from the rows the anchor checks them against. So the
retention route is gated on the store gaining a first production reader and belongs to whichever
consumer migration buys that, as its own item, filed off this item's measurement. It is out of scope
here.
