---
id: R729
title: "findColumn picks silently where two columns answer one spelled name"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# findColumn picks silently where two columns answer one spelled name

`JooqCatalog.findColumn(Table, String)` resolves a spelled column name against a table by matching
the generated Java name case-insensitively, then the SQL name case-insensitively, each with
`findFirst` over `Class.getFields()`. On a table whose column spellings collide case-insensitively,
which the quoted-identifier catalogs allow, more than one column answers and the method returns
whichever the reflective field order reached. That order is unspecified by the JVM, so the answer is
unstable rather than merely arbitrary, which is the same pathology
`intent_field_producer_method`'s comment already records for overloaded method names and answers
there by stating the arity instead of picking.

The fold itself is not the defect and must not be removed. Both tiers compare a *spelled* name
against a catalog reading, and a fold across that crossing is a semantic: an author writes `film`
for `FILM`, and a generated table class states a key-column name it may spell in either
convention. What is wrong is spending an ambiguity silently. The rule that belongs here is the one
the store states in `intent_stated_key_column_match`: gather every column that answers, let an exact
spelling win where more than one does, and report an irreducible ambiguity rather than picking.

Seventeen call sites in main reach it, spanning `ServiceCatalog`, `OrderByResolver`,
`InputBeanResolver`, `TypeBuilder`, `TenantScopeClassifier`, `ExternalFieldDirectiveResolver`,
`BuildContext`, `FieldBuilder` and `JooqCatalog`'s own index and key readers. A Spec starts with a
per-site reachability call in R702's manner, since the sites differ in what they do with an empty
result and therefore in what they can do with an ambiguous one: some already report a diagnostic and
want a second reason, some fall back and want the fallback.

R724 installs exactly this rule at one of those sites, the node-identity metadata probe, because a
store verdict about malformed metadata needs a Java enforcer or the build reports a table malformed
while the generator quietly emits against a column it picked. That site is not deferred here and
this item does not block on it; what it leaves behind is one worked example of the shape, which this
item generalises and then folds the special case into.

Distinct from R702, and the two must not be confused. R702 removes folds between two values the
catalog itself produced, where the fold is a hedge with no crossing to bridge. Every comparison here
has a real crossing and keeps its fold; only the silent pick goes.

