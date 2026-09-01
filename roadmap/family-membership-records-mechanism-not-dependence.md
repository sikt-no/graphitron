---
id: R904
title: "Family membership records how a relation was produced, not when its inputs are complete, so placement is a default rather than a decision"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# Family membership records how a relation was produced, not when its inputs are complete, so placement is a default rather than a decision

A relation's family should say who owns it, and an owner should be the earliest gatherer that can
answer it. Today the family says which mechanism produced it, which is a different question and
carries no information about order. So a rule whose inputs are complete early gets placed with the
gatherer that runs last, and its owner is then left with the one lever this repository has been
trying to stop reaching for.

## The chart

Measured from the shipped schema and the gatherer roster, not from memory.

**Who reads a corpus, and who does not.** A crawler is a gatherer with at least one corpus. Two
gatherers have none.

[cols="2,3,3"]
|===
| gatherer | corpora | depends on

| `configuration` | configuration | nothing
| `sdl` | sdl | nothing
| `catalog` | catalog, classpath | nothing
| `java-source` | java-source | nothing
| `compile` | javac | nothing
| `graphitron` | **none** | `sdl`, `catalog`
| `derivation` | **none** | all five crawlers, and `graphitron`
|===

**What each family holds.** Only the two derivers write anything that reads another relation.

[cols="2,2,2,4"]
|===
| family | base tables | views | outgoing reads

| `sql_` | 14 | 0 | none, a corpus transcription
| `jvm_` | 7 | 0 | none, a corpus transcription
| `graphql_` | 27 | 1 | none, a corpus transcription
| `graphitron_` | 64 | 0 | none in SQL; every row is written by Java that read `graphql_` and `sql_`
| `intent_` | 25 | 89 | 134 into `graphitron_`, 67 `graphql_`, 47 `sql_`, 26 `jvm_`, 12 `store_`
|===

**So there are two derivation gatherers and the boundary between them is mechanism.** `graphitron_`
is what is derived in Java and written early. `intent_` is what is derived in SQL and written late.
Neither prefix records what a relation reads. That is the overlap, and it is the whole defect: a
family that does not encode dependence cannot be used to decide order, so order gets decided by
whichever mechanism the author happened to use.

**What the default costs, counted.** Walking each `intent_` rule's body down to base facts, twelve of
the 114 read exactly one corpus family and could have been answered by a gatherer that has already
run.

[cols="2,5"]
|===
| family actually read | relation

| `sql_` | `intent_foreign_key_column_pair`, `intent_name_matched_key_pair`, `intent_node_metadata_defect`, `intent_table_key_candidate`
| `graphitron_` | `intent_argmapping_pair`, `intent_argmapping_binding_leaf`, `intent_field_navigated_type`, `intent_field_producer_reference`
| `jvm_` | `intent_jvm_ancestor`, `intent_class_member_slot`
| `graphql_` | `intent_poly_member`, `intent_type_exemption`
|===

Six more bottom out at hand-written derivations the walk cannot see through and are unresolved rather
than local. The remaining ninety-six genuinely cross two to five families and are correctly the last
gatherer's.

The worked case is `intent_jvm_ancestor`. It reads `jvm_class_supertype` and `jvm_declared_type_ref`
and nothing else, so the catalog gatherer could answer it before anything else in the run begins.
Placed with the derivation gatherer it is settled last, and its one reader asks it through a
correlated existence test that re-climbs the class hierarchy per driving row: 25.53 s of an 88.2 s
workload on a captured consumer schema. Restating the reader takes it to 2.73 s and storing the
closure takes it to 0.02 s for 2284 rows and 0.2 s of refresh, and under the right owner neither
needs a registration.

## What this item proposes

**An owner is computed, not chosen.** A relation's owner is the latest, in the gatherer dependency
order, of the owners of the relations it reads. That is a function of the schema, so it is checkable,
and it makes the default impossible to take: a rule reading only `jvm_` facts cannot be owned by the
gatherer that runs last, because nothing it reads is owned there.

**Mechanism stops being a family boundary.** Whether a relation is a Java-written table, a SQL view,
or a stored table its owner refreshes is the owner's private choice, made where the cost is visible
and changeable without anyone else being told. `graphitron_` and `intent_` then differ by owner rather
than by how the rows got there, and the question "should this be a view or a table" stops being a
schema-wide negotiation.

**`meta_materialize` has nothing left to schedule.** It exists to refresh rules with no owner. Under
computed ownership every rule has one, a family-local rule is kept by an owner that runs early, and
a crossing rule is kept by the gatherer that runs last, whose refresh plan is not a register. The
mechanism is left with no work rather than argued down row by row.

## What has to be true first

- **Declarations.** 27 of 287 relations carry a `meta_relation` row today, 260 are on the frozen
  undeclared roster. R877 is working through this family by family and has landed `sql_` and `jvm_`.
  Computed ownership cannot be checked over undeclared relations, so this item follows that one.
- **Per-gatherer transaction control.** `FactCapture` runs every gatherer inside one transaction, so
  no gatherer can commit its family and then refresh its own relations against statistics reflecting
  what it just wrote. The fact-model page names this as the genuine prerequisite. The empty-store
  path is the one exception already carved out.

## Open questions

- **Does the prefix move with the owner, or only the ownership?** `sql_` and `jvm_` are both owned by
  `catalog` today, so prefix already does not equal owner and probably should not start to. The
  cheaper reading is that `intent_jvm_ancestor` keeps its name and changes owner. That breaks the
  sentence "the derivation gatherer owns the `intent_` relations", which may be the right thing to
  break.
- **What computes the order between two relations owned by the same gatherer?** Dependence within one
  owner is real and `meta_materialize_dependency` derives it today for registrations only.
- **Are the six unresolved relations family-local?** The walk stops at hand-written derivations, so
  their true owner is whatever those read.

## Evidence

Every figure above is from a fresh capture of a 26 818-line consumer schema, priced with a bench built
outside the tree. The read figures are `SELECT count(*)` per relation, which is an upper bound and
sound for comparing arms rather than as a claim about what a consumer pays. R876 carries the arms and
the method.
