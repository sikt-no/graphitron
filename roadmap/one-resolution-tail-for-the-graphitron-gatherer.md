---
id: R963
title: "The graphitron gatherer has one resolution tail: the five producers and the stages under them join the assembly list, and FactCapture is not a second tail"
status: Backlog
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: [register-rules-become-owner-written-facts]
created: 2026-09-21
last-updated: 2026-09-21
---

# The graphitron gatherer has one resolution tail: the five producers and the stages under them join the assembly list, and FactCapture is not a second tail

## Goal

The graphitron gatherer's resolution work is one ordered list. `ClassificationDomainCapture`,
`InputOccurrencePaths`, `ArgMappingCandidates`, `TypeBackingRows`, `AuthoredClaimRejectionRows` and
`UnlowerableOrderingRejectionRows`, together with the stages the register's emptying appends below
them, sit in the same list as `GraphitronAssemblyCapture`'s stages, in one dependency order a reader
checks by reading one method, and `FactCapture` no longer exists as a second tail. Nothing about any
rule changes: the same statements run in the same order against the same rows, and what lands is that
one gatherer's resolution stages are in one place instead of two.

Three terms, glossed once. A *gatherer* is one pass that fills the fact store, the H2 database a
generator pass captures the schema, the jOOQ catalog and the classpath into; the `graphitron` gatherer
runs last, after the transcribing gatherers have flushed. A *stage* is one `INSERT ... SELECT` in that
gatherer's sequence, over rows an earlier stage or an earlier gatherer wrote. A gatherer's *resolution
tail* is the run of stages at its end that read the store whole rather than one corpus, which is what
`GraphitronAssemblyCapture`'s javadoc calls "the stages that resolve".

## Why there are two tails today, and why neither javadoc claims it

`GraphitronAssemblyCapture` says it holds "the stages that resolve: what graphitron makes of the whole
store once every corpus in it has been read", running last and crossing corpora. `FactCapture` says it
holds "the derivations the capture pass leaves behind" and "captures nothing". Those are one role
described twice, and only one of the two classes claims a future: `FactCapture` is `@Deprecated` and its
note reads "Nothing new belongs here; a derivation added today goes in `ModelCapture` beside the facts
it reads."

So the split is history rather than design. `FactCapture` is the residue of the pass the walk drove,
and the assembly class is the destination a later item minted for exactly the stages the residue still
holds. Nothing in the tree argues the two are different things; one of them argues it should not exist.

## The ordering, which is what says the list can be one list

`ModelCapture.capture` runs `GraphitronAssemblyCapture.capture`, then `FactCapture.derive`, which runs
the five producers inside a transaction and `UnlowerableOrderingRejectionRows` after the refresh. The
dependency between the three groups runs one way and does not interleave:

- `ClassificationDomainCapture` reads `graphitron_minted_field`, which `MacroCapture.expand` writes as
  a stage of the assembly list. It is downstream of that list, whatever its own javadoc says.
- Fourteen of the fifteen stages the register's emptying adds read `intent_input_occurrence_path` or
  its step relation, which `InputOccurrencePaths` writes. Four of them also read `intent_type_domain`,
  `intent_type_backing_class`, `intent_authored_claim_rejection` or `graphitron_argmapping_candidate`,
  which are the other four producers' outputs.
- No stage in the assembly list reads anything any of the five producers writes.

Assembly stages, then the five, then the fifteen, is therefore one acyclic chain already. There is no
interleaving to discover and no cycle to break, which is what makes this a move rather than a redesign,
and what lets the result be checked by reading the merged method top to bottom.

## Four objections, and why three of them dissolve

**Cadence.** The assembly list is said to mark and sweep where the producers clear and refill, but it
already does both: `MacroCapture`, the navigation statement, `FieldEndpoints`, `FieldRoutines` and
`FieldTableLinks` take the reading's instant, and every other stage in the list clears the graph's
partition and refills it, which is precisely the five producers' cadence. The `FactSink` the class
builds is one stage's need, stated as such in its own javadoc ("nothing else here does"), not a
contract the list imposes on a new member.

**The `intent_` read.** The assembly list already reads four views of the deprecated derivation family:
`intent_node_metadata_defect`, `intent_condition_method_route`, `intent_bound_table` and
`intent_routine_return_binding`. R876 states four stacked reasons a capture stage must not read that
family and then, rather than blocking a move on it, applies the family's own admission test relation by
relation and records that the stages "keep reading the view until that item lands, which costs the move
nothing". Restating a rule over owned relations is therefore a debt that test governs, on its own
schedule, and not a precondition of placement. `TypeBackingRows` seeding from `intent_type_backing_seed`
and hopping over `intent_field_accessor_hop` is the same situation as `ResolvedTypeBindings` reading
`intent_bound_table`, which is already in the list.

