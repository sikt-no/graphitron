---
id: R478
title: "Seal KeyAlternative.KeyShape so each variant carries its requiredFields/columns contract"
status: Backlog
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Seal KeyAlternative.KeyShape so each variant carries its requiredFields/columns contract

`KeyAlternative.KeyShape` is a two-value enum (`DIRECT`, `NODE_ID`) whose variants carry structurally different relationships between `requiredFields` and `columns`, stated only in javadoc prose: `DIRECT` promises `requiredFields.size() == columns.size()` with index-by-index value mapping, while `NODE_ID` promises `requiredFields == ["id"]` with a NodeId decode into the `columns` list. Nothing enforces either promise; emitters that fork on `alt.shape()` (`HandleMethodBody.emitDecodeAndGroup`, `SelectMethodBody`) each re-derive the variant's contract from prose. R477 (batch node-id wrong-arity crash) is the motivating instance: the decode half of the pair sized its output from runtime data while the select half indexed by the model's column count, and the unenforced prose contract was the gap the bug lived in. Per the "sealed hierarchies over enums for typed information" principle, a sealed `KeyShape` with a `Direct` variant carrying the index mapping and a `NodeId` variant carrying its decode contract would let each emitter arm read the fields it needs and make the fork a sealed switch. Scope check at pickup: with only two variants and two fork sites the lift may not pay for itself yet; weigh against the arrival of further key shapes or batch-decode consumers.
