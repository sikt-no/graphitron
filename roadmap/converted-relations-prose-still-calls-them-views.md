---
id: R961
title: "A docs passage describes a converted relation as the view it stopped being"
status: Backlog
bucket: cleanup
priority: 6
theme: docs
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# A docs passage describes a converted relation as the view it stopped being

## Goal

A sentence describes `graphitron_spelled_table` as a thing it stopped being when a gatherer stage
started writing it, and it was edited by the commit that made it wrong. When this lands it says what
the relation now is, so a contributor reading the passage learns the shape the tree actually
ships.

`docs/architecture/explanation/naming-the-row.adoc` introduces `graphitron_spelled_table` as "a view
that answers a small, general question". It is a base table a capture stage reconciles, and the
sentence sits in a passage whose whole argument is the contrast between one stored answer table and
a layered derivation, so the noun is doing work rather than passing by. The bullet below it,
`intent_bound_table` "is a view one layer up", is still right, which is what makes the pair
misleading: a reader takes both nouns at face value. What the passage wants now is the shape the
conversion actually landed, derived and stored, which is a better version of the point it was making
than the one it makes.

`UnregisteredRelationTest`'s class javadoc and its `TARGET` constant named two different relations,
the javadoc still carrying the thirteen-readers rationale of a relation that had stopped being
registered while the constant had moved to one that still was. R955's first rung converted that
second relation in turn and repointed both together, so this surface reads true in the shipped tree
and only the naming-the-row passage above is left. The pattern the pair showed is what outlives it:
a conversion moves the constant, and a javadoc renamed in place rather than repointed states a
rationale about a relation nothing in the class names any more.

## Tests

No new case. The javadoc reference gate does not read prose nouns and no gate compares a class
comment against a constant beside it, so this is a read-and-correct rather than something to pin.

## Provenance

Found at R954's In Review -> Done gate, in the tree that item shipped; both sentences were touched by
its rename pass. Filed rather than corrected there, the gate being findings-not-fixes and the
corrections belonging to whoever next reads the passages whole.
