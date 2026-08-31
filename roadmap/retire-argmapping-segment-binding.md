---
id: R896
title: "Price intent_argmapping_segment_binding against the candidate tree that took its readers"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Price intent_argmapping_segment_binding against the candidate tree that took its readers

`intent_argmapping_segment_binding` reconstructed how a written argMapping path lines up against the
input surface it descends through. The relations above it did that by aligning two decompositions of
one descent, the authored path's segments by position against the input type's occurrences by ordinal,
with an anti-join inside an anti-join.

**The candidate tree took that work away.** `graphitron_argmapping_candidate` spells its own paths the
way the authored segment relation spells prefixes, so how far a path resolves is now the deepest
matching prefix, an equality join ranked by depth. The relation that used to need the alignment,
`intent_argmapping_binding_leaf`, states in its own comment that both decompositions now meet on a
column capture writes and neither is reconstructed. The plan-level evidence agrees: in one read of
`intent_node_id_decode` the occurrence-path step relation fell from thirty-six instantiations to zero.

**What is not established is that nothing else reads it.** Six instantiations of
`intent_argmapping_segment_binding` still appeared in that plan at the measured commit, and a relation
with a reader is not retired by observing that its original reason went away. This item owes the
census: who names it, what each of them is asking, and whether the candidate tree answers that
question or a different one.

Retire it if the census comes back empty or repointable. If it comes back with a reader the tree
cannot serve, the useful output is the statement of what that reader needs, which is a modelling fact
rather than a cleanup.

