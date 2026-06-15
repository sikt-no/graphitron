---
id: R310
title: "Forbidden directive on an otherwise-valid DML payload carrier silently misdirects to 'use ID or a @table type'"
status: In Review
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

## Design

The fix is localized to the single site where all three DML kinds converge: the `ScalarReturnType` arm of `MutationInputResolver.validateReturnType` (`MutationInputResolver.java:171-189`). That arm already carries the precedent. It runs `ctx.scanStructuralDmlPayload(name)` and, on a `Reject`, surfaces the scan's specific reason instead of the generic message (`:179-186`); the generic `"use ID or a @table type"` string (`:187-188`) is only the fall-through when the scan returns `NotApplicable`. R310 adds a second probe, between the existing `Reject` probe and the generic fall-through, that fires when the type would classify as a carrier but for a forbidden directive on its data field.

### Why one arm covers all three DML kinds

This is the load-bearing invariant the single-site fix relies on: a forbidden directive on the data field makes the speculative scan return `NotApplicable` (`BuildContext.java:598-602`), so `promoteSingleRecordPayloads` never binds the type (`TypeBuilder.java:281-289`), `resolveReturnType` finds no `ResultType` and returns `ScalarReturnType`, and every DML kind's return-type resolution lands on that arm:

- UPDATE: the `instanceof ResultReturnType` test at `FieldBuilder.java:3383` is false, so the field falls to `classifyUpdateTableField` (`:3386`), which calls `validateReturnType` at `:3550`.
- DELETE: symmetric, via `classifyDeleteTableField` (`:3401` then `:3757`).
- INSERT / UPSERT: inline at `:3415`.

The single probe is sufficient because this chain holds. It is currently an emergent property of three separate sites rather than a pinned one, so the Tests section exercises it across two distinct call sites (`classifyUpdateTableField` and the inline INSERT path) to guard against a future change that promotes a forbidden-directive carrier through some other path, routing it to `ResultReturnType` and silently bypassing the probe.

### The would-admit-but-for-the-directive probe

A new `BuildContext` port answers exactly the question the bug names: would this payload classify as a DML carrier if not for the forbidden directive, and if so, which directive (on which field) blocked it?

```java
public record ForbiddenCarrierDirective(String dataFieldName, String directiveName) {}
public Optional<ForbiddenCarrierDirective> diagnoseForbiddenCarrierDirective(String payloadSdlName)
```

Mechanism: re-run the structural DML scan with the forbidden-directive check disabled. If that pass `Admit`s, the payload is a structurally valid DML carrier in every respect except the forbidden directive; read the offending directive off the admitted data field (the first directive in `FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES` the field carries) and return it. If the pass `Reject`s or is `NotApplicable`, the directive is not the sole blocker and the port returns empty, so the arm falls through to the generic message (correct: the type has a different or additional problem). Reading the directive off the `Admit`-returned data field is a single-field lookup, not a re-walk of the payload SDL.

Disabling the forbidden check without muddying the public family-parameterized contract: add a private `enum ForbiddenDirectivePolicy { ENFORCE, IGNORE }` and a private overload `scanStructuralPayload(name, family, policy)`. The two public methods (`scanStructuralDmlPayload`, `scanStructuralServiceCarrierPayload`) delegate with `ENFORCE`, so their behavior is byte-for-byte unchanged and every speculative caller (`promoteSingleRecordPayloads`, the existing `Reject` probe) is unaffected. The forbidden loop (`:598-602`) runs only under `ENFORCE`; `IGNORE` is consulted by `diagnoseForbiddenCarrierDirective` alone. The `family` passed is still `DML`, so the family's other policy axis (ID-element wrapper admission, `:617-636`) still applies under `IGNORE`: the question is specifically "would this admit as a *DML* carrier if not for the forbidden directive", not "would it admit under some looser family". The forbidden *set* still comes from the family; `ForbiddenDirectivePolicy` is an orthogonal, private gate on whether that set is consulted, not a third public family. The `CarrierFamily` named axis (`:519-529`) and its set/wrapper coupling are left untouched.

