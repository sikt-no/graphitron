---
id: R595
title: "The graphitron-model module exists and capture fills it"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-05
last-updated: 2026-08-05
---

# The graphitron-model module exists and capture fills it

The fact-base architecture R589 (`validation-adds-facts`) arrived at needs its substrate: a new
reactor module, `graphitron-model`, holding the fact-schema DDL (the umbrella's normalised data
model reified as SQL; R333), jOOQ codegen over it (`DDLDatabase`, no live database at build time,
the `graphitron-sakila-db` shape made hermetic), and an H2 in-memory bootstrap. The module builds
before core, so editing the DDL fails javac in every consumer that touched the changed relation;
with no persisted state anywhere, compile-time is the schema's only compatibility surface. On top
of the module, the two infallible capture loads run beside the existing pipeline and change no
behavior: the SDL visitor records existence and application facts (types, fields, applied
directives with raw arguments, argument uses, input-field edges, source locations), and the jOOQ
and service catalog scans record the catalog facts. Nobody reads the store yet; acceptance is
generated-output identity plus agreement tests asserting the store's base facts match what the
existing pipeline sees (type census, per-coordinate applied-directive counts, catalog tables).
Those agreement tests are the shadow period's honesty check and retire as consumers migrate off
`GraphitronSchema` piece by piece (the strangler frame recorded in R589). While both models are
live, new facts land only in the store. Mechanical ride-alongs: root pom module list, CLAUDE.md
and `docs/architecture/reference/modules.adoc` module enumeration, H2 version pinned in the root
pom. The spike grounding the stack choice is
`roadmap/audits/2026-08-05-fact-base-h2-spike.md`.
