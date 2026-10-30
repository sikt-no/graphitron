---
id: R1036
title: "A bad node id fails with the same client error at every @nodeId site, never redacted"
status: Ready
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-14
last-updated: 2026-10-30
---

# A bad node id fails with the same client error at every @nodeId site, never redacted

## Goal

Every node-id decode failure has one shape. A client that sends a malformed id, or an id of the wrong type, gets the `NodeIdDecodeFailure` message: `Invalid node id "<id>" for this argument: not a valid <Type> id`, or `... decodes to type "<n>", expected a <Type> id`. The error is raised as the generated `GraphitronClientException`. It does not matter whether the id sits in a query filter, a lookup argument, a `@service` argument or a DML `@mutation` input field. Today only some of those sites behave this way. The rest throw a plain `GraphqlErrorException` that says "Decoded NodeId did not match the expected type ...". It neither echoes the id nor says whether the id was malformed or of another type. `ErrorRouter.surfaceClientErrorOrRedact` does not recognise it as a client error, so on a field without an errors channel the client reads "An error occurred. Reference: <uuid>" instead of a reason. The statement still fails and writes nothing either way, so this is a diagnosability defect, not a data one: the client cannot tell a bad id from a server fault.

`NodeIdDecodeFailure`'s own javadoc states the rule this breaks: one bad id failing two ways tells a client that one spelling of an id validates and another does not.

## The sites

Already on `NodeIdDecodeFailure`, and untouched by this item: the key helpers `CompositeDecodeHelperRegistry` mints (filters), the client-error overload of `RecordDecodeFragments.decodeHelper` that `RecordDecodeHelperRegistry` calls (`argMapping` key-column projections), and the multi-candidate dispatch in `MultiTablePolymorphicEmitter` and `RecordDecodeFragments.polymorphicDecodeHelper`.

Still throwing `GraphqlErrorException` with "Decoded NodeId did not match the expected type", in three families:

* **DML input fields.** `TypeFetcherGenerator.appendDecodeLocal` (the expression overload; the `mapLocal` overload delegates to it) and both mismatch throws in `TypeFetcherGenerator.appendSetDecodeLocal` (the `CannotArrive`-with-presence arm and the shared tail after the `RefusedAsIdentity` gate). Every DML rail reaches one of the two: INSERT single-row and bulk (`buildInsertDecodeLocals`), the UPDATE and upsert SET side single-row and bulk (`emitSetMapPuts`, `emitBulkSetDecodeLocals`), and the WHERE identity of UPDATE and DELETE single-row and bulk (`buildLookupWhereSingleRow`, `emitLookupKeyDecodeLocals`, `buildBulkLookupRowIn`). So changing the two appenders changes every rail at once; no rail needs its own work.
* **`@service` arguments.** `RecordDecodeFragments.decodeHelper`'s plain overload, the one taking no supplied throw, which `InputBeanInstantiationEmitter.buildRecordDecodeHelper` calls for the input-bean family; and the three throws behind `JooqRecordInstantiationEmitter.DECODE_MISMATCH_MSG` (`emitKeyDecode`'s two arms and `emitDecodeThrow`) for the jOOQ-record family.
* **Lookup arguments.** `LookupRows`' per-row decode loop in the input-rows method `ProjectionUnitRenderer` adds.

## Decisions

**The field name is dropped; every site says "for this argument".** The shared message does not gain a location. Two of the families already on `NodeIdDecodeFailure` emit one helper per node type per host class (`CompositeDecodeHelperRegistry`, `RecordDecodeHelperRegistry` keyed on the type name), so they cannot name a field without becoming per-field helpers or taking the location as a runtime parameter, and the multi-candidate text documented in `docs/manual/how-to/global-id.adoc` already says "for this argument". Adding a location only where a site happens to have one would make two shapes again, which is the defect. What locates the fault instead: the message echoes the id and names the expected type, and the error's `path` names the field that received the argument. "Argument" stays accurate for an input field nested in an input object, which is part of an argument. The one case this loses is a DML input with two `@nodeId` fields of the same target type carrying the same bad id, where the client cannot tell which field the message is about; both are then wrong, so the fix is the same.

