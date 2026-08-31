---
id: R900
title: "The argMapping relations spell their own subject three different ways"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# The argMapping relations spell their own subject three different ways

The argMapping relations spell their own subject three ways in one schema. `graphitron_arg_mapping_pair`
and `graphitron_service_arg_mapping_sigil` use `arg_mapping`; `graphitron_argmapping_candidate` and
six `intent_argmapping_*` views use `argmapping`; the directive itself is `@argMapping`. Nothing turns
on the difference, which is exactly the problem: a reader who has seen one spelling cannot type the
other from memory, and neither can a search.

**Two of the names are also wrong about what they hold, and the schema already admits one of them.**
`intent_argmapping_binding_leaf`'s own comment ends by saying so: a leaf in the candidate tree is a
candidate with no children, while what that relation names is where a written path stopped, which is
routinely an interior candidate, and that is precisely the case its `trailing_segments` column counts.
Separately, `graphitron_arg_mapping_pair` carries "pair" from a shape it no longer has. Both halves of
an argMapping are on the row, but the row is one authored binding at a coordinate, and "pair" says
nothing a reader can use.

This is a rename with no behaviour in it, which makes it cheap and makes it easy to keep postponing.
The cost of postponing is paid by every reader of the schema, and the schema is the documentation.

Settle the spelling once, correct the two inaccurate names, and check whether the retirement sweep at
the Done gate reaches the generated jOOQ identifiers and the test sources that name them.

