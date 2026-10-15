---
id: R1039
title: "An explicit null on a nullable @nodeId input field writes NULL instead of failing the DML decode"
status: In Progress
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
- Record path: `RegistrerVeiledningsforholdInput.emneId: ID @nodeId(typeName: "Emne")` sent as `null` on `registrerVeiledningsforhold`. Expected row: `INSTITUSJONSNR_EIER` from the other ids, `EMNEKODE` and `VERSJONSKODE` null. Failing IT: `VeiledningsforholdMutationIT.registrerVeiledningsforhold_medEksplisittNullEmne_oppretterRadMedStudierettensInstitusjon`. The quoted failing statement, `agree0.add(kd<j>PresentNull ? null : kd<j>Keys[0])` (the report elides the index), is the output of `JooqRecordInstantiationEmitter`, which only a `@service` parameter typed as a jOOQ record reaches, so this mutation reaches graphitron through such a parameter. The implementer confirms the directive shape against sis at pickup; the fix below is the same either way.

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

- `buildInsertDecodeLocals`: declare each leaf's decode local in the three-statement shape R880 introduced, so an explicit `null`, a non-string wire value and a wrong-type id stop collapsing onto one `null`. Reuse `appendSetDecodeLocal`, passing `OnExplicitNull.CannotArrive` for an `ID!` leaf and `OnExplicitNull.Clears` for an `ID` leaf, read off the leaf carrier's `nonNull()`, and pass the leaf's presence expression (today's `nestedContainsKeyExpr(mapLocal, path, "id" + fi)`) as `presenceExpr`. The INSERT rail needs no carrier-grain rule of its own: the third answer, `RefusedAsIdentity`, guards a matched key, and an INSERT has none. Update `appendSetDecodeLocal`'s javadoc so it names its INSERT caller.
- `appendSetDecodeLocal`'s `CannotArrive` arm: when `presenceExpr` is non-null, emit the presence-gated shape (`<local> = (<wire> instanceof String _s<local>) ? decode(_s<local>) : null`, then `if (<presence> && <local> == null) throw`) instead of delegating to `appendDecodeLocal`, whose throw is ungated. The INSERT locals are declared outside any presence guard, single-row and bulk alike, so an ungated throw would fire on an `ID!` `@nodeId` leaf inside an omitted nullable grouping input, where today the column is left out. A null `presenceExpr` keeps the `appendDecodeLocal` delegation, which is correct for the single-row UPDATE SET caller that declares its local inside the presence guard. This is exactly the shape `emitBulkSetDecodeLocals` inlines for `CannotArrive` today, for the same reason; that inline copy goes, and the bulk SET caller routes its `CannotArrive` leaves through the seam with the presence expression it already passes. Output is byte-identical for every `ID!` leaf on both the INSERT and bulk UPDATE SET paths, which the implementer confirms by diffing `graphitron-sakila-example`'s generated sources before and after on those paths.
- `insertPresentCell`: an unshared decode column reads `insertKey_<fi> == null ? null : insertKey_<fi>.value<slot+1>()`, emitted only for a nullable leaf, so a present `null` binds a typed `NULL` instead of throwing a `NullPointerException`. Read the nullability off the flattened leaf the writer's `index()` already names (`flattenInsertLeaves`), not off `SetGroup`: that record is shared with the UPDATE SET plan, where R880 kept null semantics off it deliberately (see `CarrierNullRule`'s javadoc).
- `emitInsertAgreementPrep`: the gather already drops a `null` decode local (`appendAgreementValue` with `nullAgrees` false); rewrite the comment that relies on the throw. The shared column's cell must cover "present, but nothing to agree on": on the single-row path the cell reads `insertKeyAgree<ci>.isEmpty() ? null : insertKeyAgree<ci>.get(0)` (today an empty list reaches `get(0)`), and on the bulk path the coalesced `insertKeyCell<ci>` becomes the typed `NULL` when the column's presence gate holds and the list is empty, keeping `DSL.defaultValue` for the true gap where no writer is present.

`JooqRecordInstantiationEmitter`:

