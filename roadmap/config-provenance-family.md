---
id: R641
title: "All build configuration is captured as provenance"
status: Backlog
bucket: architecture
priority: 2
theme: tooling
depends-on: [maven-config-fact-family]
created: 2026-08-12
last-updated: 2026-08-12
---

# All build configuration is captured as provenance

A graph's configuration is knowable only while its own build is running. `store_graph` records the
build file's path and a content hash of it, and `store_graph_schema_input` /
`store_graph_schema_extension` record two of the parameters that file supplied. Everything else the
run was configured with (`<lint>`, `<sessionState>`, `<tenantColumn>`, the output package and
directory, and whatever the mojo grows next) reaches no relation, so a reader that is not that
run has no way to ask.

The asymmetry is the sharp end. `store_graph.build_file_stamp`'s comment states a contract over the
whole file: "the remembered recipe is trusted only while the build file still hashes to this, and a
mismatch marks the recipe possibly stale until the module's own next build repairs it." That is a
fitness claim about the entire configuration, and behind it sit two parameters. A reader can prove
a sibling module's configuration is unchanged and still be unable to see most of it. The
invalidation mechanism is built; the payload is missing.

The extent was cut deliberately, by the first-reader principle, and the schema-recipe item now
records that reasoning as withdrawn. The principle is right for deciding when to build a
*derivation* and wrong as a test for whether to record a *fact*: a fact's readers arrive after the
fact does, and a run that has exited cannot be asked again. Recent evidence is that the readers do
arrive. The LSP fact-store item reached for a config-shaped answer (which generated package is this
graph's) within weeks; it turned out `store_graph_source` already answered that one from what the
run read, which is the better fact where it exists, but the reach was real and the next one will
not always land on an existing relation.

## What the transcription is for

Not a saving on the Maven path. Maven parses the pom and injects the parameters before any mojo
runs, so there is no parse to skip there, and an item claiming otherwise would be selling a benefit
it cannot deliver. The reader this serves is the one with no build to run: a sibling module's
configuration read cold, a non-Maven entry point, a maintenance or editor surface answering
questions about a graph whose module has not been built this session. That is the reader the schema
recipe was already built for; this generalises it to the rest of the configuration.

## Boundaries to settle in the Spec

* **Which parameters, and at what grain.** A relation per parameter family, or one discriminated
  config relation keyed `(graph_name, ordinal)` the way the schema recipe is. The recipe's shape is
  the precedent; whether a flat key-value relation is honest or is a bag of `VARCHAR`s wearing a
  relation's clothes is the question the Spec answers.
* **Where the decode lives.** The schema-recipe item establishes the rule: the plexus-bound beans
  stay plugin-side, core assumes no Maven vocabulary, and a non-Maven entry point becomes a second
  decoder into the same typed value rather than a second capture path.
* **What stays rejected.** The store-first shape, unchanged: the store is not a channel between two
  components of one run, nothing reads config rows before capture writes them, and the round-trip
  anchor stays non-vacuous. This item is write-side transcription plus a cross-run read.
* **The read doctrine.** A reader of another graph's config rows is maintenance machinery and
  writes no conclusions outside the `store_` family. That doctrine is already stated for the recipe
  rows; confirm it carries unchanged or say what changes.
* **Whether typed values or rendered strings are transcribed** for parameters whose configured form
  is structured, and how a parameter that was never configured is distinguished from one configured
  empty. The schema recipe hit exactly this and answered it with the kind discriminator; the
  general case needs its own answer, since "configured nothing" and "not asked" are different facts
  and a nullable column conflates them.

## Relationship to the schema recipe

Sequenced after it, not folded into it. That item is argued through five review rounds and sized to
two readers; widening it now would restart the review over a design that is settled. It ships the
typed recipe, the sealed source carrier, the production decode and the round-trip anchor, and this
item generalises the pattern those establish rather than inventing one.
