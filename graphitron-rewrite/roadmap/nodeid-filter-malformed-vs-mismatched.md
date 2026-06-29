---
id: R378
title: "Filter @nodeId decode should distinguish malformed input from wrong-type and report malformed as a user error"
status: Ready
bucket: architecture
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-29
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

## Error surfacing on fetch fields (decided: path B, built forward-compatible with `@error`)

The reported bug is on a **query/fetch** field (`soknader(filter: HentSoknadInput): [Soknad!]`). Throwing from the decode helper is necessary but **not sufficient** to give the client a catchable, meaningful error, because of two architectural facts:

1. **`@error` attaches to a payload OBJECT, and a bare-entity field has no host for it.** `@error` is `on OBJECT`: handlers are declared on a payload object carrying an `errors` field, and the error *channel* binds to fields whose return type is such a payload. This is **not** mutation-only — several query field variants carry channels: `WithErrorChannel` is implemented by `QueryServiceTableField`, `QueryServiceRecordField`, `QueryServicePolymorphicField`, and `QueryTableMethodTableField` (`WithErrorChannel.java:13` names "root + child services, root + child `@tableMethod` fields"), and `CatalogBuilder` resolves `errorChannelName(f.errorChannel())` for them. What `soknader(filter:): [Soknad!]` lacks is not "query-ness" but a payload OBJECT to host the `errors` field: it returns a bare list of entities. The sealed `QueryField` interface itself does not `extends WithErrorChannel` (`QueryField.java:25`, unlike `MutationField.java:18`), so the capability lives on the service / `@tableMethod` variants, not on a plain table-fetch field like `soknader`.
2. **Fetch fields redact every thrown exception.** A query fetcher wraps its work in a redact-only catch arm (`TypeFetcherGenerator.java:799`, `redactCatchArm`). `ErrorRouter.redact` (`ErrorRouterClassGenerator.java:421-432`) logs the throwable and returns `data: null` + a generic `"An error occurred. Reference: <uuid>."`, with **no special-casing** for any client-error type. A raw throw here would be scrubbed to a correlation id.

**Decision (user): path B, done so that lifting `@error` to queries later is a clean follow-on.** A malformed / wrong-type id is a *client* mistake, not a server fault, so the query catch arm should surface its real message in the standard GraphQL `errors` array while still redacting genuine internal exceptions. The throw is **not** a bare anonymous `GraphqlErrorException`: it is a stable, recognizable type so (a) the catch arm can tell "client error → surface" from "internal fault → redact", and (b) when bare-entity query fields gain a payload object that can host `@error` handlers (the companion item below), the *same* throw is matched by an `@error` `GENERIC` handler with **zero change at the throw site**.

This is mechanically guaranteed: the channel dispatcher matches a thrown exception via `ExceptionMapping.match`, which is `exceptionClass.isInstance(throwable)` (instanceof semantics, confirmed at `ErrorRouterClassGenerator`), and falls through to the no-channel disposition on no match. So B's catch-arm change *is* the predecessor of path A: B surfaces the client-error type raw; A routes the same type through `@error`, and B's surfacing becomes A's no-channel fallback (exactly as `redact` is today for channel-less mutations).

### What path B adds (on top of the throw + message work below)

