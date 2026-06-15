---
id: R310
title: "Forbidden directive on an otherwise-valid DML payload carrier silently misdirects to 'use ID or a @table type'"
status: Backlog
bucket: bug
theme: mutations-errors
depends-on: []
created: 2026-06-15
last-updated: 2026-06-15
---

# Forbidden directive on an otherwise-valid DML payload carrier silently misdirects to 'use ID or a @table type'

When a `@mutation(typeName: INSERT/UPDATE/DELETE)` field returns a payload type whose single data field carries a directive from the DML carrier's forbidden set, the structural scan (`BuildContext.scanStructuralPayload`, `CarrierFamily.DML`) returns `DmlPayloadScan.NotApplicable` and the type is never promoted to a carrier. The author gets no signal about the real cause. Instead the unclassified return type falls through `resolveReturnType` to `ScalarReturnType`, lands in `classifyUpdateTableField` / the INSERT scalar arm, and surfaces as `"@mutation(typeName: ...) return type '<Payload>' is not yet supported; use ID or a @table type"`. That points the author at the *return type* (which is fine) instead of the *one directive on the data field* that actually disqualified it. The payload was a correct R258/R178 carrier shape minus that directive — the fix is a one-token edit, but the diagnostic gives no hint of it.

## Symptom

Real instance (downstream `utdanningsregisteret`, Graphitron 10 migration):

```graphql
type EndreUtdanningsmulighetPayload {
  utdanningsmulighet: [Utdanningsmulighet!]! @splitQuery   # @splitQuery is DML-forbidden
  errors: [EndreUtdanningsmulighetError!]
}

extend type Mutation {
  endreUtdanningsmulighet(input: [EndreUtdanningsmulighetInput!]!): EndreUtdanningsmulighetPayload @mutation(typeName: UPDATE)
}
```

`Utdanningsmulighet` is a `@table` type matching the input table, and the input covers the PK via `@nodeId` — i.e. a valid `MutationBulkUpdatePayloadField` (R258) in every respect except the `@splitQuery` on the data field. The build reports:

```
Field 'Mutation.endreUtdanningsmulighet': @mutation(typeName: UPDATE) return type 'EndreUtdanningsmulighetPayload' is not yet supported; use ID or a @table type
```

The author edits return types, payload bindings, and the input — none of which is wrong — because nothing names `@splitQuery`. Removing the single directive (and making the field nullable per the success-projection rule) classifies it cleanly. Diagnosing it here required reading the classifier source.

## Trace

1. `BuildContext.scanStructuralPayload(payloadSdlName, CarrierFamily.DML)` walks the data field; the forbidden-directive loop (`for (String forbidden : family.forbiddenDataFieldDirectives)`) hits `@splitQuery` and returns `DmlPayloadScan.NotApplicable()` — deliberately silent, because the scan runs speculatively over *all* directiveless object types in `TypeBuilder.promoteSingleRecordPayloads` and must not hard-reject non-carriers.
2. With no `DmlEmitted` binding grounded, `promoteSingleRecordPayloads` leaves the type unclassified.
3. `BuildContext.resolveReturnType` finds no `ResultType` in the `types` map and returns `ScalarReturnType`.
4. `FieldBuilder` routes UPDATE/DELETE scalar returns to `classifyUpdateTableField` / `classifyDeleteTableField` (INSERT to the scalar arm of `validateReturnType`), all of which emit the generic `"is not yet supported; use ID or a @table type"` (`MutationInputResolver.java:188`).

The DML forbidden set is broad — `@service, @sourceRow, @reference, @asConnection, @splitQuery, @externalField, @condition, @lookupKey, @notGenerated, @tableMethod, @defaultOrder, @orderBy, @multitableReference` (`FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES`) — so the misdirection reproduces for any of them, not just `@splitQuery`.

## Proposed direction

Add a field-edge diagnostic (not a change to the speculative scan, which must stay silent for non-carriers). When a `@mutation` field's return type fails to classify *and* re-running the scan with the forbidden-directive check disabled would `Admit`, emit a targeted rejection naming the offending field and directive — e.g. *"payload 'EndreUtdanningsmulighetPayload' data field 'utdanningsmulighet' carries `@splitQuery`, which is not allowed on a DML payload carrier's data field (it signals a different fetcher contract); remove it. `@splitQuery` is tolerated only on `@service`-backed carriers."* The DML-vs-`@service` asymmetry (R275: `@splitQuery` is redundant-but-tolerated on `ServiceEmitted` carriers) should be stated so authors understand why the same directive is fine on a sibling `@service` mutation. Mechanically this can reuse `scanStructuralPayload` via a "would-admit-but-for-forbidden-directives" probe, surfaced through the same field-edge path as R213's attribution work.

Sibling of [R213 (`input-field-rejection-attribution`)](input-field-rejection-attribution.md): same class of defect (a real, fixable author error rendered as a misdirected diagnostic that names the wrong site/cause). Discovered during the `utdanningsregisteret` Graphitron 10 migration.
