---
id: R193
title: "Sealed UnresolvedParam classification for @service parameter rejection arms"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-07-15
---

# Sealed UnresolvedParam classification for @service parameter rejection arms

The diagnostic-arm decision inside `ServiceCatalog.reflectServiceMethod` (`ServiceCatalog.java:258-329`, the `sourcesShape.isEmpty()` block) is a chain of predicates over the unresolved Java parameter: `classifySourcesType().isEmpty()`, then `pName == null`, then `parentPkColumns.isEmpty() && looksLikeSourcesShape(...)`, then `dtoSourcesRejectionReason(...) != null`, then the generic "unrecognized sources type" fall-through. Two recent bug items (R185 root, R187 nested) each adjusted the precedence in different directions ; R185 narrows the SOURCES-batch arm so `List<XRecord>` at root falls through to the arg-mismatch diagnostic; R187 drops the `parentPkColumns.isEmpty()` gate so the arg-mismatch arm fires at nested coordinates whenever the parameter isn't SOURCES-adjacent. Both fixes are correct, both ship as small surgical diffs, but the cumulative shape is a fan-out of overlapping predicates with no single record that says *which classification the parameter actually fell into*. The principles-architect review on R187 flagged this directly: precedence is a property of the classifier, not the diagnostic emitter, and asking it in two places invites future bugs whenever the predicate set grows again.

The proposed shape is a sealed `UnresolvedParam` (or similar) classifying the parameter once into a small set of arms ; candidates include `NoParametersFlag`, `SourcesBatchAtRoot(elementShape)`, `DtoActionable(reason)`, `NameMismatch(available, suggestion)`, `UnclassifiedSourcesType(typeName)` ; with the rejection text living in one `switch` over the result. Precedence becomes a property of one classifier, validator-mirrors-classifier applies trivially, and questions like "what does the classifier emit for `List<DTO>` with a Java-param name typo at a child coordinate?" are answered in one place with a unit test pinning it (today the same question yields different answers depending on which arm the chain enters first). Sketch: a `static UnresolvedParam classify(java.lang.reflect.Parameter, List<ColumnRef> parentPkColumns, Map<String, PathExpr> argByJavaName, Set<String> ctxKeys, Map<String, GraphQLInputType> slotTypes)` returning the sealed arm; the existing helpers (`classifySourcesType`, `looksLikeSourcesShape`, `dtoSourcesRejectionReason`, `unambiguousReachablePath`, `formatNameSet`) become implementation details of `classify`, and the call site at `ServiceCatalog.java:258-329` collapses to roughly five lines of arm dispatch.

Scope when this is picked up: subsumes R185 and R187 if they haven't shipped yet (or refactors over the top of them if they have); pins the chosen precedence between DTO-hint and arg-mismatch on `List<DTO>` with name typo (R187 chose DTO-hint wins on child; this is the right place to record that as a property of the classifier, not as a comment on a branch); preserves every existing diagnostic substring covered by `ServiceCatalogTest` so the rejection-message contract holds across the refactor. Out of scope: changing what the validator does with rejections, broadening the classifier to non-`@service` accessor paths, or moving rejection messages into i18n bundles.

Not blocked. Right time to pick this up is after R185 and R187 have either shipped or been folded in; before then, this item carries the architectural debt and the two bugs carry the user-visible fixes, with the trade-off recorded in R187's "Architectural smell (acknowledged, deferred)" section.

R72 (`slim-servicecatalog-to-lookup`) wants to move `reflectServiceMethod`'s policy and rejection-wording out to the caller; this item's sealed classifier plus single switch owns that rejection text and is a plausible vehicle for R72's extraction. Sequence but do not duplicate.
