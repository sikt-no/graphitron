---
id: R463
title: "Consume R279's ancestor-cardinality rider: fold true arrival and populate Source.OnlyChild"
status: Backlog
bucket: structural
theme: classification-model
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Consume R279's ancestor-cardinality rider: fold true arrival and populate Source.OnlyChild

Every `ChildField` hard-codes its arrival as `Source.Child` (`ChildField.source()`), the conservative
many-arrival absorber; `Source.OnlyChild` (single arrival, direct SQL instead of a one-element
DataLoader batch) is producible but unreached. R279 landed an ancestor-cardinality rider on the
classification walk context precisely so the true ancestor-product arrival fold could be computed
without re-walking, and `WrapperAlgebraTest.sourceWrapperIsTheFoldOfAncestorTargetWrappers` pins the
conservative `Child` strength until that fold exists. This item computes the fold, populates
`Source.OnlyChild` where the ancestor product is `One`, and lifts the `WrapperAlgebraTest` pin. The
in-code forward references that used to name "R279 / R308" for this work (`ChildField.java` `source()`
javadoc, `Source.java` `OnlyChild` javadoc, the `WrapperAlgebraTest` pin comment) are retargeted to
this item by R308's respec; R308 itself models carrier arrival on the `@service` payload seat only and
deliberately leaves the general fold here. The emit half (an `OnlyChild` direct-SQL strategy actually
consuming the arm) likely rides the R431 -> R432 -> R314 emit re-platforming chain; scope the split at
Spec time.
