---
id: R612
title: "Resolved Maven configuration is a fact family the schema scanner reads"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
---

# Resolved Maven configuration is a fact family the schema scanner reads

R610 transcribes one slice of resolved Maven configuration into the store (the
`store_graph_schema_input` recipe and the build identity on `store_graph`) so a freshness
check can replay a graph's schema-file expansion without building its module. That leaves
two code paths honoring one contract: the build's own scan reads mojo fields through
`RewriteContext` and `SchemaInputExpander`, while the checker replays the transcribed
recipe beside it, and R610 argues their fidelity to each other instead of having it by
construction. The pivot this item explores makes resolved configuration a first-class fact
family that the schema file scanner itself reads: the pom's resolved config is written
into the store first, expansion runs over those relations, and the scan's outputs populate
the model as today. The headline win is structural: the build's scan and the freshness
replay become the same code over the same rows, and replay fidelity stops being a property
to argue.

Named forks a Spec has to settle:

- **Bootstrap order.** Today expansion happens in the mojo before capture opens the store;
  config-facts-first reorders that plumbing (write config rows, then expand from them),
  including for programmatic callers with no pom.
- **Family identity and extent.** Whether these rows stay `store_` bookkeeping or earn a
  `config_`/`maven_` prefix with its own comment doctrine, and how much of the mojo
  configuration gets transcribed. The principle that a relation lands with its first reader
  cuts against transcribing the whole configuration speculatively.
- **Maintenance reads versus consumer reads.** R610's read discipline scopes cross-graph
  consumer reads to the SDL-derived families while its freshness machinery reads every
  graph's bookkeeping; a config family the checker must read cross-graph makes that
  carve-out explicit and this item should state it as doctrine.

R610's recipe relations are deliberately shaped to be absorbable by this family without a
rekey (graph-keyed, ordinal-ordered, config-not-derivation); this item supersedes them or
adopts them in place, and owes R610's freshness loop an unchanged contract either way.
