---
id: R684
title: "fact-model doctrine: consumers share relations, not queries"
status: Ready
bucket: architecture
theme: classification-model
depends-on: [catalog-facts-readers-move-to-the-store]
created: 2026-08-16
last-updated: 2026-08-17
---

# fact-model doctrine: consumers share relations, not queries

## Problem

The store-read discipline that every migration keeps re-deriving is written down only in transient
places. The rule: consumers of the fact store share the store's relations and views, never their
reads of it. Each consumer formulates its own queries against the `StoreHandle`, asking its own
question of the views, even where the SQL comes out similar. A read two consumers genuinely both
need is the signal for a missing relation or view, which lands in `graphitron-model` at its own
grain, never in shared Java code between the store and its readers. That shared-code layer is the
trap: a consumer-shaped accessor API that re-grows the model's read surface one tier down, so
consumers read the layer rather than the store.

The LSP migration settled the rule on the catalog-shaped completion arms and the planner/emitter
conversion imports it, but both are roadmap items, and roadmap items ship and get deleted. The
durable home is `docs/architecture/explanation/fact-model.adoc`, and the point of writing it there
is that the next item does not re-argue it.

## Three roles, one rule

The rule is about modules, not about packages inside them. Each module gets a role by what it does
with the store, and the assignment is pinned rather than re-derived per item:

| Role | Modules today | What it owns |
|---|---|---|
| The store | `graphitron-model` | The relations and views, plus the shared read surface (`model.read`: `StoreHandle`, `SourceGraph`, `SourceStamp`) and the shared boot surface (`model.boot`: `StoreReader`, `GraphitronModelStore`). |
| Consumers | `graphitron-lsp`, `graphitron-mcp` | Their own queries and their own answer shapes. `graphitron` joins this row as its passes re-source onto the store; it queries nothing today. |
| The composition root | `graphitron-maven-plugin` | Constructing the handle and handing it to each consumer (`DevMojo`). It wires consumers together and queries the store itself never. |

**The rule: a consumer never imports another consumer.** Its reads are its own, including the row
shapes they answer with. Duplicated similar-looking query text between consumers is the accepted
cost, not a defect.

**The escalation: a read two consumers genuinely both need becomes a relation or a view in
`graphitron-model`, never a shared Java class.** The rule graduates into SQL, where the vocabulary
already lives; the answer shapes stay per consumer, because two consumers can mean different things
by the same rows.

Two boundaries keep the rule from over-reaching, and both are boundaries rather than exceptions:

* **The store's own surface is shared by design.** Sharing the handle is not sharing a query.
  Everything above the handle is a consumer's own: a reader is a query plus a row shape, and a row
  vocabulary crossing a consumer boundary is the trap even when the rule underneath it is a view.
* **Decomposition inside one consumer is not this rule.** `CatalogColumns` is read by the LSP's
  completion, hover, definition, parsing and diagnostic surfaces, and that is ordinary structure
  within one consumer, not a shared layer between two.

## The live sites, and why they are not this item's work

Every cross-consumer import in the tree runs from `graphitron-mcp` into `graphitron-lsp`. Two in
main sources: `SchemaView` imports `no.sikt.graphitron.lsp.facts.ClassMemberSlots`, and
`GraphitronMcpServer` imports `no.sikt.graphitron.lsp.state.Workspace`. Three more in test sources,
all of them `Workspace` (`StoreBackedBuild`, `GraphitronMcpServerTest`, `ServerInstructionsTest`),
which is why the guard below scans test sources too: a rule about what a consumer may reach for
does not stop at the main/test line.

All of them are `catalog-facts-readers-move-to-the-store.md`'s work. "No dependency on
`graphitron-lsp`" is the first of that item's four stated goals, it lands there in full rather than
deferring to a successor, and it covers the pom edge as well as the imports. So this item plans no
source change in either module; it depends on the sibling and lands the rule plus its guard once
the sibling is done.