1. **A generated, stably-named client-error type.** Emit a `GraphQLError`-implementing exception into `<outputPackage>.schema` alongside `ErrorRouter` (no runtime support module exists; generated code's runtime types are generated), e.g. `GraphitronClientException`. It must implement `graphql.GraphQLError` so it surfaces natively and is channel-matchable; subclass `graphql.GraphqlErrorException` if the graphql-java 25 API permits a message-carrying subclass, else implement `GraphQLError` directly. The class *is* the surfacing marker (the catch arm surfaces instances of it) and the stable `@error` `className` anchor (instanceof match); future client-error producers subtype it. The nodeId decode throw uses it (optionally a `NodeIdDecodeException extends GraphitronClientException` if a narrower `className` is wanted for targeting).
2. **`ErrorRouter.surfaceClientErrorOrRedact(thrown, env)`.** New sibling to `redact`: walk the cause chain; if a `GraphitronClientException` (a `GraphQLError`) is found, return `DataFetcherResult.data(null).error(thatError)` so the real message reaches the client; else fall through to `redact` unchanged (the privacy contract for genuine faults is untouched).
3. **Repoint the no-channel catch disposition** at `surfaceClientErrorOrRedact` (the `redactCatchArm` query sites in `TypeFetcherGenerator`; and `ChannelCatchArmEmitter`'s `Optional.empty()` fallthrough). Uniform across fetchers; observable behaviour changes only for `GraphitronClientException` throws (today only the nodeId decode produces one), so blast radius is bounded to the decode failure.

### Companion item (the future lift, filed separately)

Giving **bare-entity** query fields (`soknader: [Soknad!]`, not the service / `@tableMethod` variants that already carry channels) a way to route `@error` is **out of scope for R378** and tracked as **R397** (`error-directive-on-query-fields`, Backlog); R378 is its deliberate predecessor (the client-error type + the surface/redact split are exactly what that lift consumes). The real gap R397 owns is **not** "add `WithErrorChannel` to query fields" (the service / `@tableMethod` query variants already implement it) but giving a bare-entity field a payload OBJECT to host the `errors` field; that shaping question belongs to the lift, not here.

## Implementation

### Mechanism: enrich the `Mode.THROW` body once, flip the authored filter producers

1. **Enrich `CompositeDecodeHelperRegistry.buildHelper` `Mode.THROW` body** (both the list `.map(...)` arm and the scalar arm) to replace the single fixed `MISMATCH_MESSAGE` with a two-branch message computed from `NodeIdEncoder.peekTypeId(nodeId)` on the offending wire string (the `nodeId` element local is already in scope in both arms):
   - `peekTypeId` returns `null` (or the expected typeId, the arity-mismatch sub-case) → *malformed*: e.g. `Invalid node id "<wire>" for this argument: not a valid <ExpectedType> id`.
   - `peekTypeId` returns a different typeId → *wrong type*: e.g. `Invalid node id "<wire>" for this argument: decodes to type "<got>", expected a <ExpectedType> id`.

   The message **names the offending wire value** (near-zero cost, the value is in scope; a third reason this beats the sealed-return shape, which would have to carry the value out). The expected type is a **generation-time constant** baked in as a `$S` literal, not learned at runtime: the type *name* is `decode.methodName()` with the `decode` prefix stripped (the registry already does this in `helperName`). The expected *typeId* (needed to fold the arity-mismatch sub-case into the malformed branch accurately, since a customized `@node(typeId:)` can differ from the type name) is **not** currently on `HelperRef.Decode`; thread it on as a single-source plumbing addition (it is already a generation-time constant baked into `decodeValues($S, ...)` by `buildPerTypeDecode`). If threading it proves heavier than expected, the fallback is the no-comparison form (`peek == null` → malformed, else wrong-type), accepting that the rare arity-mismatch id reads as "wrong type"; decide during implementation, prefer the typeId-carrying form.
   - This enriches **every** `Mode.THROW` consumer, not just the flipped filters: the existing lookup/mutation-key `ThrowOnMismatch` path (`FieldBuilder.java:1321`) gets the same improved diagnostics, keeping the message contract single-sourced (a malformed lookup id is also a client bug worth naming). Per the consult, enriching filter-only would let the two THROW paths drift.
   - The redundant second base64 decode (`peekTypeId` re-walks what `decode<TypeName>` already discarded) is acceptable: it runs **only on the error path**, which is about to throw and abort the field anyway. Record this justification inline rather than the weaker "peekTypeId is already shared" one.
   - **Throw the generated client-error type, not a bare `GraphqlErrorException`** (see [Error surfacing](#error-surfacing-on-fetch-fields-decided-path-b-built-forward-compatible-with-error)): the THROW body throws `GraphitronClientException` (or the `NodeIdDecodeException` subtype) carrying the two-branch message, so the query catch arm surfaces it and a future query `@error` handler matches it. This replaces the current inline `throw GraphqlErrorException.newErrorException().message(MISMATCH_MESSAGE).build()` at both arms.

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

- **Execution tier (`graphitron-sakila-example` `GraphQLQueryTest`)** — assert the *real message surfaces* (path B), not the baseline and not a redacted correlation id:
  - `filmsByNodeIdArg_allMalformedIds_returnsUnfilteredBaseline` (currently asserts the full 5-film baseline, `GraphQLQueryTest.java:756`) → rename + invert to assert `data` for the field is `null` and the response `errors` array carries the **real** message (names the bad value + expected type), *not* an `"An error occurred. Reference: …"` redaction. This is the direct regression for the reported bug.
  - Add a **wrong-type** case: a valid id of a sibling NodeType handed to a `Film` `@nodeId` filter surfaces the wrong-type message (distinct from malformed).
  - Add a **mixed** case (one valid, one malformed) → surfaces the error.
  - Add a **privacy-contract** case: a genuine internal fault on a query field still redacts to a correlation id (proves `surfaceClientErrorOrRedact` narrows to the client-error type and did not start leaking arbitrary exceptions). If no existing query fetcher throws a non-client exception conveniently, assert this at whatever tier can reach the `redact` fallthrough.
  - **Keep** the genuinely-empty cases as R375 left them: `[]` / omitted arg still returns the unfiltered baseline (`films_filteredBySameTableNodeId_emptyListReturnsUnfilteredBaseline`, `filmsConnectionByOptionalIds_*`). These prove the empty-vs-bad asymmetry holds.
  - Cover both filter surfaces: a top-level `@nodeId` filter arg and an input-object-field `@nodeId` filter (the `soknadId`/`HentSoknadInput` shape), so both flipped loci are exercised end-to-end.
- **Pipeline tier** — assert the four authored arms now classify to `ThrowOnMismatch` (pinning the intent mechanically per "Documentation names only live tests/code": a future arm reintroducing `SkipMismatchedElement` on an *authored* filter must fail a test, not just contradict prose). Assert the two shim arms still classify to `SkipMismatchedElement` (the deliberate boundary).
- **Unit tier** — `CompositeDecodeHelperRegistryTest`: the enriched `Mode.THROW` body throws `GraphitronClientException` with the two-branch message (wire value + expected type), for both list and scalar arms (code-string assertions by this file's existing convention; behaviour proven at execution tier). Plus a focused test that `ErrorRouter.surfaceClientErrorOrRedact` surfaces a `GraphitronClientException`'s message and redacts a plain `RuntimeException` (the surface-vs-redact split).
- **Compilation tier** — the generated `GraphitronClientException` + the `surfaceClientErrorOrRedact` arm compile under `graphitron-sakila-example`'s `<release>17</release>` (they ride generated output); a `@nodeId` filter field in that module exercises the throw end-to-end.

## Relationship to R273

R378 **carves out the policy decision** cleanly; it is not a collision. R273's claim that policy and the `__NODE_*` metadata refactor "are the same item" holds only at the *bare-`ID` arm* (R273 Deliverable 3), where routing through the modern resolver forces picking that arm's skip-vs-throw. R378 settles skip-vs-throw for the **four authored filter arms** independently of metadata sourcing (it flips already-classified producers; it touches neither `__NODE_*` reads nor `@node` inference). After R378:

- R273 **inherits** the settled policy and keeps only the *mechanism* (infer `@node`, source from `@node` + catalog PK, reroute the bare-`ID` arm onto the settled throw policy).
- R273's "What to decide (policy)" section and Deliverable 5's *delete-`ThrowOnMismatch`* branch go **stale on R378 merge** (they describe an open decision R378 closed, and R378 chose the opposite of the "skip everywhere" branch that would have deleted `ThrowOnMismatch`). R273 should be edited to reference R378's decision rather than re-litigate it, and `depends-on: [R378]` added. (This Spec commit makes that R273 edit alongside, so the two items do not encode contradictory fates for the same carrier.)

R378 stays independent (`depends-on: []`): it is shippable without R273's refactor and directly fixes the reported bug.

## Relationship to R375

Orthogonal. R375 (shipped) made the *empty list* narrow by nothing; R378 governs the *decode policy* for non-empty invalid input. R375's empty-list behaviour is unchanged and is the first row of the failure-mode table above; R378 ensures a bad id throws *before* it can degrade into the empty-list path (the mechanism by which the reported bug slipped through: all-malformed → empty list → R375 baseline).
