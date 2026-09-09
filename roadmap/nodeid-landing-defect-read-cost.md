---
id: R939
title: "A graphitron:dev round on a consumer schema answers in seconds again: the @nodeId landing verdict expands an unregistered expensive view per driving row"
status: Backlog
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# A graphitron:dev round on a consumer schema answers in seconds again: the @nodeId landing verdict expands an unregistered expensive view per driving row

## Goal

A `graphitron:dev` round on a real consumer schema answers in seconds again. Today one costs
minutes, and on the schema that exposed it a round does not finish at all: a dev session on the
`sis` consumer schema ran 31 minutes without ever opening its port, and its predecessor died at
about 17 minutes having never opened one either. The whole cost is one read of one derived
relation, and it is paid on every round, at boot and again after every save, so the dev loop is
unusable at that schema size. The consumer feels it as a validation gate that cannot run: a
migration branch has 13 author-error fixes applied against the very verdict this relation
computes, and no way to check them.

Two terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and then answers its verdicts out of by SQL. An
`intent_` *relation* is one of those answers stated as a view over captured facts rather than as
Java walking a model.

When this lands, a dev round on a consumer schema of that size pays a bounded, measured cost for
the `@nodeId` landing verdict, and the verdict itself is unchanged: the same schemas are refused,
with the same messages, for the same reasons. Nothing about what the check *decides* is in scope
here. Only what it costs to ask.

## What the measurement says

Measured against a copy of the real `sis` store, the population that exposed this, with H2 query
statistics rather than hand-rolled timing. The store copy came from the idle stamp directory, so
the live session was never perturbed. Numbers below are single-pass, which ranks rather than
measures; the separation is about four orders of magnitude, so the ordering is not in question
while the individual figures stay provisional.

`FactCapture.detect` issues seven reads per pass. Six of them, on that population:

| read | time |
|---|---|
| `intent_node_id_decode_defect` | 747 ms |
| `intent_authored_claim_conflict` | 262 ms |
| `intent_argmapping_projection_defect` | 108 ms |
| `intent_resolved_node_key_projection` | 69 ms |
| `intent_field_unlowerable_ordering` | 58 ms |
| `intent_reference_for_application` | 3 ms |

The seventh, `intent_node_id_decode_landing_defect`, did not return in 24 minutes.

Bisecting that view's body against the same population localises it to one term:

| slice | time |
|---|---|
| child `intent_node_id_decode_hop` | 41.5 s for 377 rows |
| the `judged` CTE | 115 ms (310 rows) |
| the `judged` CTE with its correlated `branches` subquery removed | 88 ms |
| the `stopped` CTE, which is `judged` joined to the hop relation | 22.8 s |
| arm 1, `PATH_STOPS_SHORT` | 28.1 s |
| arm 2, `LANDING_TYPE_DISAGREEMENT` | did not return in 300 s |

Every other child answers in single-digit milliseconds. So the cost is not cardinality anywhere:
the driving populations are hundreds of rows.

**The shape.** `stopped` is a non-recursive `WITH`, which H2 inlines exactly like a view with no
common-subexpression elimination, and arm 2's only reference to it is inside a correlated
`NOT EXISTS`. A 23-second expansion re-evaluated per driving row is the whole story, and it is the
form the fact-model page already names under "Derived reads are views, not stored facts". The
correlated `branches` subquery in `judged` is *not* the term, which the third row above rules out;
it is the one hypothesis worth pre-empting because it is the thing a reader notices first.

**Why this is a regression and not a pre-existing cost.** `intent_node_id_decode_hop` has been
expensive since well before the verdict landed. What changed is that nothing used to evaluate it at
read cadence. Its only two other readers in the DDL are `intent_node_id_decode_hop_column_live` and
`intent_node_id_decode_column_live`, the source views of materialized tables, so the hop relation
was paid once per refresh. `intent_node_id_decode_landing_defect` is the first reader to expand it
live, it expands it more than once, and it carries no `meta_materialize` registration. Confirmed
from the DDL independently of the timings: the view has zero `FROM`/`JOIN` references anywhere, so
`NodeIdLandingDefects.detect` is its only reader in the tree, and grepping the register for it
returns nothing.

## Implementation