The reasoning for closing them is the sibling's, in its "The MCP writes its own queries" section,
and is not restated here beyond the shape: a reader is a query plus a row shape, so what crosses
the boundary when one consumer imports another's reader is a Java row vocabulary that "one model,
many views" is satisfied by neither module owning. Both modules reading the base is the arrangement
the doctrine describes; one module reading the other's view of the base is not. That the sibling
had to argue this from first principles, in its own body, over the obvious move of reusing the
LSP's `facts` package, is the case for this item: the argument is general, it is being made per
item, and it should be citable instead.

An earlier draft of this spec decided the first seam the other way, relocating `ClassMemberSlots` to
`graphitron-model`'s `read` package as shared store surface. Withdrawn, and the escalation rule
above is why: the model's answer for a rule two consumers need is a store view, never a shared Java
class. The qualifier split and the case-insensitive match graduated exactly that way, into
`intent_spelled_table`. The drift worry about two consumers decoding one closed vocabulary is
answered by the same rule: a decode that is load-bearing across consumers belongs in the view's own
columns, where the vocabulary lives, not in a Java class beside the handle.

## What is deliberately outside the rule

`graphitron-lsp` and `graphitron-mcp` both import generator types from `graphitron` today
(`LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape`
and their neighbours). Those are not cross-consumer store reads and this rule does not govern them:
they are the pre-store projection surface, a producer surface inside the strangler window, and they
drain by the criterion "One base, many views" already states. Saying so explicitly is half the
point of the section, because the alternative is each migration item re-arguing whether a
`graphitron` import is the trap. It is not; it is the debt, and it has its own frame.

The two rules meet when the last projection drains: `graphitron` stops being a producer surface for
the other two, becomes a consumer like them, and its inbound module edges close under this rule
rather than under the strangler frame's.

## The doc section

One new `==` section in `fact-model.adoc`, titled "Consumers share relations, not queries", placed
between "One base, many views" and "The back half: complete commands, a closed graph". The tail of
"One base, many views" is already about reader shape, so the sharing discipline lands as continuous
prose there; the alternative home inside "Derived reads are views, not stored facts" is rejected
because that section owns the shape of a single derivation and already carries the missing-view
half of the rule. What is genuinely new is short, and short is the target. The section carries, in
order:

- The rule and the three roles, as the table above states them but in prose: the store's surface,
  the consumers' own queries, the composition root that wires them.
- The accepted cost, one clause: similar-looking query text between consumers is not a defect.
- The signal, one clause with an xref rather than a paragraph: a read a second consumer needs is
  the "derivation gets a relation as soon as a second reader asks it" sentence, applied from the
  sharing side.
- The trap, one clause: a shared query-helper layer is the private-model smell one tier down, the
  fork sitting in the read path rather than the model. (The paragraph above the insertion point
  already argues the fork; do not restate it.)
