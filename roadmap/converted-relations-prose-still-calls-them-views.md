---
id: R961
title: "Two prose surfaces describe a converted relation as the view it stopped being"
status: Backlog
bucket: cleanup
priority: 6
theme: docs
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# Two prose surfaces describe a converted relation as the view it stopped being

## Goal

Two sentences describe `graphitron_spelled_table` and `graphitron_resolved_type_binding` as things
they stopped being when a gatherer stage started writing them, and both were edited by the commit
that made them wrong. When this lands, each says what the relation now is, so a contributor reading
either learns the shape the tree actually ships.

`docs/architecture/explanation/naming-the-row.adoc` introduces `graphitron_spelled_table` as "a view
that answers a small, general question". It is a base table a capture stage reconciles, and the
sentence sits in a passage whose whole argument is the contrast between one stored answer table and
a layered derivation, so the noun is doing work rather than passing by. The bullet below it,
`intent_bound_table` "is a view one layer up", is still right, which is what makes the pair
misleading: a reader takes both nouns at face value. What the passage wants now is the shape the
conversion actually landed, derived and stored, which is a better version of the point it was making
than the one it makes.

`UnregisteredRelationTest`'s class javadoc says "The relation under test is
`graphitron_resolved_type_binding`, chosen because it is named from thirteen view bodies". The
class's `TARGET` constant reads `intent_field_column_scope` and its `DEPENDENT` reads
`intent_field_column_table`: the relation it used to exercise stopped being registered, so the case
moved to one that still is, and the javadoc was renamed in place rather than repointed. The
thirteen-readers rationale belongs to whichever relation the constant names.

## Tests

No new case. The javadoc reference gate does not read prose nouns and no gate compares a class
comment against a constant beside it, so this is a read-and-correct rather than something to pin.

## Provenance

Found at R954's In Review -> Done gate, in the tree that item shipped; both sentences were touched by
its rename pass. Filed rather than corrected there, the gate being findings-not-fixes and the
corrections belonging to whoever next reads the passages whole.