The lever is not settled, and picking it is the first thing this item does rather than something
its plan can assume. Both candidates are named here with the measurement each needs.

**Register `intent_node_id_decode_hop`.** The stronger case on the lever hierarchy: it is the
expensive term, it is a plain view, and it sits upstream of two `_live` refresh sources as well as
the new reader, so a registration serves three readers rather than the one that exposed it. It is
also the shape the registration convention is built for, an existing named view keeping its text
under a `_live` name while a table takes the canonical name every reader already spells.

One caveat has to be measured before this is chosen, and it could invalidate it outright:
registrations refresh inside the capture transaction, where H2 disables its query-expression cache,
so a view costing 41.5 s standalone may not cost that in-transaction, in either direction. **Time
the refresh in-transaction, not standalone, before committing to this lever.** That caveat belongs
in the registration's own `reason` column and not only here, because the `reason` row is where the
next person pricing this relation will look, and this file is deleted at Done.

**Or rewrite arm 2 so it stops correlating on `stopped`.** Sidesteps the in-transaction question
entirely by adding no registration. Weigh it knowing the fact-model page's warning that rewrites
regress more often than they read like they will; if this is tried and loses, record it as
measured-and-lost in the same place the winning lever's arithmetic goes, so the next reader does
not re-run it.

Whichever lever wins, the verdict's rows must be unchanged. `source EXCEPT target` and
`target EXCEPT source` both empty is the two-line proof for the registration route; for the
rewrite route it is the same comparison against the current view's output on a populated store.

## Tests

The regression corpus already exists and is better than anything a fixture would invent. The `sis`
migration branch's 19 author-errors, 13 `PATH_STOPS_SHORT` and 6 `LANDING_TYPE_DISAGREEMENT`,
exercise both arms across 4 node types and 8 distinct landing tables. Pin the verdict against that
shape so a lever that changes rows fails rather than merely getting faster.

What this item should also leave behind is a guard, because the defect was invisible to the
instrument that was pointed at it. A read-cadence reader of an unregistered view that is itself
defined over an expensive unregistered view is the pattern; a meta-test over the DDL and the
detect-pass readers can name that pattern without pricing anything, which is what makes it cheap
enough to keep.

A wall-clock gate is explicitly *not* in scope. Nothing in this repository captures a consumer
schema of the size that exposes this, and a fixture that did would be a build wall-clock gate,
which R733 owns.

## Other solutions we've considered

**Reopening R926, which shipped the verdict.** Not available, and worth recording so the next
person does not spend the same twenty minutes on it. There is no `Done → anything` transition in
either `roadmap/workflow.adoc` or the tool's transition map; `Done` is a file-deletion transition,
so R926 has no file to flip; and IDs are never reused, precisely so that the `changelog.md` entry
and the commits referencing R926 keep their meaning. A defect found in shipped work gets a fresh
item, which is what R926's own history did twice: R925 was filed from its implementer review and
R927 from its deferral.

**Folding this into R876, which owns the expensive-derived-read narrative.** A real candidate,
since this is exactly its subject and it is In Progress at priority 1. Rejected on that item's own
terms: it files the threads it opens as separate items rather than carrying them, and it explicitly
disclaims consumer-scale wall-clock work as belonging elsewhere. The diagnosis here should still be
read against its lever ordering.

## Provenance

Filed after two sessions reached different answers, and the disagreement is the useful part.

A session investigating the same slowdown measured the diagnostic read across R677's landing commit
and its parent, got a clean 15x regression, and concluded R677. That conclusion was correct about
its own instrument and wrong about the cause: `intent_node_id_decode_landing_defect` has no SQL
reader, so it never reaches the diagnostic surface, and no measurement taken there could have seen
it whatever it cost. R677 and this verdict also added a detector to the same unconditional switch
in `FactCapture.detect` within a day of each other, which is most of why R677 fit every
circumstantial test.

Two things follow, and they are why this section exists rather than the story living in a commit
message. Anyone re-deriving this from the read surface lands where that session landed, so the
guard proposed under Tests is aimed at the blind spot and not at the relation. And the 15x on the
diagnostic read is real and still unfiled: it is roughly three orders of magnitude below this one,
on the language-server publish cadence rather than the dev round, and it wants its own item rather
than a paragraph here.