- The escalation: the rule graduates to a view, the answer shapes stay per consumer.
- The smell test: the row-assertion check from "Name the row, not the question" applied unchanged
  to a reader, state what it answers without naming which surface asked. Both live LSP readers pass
  it on their leading sentence (`CatalogTables`: "the tables of the catalog census, by the name an
  author wrote or by a key a resolution produced"), which is the tell that this is the existing
  check rather than a new one. Say that it is the reader's stated answer being checked, not the
  rationale paragraph underneath it, or the next reader will point at "Shared because completion
  and hover want the same rows" and call it a failure.
- The within-consumer boundary, one clause: decomposition inside a consumer is ordinary structure,
  and `CatalogColumns` with its five call sites is the shipped case.
- The strangler boundary, one clause with an xref: a pre-store projection import is the migration
  debt, not this trap.
- The enforcer line naming the guard.

## Enforcement

The cross-consumer half is mechanically checkable and the guard is the rule verbatim: no consumer
module imports another consumer module, in main or test sources. A guard in the
`PackageImportDirectionTest` mould, textually scanning sibling modules' sources off
`GuardScope.locateRepoRoot()` the way that test already scans package roots, so it needs no module
dependency on the modules it polices.

The consumer set is derived rather than remembered, in the census style
`borrowDialComponentClosureIsPinned` already uses: scan every module's main sources for
`no.sikt.graphitron.model.read.StoreHandle`, and pin the resulting module set against the role table
above. A module that starts querying the store therefore joins the guard's scope by a deliberate
edit to the pinned roles, never silently. `graphitron-model` (the store) and
`graphitron-maven-plugin` (the composition root, which constructs the handle rather than querying
it) are roles in that table, not carve-outs in the import rule: the rule scopes to the consumer row.

After the sibling deletes the imports the guard fires on zero sites, clean rather than
grandfathered, which is what the `depends-on` encodes. It carries the mould's non-vacuity
assertions on files scanned, because a guard whose whole point is a clean zero is otherwise
unfalsifiable.

The sibling ships its own guard in two halves: an import scan over `graphitron-mcp`'s main and test
sources, and an allowlist over that module's declared `no.sikt` pom coordinates. The relationship
is worth stating so a later reader deletes neither by mistake.

This guard generalises the import half: one rule over every consumer pair in both directions, with
the consumer set derived rather than named, so a third store consumer is covered on the day it
appears instead of waiting for someone to write its pair. That is the sibling's own allowlist
argument in the other axis. A guard naming the pairs that exist today asserts the history rather
than the rule, exactly as a denylist naming `graphitron-lsp` would.

The pom half generalises nowhere and stays the sibling's. It is a whole dependency policy for one
module rather than a statement about consumers, and it catches what no import scan can: a declared
dependency with no import, which is the state that lets the next reader reach for a type without
noticing they are widening a dependency. Whether the sibling's import half then folds into this
guard is that item's call at its own Done gate, not a deletion this item performs.

The within-consumer half (a consumer's own internal helper drifting into a query layer) is honestly
not mechanically enforceable: within one consumer, a shared reader class is structurally
indistinguishable from legitimate decomposition, which is exactly why the section states that case
as a boundary rather than a violation. It gets the bounded form of the "*Not mechanically
enforced:*" label, naming what the guard above does catch, so the label is a boundary statement
rather than a blanket concession.

## Deliverables

1. Add the cross-consumer import guard with its derived-and-pinned consumer set, once the sibling
   item has deleted `graphitron-mcp`'s dependency on `graphitron-lsp`.
2. Add the `fact-model.adoc` section per above, its enforcer line naming the now-live guard.
3. No `development-principles.adoc` edit: "One model, many views" already xrefs `fact-model.adoc`,
   and the doc stands at 3,497 of its 3,500-word enforced budget.

Order matters: the sibling's dependency deletion, then the guard, then the doc, so the doc's
"*Enforced by:*" line names a green test at the moment it lands. Nothing here touches
`graphitron-mcp` or `graphitron-lsp` main sources.

## Risks

- Sequencing: this item waits on a large sibling, and waits for all of it rather than for one
  import, because the sibling's completion criterion is the whole `graphitron-lsp` dependency. If
  that is far off, the doctrine sits unwritten while the trap is live. Mitigation, if the wait
  proves long: the doc section is independent of the guard and could land first with its enforcer
  line deferred, at the cost of a section that describes a rule nothing checks. Prefer waiting;
  take the split only on an explicit call.
- Padding: the signal and the trap are one clause each, and the fork argument already sits in the
  paragraphs above the insertion point. Restating doctrine in new vocabulary is the failure mode
  this section warns about, applied to itself. The section's length budget is the sum of its
  bullets, not a page.

## Done criteria

- The section exists at the stated position with the elements above; the docs module renders.
- The guard is green with zero grandfathered sites and no carve-outs in the import rule.
- The pinned consumer set matches what a `StoreHandle` scan derives, so the guard's scope cannot
  drift from the role table.
- Full `mvn install -Plocal-db` is green.
- The planner/emitter item's "Planners share relations, not queries" section and the LSP item's
  settled note can cite the durable section instead of restating it; whether to reword them is
  left to those items.