**A non-`String` wire value reads as malformed.** It peeks as `null`, takes the "not a valid `<Type>` id" branch, and is echoed by string concatenation (a list prints as `[a, b]`). This is what `NodeIdDecodeFailure.multiCandidateThrowStatement` already does, so the single-type and multi-candidate messages agree on it. R419 stays the build-time fix for the one shape known to send a list here; this item only decides what the client reads until then. It also closes a second leak: `JooqRecordInstantiationEmitter` casts the wire value with `(String)` before `decodeColumns` at five sites (two in `emitKeyDecode`, three in `emitPrepare`'s decode arms), so a non-`String` value throws `ClassCastException` today, which is a server fault and redacted. The emitter narrows with `instanceof String` instead and lets a non-`String` reach the throw.

**Out of scope:** an explicit `null` on a `@nodeId` field (`identityClearMessage`, the "Conflicting values" texts). Those are refusals of a well-formed request, not decode failures, and keep their own messages and exception type.

## Implementation

1. **One single-type throw for a wire local.** Add an overload to `NodeIdDecodeFailure` beside `multiCandidateThrowStatement`, with the same shape: it takes the client-exception `ClassName`, the encoder class, the expected type id, the display name and the name of an `Object` local holding the wire value. It declares `<wireLocal>Peeked` off `peekTypeId(<wireLocal> instanceof String <wireLocal>Text ? <wireLocal>Text : null)` and throws the client exception with the existing single-type message expression (`messageExpr`, parameterised on the peeked local's name), fed `<wireLocal>` as the echoed value. Deriving the local names from the wire local, as the multi-candidate overload does, keeps two throws in one generated method from colliding. Its javadoc says the local must not be statically `String`: generated code targets Java 17, where `instanceof String s` on a `String` expression is a compile error. That is also why the existing `throwStatement` stays, for the helpers that already hold a `String`.
2. **Where the client-exception type comes from.** The unit name is minted once, by `GeneratedUnits.clientException()`, and the legacy-core callers resolve it there rather than writing `<outputPackage>.schema.GraphitronClientException` again: `TypeFetcherGenerator.generateForType` resolves it once, as `ErrorRouterClassGenerator.buildSurfaceClientErrorOrRedactMethod` does, and threads the `ClassName` down. The `render` package may not import `plan` (`render/package-info.java`, enforced by `PackageImportDirectionTest`), so render-side callers cannot do the same. For them `NodeIdDecodeFailure` gains one public `clientException(String outputPackage)` that its two existing inline copies and `RecordDecodeFragments.polymorphicDecodeHelper`'s copy also call, so the render side has one home for the formula instead of three. Two homes remain, one per side of the import rule; folding them needs the formula in a package both may import, which is a larger move than this item.
3. **DML.** `appendDecodeLocal` and `appendSetDecodeLocal` bind the wire expression to an `Object <recLocal>Wire` local where they do not already (the non-`CannotArrive` arm of `appendSetDecodeLocal` already does), and every mismatch branch calls the new overload with `nidk.decodeMethod().encoderClass()`, `typeId()` and `targetTypeName()`. Binding the wire to a local also stops the nested-input descent from being evaluated twice. The client-exception `ClassName` is threaded down the static chain listed under The sites, beside the `tablesOnly` and `tableRef` those methods already take.
4. **`@service`, input-bean family.** `RecordDecodeFragments.decodeHelper`'s plain overload and the `HelperRef.Decode` convenience overload that delegates to it are deleted; `InputBeanInstantiationEmitter.buildRecordDecodeHelper` calls the client-error overload with `rec.typeName()` and the output package. The client-error overload's javadoc loses the paragraph that justifies two failure forms ("a different consumer at a different boundary and keeps the plain form above"), since there is now one.
5. **`@service`, jOOQ-record family.** `DECODE_MISMATCH_MSG` goes, and the `GRAPHQL_ERROR` constant with it if nothing else reads it. All five decode sites read the wire into an `Object <base>Raw` local, three of them newly (`emitKeyDecode`'s non-null arm, and `emitPrepare`'s two non-null arms, which read `raw.get(leaf)` and `<parentMap>.get(leaf)` inline today), and pass `decodeColumns` the `instanceof String`-narrowed value instead of a cast. Every throw calls the new overload with `kd.encoderClass()`, `kd.typeId()`, `kd.typeName()` and the raw local; `emitDecodeThrow` therefore takes the `RecordKeyDecode` and the raw local's name, not just `base`. The emitter gains the client-exception `ClassName` from `TypeFetcherGenerator`'s two call sites.
6. **Lookup.** `LookupRows` binds `rawElem` to an `Object` local and throws through the new overload with `db.decodeMethod()`'s facts; `buildInputRowsMethod` takes the client-exception `ClassName`, which `ProjectionUnitRenderer.renderUnit` derives from the output package it already holds through `NodeIdDecodeFailure.clientException`.
7. **Docs.** `docs/manual/how-to/global-id.adoc` documents the multi-candidate message only. Add, near its first quoted message, the single-type pair (malformed, other type) with the statement that every `@nodeId` site, filter, lookup, `@service` argument and mutation input alike, fails with this text as a client error, so a mutation without an errors channel reports it rather than a reference.

## Routing

No routing change. `GraphitronClientException` subclasses `graphql.GraphqlErrorException` (`GraphitronClientExceptionClassGenerator`, superclass from `GeneratedClientException.SUPERCLASS`) and a `GENERIC` handler matches by `isInstance`, so a payload whose `@error` handler names `GraphqlErrorException` claims these errors exactly as today. Without a channel, `ErrorRouter.surfaceClientErrorOrRedact` now recognises them and surfaces the text through `FetcherErrorResponse.clientErrorMessage` instead of a reference. With R1126 the claimed error's `path` names the field that owns the payload, as for every other claimed error. Every site throws before any statement runs, so the existing "fails and writes nothing" guarantees are unaffected.

## Tests

All in `graphitron-sakila-example`'s execution tier; the change is in generated runtime behaviour a client sees, and no generator unit test asserts the old text.

* `TableOnlyNodeIdLeafExecutionTest.update_withAWrongTypeId_failsAndWritesNothing` asserts the message contains `Invalid node id` and `expected a StudyLevel id`, the same text `filter_withAWrongTypeId_failsWithAClientError` asserts for the same id, and keeps its writes-nothing assertion. Its comment saying the router redacts the text goes.
* `StraddlingReferenceUpdateExecutionTest.wrongTypeShelfId_throwsRatherThanClearing` and `bulkWrongTypeShelfId_throwsRatherThanClearing` assert the decode text (expected a `CatalogueShelf` id) beside the unchanged column; the comment saying the message is redacted goes. These cover the single-row SET arm and the bulk VALUES-join arm.
* New cases, each asserting the client sees `Invalid node id` and the expected type, and no `Reference:` text, on a field with no errors channel:
  * a DML INSERT reference field (the `buildInsertDecodeLocals` arm);
  * a DML DELETE by `@nodeId` identity (the WHERE-identity arm);
  * a `@service` argument through the input-bean family, and two through the jOOQ-record family: one reaching `emitKeyDecode`, and one reaching `emitPrepare`'s overlap path (a record with a key decode and a `@field` writing the same column), since the two arms emit their decode separately;
  * a lookup argument.
  Where an existing fixture already has the shape, the case goes beside its other tests; where none has, the implementer adds the smallest schema field that does. One case per family also sends a malformed id (not base64) and asserts the "not a valid" branch, so both message branches are pinned at every family.
* No execution test sends a non-`String` wire value. GraphQL validation keeps one out of a single `ID` slot; the one known shape that sends a list (R419's list-valued INSERT reference) is about to be rejected at build time, and a fixture built on it would pin behaviour that item removes. The narrowing is pinned by the generated code compiling, and the decision above records what the client reads.

## Retired vocabulary

* The message text "Decoded NodeId did not match the expected type" (including its "for input field", "for argument" and "for this argument" endings). After this lands it appears nowhere in `graphitron/src/main`; R419's and R882's bodies quote it as today's behaviour and are updated to the `Invalid node id` text in the implementing commit.
* `JooqRecordInstantiationEmitter.DECODE_MISMATCH_MSG`.
* `RecordDecodeFragments.decodeHelper`'s plain-failure overloads, and the "two failure forms" rationale in the remaining overload's javadoc and in `InputBeanInstantiationEmitter.buildRecordDecodeHelper`'s javadoc ("mirroring the `ThrowOnMismatch` arm" stays true; "plain form" does not).
* Test comments stating that a wrong-type id's message is redacted.

## Evidence

Seen while writing `TableOnlyNodeIdLeafExecutionTest` (sakila-example). `levelIntervals(filter: {fromLevelIds: [<XlatParent id>]})` returns the "Invalid node id" message, while `updateLevelInterval(in: {fromLevelId: <XlatParent id>})` returns the redacted reference. sis (the Graphitron 10 port, request C38, 2026-10-30) widened the item to every site. sis has 20 mutations without an errors channel where a wrong-type reference id currently returns a log reference. Graphitron 9 returned the decode text everywhere (an `IllegalArgumentException` whose message `TopLevelErrorHandler` passed through). sis's `UgyldigIdIMutasjonIT` already asserts the `Invalid node id` text for a `@service` mutation and a DML DELETE, so sis needs no change when this lands.
