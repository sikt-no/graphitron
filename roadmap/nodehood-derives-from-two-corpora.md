---
id: R711
title: "Nodehood derives from two corpora instead of being decided in capture"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: [jooq-node-metadata-as-stated-facts, three-strata-capture-derive-query]
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

## A misplaced stratum, not an inverted polarity

The store has three strata. Capture transcribes facts from a corpus. Derivation computes further
facts from captured ones. Queries read facts to serve a goal. Every relation belongs to exactly one
stratum, and the test is mechanical: a row that can be recomputed from captured facts alone is a
derived fact and must not be captured.

Federation-key synthesis fails that test. It consumes captured facts, the SDL claim rows and the
node metadata the sibling item records, and produces a fact computable from them. It is stratum two
running inside stratum one, and its output lands in stratum-one relations where nothing distinguishes it
from a transcription.

`MacroCapture`'s javadoc once defended the arrangement in a different vocabulary, as keeping the
store's picture effective rather than authored. That vocabulary is retired and the javadoc now states
the stratum instead, but the reason it does not survive contact with the other corpora is worth
keeping here. Everything in the store is authored by somebody: the DDL behind the jOOQ
classes was authored, the service methods were authored, the configuration was authored. "Authored"
therefore partitions nothing, and "effective" is singular where the truth is plural, since a
round-trip emitter, a federation publisher and an LSP hover each want a different composition. Baking
one of them into the base relations makes one goal's answer the store's shape.

The consequence of the misplacement is visible in the schema, and is the clearest argument for the
stratum reading. `graphitron_field_synthesis.authored_type_sdl` exists because the connection macro
overwrote a captured fact in `graphql_field` with a derived one, so the captured fact had to be
stashed in the provenance table as unparsed text ("the type expression as the author wrote it,
pre-expansion"), and a view now recovers it with nested `REPLACE` calls stripping `[`, `]` and `!`.
A captured fact is being reconstructed by string surgery because a derived fact took its seat.

The three synthesis relations, `graphitron_type_directive_synthesis`,
`graphitron_field_synthesis` and `graphitron_type_declaration_synthesis`, each carry a foreign key to
the stratum-one relation whose rows they annotate. They exist only to mark which rows a macro put there.
Under stratum discipline a derived fact lives in a derived relation and the relation is its own
provenance, so all three become unnecessary. Retiring them is out of scope here (this item moves one
macro), but they are the measure of whether the frame is right.

The wrinkle most likely to bite: `expandFederationKeys` writes through
`SdlFactCapture.captureTypeDirective` with an ordinal drawn from the SDL walk's per-type counter, so
a synthesized application is interleaved into a stratum-one relation's ordinal sequence. A derivation
has to allocate above the maximum transcribed ordinal for its type instead. That is computable from
the rows, but it changes where a synthesized application sits, and any reader comparing ordinals
across the two strata has to be found.

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
- Readers wanting the composition including synthesized keys issue a stratum-three query over the
  transcribed rows plus the derived ones, rather than reading one composition off stratum one.

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

- Reclassifying the `graphitron_` family. Those relations are decodes of the generic directive
  applications `graphql_` transcribes, so the family is a derivation over captured facts and not a
  second corpus; the 48 foreign keys from it into `graphql_` are a derivation's edges to its inputs.
  `graphitron_node` is the relation this item's nodehood derivation belongs in, which makes this item
  the first instance of that reclassification rather than a special case. The rest of the family is
  its own item.
- The `CONNECTION` macro, and the general stratum correction across every family. Also the three
  synthesis relations and `graphitron_field_synthesis.authored_type_sdl`, which the stratum reading
  predicts become unnecessary but which this item does not touch.
- Splitting the capture transaction per crawler.
