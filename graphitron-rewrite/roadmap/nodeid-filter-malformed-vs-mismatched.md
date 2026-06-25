---
id: R378
title: "Filter @nodeId decode should distinguish malformed input from wrong-type and report malformed as a user error"
status: Backlog
bucket: architecture
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Filter @nodeId decode should distinguish malformed input from wrong-type and report malformed as a user error

## Problem

A list `@nodeId` filter (`[ID!] @nodeId(typeName: T)` on an input-object field or top-level field argument) classifies to `CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement`: every element whose `decode<T>` returns `null` is silently dropped, and the surviving subset feeds the `WHERE col IN (...)` predicate (`TypeConditionsGenerator` `BodyParam.In`/`RowIn`). After R375, an all-dropped filter narrows by nothing and returns the unfiltered baseline; before R375 it zeroed the query. Either way the drop is silent.

The decoder (`NodeIdEncoder.decode<T>` → `decodeValues(expectedTypeId, base64Id)`) collapses two materially different failures into the same `null`:

[cols="1,3"]
|===
| Failure | What it means

| *Structurally malformed* — not valid base64, no `type:key` colon, wrong key arity
| Cannot be a node id of *any* type. It did not come from a server round-trip, so it is almost certainly a client bug.

| *Well-formed, wrong type* — a valid node id for a different `typeName` (e.g. an `Actor` id handed to a `Film` filter)
| A legitimate input under the Relay global-id model: heterogeneous id sets (search results, union/interface connections, cross-type caches) are filtered down to the one type the field selects.
|===

`SkipMismatchedElement` is the right policy for the *wrong-type* case: set-intersection semantics ("keep rows whose id is in this set") mean a wrong-type id simply is not in the set, and skipping preserves the cross-type-id-source pattern that is the whole reason filters skip rather than throw (contrast `ThrowOnMismatch`, used for lookup / mutation *keys*, where a bad id is a contract violation). It is the *wrong* policy for the *malformed* case: silently swallowing structurally invalid input masks a client error that the sibling `ThrowOnMismatch` arm would surface as a `GraphqlErrorException`.

## Why it matters

Surfaced during R375 review (see that item's "Implementation notes"). R375 correctly fixed the empty-list footgun (Apollo serialising an empty selection as `[]` = "no filter"), but a query that sends *only* malformed ids now returns the entire table with no signal that the input was bad. That is a more surprising silent outcome than the empty-list case R375 set out to fix, and it is reachable: the GraphQL type system guarantees only that each element is a non-null `ID` *string*; node-id well-formedness is opaque to it and is checked solely at decode time.

## Sketch (for Spec, not yet decided)

Two candidate shapes, to be weighed at Spec:

- *(a) Flip filters to `ThrowOnMismatch` wholesale.* Simplest; strict. Cost: breaks the legitimate heterogeneous-id-source pattern (any wrong-type id in a mixed set hard-fails the whole query), which is the case `SkipMismatchedElement` exists to serve. Likely too blunt.
- *(b) Split the decode result so the policy can fork on failure kind.* Give `decodeValues` a richer return than `null` (parse-failure vs type-mismatch), and have the filter arm throw on parse-failure while still skipping type-mismatch. Preserves set-intersection semantics and the cross-type pattern; reports genuine client errors. Costs a richer decoder return and a third behavioural arm. Recommended starting point.

Either shape must define behaviour for the *mixed* case (some malformed, some valid) and for *partial* drops, and decide whether the `ThrowOnMismatch` key path also wants the malformed-vs-mismatch distinction in its error message. The error contract should mirror the classifier in the validator per "Validator mirrors classifier invariants". Out of scope for this stub; decide at Spec.

## Relationship to R375

Orthogonal. R375 (shipped) handles the *empty list* correctly under whatever decode policy is in place; this item is about the *decode policy's* treatment of non-empty invalid input. R375 stands regardless of how this resolves.
