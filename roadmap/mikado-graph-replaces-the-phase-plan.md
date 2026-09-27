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
