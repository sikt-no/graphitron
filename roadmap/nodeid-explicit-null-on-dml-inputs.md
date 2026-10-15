---
id: R1039
title: "An explicit null on a nullable @nodeId input field writes NULL instead of failing the DML decode"
status: Spec
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An explicit null on a nullable @nodeId input field writes NULL instead of failing the DML decode

## Goal

A client that sends an explicit `null` for a nullable `@nodeId` field on an INSERT input, or on a `@service` input that graphitron turns into a jOOQ record, gets a row instead of an error: the field's own reference columns come out `NULL`, and a column it shares with another field on the input takes that other field's value. Today the INSERT rail rejects the `null` as "Decoded NodeId did not match the expected type for input field 'f'", and the `@service` record helper treats it as a `NULL` for the shared column and reports "Conflicting values supplied for input fields ...". The rule behind the fix is that a `@nodeId` names a *reference* (a whole foreign-key tuple), not a column: a missing reference says nothing about a column some other reference on the input also writes. Graphitron 9 behaved this way and sis's schema depends on it.

The sakila fixture `InsertEmailReplyInput` has the sis shape: two `@nodeId` fields that share `email.mailbox_id`, one of them nullable.

```graphql
input InsertEmailReplyInput {
    mailboxRef: ID! @nodeId(typeName: "Mailbox")           # writes mailbox_id
    messageNo: Int! @field(name: "message_no")
    subject: String @field(name: "subject")
    inReplyTo: ID @nodeId(typeName: "Email")                # writes (mailbox_id, in_reply_to_no)
        @reference(path: [{key: "email_in_reply_to_fk"}])
}
```

```graphql
# Works today: inserts (mailbox_id 5, message_no 7, in_reply_to_no NULL)
insertEmailReply(in: {mailboxRef: "<Mailbox 5>", messageNo: 7})
# Fails today, inserts the same row once this lands
insertEmailReply(in: {mailboxRef: "<Mailbox 5>", messageNo: 7, inReplyTo: null})
```

Only a non-null value that fails to decode still throws. A `@nodeId` field spelled `ID!` is unaffected, since GraphQL rejects its `null` before graphitron sees it. What an explicit `null` means on a `@mutation(typeName: UPDATE)` input is out of scope and moves to R1042 (see Scope).

## Evidence

Reported from the sis port on 2026-10-15, verified on trunk `e4cfef7c1` with sis integration tests. Both inputs carry several `@nodeId` fields whose keys all include `INSTITUSJONSNR_EIER`; omitting the nullable field works in both.

- INSERT rail: `LeggTilEmnerIUtdanningsplanerInput.studieoppbygningsdelId: ID @nodeId(typeName: "Studieoppbygningsdel")` sent as `null`. Generated `MutationFetchers.leggTilEmnerIUtdanningsplaner` decodes with `instanceof String _s ? decode(_s) : null` and then throws on `containsKey && insertKey == null`. Failing IT: `LeggTilEmnerIUtdanningsplanerMutationIT.leggTilEmnerIUtdanningsplaner_medEksplisittNullStudieoppbygningsdel_oppretterRadOgReturnererPayload`.
- Record path: `RegistrerVeiledningsforholdInput.emneId: ID @nodeId(typeName: "Emne")` sent as `null` on `registrerVeiledningsforhold`. Expected row: `INSTITUSJONSNR_EIER` from the other ids, `EMNEKODE` and `VERSJONSKODE` null. Failing IT: `VeiledningsforholdMutationIT.registrerVeiledningsforhold_medEksplisittNullEmne_oppretterRadMedStudierettensInstitusjon`. The quoted failing statement, `agree0.add(kdPresentNull ? null : kdKeys[0])`, is the output of `JooqRecordInstantiationEmitter`, which only a `@service` parameter typed as a jOOQ record reaches, so this mutation reaches graphitron through such a parameter. The implementer confirms the directive shape against sis at pickup; the fix below is the same either way.

## The rule

For a nullable `@nodeId` field on an INSERT-family input or a `@service` record input, per call:

- **Omitted**: unchanged. The field's columns are not written (the database default applies on INSERT; the record column stays untouched).
- **A value**: unchanged. It decodes; a wrong-type or malformed id still throws, and the decoded values take part in agreement with the other writers of any shared column.
- **Explicit `null`**: the field writes `NULL` to each column only it writes, and contributes nothing to a column it shares with another writer on the input. A shared column then takes the agreed value of the writers that carry one. If no writer of that column carries a value but at least one sent `null` (an explicit `null` on a plain `@field` included), the column is written `NULL`; if none is present at all, the column is omitted as today.