- `emitAgreement` / `agreeValueExpr`: a nullable decode contributes only when it is present *and* not `null`. The gather guard becomes `if (<base>Present && !<base>PresentNull)` and the value is `<base>Keys[<slot>]`. Plain writers are unchanged.
- `emitLoadPrepared`: a present-`null` decode sets `NULL` unconditionally on a target column no other writer shares, and on a shared column only when that column's agreement list is empty. Today the guard is moot because agreement throws first; once a `null` drops out of agreement, a cleared decode loading after a decode that carries the value would overwrite the shared column. `emitWithAgreement` numbers the agreement lists `agree<ci>` in its loop and keeps no mapping today; it records each shared column's list name as it emits the agreement phase and passes that map into the load phase.
- The class javadoc's flag-reset note still applies: a `NULL` set by a cleared decode loses its touched flag when a later decode loads through `fromArray`, so a service that inserts the record sees the column omitted (the default applies) rather than bound to `NULL`. For a column with no default the row is the same, which covers both the sis case and the fixture below; it is pre-existing behaviour of the overlap-free path too, and this item does not change it.

## Tests

Execution tier, `graphitron-sakila-example` against PostgreSQL. These are the evidence the goal is delivered; none asserts on generated code.

- `SelfFkNodeIdInsertExecutionTest` (single-row INSERT, sis's INSERT shape): `explicitNullNullableSelfFk_insertsInMailboxRefsMailboxWithNullReplyPointer`, which asserts the row `(5, n, NULL)` and the same returned `Email` as the omitted case, plus `wrongTypeInReplyTo_stillThrowsAndInsertsNothing`, which sends a `Mailbox` id as `inReplyTo`.
- Bulk INSERT, sis's shape: a new fixture `insertEmailReplies(in: [InsertEmailReplyInput!]!): [Email!]! @mutation(typeName: INSERT)` beside `insertEmailReply`, and a case in the same test class whose three rows send a value, an explicit `null` and nothing for `inReplyTo` across both seeded mailboxes. It asserts each row's `mailbox_id` comes from its own `mailboxRef` and that `in_reply_to_no` is the parent's number, `NULL` and `NULL` respectively. `mailbox_id` always has `mailboxRef`'s value, and `in_reply_to_no` has `inReplyTo` as its only writer, so this case exercises the shared cell's value arm per row and the unshared cell's new present-`null` arm (`insertPresentCell`) and existing gap arm; it does not reach the shared cell's empty-list arms, which the next case does.
- Bulk INSERT, shared column with nothing to agree on: a new fixture `insertBookmarkOverlaps(in: [FilmBookmarkOverlapInput!]!): [FilmBookmark!]! @mutation(typeName: INSERT)` beside `insertBookmarkOverlap`, over `film_bookmark`, whose `bookmarked_film` is nullable and shared by `filmRef` and the plain `bookmarkedFilm`. One case in `DmlBulkMutationsExecutionTest`, beside `insertBookmarkOverlap_sharedColumnWithNoWriterPresentIsNotNamed`, sends three rows that each carry a distinct `note` and differ only in `filmRef`: set, explicit `null`, and omitted. Every row supplying `note` keeps `note` out of the gap arm, so `bookmarked_film` is the only column that can take a `default` cell. It asserts, matching rows by `note`, that `bookmarked_film` is the film, `NULL` and `NULL`, and, through the class's captured `insertStatements()`, that the statement renders exactly one `default` cell: the third row's `bookmarked_film` gap. The second row binds a typed `NULL` rather than `DEFAULT`, which is the only observable difference between the present-`null` arm and the gap arm on a column with no default.
- `NodeIdValueAgreementExecutionTest` (INSERT, shared column with a *plain* co-writer, over `InsertEndorsementOverlapInput`): `filmRef: null` with `endorsedFilm` set writes the plain value; `filmRef` set with `endorsedFilm: null` still conflicts, pinning that a plain field's `null` keeps its column meaning. `endorsed_film` is `NOT NULL`, so the lone-`null` case cannot land a row there and lives on `film_bookmark` instead.
- Single-row INSERT, lone explicit `null` on a shared column: in `DmlBulkMutationsExecutionTest` beside the existing `insertBookmarkOverlap` case, `insertBookmarkOverlap(in: {filmRef: null, note: ...})` lands a row with `bookmarked_film` `NULL`, and the captured statement names `bookmarked_film` (the column is present, bound to `NULL`) where the existing omitted case leaves it out.
- `@service` record path: a new `EmailReplyRecordService.describeReply(EmailRecord)` in `graphitron-sakila-service`, wired as `describeEmailReply(in: InsertEmailReplyInput!): String @service(...)`, reporting `mailbox_id` and `in_reply_to_no` off the record the helper built, with no database write, in the manner of `StorageBinRecordAgreementService`. Cases go in `NodeIdValueAgreementExecutionTest`'s service section: explicit `null` `inReplyTo` reports mailbox 5 and a null pointer; agreeing ids report both; disagreeing ids still throw "Conflicting values". `inReplyTo` follows `mailboxRef` in the input, so its decode loads last, which is the order in which a missing `emitLoadPrepared` guard would overwrite `mailbox_id`.

The UPSERT insert arm and the payload-carrier INSERT chains reuse the decode seam and the cell builders unchanged, so they get no separate fixture.

## User documentation (first-client check)

`docs/manual/reference/directives/nodeId.adoc`, beside the existing UPDATE-clearing paragraph:

> On an INSERT input, a nullable (`ID`) reference can be sent as an explicit `null`. The row is inserted with the reference's own columns `NULL`. A column the reference shares with another field on the input, such as a tenant column several references include, takes that field's value instead, so a missing reference never conflicts with the references that are present. A value that is not a valid id of the right type is still rejected.

And the `@service` jOOQ-record paragraph gains one sentence after "an explicit `null` writes `NULL`": "where the field shares a column with another field on the input, that column takes the other field's value." The same paragraph's closing sentence, "A self-reference (an `@reference` on a same-table `@nodeId`) is rejected as out of scope.", is already false (`InputBeanResolver.buildRecordKeyDecode` maps a same-table `@reference` through the self-FK, pinned by `JooqRecordServiceParamPipelineTest.selfFkReferenceOnSameTableNodeId_admits_targetsSelfFkChildColumns`) and is removed in the same edit, since this item's `describeEmailReply` fixture is exactly that shape.

## Other solutions we've considered

- **Treat an explicit `null` as omitted.** Simpler to emit and matches the sis rows exactly, but on INSERT it means a column default instead of `NULL` for a defaulted reference column, and it gives a `@nodeId` a different `null` meaning from a plain `@field` on the same input for no reason the author can see. Writing `NULL` to the reference's own columns keeps the per-column meaning, and only the shared-column rule is specific to references.
- **A carrier-grain null rule for INSERT, stated by a walker the way `CarrierNullRule` is for UPDATE.** There is no third answer on INSERT to state, so the fact would be a copy of the field's SDL nullability that the leaf already carries.
- **Fix the UPDATE shared-SET agreement here too.** One rule across rails is the end state, but it reverses R880's reviewed `nullAgrees` decision and is entangled with sis's "ignore on UPDATE" expectation, so R1042 owns it.

## Reviewer findings

### Round 1 (2026-10-15, Spec -> Ready, reviewer session 01V3bb9ygBzzjMoHEbMNG9ih)

Verdict: withhold. One finding on question two, two on question one. The goal reads cleanly on its own: an explicit `null` on a nullable `@nodeId` reference in an INSERT or `@service` record input becomes "no reference" (its own columns `NULL`, shared columns taken from the other writers) instead of an error. The rule is sound, the census is accurate, the record-helper half is ready as written, and every symbol the spec names exists as named. The three findings are each narrow.

**Finding 1 (question two: architecture fit). Reusing `appendSetDecodeLocal` with `OnExplicitNull.CannotArrive` for an `ID!` leaf is not byte-identical to today, and regresses a nested `ID!` leaf.**

The `CannotArrive` arm delegates to `appendDecodeLocal`, whose throw is `if (<local> == null)` with no presence gate. That shape is only correct where the local is declared inside the leaf's presence guard (the single-row UPDATE SET path, which passes `presenceExpr == null` for that reason). `buildInsertDecodeLocals` declares its locals unconditionally, single-row and bulk alike, and gates today's throw on `nestedContainsKeyExpr(...) && insertKey_<fi> == null`. Routed through `CannotArrive`, the gate disappears: for a top-level `ID!` leaf the change is textual only, but for an `ID!` `@nodeId` leaf inside an omitted nullable grouping input (`flattenInsertLeaves` descends `NestingField`, and the decode-local comment says nested NodeId leaves are read through the null-safe descent) the wire value reads `null`, the local is `null`, and the INSERT throws "Decoded NodeId did not match" where today the column is simply left out. The tree's own sibling already met this: `emitBulkSetDecodeLocals`, whose locals are also declared unconditionally, does not call `appendSetDecodeLocal` for `CannotArrive` and inlines the presence-gated shape instead.

What would satisfy it: say how the `ID!` arm keeps its presence gate, either inline as `emitBulkSetDecodeLocals` does, or by having `appendSetDecodeLocal`'s `CannotArrive` arm honour a non-null `presenceExpr` (which would also let `emitBulkSetDecodeLocals` drop its inline copy). Then the byte-identical claim holds.

> Author response: took the second arm. `appendSetDecodeLocal`'s `CannotArrive` arm now emits the presence-gated shape when `presenceExpr` is non-null; `buildInsertDecodeLocals` passes the leaf's presence expression for both arms, and `emitBulkSetDecodeLocals` drops its inline copy and routes through the seam. The Implementation bullet states the byte-identical claim for both callers and how the implementer confirms it.

**Finding 2 (question one: viability of the named evidence). `filmRef: null` alone on `InsertEndorsementOverlapInput` cannot write `NULL`.**

`film_endorsement.endorsed_film` is `int NOT NULL` in `init.sql`, so that case gets a database NOT NULL violation, not a row with `NULL`. The schema comment above `FilmBookmarkOverlapInput` says exactly this and names `film_bookmark.bookmarked_film` (nullable) as the fixture for the shape; `insertBookmarkOverlap` already exists over it, with `DmlBulkMutationsExecutionTest.insertBookmarkOverlap_sharedColumnWithNoWriterPresentIsNotNamed` as the omitted-writers precedent. What would satisfy it: move the "lone explicit `null` writes `NULL`" case to `insertBookmarkOverlap` (or another nullable shared column), keeping the two `InsertEndorsementOverlapInput` cases where a value lands.

> Author response: moved. The lone-`null` single-row case now runs on `insertBookmarkOverlap` in `DmlBulkMutationsExecutionTest`, beside the omitted-writers precedent, and also asserts the statement names the column; the two endorsement cases where a value lands stay in `NodeIdValueAgreementExecutionTest`, with a sentence saying why the third moved.

**Finding 3 (question one: the bulk test does not reach the arm it claims to).**

The bulk case is said to exercise "the coalesced shared cell's value, present-`null` and gap arms". On `InsertEmailReplyInput` the only shared column is `mailbox_id`, and its writer `mailboxRef` is `ID!`, so `insertKeyAgree<ci>` is never empty on any row; `in_reply_to_no` is written by `inReplyTo` alone and so goes through the unshared cell, not `insertKeyCell<ci>`. The three rows do pin the new unshared null arm of `insertPresentCell` and the existing unshared gap cell, which is worth keeping, but the new bulk shared-cell branch (typed `NULL` when the presence gate holds and the list is empty, `DSL.defaultValue` otherwise) has no execution case anywhere in the plan; the single-row shared-cell case in Finding 2 does not reach the bulk prep. What would satisfy it: a bulk case over a shared column whose writers can all be absent or `null` within one batch (a bulk sibling of `insertBookmarkOverlap` would do: rows sending `filmRef: null`, nothing, and a value), or a corrected claim with a reason that arm needs no execution case.

> Author response: both. The `insertEmailReplies` bullet now says what it does and does not reach. A new bulk fixture `insertBookmarkOverlaps` over `FilmBookmarkOverlapInput` sends `filmRef` set, `filmRef: null` alone and nothing, and pins the empty-list split through the captured SQL: `bookmarked_film` has no default, so a typed `NULL` and a `DEFAULT` land the same row, and the statement's single `default` cell is what tells the present-`null` arm from the gap arm.

Non-blocking: the evidence quotes the failing statement as `agree0.add(kdPresentNull ? null : kdKeys[0])`, but `JooqRecordInstantiationEmitter` names a decode's locals `kd<j>` (`writersOf`), so the emitted text is `kd<j>PresentNull ? null : kd<j>Keys[<slot>]`; presumably the report elided the index. It does not change the diagnosis.

> Author response: the Evidence quote now shows the index placeholder.

### Round 2 (2026-10-15, Spec -> Ready, reviewer session 01EdUA1EAhxRk7pynnivzm12)

Verdict: withhold, on one narrow finding under question one. The round 1 responses hold up against the tree. The goal is clear without the phase list: an INSERT, or a `@service` that takes a jOOQ record, accepts an explicit `null` for a nullable `@nodeId` reference and treats it as "no reference". The reference's own columns become `NULL`, and a column it shares takes the other writers' value, so the request no longer fails. The plan fits the architecture. It widens the existing three-way decode seam (`appendSetDecodeLocal`), deletes the inline copy in `emitBulkSetDecodeLocals` instead of adding a third one, and reuses `appendAgreementValue` with `nullAgrees` false, which is already the reading the INSERT gather uses. The reason it gives for keeping a carrier-grain rule off INSERT is sound. The census is accurate, and the record path admits the self-FK `@reference` decode the new service fixture needs (`InputBeanResolver.buildRecordKeyDecode`, pinned by `JooqRecordServiceParamPipelineTest.selfFkReferenceOnSameTableNodeId_admits_targetsSelfFkChildColumns`).

**Finding 1 (question one: the new bulk case cannot pass the assertion it names).**

The `insertBookmarkOverlaps` case sends `filmRef` set, `filmRef: null` alone, and `note` alone, then asserts that the statement "renders exactly one `default` cell: the third row's gap". `note` is an unshared column. The third row supplies it, so `insertInc` names it, and `buildBulkInsertValues` gives every row that leaves it out the gap cell `DSL.defaultValue(...)`. As the rows are written, the second row and probably the first carry a `default` for `note`, on top of the third row's `default` for `bookmarked_film`. The assertion fails on a correct implementation, and the single-`default` count is the only thing in this case that tells the present-`null` arm from the gap arm, which is what round 1's Finding 3 asked for. What would satisfy it: make the rows agree on every column other than `bookmarked_film` (every row sends a `note`, say), or pin the `default` to the `bookmarked_film` cell some other way. Then restate the assertion to match.

Non-blocking, for the implementer: `docs/manual/reference/directives/nodeId.adoc`'s `@service` jOOQ-record paragraph, the one this item extends, ends with "A self-reference (an `@reference` on a same-table `@nodeId`) is rejected as out of scope." That stopped being true before this item, and the new `describeEmailReply` fixture is exactly such a self-reference on that path, so the sentence should go in the same doc edit. Separately, `emitWithAgreement` does not keep a column-to-list map today. It numbers the lists `agree<ci>` inside its loop, so passing "the mapping it already builds" means building one, which is a small edit.

> Author response: took the first arm. Every row of the `insertBookmarkOverlaps` case now sends a distinct `note` and the rows differ only in `filmRef` (set, explicit `null`, omitted), so `note` never takes a gap cell and the single `default` the case asserts is the third row's `bookmarked_film`; rows are matched by `note`. Both non-blocking notes are folded in: the User documentation section now removes the stale self-reference sentence from the `@service` jOOQ-record paragraph, and the `emitLoadPrepared` bullet says `emitWithAgreement` builds the column-to-list map rather than already having one.
