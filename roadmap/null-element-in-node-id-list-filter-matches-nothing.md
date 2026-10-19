---
id: R1073
title: "A null element in a node-id list filter matches nothing instead of failing the field"
status: Backlog
bucket: bug
priority: 5
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A null element in a node-id list filter matches nothing instead of failing the field

## Goal

A filter field typed as a list of node ids with nullable elements (`[ID]`) treats a `null`
element as matching nothing, the same way nullable-element scalar list filters already do, instead
of failing the field with "Invalid node id \"null\"".

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19).
`studieoppbygningsdeler(filter: {eierOrganisasjonskode: "1234", studieretninger: [null]})` returns
0 rows with no error on v9; v10 nulls the field with
`Invalid node id "null" for this argument: not a valid Studieretning id`. `[<valid id>, null]` fails
the same way on v10, where v9 returned the valid id's 4 rows. Scalar lists in the same input
(`eqfniva: [null]`, `kullErAktiv: [null]`) already read a null element as matching nothing on v10,
which is the rule the list-of-input-objects filter work (R1057, R1059) also follows.

## Plan sketch

The node-id decode for a list element skips `null` before decoding, so the element contributes no
key, and the documented rule ("a null element matches nothing") holds for every list filter shape.
A non-null element that does not decode keeps its client error.
