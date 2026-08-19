---
id: R691
title: "multi-hop-nodeid-filter.adoc overstates the single-direct-FK no-JOIN claim"
status: Backlog
bucket: documentation
priority: 6
theme: nodeid
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# multi-hop-nodeid-filter.adoc overstates the single-direct-FK no-JOIN claim

`docs/manual/how-to/multi-hop-nodeid-filter.adoc` opens by contrasting multi-hop chains against the easy
single-hop case, and overstates the easy case:

> With a single direct FK, that property is automatic: the FK source columns are on the parent's row, so
> the predicate is `WHERE parent.fk_columns IN (decoded_keys)`. No JOIN, no subquery; one column tuple,
> one IN clause.

"Automatic" is the wrong word, and now visibly so. A single direct FK gives you the no-JOIN shape only
when the FK's target-side columns are the NodeType's key columns; when the FK targets some *other*
unique column, the parent's row holds no column carrying the decoded key, and the filter emits a
correlated `EXISTS` over the FK instead (`NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk`, lowered
through `BodyParam.RemoteColumnPredicate`). The claim was already imprecise before that emission shipped
(the translated single-hop shape never had the no-JOIN property, it was simply rejected), and shipping
the emitter made it wrong in a second way: there is now a single-direct-FK schema whose generated SQL is
a subquery, which the page tells the reader cannot happen.

The fix is a paragraph, not a restructure: the page's subject is multi-hop chains and its multi-hop
content is accurate. Name the two single-hop shapes and what discriminates them (does the FK target the
NodeType's key columns), and point at the reference material for the translated one, so the contrast the
page is drawing survives with a true premise.

**Absorbed by R728 (`nodeid-effective-at-every-coordinate`); this file is a tombstone.** That item
edits this same page twice on its own account: it retires the page's `=== identity-carrying FKs`
rejection section when it makes junction chains authorable, and it corrects the reverse-hop guidance
in the section this item is about. Three passes over one file to fix one page's account of one
mechanism is what absorption avoids, so the paragraph above ships inside R728 and this file deletes
at that item's Done gate. Nothing here is withdrawn: if R728 is discarded or descoped away from the
page, this item stands again as written.
