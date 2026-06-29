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

There is a case for keeping `SkipMismatchedElement` on the *wrong-type* arm: set-intersection semantics ("keep rows whose id is in this set") mean a wrong-type id simply is not in the set, and skipping preserves the cross-type-id-source pattern (contrast `ThrowOnMismatch`, used for lookup / mutation *keys*, where a bad id is a contract violation). The *malformed* case is harder to defend: silently swallowing structurally invalid input masks a client error that the sibling `ThrowOnMismatch` arm would surface as a `GraphqlErrorException`. The decision below (see [Decision](#decision)) **rejects the skip-on-wrong-type lean** and throws on both failure kinds, accepting the loss of the heterogeneous-id-source pattern deliberately; the framing above is retained because it is the tradeoff the decision weighed.

## Why it matters

Surfaced during R375 review (see that item's "Implementation notes"). R375 correctly fixed the empty-list footgun (Apollo serialising an empty selection as `[]` = "no filter"), but a query that sends *only* malformed ids now returns the entire table with no signal that the input was bad. That is a more surprising silent outcome than the empty-list case R375 set out to fix, and it is reachable: the GraphQL type system guarantees only that each element is a non-null `ID` *string*; node-id well-formedness is opaque to it and is checked solely at decode time.

## Decision

Decided by the user (bug report: `soknadId: ["IKKE_EN_ID"]` returned the full unfiltered table). In **filter position**, an authored `@nodeId` argument or input-object field throws a `GraphqlErrorException` on **any** decode failure: both *structurally malformed* input and *well-formed-but-wrong-type* input fail. The Relay heterogeneous-id-source pattern (the reason wrong-type ever skipped) is deliberately given up; the user accepted that tradeoff with it surfaced. The two failure kinds are not behaviourally distinct (both throw) but **are distinguished in the error message** so a consumer can tell a typo'd id from a right-shape-wrong-type id.

This is R378's sketch option (a) for *behaviour* (throw on both, no skip), combined with the *diagnostics* goal the item's title carries (distinguish the two kinds). It is **not** sketch option (b): we do not give `decodeValues`/`decode<TypeName>` a richer return. Per the `principles-architect` consult, the malformed-vs-wrong-type distinction is recoverable at the throw site from the existing boundary primitive `NodeIdEncoder.peekTypeId`, so a sealed decode-result type would add a carrier no third consumer needs (skip drops the failure, record-decode throws generic, dispatch already calls `peekTypeId` directly). The stub pre-recommended (b); the consult moved it to the localized form below.

## Failure-mode contract (the boundary, written out)

A list `@nodeId` filter now has three runtime outcomes; an authored single `@nodeId` filter arg the scalar analogue. This table is the explicit boundary contract (it extends R375's empty-list row with R378's bad-element rows so the asymmetry is a recorded decision, not emergent behaviour):

[cols="1,2"]
|===
| Wire input | Outcome

| Empty list `[]` (or absent optional arg)
| No narrowing: predicate omitted, `DSL.noCondition()` identity. **R375, unchanged.** A well-formed empty selection means "no filter".

| Non-empty list, every element decodes
| `WHERE col IN (decoded...)` / `RowIn`. Unchanged.

| Non-empty list, *any* element malformed or wrong-type (incl. the all-bad and mixed cases)
| Throw `GraphqlErrorException`. **R378.** The field fails; no partial result.
|===

The empty-vs-bad-element asymmetry is intentional and principled: emptiness is *structural* ("no constraint supplied"), element validity is *content* ("a constraint was supplied and it is garbage"). The mixed case (some valid, some bad) throws under the "any bad element" rule, i.e. one fat-fingered id fails the whole field; this is the same Relay-heterogeneous tradeoff the decision already accepted, made explicit so "mixed" is not an unstated fourth case.

## OPEN DECISION (blocking): how does the thrown error reach the client on a *fetch* field?

The reported bug is on a **query/fetch** field (`soknader(filter: HentSoknadInput): [Soknad!]`). Throwing a `GraphqlErrorException` from the decode helper is necessary but **not sufficient** to give the user a catchable, meaningful error, because of two architectural facts:

1. **`@error` is mutation-only.** `@error` is `on OBJECT` and routes through an error *channel* that only `MutationField` carries (`MutationField implements WithErrorChannel`; `QueryField` does **not**, `QueryField.java:25`). A list-returning query field has no payload object on which to declare `@error` handlers, so `@error` is not applicable to `soknader` as it stands.
2. **Fetch fields redact every thrown exception.** A query fetcher body wraps its work in a redact-only catch arm (`TypeFetcherGenerator.java:799`, `redactCatchArm`). `ErrorRouter.redact` (`ErrorRouterClassGenerator.java:421-432`) logs the throwable and returns `data: null` + a generic `"An error occurred. Reference: <uuid>."` GraphQL error, with **no special-casing for `GraphqlErrorException`**. So our distinguished malformed-vs-wrong-type message would be **scrubbed** to a correlation id, and `@error` never sees it.

So, as scoped above, R378's throw on the `soknader` field would yield `{data: {…soknader: null}, errors: [{message: "An error occurred. Reference: <uuid>."}]}` — strictly better than the silent full-table answer (the original bug), but **not** the catchable, useful `@error` outcome the user requires.

Candidate paths (to settle with the user before this leaves Backlog):

- **(A) Extend the `@error` error channel to query/fetch fields.** Make `QueryField` carry an error channel and route fetch-field throws through it (the research's 5-step list: `WithErrorChannel` on `QueryField`, resolve channels for query results, swap the redact-only catch arm for channel dispatch, validator coverage). Largest. Awkward for list-returning queries: `@error` needs a payload OBJECT to host the `errors` field, which a `[Soknad!]` query does not have. Likely a separate companion roadmap item, not R378.
- **(B) Don't redact client-input errors on query fields; pass their real message through.** Distinguish a *user-input* error (a decode `GraphqlErrorException` — a bad id is a client mistake) from an *internal* fault (redact-worthy, may leak internals). The query catch arm lets the user-input error surface its real message in the standard GraphQL `errors` array while still redacting genuine internal exceptions behind a correlation id. Smaller, and arguably the correct behaviour regardless of `@error`: redaction exists to hide server faults, not to swallow "you sent a malformed id." Surfaces via the standard `errors` array, **not** via `@error`-typed payloads. This is the recommended path *if* the user's intent is "the client sees a catchable, meaningful error" rather than "specifically the `@error` typed-payload mechanism."
- **(C) Pre-execution argument validation.** Decode/validate `@nodeId` args before the fetcher runs, surfacing errors natively outside the redact scope. Larger; overlaps the `VALIDATION` `ErrorHandlerType` surface.

This decision determines the rest of the Implementation section (whether R378 is "flip producers + enrich message + adjust query catch arm for user-input errors" (B), or "flip producers + enrich message" with a companion item for (A)). Resolve before Spec sign-off.

## Implementation

### Mechanism: enrich the `Mode.THROW` body once, flip the authored filter producers

1. **Enrich `CompositeDecodeHelperRegistry.buildHelper` `Mode.THROW` body** (both the list `.map(...)` arm and the scalar arm) to replace the single fixed `MISMATCH_MESSAGE` with a two-branch message computed from `NodeIdEncoder.peekTypeId(nodeId)` on the offending wire string (the `nodeId` element local is already in scope in both arms):
   - `peekTypeId` returns `null` (or the expected typeId, the arity-mismatch sub-case) → *malformed*: e.g. `Invalid node id "<wire>" for this argument: not a valid <ExpectedType> id`.
   - `peekTypeId` returns a different typeId → *wrong type*: e.g. `Invalid node id "<wire>" for this argument: decodes to type "<got>", expected a <ExpectedType> id`.

   The message **names the offending wire value** (near-zero cost, the value is in scope; a third reason this beats the sealed-return shape, which would have to carry the value out). The expected type is a **generation-time constant** baked in as a `$S` literal, not learned at runtime: the type *name* is `decode.methodName()` with the `decode` prefix stripped (the registry already does this in `helperName`). The expected *typeId* (needed to fold the arity-mismatch sub-case into the malformed branch accurately, since a customized `@node(typeId:)` can differ from the type name) is **not** currently on `HelperRef.Decode`; thread it on as a single-source plumbing addition (it is already a generation-time constant baked into `decodeValues($S, ...)` by `buildPerTypeDecode`). If threading it proves heavier than expected, the fallback is the no-comparison form (`peek == null` → malformed, else wrong-type), accepting that the rare arity-mismatch id reads as "wrong type"; decide during implementation, prefer the typeId-carrying form.
   - This enriches **every** `Mode.THROW` consumer, not just the flipped filters: the existing lookup/mutation-key `ThrowOnMismatch` path (`FieldBuilder.java:1321`) gets the same improved diagnostics, keeping the message contract single-sourced (a malformed lookup id is also a client bug worth naming). Per the consult, enriching filter-only would let the two THROW paths drift.
   - The redundant second base64 decode (`peekTypeId` re-walks what `decode<TypeName>` already discarded) is acceptable: it runs **only on the error path**, which is about to throw and abort the field anyway. Record this justification inline rather than the weaker "peekTypeId is already shared" one.

2. **Flip the four authored filter producers** from `SkipMismatchedElement` to `ThrowOnMismatch`:
   - `FieldBuilder.java:1242` (top-level same-table `@nodeId` arg filter)
   - `FieldBuilder.java:1264` (top-level FK-target `@nodeId` arg filter, `DirectFk`)
   - `BuildContext.java:2324` (input-object-field `@nodeId` `SameTable`) — the exact `soknadId` bug path
   - `BuildContext.java:2336` (input-object-field `@nodeId` `FkTarget.DirectFk`)

   Update the stale "drops silently to 'no row matches'" comments at these sites (e.g. `FieldBuilder.java:1232-1240`, `1254-1258`; `BuildContext.java:2308-2312` on `inputFieldFromNodeIdResolved`) to state the throw policy.

### Out of scope of the flip (deliberate boundary)

- **Do not flip the two synthesis-shim arms** (`BuildContext.java:2256`, `:2391`). They are legacy `__NODE_*`-driven, on the `retire-synthesis-shims.md` track, and entangled in R273's `__NODE_*` rerouting. Flipping them here would silently couple R378 to shim-retirement semantics and step on R273's deliverables.
- **Do not retire `SkipMismatchedElement` or registry `Mode.SKIP`.** Both still have live producers (the two shim arms) after R378, so retiring the carrier would break them or force R378 to absorb their retirement, both inflating blast radius past the policy decision. The carrier retires on its own track once the shims go.
- **`RecordKeyDecode` / `NodeIdDecodeRecord` sibling throw arms** (R195/R315, in `NodeIdEncoderClassGenerator`/`InputBeanInstantiationEmitter`) keep their own generic messages; a separate carrier and emitter, named here so the boundary is recorded, not silently uneven.

### Validator

No new validate-time rule. Malformed/wrong-type-ness is a *runtime* property of an opaque wire value; the GraphQL type system guarantees only that each `[ID!]` element is a non-null string, so there is nothing to reject at validate time (R262 separately owns `@nodeId`-on-non-ID-coordinate). The "validator mirrors classifier invariants" rule is satisfied **vacuously**: both `NodeIdDecodeKeys` arms (`SkipMismatchedElement`, `ThrowOnMismatch`) and both registry modes are already fully implemented, so flipping a producer from one implemented arm to the other introduces no new unhandled classification. State this in the implementation commit so a reviewer does not read the absent validator change as an oversight.

## Tests

- **Execution tier (`graphitron-sakila-example` `GraphQLQueryTest`)** — re-invert the R375 leftover and add the wrong-type case:
  - `filmsByNodeIdArg_allMalformedIds_returnsUnfilteredBaseline` (currently asserts the full 5-film baseline, `GraphQLQueryTest.java:756`) → rename + invert to assert a `GraphqlErrorException` (errors-list entry naming the bad value + expected type), not the baseline.
  - Add a **wrong-type** case: a valid id of a sibling NodeType handed to a `Film` `@nodeId` filter throws, with a message distinguishing it from malformed.
  - Add a **mixed** case (one valid, one malformed) → throws.
  - **Keep** the genuinely-empty cases as R375 left them: `[]` / omitted arg still returns the unfiltered baseline (`films_filteredBySameTableNodeId_emptyListReturnsUnfilteredBaseline`, `filmsConnectionByOptionalIds_*`). These prove the empty-vs-bad asymmetry holds.
  - Cover both filter surfaces: a top-level `@nodeId` filter arg and an input-object-field `@nodeId` filter (the `soknadId`/`HentSoknadInput` shape), so both flipped loci are exercised end-to-end.
- **Pipeline tier** — assert the four authored arms now classify to `ThrowOnMismatch` (pinning the intent mechanically per "Documentation names only live tests/code": a future arm reintroducing `SkipMismatchedElement` on an *authored* filter must fail a test, not just contradict prose). Assert the two shim arms still classify to `SkipMismatchedElement` (the deliberate boundary).
- **Unit tier (`CompositeDecodeHelperRegistryTest`)** — the enriched `Mode.THROW` body emits the two-branch message naming the wire value and expected type, for both list and scalar arms. Note this file uses code-string assertions by its existing convention; the behaviour is independently proven at the execution tier.

## Relationship to R273

R378 **carves out the policy decision** cleanly; it is not a collision. R273's claim that policy and the `__NODE_*` metadata refactor "are the same item" holds only at the *bare-`ID` arm* (R273 Deliverable 3), where routing through the modern resolver forces picking that arm's skip-vs-throw. R378 settles skip-vs-throw for the **four authored filter arms** independently of metadata sourcing (it flips already-classified producers; it touches neither `__NODE_*` reads nor `@node` inference). After R378:

- R273 **inherits** the settled policy and keeps only the *mechanism* (infer `@node`, source from `@node` + catalog PK, reroute the bare-`ID` arm onto the settled throw policy).
- R273's "What to decide (policy)" section and Deliverable 5's *delete-`ThrowOnMismatch`* branch go **stale on R378 merge** (they describe an open decision R378 closed, and R378 chose the opposite of the "skip everywhere" branch that would have deleted `ThrowOnMismatch`). R273 should be edited to reference R378's decision rather than re-litigate it, and `depends-on: [R378]` added. (This Spec commit makes that R273 edit alongside, so the two items do not encode contradictory fates for the same carrier.)

R378 stays independent (`depends-on: []`): it is shippable without R273's refactor and directly fixes the reported bug.

## Relationship to R375

Orthogonal. R375 (shipped) made the *empty list* narrow by nothing; R378 governs the *decode policy* for non-empty invalid input. R375's empty-list behaviour is unchanged and is the first row of the failure-mode table above; R378 ensures a bad id throws *before* it can degrade into the empty-list path (the mechanism by which the reported bug slipped through: all-malformed → empty list → R375 baseline).
