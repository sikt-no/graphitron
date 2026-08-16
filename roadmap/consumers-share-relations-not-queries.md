---
id: R684
title: "fact-model doctrine: consumers share relations, not queries"
status: Spec
bucket: architecture
theme: classification-model
depends-on: [catalog-facts-readers-move-to-the-store]
created: 2026-08-16
last-updated: 2026-08-16
---

# fact-model doctrine: consumers share relations, not queries

## Problem

The store-read discipline that every migration keeps re-deriving is written down only in transient
places. The rule: consumers of the fact store share the relations and derived views, never the
queries. Each consumer formulates its own reads against the `StoreHandle`, asking its own question
of the views, even where the SQL comes out similar; a read two consumers genuinely both need is
the signal for a missing derived view, which lands in the store at its own grain, not in a shared
query-helper layer between the store and its readers. That layer is the trap: a consumer-shaped
accessor API that re-grows the model's read surface one tier down, so consumers read the layer
rather than the store. (The walk comparison motivates this item but stays out of the durable doc:
the walk is a transitional producer surface and the helper layer is a consumer surface, so
equating them in `fact-model.adoc` would wrongly invite the strangler frame's drain criterion.)

The LSP migration settled the rule on the catalog-shaped completion arms and the planner/emitter
conversion imports it, but both are roadmap items, and roadmap items ship and get deleted. The
durable home is `docs/architecture/explanation/fact-model.adoc`.

## The seam the doctrine must decide

The main tree holds exactly one cross-consumer store-reader import: `SchemaView` in
`graphitron-mcp` imports `no.sikt.graphitron.lsp.facts.ClassMemberSlots`. That javadoc argues the
sharing deliberately ("shared by every surface that asks the question"), and the underlying rule
did correctly become a view (`intent_class_member_slot`). Landing a doctrine whose single live
counterexample is a design the tree reasoned itself into, without saying which side of the line
it sits on, would be the weak form.

Decision: the seam closes, and `catalog-facts-readers-move-to-the-store.md` already plans the
closure; this item depends on it rather than duplicating it. That item's "The MCP writes its own
queries" section settles the reasoning: once the bean rule graduated to the view, a reader is a
query plus a row shape, and what crosses the module boundary when one consumer imports another's
reader is a Java row vocabulary that "one model, many views" is satisfied by neither module
owning. Both modules reading the base is the arrangement the doctrine describes; one module
reading the other's view of the base is not. So MCP writes its own query over
`intent_class_member_slot` (a projection of three columns; the rule stays in the view) and the
import deletes there.

An earlier draft of this spec decided the seam the other way, relocating `ClassMemberSlots` to
`graphitron-model`'s `read` package as shared store surface. Withdrawn: the model's escalation for
a rule two consumers genuinely need is a store view, never a shared Java class (the qualifier
split and the case-insensitive match graduated exactly that way, into `intent_spelled_table`), and
consumers mean different things by the same rows even where the SQL agrees, which is what a shared
answer shape papers over (the LSP's `NoCensus` arm against the catalog tools' "absence of rows is
absence of tables"). The drift worry about two consumers decoding one closed vocabulary is
answered by the same rule: a decode that is load-bearing across consumers belongs in the view's
own columns, where the vocabulary lives, not in a Java class beside the handle.

## The doc section

One new `==` section in `fact-model.adoc`, titled "Consumers share relations, not queries",
placed between "One base, many views" and "The back half: complete commands, a closed graph". The
tail of "One base, many views" is already about reader shape, so the sharing discipline lands as
continuous prose there; the alternative home inside "Derived reads are views, not stored facts"
is rejected because that section owns the shape of a single derivation and already carries the
missing-view half of the rule. What is genuinely new is short, and short is the target. The
section carries:

- The rule: each consumer formulates its own reads against the `StoreHandle`, asking its own
  question of the views even where the SQL comes out similar; what consumers share is the store's
  relations, derived views, and read surface, never the query. Duplicated similar-looking query
  text between consumers is the accepted cost, not a defect.
- The signal, one clause with an xref, not a paragraph: a read a second consumer needs is the
  "derivation gets a relation as soon as a second reader asks it" sentence, applied from the
  sharing side.
- The trap, one clause: a shared query-helper layer is the private-model smell one tier down, the
  fork sitting in the read path rather than the model. (The paragraph above the insertion point
  already argues the fork; do not restate it.)
- The smell: the row-assertion check applied unchanged, state what a shared reader answers
  without naming which surface asked. Both live readers pass it as written, which is the tell
  that it is the right check rather than a new one.
- The boundary: the store's own read surface, `StoreHandle` and its `reads()` scoping predicate,
  is shared by design; sharing the handle is not sharing a query. Everything above the handle is
  a consumer's own: a reader is a query plus a row shape, and a row vocabulary crossing a
  consumer boundary is the trap even when the rule underneath is a view.
- The escalation: where a rule two consumers genuinely need arises, it graduates to a store view,
  never to a shared Java reader class. The answer shapes stay per consumer, because two consumers
  can mean different things by the same rows.
- The exemplar, pairing both dispositions on one surface: the catalog arms (`CatalogTables` /
  `CatalogColumns`), where the table arms stayed apart because hover asks a different question
  and the column arm joined hover's reader once a second reader could say what it wanted from the
  same rows, and `intent_class_member_slot`, where the bean rule became a view and each surface
  reads it with its own query. The latter is already the exemplar in the bean-rule paragraph of
  "Derived reads are views, not stored facts", so the xref is a real link.
- The enforcer close, split as below.

## Enforcement

Split the way the rule splits. The cross-consumer half is mechanically checkable: no consumer
module imports another consumer module's store-reader package, a guard in the
`PackageImportDirectionTest` mould (or an added leg on it). After the sibling item deletes
`SchemaView`'s import the guard fires on zero sites, clean rather than grandfathered, which is
what the `depends-on` encodes. The guard is the general form across all consumer pairs, present
and future; it does not wait for the MCP pom's LSP edge to delete (the sibling defers that to
whichever of its three named items lands last), only for the reader import to go, and it
complements rather than duplicates the connection-ownership scan the sibling's tests section
already specifies. The within-consumer half (a
consumer's own internal helper drifting into a query layer) is honestly not mechanically
enforceable: a shared reader class is structurally indistinguishable from legitimate
within-consumer decomposition. It gets the bounded form of the "*Not mechanically enforced:*"
label, naming what the guard above does catch, so the label is a boundary statement rather than a
blanket concession.

## Deliverables

1. Add the cross-consumer import guard, once the sibling item's `SchemaView` migration has
   deleted the one live import.
2. Add the `fact-model.adoc` section per above, its enforcer line naming the now-live guard.
3. No `development-principles.adoc` edit: "One model, many views" already xrefs
   `fact-model.adoc`, and the doc stands at 3,497 of its 3,500-word enforced budget.

Order matters: the sibling's import deletion, then the guard, then the doc, so the doc's
"*Enforced by:*" line names a green test at the moment it lands. The seam closure itself is the
sibling's deliverable, not this item's; nothing here touches `graphitron-mcp` or `graphitron-lsp`
main sources.

## Risks

- Sequencing: this item waits on a large sibling. If the sibling's `SchemaView` migration is far
  off, the doctrine sits unwritten while the trap is live; the mitigation is that the sibling can
  land its member-slot query as an early increment, since that read is a three-column projection
  independent of the catalog-tool work, and this item unblocks the moment the import is gone.
- Padding: the signal and the trap are one clause each, and the fork argument already sits in the
  paragraphs above the insertion point. Restating doctrine in new vocabulary is the failure mode
  this section warns about, applied to itself.

## Done criteria

- The section exists at the stated position with the elements above; the docs module renders.
- The guard is green with zero grandfathered sites and no carve-outs.
- Full `mvn install -Plocal-db` is green.
- The planner/emitter item's "Planners share relations, not queries" section and the LSP item's
  settled note can cite the durable section instead of restating it; whether to reword them is
  left to those items.