A plain `@field` keeps its own meaning, because it names a column: an explicit `null` on a plain field still enters agreement as `NULL` and still conflicts with a decoded value on the same column. A half-null composite foreign key imposes no referential obligation under PostgreSQL's default `MATCH SIMPLE` or Oracle's composite-FK semantics, so `(mailbox_id 5, in_reply_to_no NULL)` is an absent reply pointer, not a dangling one; the same holds for sis's `(INSTITUSJONSNR_EIER, EMNEKODE NULL, VERSJONSKODE NULL)`. Whether a `NULL` reaches a NOT NULL column is the database's to refuse, which stays R881's axis.

## Scope

A census of every generator site that decodes a `@nodeId` from an input, against the three ways a present `null` can go wrong (throws as a mismatch, enters agreement as `NULL`, or writes `NULL` over a shared column):

- **INSERT family, in scope.** `TypeFetcherGenerator.buildInsertDecodeLocals` is the one decode seam behind `buildSingleRowInsertValues` and `buildBulkInsertValues`, which every INSERT-values site calls: direct INSERT, the UPSERT insert arm (`buildMutationUpsertFetcher`), the payload-carrier `buildRecordInsertChain` / `buildRecordUpsertChain`, and `buildBulkRecordPerRowInsertBody`. The UPSERT update arm (`emitSetExcludedPuts`) reads `DSL.excluded(col)`, so it follows whatever the insert arm binds and needs no change.
- **`@service` record helper, in scope.** `JooqRecordInstantiationEmitter`'s agreement path (`emitWithAgreement`: `emitPrepare`, `emitAgreement` / `agreeValueExpr`, `emitLoadPrepared`). The overlap-free path (`emitKeyDecode`) already handles a present `null` correctly: there is no shared column for it to get wrong.
- **UPDATE SET, out of scope, moved to R1042.** R880 already gives nullable SET carriers a three-way decode (`appendSetDecodeLocal` under `CarrierNullRule.OnExplicitNull.Clears`), so no UPDATE decode throws on `null`. What differs is policy: R880 made a clearing writer conflict with a co-writer on a shared SET column (`appendAgreementValue`'s `nullAgrees`), the opposite of the rule above, and sis expects `endreVeiledningsforhold`'s nulls to be ignored. Both retract a reviewed R880 decision rather than fix an emitter, so R1042 decides them together. Until it lands, INSERT and UPDATE disagree about a `null` on a shared column; the user-manual text below states the INSERT rule only for INSERT.
- **Key positions, out of scope by design.** UPDATE/DELETE WHERE, bulk lookup rows and lookup arguments (`appendDecodeLocal`, `LookupRows`) throw on a `null` id. A null names no row, so a throw is the right outcome; the message quality there is R1036's.
- **Filters and `@condition` arguments, already correct.** `RecordDecodeFragments.decodeHelper` returns `null` for a non-string wire value before decoding.

## Implementation

`TypeFetcherGenerator`:

- `buildInsertDecodeLocals`: declare each leaf's decode local in the three-statement shape R880 introduced, so an explicit `null`, a non-string wire value and a wrong-type id stop collapsing onto one `null`. Reuse `appendSetDecodeLocal`, passing `OnExplicitNull.CannotArrive` for an `ID!` leaf (byte-identical output to today) and `OnExplicitNull.Clears` for an `ID` leaf, read off the leaf carrier's `nonNull()`. The INSERT rail needs no carrier-grain rule of its own: the third answer, `RefusedAsIdentity`, guards a matched key, and an INSERT has none. Update `appendSetDecodeLocal`'s javadoc so it names its INSERT caller.
- `insertPresentCell`: an unshared decode column reads `insertKey_<fi> == null ? null : insertKey_<fi>.value<slot+1>()`, emitted only for a nullable leaf, so a present `null` binds a typed `NULL` instead of throwing a `NullPointerException`. Read the nullability off the flattened leaf the writer's `index()` already names (`flattenInsertLeaves`), not off `SetGroup`: that record is shared with the UPDATE SET plan, where R880 kept null semantics off it deliberately (see `CarrierNullRule`'s javadoc).
- `emitInsertAgreementPrep`: the gather already drops a `null` decode local (`appendAgreementValue` with `nullAgrees` false); rewrite the comment that relies on the throw. The shared column's cell must cover "present, but nothing to agree on": on the single-row path the cell reads `insertKeyAgree<ci>.isEmpty() ? null : insertKeyAgree<ci>.get(0)` (today an empty list reaches `get(0)`), and on the bulk path the coalesced `insertKeyCell<ci>` becomes the typed `NULL` when the column's presence gate holds and the list is empty, keeping `DSL.defaultValue` for the true gap where no writer is present.

`JooqRecordInstantiationEmitter`:

- `emitAgreement` / `agreeValueExpr`: a nullable decode contributes only when it is present *and* not `null`. The gather guard becomes `if (<base>Present && !<base>PresentNull)` and the value is `<base>Keys[<slot>]`. Plain writers are unchanged.
- `emitLoadPrepared`: a present-`null` decode sets `NULL` unconditionally on a target column no other writer shares, and on a shared column only when that column's agreement list is empty. Today the guard is moot because agreement throws first; once a `null` drops out of agreement, a cleared decode loading after a decode that carries the value would overwrite the shared column. `emitWithAgreement` passes the column-to-list mapping it already builds into the load phase.
- The class javadoc's flag-reset note still applies: a `NULL` set by a cleared decode loses its touched flag when a later decode loads through `fromArray`, so a service that inserts the record sees the column omitted (the default applies) rather than bound to `NULL`. For a column with no default the row is the same, which covers both the sis case and the fixture below; it is pre-existing behaviour of the overlap-free path too, and this item does not change it.

## Tests

Execution tier, `graphitron-sakila-example` against PostgreSQL. These are the evidence the goal is delivered; none asserts on generated code.

- `SelfFkNodeIdInsertExecutionTest` (single-row INSERT, sis's INSERT shape): `explicitNullNullableSelfFk_insertsInMailboxRefsMailboxWithNullReplyPointer`, which asserts the row `(5, n, NULL)` and the same returned `Email` as the omitted case, plus `wrongTypeInReplyTo_stillThrowsAndInsertsNothing`, which sends a `Mailbox` id as `inReplyTo`.
- Bulk INSERT: a new fixture `insertEmailReplies(in: [InsertEmailReplyInput!]!): [Email!]! @mutation(typeName: INSERT)` beside `insertEmailReply`, and a case in the same test class whose three rows send a value, an explicit `null` and nothing for `inReplyTo` across both seeded mailboxes. It asserts each row's `mailbox_id` comes from its own `mailboxRef` and that `in_reply_to_no` is the parent's number, `NULL` and `NULL` respectively, which exercises the coalesced shared cell's value, present-`null` and gap arms.
- `NodeIdValueAgreementExecutionTest` (INSERT, shared column with a *plain* co-writer, over `InsertEndorsementOverlapInput`): `filmRef: null` with `endorsedFilm` set writes the plain value; `filmRef: null` alone writes `NULL`; `filmRef` set with `endorsedFilm: null` still conflicts, pinning that a plain field's `null` keeps its column meaning.
- `@service` record path: a new `EmailReplyRecordService.describeReply(EmailRecord)` in `graphitron-sakila-service`, wired as `describeEmailReply(in: InsertEmailReplyInput!): String @service(...)`, reporting `mailbox_id` and `in_reply_to_no` off the record the helper built, with no database write, in the manner of `StorageBinRecordAgreementService`. Cases go in `NodeIdValueAgreementExecutionTest`'s service section: explicit `null` `inReplyTo` reports mailbox 5 and a null pointer; agreeing ids report both; disagreeing ids still throw "Conflicting values". `inReplyTo` follows `mailboxRef` in the input, so its decode loads last, which is the order in which a missing `emitLoadPrepared` guard would overwrite `mailbox_id`.

The UPSERT insert arm and the payload-carrier INSERT chains reuse the decode seam and the cell builders unchanged, so they get no separate fixture.

## User documentation (first-client check)

`docs/manual/reference/directives/nodeId.adoc`, beside the existing UPDATE-clearing paragraph:

> On an INSERT input, a nullable (`ID`) reference can be sent as an explicit `null`. The row is inserted with the reference's own columns `NULL`. A column the reference shares with another field on the input, such as a tenant column several references include, takes that field's value instead, so a missing reference never conflicts with the references that are present. A value that is not a valid id of the right type is still rejected.

And the `@service` jOOQ-record paragraph gains one sentence after "an explicit `null` writes `NULL`": "where the field shares a column with another field on the input, that column takes the other field's value."

## Other solutions we've considered

- **Treat an explicit `null` as omitted.** Simpler to emit and matches the sis rows exactly, but on INSERT it means a column default instead of `NULL` for a defaulted reference column, and it gives a `@nodeId` a different `null` meaning from a plain `@field` on the same input for no reason the author can see. Writing `NULL` to the reference's own columns keeps the per-column meaning, and only the shared-column rule is specific to references.
- **A carrier-grain null rule for INSERT, stated by a walker the way `CarrierNullRule` is for UPDATE.** There is no third answer on INSERT to state, so the fact would be a copy of the field's SDL nullability that the leaf already carries.
- **Fix the UPDATE shared-SET agreement here too.** One rule across rails is the end state, but it reverses R880's reviewed `nullAgrees` decision and is entangled with sis's "ignore on UPDATE" expectation, so R1042 owns it.
