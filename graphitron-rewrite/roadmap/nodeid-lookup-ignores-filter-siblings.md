---
id: R106
title: "Lookup classification short-circuits past filter-semantic siblings on NodeId-typename match"
status: Spec
bucket: validation
depends-on: []
---

# Lookup classification short-circuits past filter-semantic siblings on NodeId-typename match

A query field that takes an input type containing a same-typename `@nodeId` field (e.g. `field(input: { id: ID @nodeId(typename: "Customer"), name: String, active: Boolean }): Customer`) is classified as a `QueryLookupTableField` even when the input also carries fields with filter semantics. The classifier short-circuits on the first NodeId-typename match: `NodeIdLeafResolver.resolve` returns `Resolved.SameTable` whenever the `@nodeId(typeName:)` annotation's table equals the field's return-type table (`NodeIdLeafResolver.java:258`), `FieldBuilder.buildNodeIdArgPlan` records that as `anyArgSameTable = true` at the first hit, and `classifyQueryField` (`FieldBuilder.java:2336`) routes to `QueryLookupTableField` purely on `anyArgSameTable()` regardless of sibling input fields or sibling arguments. The same shape exists on the child-field path via `hasLookupKeyAnywhere` / `inputTypeHasLookupKey` (`FieldBuilder.java:2510-2530`), which recurses into nested input types and returns on first hit.

Mixing a NodeId lookup with sibling filter arguments is not a valid graphitron shape: `QueryLookupTableField` exists to deliver the N×M cartesian-on-PK contract documented in `code-generation-triggers.adoc:68-88`; layering filter predicates on top is a different operation (search/filter narrowing). The classifier should not silently absorb such fields into the lookup arm. Per *Validator mirrors classifier invariants*, the right shape is to surface it as a validator rejection (`UnclassifiedField`) rather than to emit a half-correct lookup or to invent a new "lookup-with-filters" emitter arm.

## Goal

Reject `QueryLookupTableField`-classified fields whose argument set carries non-lookup-semantic siblings alongside the same-typename NodeId. The author resolves the rejection by either dropping the filter siblings (keeping the field as a single-record lookup) or restructuring as a separate search field — the latter being out of scope here and tracked under `faceted-search.md`.

## Implementation

- `GraphitronSchemaValidator.java`: new check that fires on `QueryField` candidates entering the lookup-promotion gate. After `hasLookupKeyAnywhere(fieldDef) || lookupPlan.anyArgSameTable()` evaluates true at `FieldBuilder.java:2336`, the validator inspects sibling arguments and sibling input-type fields:
  - "Lookup-semantic" args/fields are: `@nodeId`-decorated leaves, `@lookupKey`-decorated args/fields, and pagination args (`first`, `after`, `last`, `before`) per the existing connection-arg recogniser.
  - Anything else (a plain scalar arg, a non-lookup-decorated input field) is a filter sibling and triggers rejection.
  - The error message names the rejected field, the offending sibling argument or input-field path, and points at the two resolutions (drop the sibling; or restructure as a search field — link to `faceted-search.md`).
- `FieldBuilder.classifyQueryField` and the parallel child-field path (`FieldBuilder.java:456,468-475`): on validator rejection, route through the existing `UnclassifiedField` arm, mirroring how other classifier-side rejections surface today.
- `FieldBuilder.buildNodeIdArgPlan` and `walkInputTypeForSameTableNodeId`: no behaviour change — the validator runs alongside the existing classification, not inside it. The existing short-circuit is fine for *valid* lookup shapes; the validator catches the *invalid* shapes the short-circuit currently lets through.

## Tests

Pipeline-tier (primary behavioural tier per `rewrite-design-principles.adoc`):

- `QueryLookupTableFieldValidationTest`: new `rejects_nodeIdLookup_withFilterSibling_argument` case — a query field with a same-typename `@nodeId` argument and a sibling scalar argument; assert the schema-validator emits the new rejection diagnostic and the field classifies as `UnclassifiedField`.
- `QueryLookupTableFieldValidationTest`: new `rejects_nodeIdLookup_withFilterSibling_inputField` case — same shape but the NodeId is nested inside an input type alongside a filter input-field; assert rejection.
- `QueryLookupTableFieldValidationTest`: new `accepts_nodeIdLookup_withPaginationSiblings` case — pagination args alongside the NodeId classify as `QueryLookupTableField` cleanly (pin: pagination is not a "filter sibling").
- `LookupTableFieldValidationTest`: the parallel child-field cases (`rejects_childLookup_withFilterSibling_argument`, `rejects_childLookup_withFilterSibling_inputField`).

Unit-tier:

- A targeted test on the new validator helper that the predicate "argument is lookup-semantic" classifies the four positive cases (`@nodeId`, `@lookupKey`, pagination, the NodeId-inside-input recursion) and rejects everything else.

Existing pipeline-tier `LookupTableFieldPipelineTest` and `NodeIdPipelineTest` cases stay green: every fixture there uses pure-lookup inputs (no filter siblings), so the new validator is a no-op against them.

## Open question for the reviewer

The implementation rejects mixed shapes. The genuine alternative — classifying mixed shapes as a search/filter field — is rejected here on two grounds: (a) graphitron does not yet have a "search field" classifier arm, so option (b) would invent a new emitter shape with no existing precedent; (b) `faceted-search.md` already tracks the search-field problem space, and folding it into this item conflates a bug fix with a feature lift. Reviewer input wanted before sign-off: is the rejection direction the right call, or is the user-facing impact (existing schemas with this shape suddenly failing the build) load-bearing enough to warrant a softer migration path (e.g. WARN once, then ERROR in a future release)?

## Out of scope

- Adding a search/filter classifier arm. Tracked under `faceted-search.md`.
- The rendered error message's wording; pin the diagnostic key in the implementation, draft prose can iterate.
- Touching the `@lookupKey`-only path (no NodeId involved). The same `inputTypeHasLookupKey` short-circuit exists there, but the directive is explicit user intent, not implicit; rejection on filter siblings under explicit `@lookupKey` is a user-surface change worth a separate item if it ever shows up in practice.
