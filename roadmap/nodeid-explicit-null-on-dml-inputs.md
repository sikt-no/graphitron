---
id: R1039
title: "An explicit null on a nullable @nodeId input field writes NULL instead of failing the DML decode"
status: Backlog
bucket: bug
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An explicit null on a nullable @nodeId input field writes NULL instead of failing the DML decode

## Goal

A client that sends an explicit `null` for a nullable `@nodeId` field on a DML input gets the same row it would get by naming no id there: the field's own non-shared FK columns are written NULL, and a column the field shares with other `@nodeId` fields on the input (sis's tenant column `INSTITUSJONSNR_EIER`) takes its value from those other fields. Today two DML paths treat the null as data. On the INSERT rail the generated decode guard tests `row.containsKey(f) && decoded == null`, so a present-but-null value fails as "Decoded NodeId did not match the expected type for input field 'f'", which also reaches the client redacted (the R1036 shape). On the record path (`create<Input>Record`) the null takes part in the shared-column agreement: `agree0.add(kdPresentNull ? null : kdKeys[0])` feeds a null into `NodeIdEncoder.requireColumnAgreement`, which reports "Conflicting values supplied for input fields ..." against the other ids' tenant value. Only a non-null value that fails to decode should throw, and a null id should contribute nothing to agreement over shared columns. Graphitron 9 behaved this way, and sis's schema depended on it.

## Evidence

Reported from the sis port on 2026-10-15, verified on trunk `e4cfef7c1` with sis integration tests. Both inputs carry several `@nodeId` fields whose keys all include `INSTITUSJONSNR_EIER`; omitting the nullable field works in both.

- INSERT rail: `LeggTilEmnerIUtdanningsplanerInput.studieoppbygningsdelId: ID @nodeId(typeName: "Studieoppbygningsdel")` sent as `null`. Generated `MutationFetchers.leggTilEmnerIUtdanningsplaner` decodes with `instanceof String _s ? decode(_s) : null` and then throws on `containsKey && insertKey == null`. Failing IT: `LeggTilEmnerIUtdanningsplanerMutationIT.leggTilEmnerIUtdanningsplaner_medEksplisittNullStudieoppbygningsdel_oppretterRadOgReturnererPayload`.
- Record path: `RegistrerVeiledningsforholdInput.emneId: ID @nodeId(typeName: "Emne")` sent as `null` on `registrerVeiledningsforhold` (`@mutation(typeName: INSERT, table: "VEILEDNINGSFORHOLD")`). Expected row: `INSTITUSJONSNR_EIER` from the other ids, `EMNEKODE` and `VERSJONSKODE` null. Failing IT: `VeiledningsforholdMutationIT.registrerVeiledningsforhold_medEksplisittNullEmne_oppretterRadMedStudierettensInstitusjon`.

## Context, not scope

`endreVeiledningsforhold` (UPDATE) with an explicit null on the required-FK fields `veilederkategoriId` / `internstatusId` ends in an Oracle `DataAccessException`, where sis's v9 test expects the nulls to be ignored. Whether an explicit null on a `@nodeId` in an UPDATE means SET NULL, "leave unchanged", or a rejection is a contract question that touches R881 (no write carrier reads column nullability); the Spec decides whether it belongs here or in a sibling item. The Spec also owes a census of which other rails (UPSERT, DELETE, filter) share either emitter shape.
