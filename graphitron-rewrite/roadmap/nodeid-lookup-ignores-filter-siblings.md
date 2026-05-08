---
id: R106
title: "Lookup classification short-circuits past filter-semantic siblings on NodeId-typename match"
status: Backlog
bucket: validation
depends-on: []
---

# Lookup classification short-circuits past filter-semantic siblings on NodeId-typename match

A query field that takes an input type containing a same-typename `@nodeId` field (e.g. `field(input: { id: ID @nodeId(typename: "Customer"), name: String, active: Boolean }): Customer`) is classified as a `QueryLookupTableField` even when the input also carries fields with filter semantics. The intended shape there is a search/filter, not a single-record lookup; the misclassification routes the field through the lookup pipeline (N×M derived-table contract) and silently drops the filter siblings from the generated SQL.

Root cause: the classifier short-circuits on the first NodeId-typename match without inspecting siblings. `NodeIdLeafResolver.resolve` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/NodeIdLeafResolver.java:258`) returns `Resolved.SameTable` whenever the `@nodeId(typeName:)` annotation's table equals the field's return-type table; `FieldBuilder.buildNodeIdArgPlan` records that as `anyArgSameTable = true` at the first hit (see `walkInputTypeForSameTableNodeId` around `FieldBuilder.java:299`); and `classifyQueryField` (`FieldBuilder.java:2336,2350`) then routes to `QueryLookupTableField` purely on `anyArgSameTable()`, regardless of sibling input fields or sibling arguments. The same shape exists on the child-field path (`FieldBuilder.java:456,468-475`) via `hasLookupKeyAnywhere` / `inputTypeHasLookupKey` (`FieldBuilder.java:2510-2530`), which similarly recurses into nested input types and returns on first hit.

Out of scope for this item — captured here for the Spec author: decide *what* the right shape for "NodeId + filter siblings" is. Two candidates: (a) reject at validation time and require the schema author to split the lookup-by-id field from the search field; (b) classify as a search/filter field and treat the NodeId argument as one filter among many. Option (a) is consistent with classifier-mirrors-validator and avoids inventing new emitter shapes; option (b) is friendlier to schema authors but expands the search/filter classifier's responsibility. The Spec needs a deliberate pick with a justification, not a hedge.

Test gap to close in the Spec: `LookupTableFieldValidationTest`, `QueryLookupTableFieldValidationTest`, and `LookupTableFieldPipelineTest` cover NodeId-only inputs but none mix NodeId with filter siblings. The Spec must name the new pipeline-tier and validation-tier cases that pin whichever resolution is chosen.
