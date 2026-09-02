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

**One name is also wrong about what it holds.** `graphitron_arg_mapping_pair` carries "pair" from a
shape it no longer has. Both halves of an argMapping are on the row, but the row is one authored
binding at a coordinate, and "pair" says nothing a reader can use.

The other one landed elsewhere and is named here so this item is not read as still owing it.
`intent_argmapping_binding_leaf` is now `graphitron_argmapping_match`, renamed in R876 rather than
deferred to this sweep, because its defect was not spelling. Every relation it reads belongs to the
graphitron gatherer, so the `intent_` prefix on it recorded a placement nobody chose, and correcting
the noun and the family is one edit rather than two. It is the schema's first `graphitron_` view.

This is a rename with no behaviour in it, which makes it cheap and makes it easy to keep postponing.
The cost of postponing is paid by every reader of the schema, and the schema is the documentation.

Settle the spelling once, correct the two inaccurate names, and check whether the retirement sweep at
the Done gate reaches the generated jOOQ identifiers and the test sources that name them.

