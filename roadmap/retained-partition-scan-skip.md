---
id: R620
title: "Skip the classpath scan for partitions the warm store retains"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Skip the classpath scan for partitions the warm store retains

The warm store already avoids re-inserting an unchanged jar's rows, but it still pays to read the
jar. `StoreRefresh` retains a classpath partition when `store_source` recorded a content hash for the
entry and the entry still hashes to that, and it enforces the retention by pre-claiming rather than
by filtering the walk: it seeds `FactSink.claim` with the class names the surviving partitions hold,
so capture walks exactly as it would cold and the rows it would have re-inserted are dropped where
duplicates always are. Dropping a duplicate is cheap. Producing one is not. By the time the claim
gate sees a class name, `ClasspathScanner` has opened the entry, walked it, and parsed the class file
for every public method, parameter, and record component it holds.

This item exists to record that the saving is not available on the terms it was filed under, and
what it would actually cost. It was carried out of the fact-store delivery as a change to the scan's
*caller* rather than to the store, on the reasoning that the census is built before capture runs.
The first half holds: `GraphQLRewriteGenerator.captureFacts` hands `FactCapture.run` a finished list
produced by `CatalogBuilder.buildExternalReferences`, the sole production caller of the scanner. The
conclusion does not, for three reasons that each sit outside the caller:

- **The retain decision is derived from the scan's output.** `StoreRefresh.freshSources` iterates
  `namedSources(extensions)`, the `ExternalReference.sourceName` values the scan produced, and looks
  each one up against the recorded stamp. Under the current input contract a partition cannot be
  known to be retained without having been scanned. Reaching the decision earlier means feeding
  `StoreRefresh` the entry list from `RewriteContext.classpathRoots()` instead, which changes its
  contract rather than its caller's argument.
- **The store is not open when the scan runs.** The stamp comparison needs `store_source` rows, and
  the store opens inside `FactCapture.run`, strictly after `buildExternalReferences` has returned.
  `StoreRefresh` and `ClasspathSources` are both package-private to the capture package, so the
  scanner's caller cannot reach the freshness check at all today.
- **Keeping the census whole while skipping the scan means reading the store back.** A retained
  jar's classes exist in exactly one place, the `jvm_` family, so serving the second consumer from
  retained rows would be the store's first production read. `FactCapture` states the opposite as a
  property it holds: nothing reads the store yet, consumers migrate onto it one at a time.

The shape this pushes toward is already rejected on the record. R612 retired a store-first sketch
that made the store the channel between two components of one run, and two of its three arguments
transfer verbatim: it puts the store on the critical path of a build that currently cannot fail on
store trouble, and under read-your-own-writes it makes the agreement anchor vacuous, since the
pipeline's inputs would be derived from the rows the anchor checks the pipeline against. So this is
not a caller tweak that can land opportunistically. It is gated on the store having a first
production reader, and it has to answer R612's arguments in its own words rather than around them.

Two things to establish before any of that is worth designing:

- **What the scan costs on its own.** The inherited claim is that the scan holds the remaining two
  thirds of the class-census cost. Nothing measures that. The recorded figures measure neighbours:
  the census at 28,556 classes and 205,262 methods with the *insert* brought from 23 s to 13 s by a
  per-relation bind batch, and store persistence taking about a third off the sakila-example module
  build. The two-thirds figure is an inference from the second number, and there is no timing
  instrumentation anywhere in the capture or catalog packages to refine it. If a measurement puts
  read-and-parse well below the estimate, the right outcome is to discard this item and say so,
  not to buy the store's first reader to win it.
- **What the second consumer can tolerate.** Partial is not a degraded answer here, it is a wrong
  one: the LSP diagnostics guard only the fully-empty census, so a list missing a retained jar's
  classes reports the author's valid class name as unknown, which is the jar-resident red-squiggle
  bug the jar-widening shipped to fix. On the `buildOutput` path the two consumers are not even
  separable as call sites, one `CatalogBuilder.build` census being handed to capture as
  `catalog.externalReferences()`.

A cheaper and unrelated saving surfaced while establishing the above, and belongs to whoever picks
up the dev loop rather than to this item: `DevMojo.regenerate` runs a generator pass and then
`buildOutput`, so a schema save scans the whole classpath twice, once per entry point. That one is
caller-local, needs no store read, and is not gated on anything here.
