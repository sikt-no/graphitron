---
id: R979
title: "The spec is a Mikado graph, because a plan written before the work is a guess and four sessions cannot share a phase list"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-27
last-updated: 2026-09-27
---

# The spec is a Mikado graph, because a plan written before the work is a guess and four sessions cannot share a phase list

## Goal

**The gate to Ready stops asking for a plan and starts asking for a goal.** Today `Spec -> Ready`
wants the steps, which means it wants them before any of them has been tried, so the plan a reviewer
approves is the one an author could write without the information the work produces. The gate should
instead ask what a reader can judge up front: what the goal is, why reaching it makes graphitron a
better product, what changes for a consumer when it lands, a high-level strategy for getting there,
and how the item sits against the parallel items and the upkeep around it. None of that is
invalidated by a blocker. The steps always are.

The plan then becomes a Mikado graph that the work grows. When this lands, exploratory work happens
inside the workflow instead of beside it, and several sessions can hold orthogonal leaves of one item
at once.

## Why now, from our own tree

Three pieces of evidence, none of them hypothetical.

**A plan written at Ready was wrong about the route and had to be.** R876's ninth slice reads
`Moot. Slice 8 took the rest with it`, and its eighth reads `Done, by R955. Not retired row by row
and not shrunk to a defensible core: the gatherer it compensated for stopped existing`. The goal held
throughout. The steps did not survive contact, and no reviewer could have caught that at the gate,
because the information did not exist yet.

**Work the workflow had no room for went outside it.** Three Mikado files sit in a scratch directory
at 243, 361 and 1105 lines. The largest of them opens with a banner saying its graph names relations
that have since been deleted and that the current state lives in a second scratch file. Nothing gates
them, so they rot and fork. That is the workflow routing around itself.

**Four sessions are on one item.** Coordination today is a phase list that cannot say which phases are
independent, so orthogonality is discovered after the fact: each harvest has begun with a conflict
matrix computed from file overlap between branches. That finds disjointness by luck and then verifies
it.

## What a graph is here

The goal is the root. A prerequisite discovered by trying becomes a child. A leaf is doable now, and a
branch waits on its children. Each node is marked `done`, `blocked` or `open`, and a `blocked` node
names what holds it, including another session.

**A node is a claim and at most a sentence of why.** This is the whole discipline and it is what keeps
the graph readable at a hundred leaves. The 1105-line scratch file is what a node with a paragraph
becomes. Evidence goes where evidence already goes, measurements to `roadmap/audits/` and the story to
`changelog.md` at Done; the node carries the claim and a pointer.

## The method forces add, flip, subtract, and the subtraction is the one that gets skipped

Small reversible steps are not a style. They are the only way to change a shape while every
intermediate state still builds, and the shape of that is always the same three moves: **add** the
new relation beside the old, **flip** its readers over one at a time, **subtract** the old one. Each
move is green on its own, which is what makes the work safe to run unattended and safe to harvest.

What buys that safety is a middle state where both shapes exist. That is the method's cost, paid
deliberately, and it is also precisely where the danger is, because the three moves do not feel
alike. Adding is satisfying and demonstrable. Flipping is visible progress with a number attached.
Subtracting produces no new capability, breaks things when it is wrong, and reads as tidying. So it
is the one that gets deferred, and a deferred subtraction does not sit still.

It leaves two producers of one fact with nothing comparing them, which is the defect R876 is named
for. It leaves the read expensive for every consumer still on the old relation, so the work bought
nothing for them. And it makes every reader choose between two relations that answer the same
question, which is a cost the addition was supposed to remove and has instead doubled. Three
unsubtracted flips, one leaf each, and a contributor can no longer tell which relation is the
current answer.

The tree already shows both ends of this. `intent_` is eighty relations and its own family header
opens by calling the family nobody's; that is what additions without subtractions look like after
long enough. Against it, the classpath census went from thirteen relations to none, and the commit
that finished it was pure removal with no new capability in it at all.

