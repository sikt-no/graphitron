---
id: R908
title: "The dependency-currency advisory reads an input no relation holds, so one warning producer of four cannot be recomputed"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# The dependency-currency advisory reads an input no relation holds, so one warning producer of four cannot be recomputed

A build warning is supposed to be a fact about something the store captured. Three of the four
producers that fill the warning channel satisfy that: the lint engine walks the parsed registry the
`graphql_` family transcribes, the session-state advisories read a configuration the
`store_graph_session_mount` and `store_graph_session_unmount` relations hold, and the classifier's own
advisories come off the model the walk built from captured SDL. The fourth does not.
`DependencyVersionWarnings.forVersions` reads the resolved graphql-java and jOOQ versions that the
mojo decodes off both dependency graphs, and no relation in the store holds them, so its rows cannot
be recomputed from the store and nothing can check them against anything.

That is a gap in the configuration family rather than a documentation problem. The configuration
corpus is defined as the configuration the run holds in hand about its subject graph, handed to
capture rather than read from a file, which is exactly what a resolved dependency version is: the
mojo already has it, it is a fact of the round, and every other input on the same channel is
captured. Capturing it would make the whole warning channel a function of stored facts, which is what
`lint_finding`'s stratum-two assignment on the fact model page already claims for the family as a
whole and what the fourth producer quietly falsifies.

Found while declaring the scaffolding families for the store's own grain roster, by tracing what each
warning producer depends on rather than by reading the writer's own account of itself.