Rejected alternatives:

- Enriching the public `DmlPayloadScan.NotApplicable` to carry the offending directive: insufficient alone (the scan short-circuits at the directive before the element-shape and cardinality checks, so it cannot establish would-admit), and widening a public sealed type for a private diagnostic is the wrong grain.
- Taint-and-continue (record the directive, keep classifying, veto at the end): flips the currently-observable precedence of forbidden-directive over shape rejection (today the forbidden check short-circuits before the shape checks, so a forbidden-plus-bad-shape type returns `NotApplicable`, not `Reject`); the `IGNORE`-pass approach preserves that precedence because the public `ENFORCE` path is unchanged.
- A third public `CarrierFamily` with an empty forbidden set: conflates the two coupled family axes (forbidden set and ID-wrapper policy) that the `CarrierFamily` doc comment exists to keep distinct.

### Scope: message, not location

R310 fixes the message *content* so it names the offending field and directive. The rejection's `SourceLocation` stays on the `@mutation` field's `UnclassifiedField`, matching the existing `Reject` arm and every sibling `validateReturnType` rejection (the `Rejection` taxonomy carries no `SourceLocation`; location is owned one layer up by `UnclassifiedField`). Relocating the location to the payload data field is R213's separate concern (`input-field-rejection-attribution.md`); `depends-on` is empty and the two are orthogonal (R310 changes the message, R213 will later move the carrier). The message names the field and directive in prose specifically so it still reads correctly if R213 later relocates the carrier to the data field's location.

### Message

Prefixed like the existing arm (`@mutation(typeName: <kind>) return type '<Payload>': `):

> payload data field '<field>' carries @<directive>, which a DML payload carrier's data field may not have (it signals a different fetcher contract than the carrier's record-backed data-field path); remove it so the payload classifies as a carrier.

When the directive is `@splitQuery`, append the asymmetry note, phrased to match what R275 actually pins (redundant and *ignored with an advisory* on `@service`, not silently accepted):

> @splitQuery is redundant on an @service-backed carrier's data field (which already resolves through a PK-keyed follow-up SELECT, where it fires the `warnIfSplitQueryOnRecordParent` advisory), but it is rejected on a DML carrier.

The rejection keeps the prose `Rejection.structural` shape. Every arm of `validateReturnType` is uniformly prose today (Int, Boolean, Connection, polymorphic, list-payload), so typing this one rejection as a new `AuthorError` arm would single it out asymmetrically; a typed `ReturnTypeError` sub-seal lift across the whole `validateReturnType` surface is a larger architecture item (noted under References), out of scope for this bug fix.

## Implementation

- `BuildContext.java`
  - Add private `enum ForbiddenDirectivePolicy { ENFORCE, IGNORE }`.
  - Add a private overload `scanStructuralPayload(String, CarrierFamily, ForbiddenDirectivePolicy)`; the existing two-arg private core becomes a delegate that passes `ENFORCE` (or the two public methods call the three-arg form with `ENFORCE` directly). Gate the forbidden loop (`:598-602`) on `policy == ENFORCE`.
  - Add the public record `ForbiddenCarrierDirective(String dataFieldName, String directiveName)` near `DmlPayloadScan`.
  - Add public `Optional<ForbiddenCarrierDirective> diagnoseForbiddenCarrierDirective(String payloadSdlName)`: run `scanStructuralPayload(name, CarrierFamily.DML, IGNORE)`; on `Admit(dataField, _)`, return the first `FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES` directive `dataField` carries (as `@`-prefixed) paired with `dataField.getName()`; otherwise `Optional.empty()`.
