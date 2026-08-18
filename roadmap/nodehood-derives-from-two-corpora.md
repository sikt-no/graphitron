---
id: R711
title: "Nodehood derives from two corpora instead of being decided in capture"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: [jooq-node-metadata-as-stated-facts]
created: 2026-08-18
last-updated: 2026-08-18
---

# Nodehood derives from two corpora instead of being decided in capture

Capture has exactly one place where the rows it writes about one corpus depend on the contents of
another. `MacroCapture.expandFederationKeys` asks `NodeDeclaration.isNodeType(object)` before
synthesizing a federation `@key`, and that predicate conjoins the SDL declaration with
`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` read by reflection off the backing jOOQ class. So changing a
jOOQ-generated class makes the SDL crawler write different `graphql_` and `graphitron_` rows from an
unchanged `.graphqls` file.

That is validation and expansion happening in capture. The SDL states a claim (`@node`, or `@table`
plus `implements Node`); deciding whether the claim holds needs data the SDL does not contain, and
therefore belongs to a reader of the store rather than to a writer of it.

## Why no foreign key caught it

Worth recording, because it is the reason this survived a schema designed to prevent exactly this. A
foreign key constrains *references*, and the fact schema already refuses to model SDL-to-jOOQ
resolution as one: there is no `graphql_ -> sql_` edge anywhere, and a `@table(name:)` row is a
string a crawler transcribed, not a pointer at `sql_table`. But this coupling does not add a
reference. It changes *which rows exist*. No constraint expressible in the DDL could have rejected
it, which is why the gate below is part of the deliverable rather than a nice-to-have.

## The polarity inversion

`MacroCapture`'s javadoc states today's arrangement deliberately: running expansion inside capture
"keeps the store's picture effective rather than authored, and keeps the authored picture recoverable
as the anti-join against the provenance relations". This item inverts that for one macro. The
authored picture becomes what the base relations hold; the effective picture becomes derived; and the
anti-join stops being needed, because the derivation is the provenance.

The wrinkle most likely to bite: `expandFederationKeys` writes through
`SdlFactCapture.captureTypeDirective` with an ordinal drawn from the SDL walk's per-type counter, so
a synthesized application is interleaved into a base relation's ordinal sequence. A derivation has to
allocate above the maximum authored ordinal for its type instead. That is computable from the rows,
but it is a real change to how a synthesized application is positioned, and any reader comparing
ordinals across the two pictures has to be found.

## Shape

- `NodeDeclaration` leaves the capture API outright: the `nodes` parameter on `FactCapture.run` and
  `runWithDetections`, the field on `SdlFactCapture`, the field on `MacroCapture`. The class survives
  for its pipeline consumers; what retires is capture's dependency on it.
- Nodehood becomes a derivation joining the SDL claim rows to the validated node metadata the sibling
  item records. First cross-corpus join in the tree, and the right home for one: a derivation may
  join corpora precisely because no crawler may.
- The federation-key macro moves whole, not by arm. The rule is a disjunction over a declared arm
  (pure SDL) and an inferred arm (needs jOOQ), and splitting it across capture and derivation would
  give one rule two homes and two ordinal allocators.
- Readers of the effective picture move to a view over base plus derived synthesis.

## The gate that keeps it fixed

"Each crawler is responsible for a corpus that exists independently" is testable: run a crawler with
the other corpora absent and with them present, and its rows must be identical. Run `SdlFactCapture`
against a null jOOQ catalog and against a real one, and the `graphql_` / `graphitron_` output must not
differ. That assertion fails today on the federation-key synthesis rows, which makes it the
regression test for this item, and it goes on to reject the next capture-time cross-corpus read,
which is what a foreign key cannot do.

`NodeDeclaration` already accepts a null catalog for test contexts, so the negative arm needs no new
plumbing.

## Sequencing

Depends on the sibling item recording the stated node metadata; there is nothing to join against
until those rows exist. This item is the pilot for a general rule (capture states, derivation
expands) and not the rule itself: the other synthesis macro, `CONNECTION`, is nodehood-free and pure
SDL, so it neither blocks this nor is fixed by it.

## Out of scope

- Splitting `graphitron_` from `graphql_`. The eventual reason to split them is that one is a corpus
  anyone can transcribe with no graphitron knowledge and the other is a claim layer needing jOOQ,
  the classpath and configuration to resolve, which only becomes true once expansion has moved out
  of capture. This item is a precondition for that argument, not the split.
- The `CONNECTION` macro, and the general authored/effective inversion across every family.
- Splitting the capture transaction per crawler.
