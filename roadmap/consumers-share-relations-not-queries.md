---
id: R684
title: "fact-model doctrine: consumers share relations, not queries"
status: Spec
bucket: architecture
theme: classification-model
depends-on: []
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

Decision: the sharing is legal in substance and wrong in place. What `ClassMemberSlots` shares is
the typed decode of a relation's closed value vocabulary, and that decode is store surface in the
same sense `StoreHandle.reads()` is: a closed vocabulary has exactly one correct decode, and
duplicating it per consumer installs a second dispatch set over one closed set, the drift smell
"every invariant has an enforcer" names. So the boundary is two things, not one: the handle with
its scoping predicate, and the typed decode of a relation's closed vocabulary with its
three-valued answer shape (the `known` / `unknown` / `nothing captured` arms the tail of "One
base, many views" already mandates). A decode lives with its consumer while it has one, and moves
to `graphitron-model`'s `read` package beside `StoreHandle` when a second consumer needs it, never
sideways into a sibling consumer's package. `ClassMemberSlots` moves down, not out.

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
- The boundary, two things: `StoreHandle` and its `reads()` scoping predicate; and the typed
  decode of a relation's closed vocabulary with its three-valued answer shape, which lives with
  its consumer while it has one and moves down beside `StoreHandle` when a second consumer asks,
  never sideways.
- The exemplar, pairing both dispositions on one surface: the catalog arms (`CatalogTables` /
  `CatalogColumns`), where the table arms stayed apart because hover asks a different question
  and the column arm joined hover's reader once a second reader could say what it wanted from the
  same rows, and `intent_class_member_slot`, where four readers made the rule a view. The latter
  is already the exemplar in the bean-rule paragraph of "Derived reads are views, not stored
  facts", so the xref is a real link.
- The enforcer close, split as below.

## Enforcement

Split the way the rule splits. The cross-consumer half is mechanically checkable: no consumer
module imports another consumer module's store-reader package, a guard in the
`PackageImportDirectionTest` mould (or an added leg on it), with `graphitron-model`'s `read`
package as the sanctioned home for anything two consumers need. After the `ClassMemberSlots` move
the guard fires on zero sites, clean rather than grandfathered. The within-consumer half (a
consumer's own internal helper drifting into a query layer) is honestly not mechanically
enforceable: a shared reader class is structurally indistinguishable from legitimate
within-consumer decomposition. It gets the bounded form of the "*Not mechanically enforced:*"
label, naming what the guard above does catch, so the label is a boundary statement rather than a
blanket concession.

## Deliverables

1. Relocate `ClassMemberSlots` (with its closed-vocabulary decode and answer types) from
   `no.sikt.graphitron.lsp.facts` to `graphitron-model`'s `read` package beside `StoreHandle`;
   repoint the LSP and MCP callers.
2. Add the cross-consumer import guard.
3. Add the `fact-model.adoc` section per above, its enforcer line naming the now-live guard.
4. No `development-principles.adoc` edit: "One model, many views" already xrefs
   `fact-model.adoc`, and the doc stands at 3,497 of its 3,500-word enforced budget.

Order matters: move, then guard, then doc, so the doc's "*Enforced by:*" line names a green test
at the moment it lands.

## Risks

- Relocation feasibility: `ClassMemberSlots` should need only the store's generated tables and
  jOOQ, both already in `graphitron-model`, but the implementer verifies before moving; if it
  drags LSP-only types, split the decode from the LSP-shaped convenience wrapper and move only
  the decode.
- Padding: the signal and the trap are one clause each, and the fork argument already sits in the
  paragraphs above the insertion point. Restating doctrine in new vocabulary is the failure mode
  this section warns about, applied to itself.

## Done criteria

- The section exists at the stated position with the elements above; the docs module renders.
- The guard is green with zero grandfathered sites; `ClassMemberSlots` lives in
  `graphitron-model`'s `read` package and both former callers read it from there.
- Full `mvn install -Plocal-db` is green.
- The planner/emitter item's "Planners share relations, not queries" section and the LSP item's
  settled note can cite the durable section instead of restating it; whether to reword them is
  left to those items.