- `MutationInputResolver.java`
  - In the `ScalarReturnType` arm (`:179-189`), after the existing `Reject` probe and before the generic `yield`, consult `ctx.diagnoseForbiddenCarrierDirective(s.returnTypeName())`; when present, `yield` the targeted message (with the `@splitQuery` asymmetry note appended conditionally). The generic `"use ID or a @table type"` stays as the final fall-through for genuinely unsupported scalar returns (`Int`, `Boolean`).

## Tests

Classification-pipeline tier (the primary behavioural tier for a classifier-diagnostic change), as siblings of the `DML_*_REJECTED` cases in `GraphitronSchemaBuilderTest` (`:7661-7715`) and the existing `validateReturnType` rejection at `:7822`:

- UPDATE (the repro): bulk `@table` input covering the PK via `@nodeId`, payload `{ data: [T!]! @splitQuery, errors }`. Assert `UnclassifiedField`; the reason contains the data-field name and `@splitQuery`; the reason does *not* contain `"use ID or a @table type"`; and it contains the `@service` asymmetry note. Base the SDL on an existing would-admit R258 bulk-update-payload fixture, adding `@splitQuery` to the data field.
- INSERT: the same payload shape under `@mutation(typeName: INSERT)`, which reaches the arm through the inline `:3415` path rather than `classifyUpdateTableField`. Two kinds through two distinct routing paths pin the "one arm covers all DML kinds" invariant.
- A non-`@splitQuery` forbidden directive (for example `@condition` or `@reference`) on an otherwise-valid carrier data field: assert the targeted reason names that directive and does *not* carry the `@splitQuery`-only asymmetry note. Pins message generality across the broad forbidden set.
- Negative control (the would-admit gate): a forbidden directive on a payload that would *not* otherwise admit (for example a second data-channel-shaped field, or an unrecognized element type). Assert it still falls to the generic message, so the probe does not over-fire.

Assert load-bearing tokens (the field name, `@splitQuery`, the `UnclassifiedField` kind), not the full sentence, so the asymmetry prose can be re-tuned without churning tests. This stays the right side of the generated-body-string ban: the asserted artifact is a classifier rejection on `UnclassifiedField` (a classified-model fact), never an emitted method body, and the disqualified field never reaches emission.

Optionally, a focused `diagnoseForbiddenCarrierDirective` assertion in `SingleRecordPayloadPipelineTest` (which already drives `scanStructuralDmlPayload` directly), checking the returned record's field and directive for the repro payload.

## References

- Implementation sites: `BuildContext` scan core (`:558-659`), forbidden loop (`:598-602`), `CarrierFamily` (`:519-538`), `DmlPayloadScan` (`:480-490`); `validateReturnType` `ScalarReturnType` arm (`MutationInputResolver.java:179-189`); the `validateReturnType` call sites and the UPDATE/DELETE direct-vs-payload branch (`FieldBuilder.java:3374-3402`, `:3415` / `:3550` / `:3757`); `promoteSingleRecordPayloads` (`TypeBuilder.java:257-294`); `warnIfSplitQueryOnRecordParent` (`FieldBuilder.java:4419-4426`).
- R275 (`source-record-carrier-service-error-channel`, shipped; `changelog.md` R275 entry) grounds the DML-vs-`@service` `@splitQuery` asymmetry the message explains.
- R213 (`input-field-rejection-attribution`) is the sibling `SourceLocation`-attribution item; orthogonal to R310 (message vs location). R310's message is written to survive R213's later relocation of the carrier to the data field.
- Possible follow-up (not filed): lift the whole `validateReturnType` prose surface to a typed `ReturnTypeError` sub-seal of `AuthorError` with `lspCode()`, as R238 / R246 / R266 typed their walker rejections. Typing one arm while its siblings stay prose would be a worse asymmetry, so this is deferred as its own item rather than folded into R310.

Discovered during the `utdanningsregisteret` Graphitron 10 migration; sibling of R213, same class of defect (a real, fixable author error rendered as a misdirected diagnostic that names the wrong site or cause).
