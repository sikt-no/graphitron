---
id: R146
title: "Unique-index coverage as an alternative to PK for mutation cardinality safety"
status: Backlog
bucket: architecture
priority: 14
theme: mutations-errors
depends-on: [mutation-cardinality-safety-default]
---

# Unique-index coverage as an alternative to PK for mutation cardinality safety

R144's PK-coverage check on DELETE / UPDATE filter columns is the conservative
cut: PK is the only unique key the validator considers when deciding whether
to require `@multiRow`. Real schemas often have alternate unique constraints
(e.g. a natural-key column with a unique index alongside a surrogate PK), and
forcing those mutations under `@multiRow` discards the per-row cardinality
guarantee the unique index would otherwise provide. This item generalises the
check from "covers PK" to "covers some unique key" by exposing unique-index
metadata on `JooqCatalog` and threading the matched-key identity through to
the lookup-WHERE emitter (so RETURNING / follow-up SELECT know which columns
to round-trip). Independent from R145's UPSERT story; either can ship first.