**So the graph carries subtraction as a node and never as cleanup.** A branch is done when what it
replaced is gone, which is the rule R876 now states at its root. A branch whose additive and flip
leaves are all green and whose subtraction leaf is open is not a finished branch; it is the most
dangerous state the method has, because it looks finished. Two live instances are in R876 right
now: `RoutineWriteFacts` still reads `intent_mutation_routine_seat` while `graphitron_entry_defect`
states the same refusals, and `graphitron_field_reference_step_hop` still resolves the elements
`graphitron_field_chain_link_reading` resolves, measured to agree exactly on a fixture reaching
every arm. Both are flips that landed and subtractions that did not.

This is why the QC triggers below are not optional bookkeeping. A subtraction node open across more
than one harvest is worse than a stale `blocked`: the stale one makes the graph lie, and this one
degrades the code while the graph tells the truth.

## Why this is what lets several agents work one item

A phase list orders work that may not be ordered. A graph states the dependencies that are real and
says nothing about the rest, so two leaves under different branches are orthogonal by construction
rather than by inspection. A session claims a leaf, which is the smallest coordination token there is:
flipping one line from `open` to `done` rarely conflicts, where rewriting a phase section always does.
And a session blocked on another session is an edge in the graph instead of a remark in a paragraph
somebody else has to find.

The method's own property matters as much as the shape. Small steps, each reversible, each verified
before the next. That is what makes a session safe to run unattended and safe to harvest.

## Quality gates the graph needs

* **Node size**, mechanically checkable, and the first trigger. Graph size is the symptom; a node that
  has grown a paragraph is the disease.
* **A graph growing sideways under the root** is a prompt to ask whether the item has two goals in it.
* **A leaf `blocked` across more than one harvest** without being re-checked. A stale `blocked` is how
  a graph starts lying, which is exactly what the scratch file's banner records.
* **A subtraction node open across more than one harvest**, which is the one above with teeth. The
  graph is telling the truth here and the code is getting worse anyway, so nothing surfaces it but
  the trigger.
* **A branch marked `done` with an open leaf under it** is checkable rather than a matter of
  judgment, and it is the shape a skipped subtraction takes when somebody closes the branch on the
  strength of its additive leaves.

## First evidence from the trial

Two sessions added a sibling branch to R876's graph on the day it was converted, and harvesting both
at once is the first thing this proposal has been tested by.

The structural claim held and the prose one did not. Both branches were genuinely independent work,
and the merge conflict was a pure insertion collision, two `###` sections landing at one anchor,
which is the cheapest kind to resolve. What the merge produced that neither commit contained was a
dangling cross-reference: one branch pointed at a bullet under `++##++ Owed, not done here`, and the
other had moved that bullet into a node of its own. Each commit was correct alone and the pair was
not.

So leaves are orthogonal by construction, but prose references between branches are a coupling the
structure does not capture, and only the merge shows it. That is an argument for a node naming
another node rather than naming a section, and for the harvest reading cross-references after a
multi-branch merge. It is not an argument against the shape: under a phase list the same two authors
would have edited one numbered list and conflicted over its ordering as well as its text.

## Open question this item must settle

Two state systems. The board has `Backlog -> Spec -> Ready -> In Progress -> In Review -> Done` with
reviewer gates, and the tool renders the roll-up from that front-matter. A graph has `done`, `blocked`
and `open` per node. Either the item status becomes derived, `In Progress` when the graph has open
leaves and `Done` when the root is done, or the two stay independent with the graph as the plan. The
first is cleaner and is a real change to the tool and to `workflow.adoc`; the second is cheaper and
invites the two to drift. This item chooses one.

## The attempt in flight

R876 is being converted to a graph as the trial of this proposal, deliberately and while this item is
still Backlog. Its own phase list is the exhibit: ten numbered slices whose ordering was partly real
dependency written as prose (`Ordered after the dissolutions above`) and partly narrative. Whatever
the conversion costs and whatever it reveals is this item's evidence, and a conversion that goes badly
is a finding rather than a setback.

## Provenance

Session C added a Mikado graph of its two in-flight arcs to R876 so another author could tell what was
held and what was free. The section was held back from that harvest, not on quality: it would have put
a second plan structure into an item that already had one. Asking which structure should survive is
what produced this item.