**The transaction.** `GraphitronStore` calls `ModelCapture.capture` inside `store.dsl().transaction`,
so the assembly stages and the five producers already run under one transaction and `FactCapture.derive`
opening its own is nested inside it. There is no boundary to cross.

**The schema argument, which is the one real friction.** `ClassificationDomainCapture` takes the
assembled `GraphQLSchema`, and `GraphitronAssemblyCapture` neither receives one nor wants one: its
javadoc says it "reads captured rows and no corpus of its own, which is what lets it cross corpora at
all". That is a claim to settle rather than a barrier, because the traversal's own javadoc already
settles the substance: "What decides the stratum is what a relation's rows are a function of, never
which program computes them, and these rows stay a function of the captured sources alone." The item
decides whether the schema is threaded into the list's signature or the traversal reaches it another
way, and restates whichever javadoc ends up saying the wrong thing.

## What is left over, and it is one call

Emptying the register removes `Materializations.refresh` and the analysing-cadence branch. What remains
in `FactCapture.derive` after that is the five producers, `UnlowerableOrderingRejectionRows`, and
`Materializations.analyse`. Only the last is not a resolution stage, and its reason is a dependency
rather than a preference, stated where it stands: H2's `ANALYZE` commits, so it has to run after the
load transaction rather than inside it. That one call is the whole of what cannot join the list, and
placing it at `ModelCapture`'s tail is what retires the class.

`UnlowerableOrderingRejectionRows` runs after the refresh today only because the view it renders reads
`intent_field_scope_table`, which is materialized. The register's emptying makes that relation a
stage-written table and the producer an ordinary stage, and already says so in its own scope.

## Why this waits on the register, rather than the register waiting on this

The fifteen stages the register's emptying adds read the five producers' tables, so they cannot precede
them, and moving the five is not the register's business: its goal is that `meta_materialize` holds no
rows. Its plan already says the unification is somebody else's ("Whether the gatherer's eight stages,
the five producers and these fifteen become one list under one class is R876's 'The capture layer
dissolves into one linear read' and is not reorganised here"), and appending to the tail that holds its
inputs is the correct placement for it.

What R876 owns is the entry points. Its phase 1 retires "`FactCapture`'s entry points, whose body
becomes `ModelCapture`'s", under an explicit "No relation moves and no gatherer changes what it writes".
So R876 says where the body goes and says nothing about whether those producers are stages of the
graphitron gatherer's own resolution list, which is the question here and which its ownership rule
answers in the affirmative: every one of the six computes to the `graphitron` owner.

Taking this after the register empties also makes it the smaller change. The tail is six producers now
and twenty-one once the register's fifteen land, but the fifteen arrive in ladder order with their
placement already argued relation by relation, so the move that follows them is a relocation of a list
whose order is settled rather than one still being established.

## Doc corrections this carries

Three javadocs disagree with the tree, and each is repaired by the move rather than separately:

- `FactCapture`'s deprecation note names `ModelCapture` as where a new derivation goes. Which of the two
  it is, `ModelCapture`'s body or the assembly list, is what this item settles, and the note goes with
  the class either way.
- `ClassificationDomainCapture` calls itself "the SDL gatherer's rooted traversal" and "a one-corpus
  producer", on the ground that nothing in it reads the catalog. It reads `graphitron_minted_field`,
  which the assembly list's expansion writes, so its owner is `graphitron` under the rule R876 computes
  ownership with, and the sentence is true of the catalog rather than of the store.
- `GraphitronAssemblyCapture`'s "reads captured rows and no corpus of its own" is either kept, with the
  traversal reaching its schema some other way, or restated to say what the contract is actually about.

## What this item does not do

- No rule, relation or row changes, and there is no DDL in it. A reader asking the store a question gets
  the same answer before and after.
- It does not empty the register, which is R955's whole subject, and it does not touch the ladder,
  the stage shapes or the test plan that item carries.
- It does not move the decode or touch `GraphitronFactCapture`, which holds what a single directive
  application restates and belongs where the walk drives it.
- It does not restate the four `intent_` reads over owned relations. Those are the admission test's
  backlog and each has its own owner.
