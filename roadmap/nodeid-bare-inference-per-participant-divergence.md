---
id: R726
title: "Bare @nodeId inference on a multitable filter can answer differently per participant with no diagnostic"
status: Backlog
bucket: bug
priority: 4
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-20
---

# Bare @nodeId inference on a multitable filter can answer differently per participant with no diagnostic

A `@nodeId` without `typeName:` on a filter input field or argument infers its node type from the containing table (`NodeIdLeafResolver.inferTypeName`, backed by `ctx.nodes.forTable`). On a query returning a multitable interface, classification re-runs once per participant with that participant's table, so the inference re-runs too: each branch can infer a different node type, or one branch can reject as ambiguous while its siblings resolve, and nothing tells the author the leaf means different things on different branches. The decoded filter then compares against differently-typed keys per branch, silently. The likely shape of the fix is a consistency check at the consuming field (all participants must infer the same node type, otherwise demand an explicit `typeName:`), which matches how `inferTypeName` already prefers rejection with a "specify explicitly" message over guessing at a single coordinate. Surfaced while speccing the per-participant join-path item for the same coordinate; the participant-identity threading that item builds gives this check its natural seam.

## The instruction relation cannot state this question yet (2026-08-20)

The `@nodeId` instruction population became a store relation on 2026-08-20, which changes where
this bug should be detected and adds a prerequisite the original filing did not know about.

`intent_node_id_instruction` resolves a bare directive's node type in two arms, and both reach the
slot's table through `intent_argument_scope_table`. That relation demands an unambiguous binding
and says so in its own comment: two candidate tables are two different predicates, so a pair that
is not certain is not the pair the classifier would have had in hand. A field returning a
multitable interface or union has no such binding, so **the coordinate this item is about
currently produces no instruction row at all**: not a divergent one, and not an ambiguous one.
The `TARGET_TABLE_NODE_TYPE` arm additionally demands `candidates = 1`, and the name-carried
`TARGET_ID_NAME` arm joins the field's return type to `intent_node_type`, which a multitable
interface is not.

So the relation has no participant dimension at any grain, and nothing pins the silence:
`NodeIdInstructionTest` has no interface or union case. Its nearest case,
`oneInputFieldConsumedTwiceIsTwoRowsThatCanDisagree`, establishes disagreement across two
*consuming fields*, which is the use-site grain rather than the participant grain this item needs.

Two consequences for this item's shape. The fix's natural home moves: a consistency check stated
over the population is a query, not a pass over `inferTypeName`'s call sites, and it lands beside
the defect stratum rather than inside the resolver. But it cannot be written until the population
reaches the coordinate, so **the prerequisite is a participant-keyed arm on the instruction
relation** (or an explicit decision that the multitable coordinate is out of the population, in
which case this item becomes that rejection instead). That prerequisite is shared with R673 and
R676, and it is also a hole in the relation-move item's own claim that the resolution relations
and its defect view partition the instruction population: this coordinate falls in neither.
Raised there rather than duplicated here.

Detail and the verification behind it: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`,
Finding 1.
