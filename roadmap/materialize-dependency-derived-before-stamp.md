---
id: R761
title: "The dependency relation is derived after the store is stamped, so an interrupted boot leaves a warm store ordering alphabetically"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The dependency relation is derived after the store is stamped, so an interrupted boot leaves a warm store ordering alphabetically

`GraphitronModelStore.openAt` creates a file-backed store in three steps, in this order: execute the
DDL, write the `store_stamp` row, derive `meta_materialize_dependency` from the stored view
definitions. A later boot decides the file is warm by reading that stamp alone, and a warm boot
deliberately skips the derivation, on the sound argument that the rows are a function of the DDL the
stamp names. The order of the last two steps is what leaves a hole: a process that dies between the
stamp and the derivation leaves a file every later boot accepts as warm with a dependency relation
that is empty rather than derived. Nothing fails; the refresh order simply falls back to the
alphabetical identity case, which is exactly the quiet wrong answer the derived order exists to
prevent, and it survives every subsequent boot because the stamp says the store is complete. The
window is two statements wide and the shipped DDL registers no dependent derivation, so today there
are no edges to lose; the first registration whose view reads another registration's target makes it
real. Deriving before stamping closes it outright, the stamp then meaning "this file holds
everything a created store holds" rather than "the DDL ran". Worth checking in the same pass whether
anything else has accumulated behind the stamp, and whether the stamp write belongs at the end of
creation as a matter of rule rather than as a fix to one caller.

A second, smaller observation from the same review, recorded here so it is not lost rather than
because it needs the same fix: `MaterializeRegistryGateTest.theRefreshOrderRespectsEveryDependencyRow`
is vacuous on the shipped DDL. With zero rows in the relation it has nothing to violate, and it
passes with the topological sort bypassed. That is honest and the class says so, the production
evidence being `FactSchemaGateTest.everyMaterializedTargetEqualsItsRule`'s synthetic registration
instead; but a gate that cannot fail is worth either a fixture of its own or a note saying which
other case carries its weight.
