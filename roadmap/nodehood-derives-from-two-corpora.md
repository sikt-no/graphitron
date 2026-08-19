---
id: R711
title: "Nodehood derives from two corpora instead of being decided in capture"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-19
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
store's picture effective rather than authored. That vocabulary is retired and the javadoc now calls
the expansion a derivation inside the capture walk instead, but the reason it does not survive
contact with the other corpora is worth
keeping here. Everything in the store is authored by somebody: the DDL behind the jOOQ
classes was authored, the service methods were authored, the configuration was authored. "Authored"
therefore partitions nothing, and "effective" is singular where the truth is plural, since a
round-trip emitter, a federation publisher and an LSP hover each want a different composition. Baking
one of them into the base relations makes one goal's answer the store's shape.

The consequence of the misplacement is visible in the schema, and is the clearest argument for the
stratum reading. `graphitron_field_synthesis.authored_type_sdl` exists because the connection macro
overwrote a captured fact in `graphql_field` with a derived one, so the captured fact had to be
stashed in the provenance table as unparsed text ("the type expression as the author wrote it,
pre-expansion"), and two views now recover it with nested `REPLACE` calls stripping `[`, `]` and `!`.
A captured fact is being reconstructed by string surgery because a derived fact took its seat.

The three synthesis relations, `graphitron_type_directive_synthesis`,
`graphitron_field_synthesis` and `graphitron_type_declaration_synthesis`, each carry a foreign key to
the stratum-one relation whose rows they annotate. They exist only to mark which rows a macro put there.
Under stratum discipline a derived fact lives in a derived relation and the relation is its own
provenance, so all three become unnecessary. This item retires only the first, whose sole macro is
the one that moves; the other two are `CONNECTION`'s and stay.

## Design

The move lands as four derived views (two node relations, the synthesis rule, and the composed key
reduction) and a capture deletion. Nothing reaches generated code: the
emitted schema's synthesized `@key` comes from `KeyNodeSynthesiser`'s registry rewrite on the live
pipeline, which stays exactly as it is. What changes is how the store describes the same schema.

### `intent_inferred_node_type` and `intent_node_type`

Two relations rather than one tagged one, per the fact model's provenance rule: authored and
inferred values coming from independent walks live in separate relations coalesced by a view, not in
one relation with a provenance tag no reader forks on.

`intent_inferred_node_type`, keyed `(graph_name, type_name)`, is the inferred population: a
`graphitron_table` row, plus `graphql_implements` on `Node`, plus the binding resolved through
`intent_bound_table` with `candidates = 1` (transcribing the classifier's Ambiguous verdict, per
that view's own comment), plus a `sql_node_metadata` row on the resolved table's full key with no
`intent_node_metadata_defect` row for it. The well-formedness conjunction is the one
`intent_node_metadata_defect`'s comment states: a row exists and no defect rows do. The view carries
the resolved table's `(table_source_name, table_schema, table_name)` as witness columns, so a reader
asking which table's metadata made this a node holds the answer instead of re-deriving the binding;
that is also what makes the out-of-scope identity item a join rather than a re-derivation.

`intent_node_type`, keyed `(graph_name, type_name)`, is the membership reduction: the `UNION` of
`graphitron_node`'s key columns and the inferred relation's. `@node` without `implements Node`
still reads as a node here, matching `NodeDeclaration.isNodeType`'s declaration-level answer;
rejecting that shape stays the classifier's job. The predicate's declared-wins short-circuit needs
no transcription: a `UNION` dedupes, so precedence dissolves with the tag column that would have
asked for it.

A cross-corpus join is the licensed shape here rather than a new one: a derivation may join corpora
precisely because no crawler may, and seven shipped `intent_` views already do it. The nearest is
`intent_resolved_node_key_column`, whose `JOOQ_METADATA` tier already carries the conjunction this
view needs, a `sql_node_metadata` row on the binding's table key, `candidates = 1`, and no
`intent_node_metadata_defect` row for that table. Two things follow, and both are settled here
rather than at the keyboard.

The binding the two stand on differs, deliberately. That tier stands on
`intent_resolved_type_binding`, which coalesces the `@table` binding with the one a `@routine`
chain's return derives; this view stands on `intent_bound_table`, the `@table` arm alone, because
nodehood demands a written `@table`: `NodeDeclaration.isNodeType` reads the directive's presence
before it probes anything, so a type whose only binding is a routine return is not a node however
well-formed that table's metadata is. Either stand scopes the catalog side through
`store_graph_source`, which already routes the join the `store_graph` comment calls underdetermined
without it, so the scoping is not what picks between them.

The conjunction therefore gets a second spelling in SQL, and this item accepts that rather than
extracting it. The extraction (a well-formed-stated-metadata relation on `sql_table`'s own key,
which both stands could then join) would retrofit a shipped tier and the seeded anchor that pins it
(`ResolvedNodeKeyColumnTest`) for a rule this item does not otherwise touch; the out-of-scope list
names it as the follow-on. What the item does owe is that the duplication is visible rather than
latent: each of the two view comments names the other as the sibling spelling, so a reader who finds
one finds the pair.

Views rather than materializations: there is no recursion, and the fact
model sanctions materialization only where a view cannot serve; being views, they also cannot go
stale against a warm-store catalog refresh, since `CatalogFactCapture` writes in the same
transaction the readers run in.

**Population: the merged type, stated deliberately.** `graphitron_table` and `graphql_implements`
sit at the type grain, extension sites included, while `MacroCapture` read the base
`ObjectTypeDefinition` alone. Merged is the correct answer: the assembled-schema overload of
`isNodeType` that `SchemaReachability`, `ArrivalIndex` and `CatalogBuilder` use already sees the
merged type, so the macro was the outlier. The one place the difference can show is the agreement
anchor, whose expectation side (`KeyNodeSynthesiser`) uses the raw-registry base-only overload: the
fixture corpus exercises no extension-contributed `implements Node` or `@table` today, and
implementation confirms that rather than assumes it.

The live probe (`JooqCatalog.nodeIdMetadata`) and the inferred relation can in principle part on an
ambiguous table spelling; the agreement anchor below is where such a divergence surfaces, exactly as
the sibling item's stated-versus-live agreement assertion already watches the metadata verdict
itself.

### `intent_synthesized_federation_key`

Federation's node-entity rule as a relation: one row per `(graph_name, type_name)` that gets a
synthesized key. A row exists when all three hold:

- the graph is federation-linked: a `graphitron_link` row whose `url` has prefix
  `FederationSpec.SPEC_PREFIX`. The decode, not the verbatim twin: `graphitron_link.url` is stored
  as written and the relation's own comment already says the federation opt-in is a predicate over
  it, whereas
  reading `graphql_schema_directive_arg.value_sdl` would mean compensating for AST quoting, the
  exact string surgery this item's problem statement condemns. A `url` the author omitted is a null
  and matches nothing, which is `isFederationLink`'s null guard falling out of the join. The prefix
  is a SQL literal, a third spelling beside the two Java readers the constant keeps
  (`TagLinkSynthesiser` and `FederationLinkApplier`; this item removes the third), so a named test
  pins the literal against `FederationSpec.SPEC_PREFIX` (a view cannot bind a query parameter the way
  `ReachabilityRows` binds `DeclaredDirectives.names()`);
- `intent_node_type` has the type;
- no authored id-key: no `graphitron_federation_key` row on the type whose decode is exactly the
  single path `id` (exactly one `_field` row, at position 0, with exactly one segment `id`). This
  transcribes `hasIdKey` including its deliberate asymmetry: a malformed `fields:` decodes to no
  field rows and therefore does not count as the id key, so the misuse reaches its detection instead
  of suppressing synthesis on the strength of a parse failure.

The view projects the authored decode's key-grain columns beside the membership key: `fields_sdl`
as `"id"` and `resolvable` as true, the rule's constants appearing once, in SQL, rather than in a
comment every composing reader re-mints from. And because the composition already has two askers
(the agreement anchor below, and the round-trip emitter the provenance table's comment was written
for), it gets a relation now rather than a re-spelling later: `intent_federation_key`, the key-grain
reduction unioning the authored `graphitron_federation_key` rows with the synthesized ones. The
path-and-segment grain stays authored-only until a reader asks for it composed. The synthesized
relation is its own provenance, which is what retires the provenance table.

One guard travels nowhere: `expandFederationKeys` skips a type whose declaration quarantined as a
duplicate, but only because there was no site row to hang the `graphql_type_directive` foreign key
off. The derived relation carries no site reference, so the guard vanishes with the write it
protected; on a duplicate-declaration schema the detection remains the story.

### Where the synthesized rows stop landing

The synthesized application leaves `graphql_type_directive`, `graphql_type_directive_arg`,
`graphitron_federation_key` and its children, and `graphitron_type_directive_synthesis` entirely.
Stratum one becomes pure transcription of the SDL corpus at this coordinate, and
`graphitron_federation_key` becomes what its family charter says it is, a decode of what the walk
read.

The alternative, a capture-cadence derivation writer inserting into `graphql_type_directive` at
`max(ordinal) + 1`, was considered and rejected: it recreates the inversion at a different cadence
(a derived fact still sitting in stratum-one relations, distinguishable only by provenance), it
breaks the shipped pattern that a derivation writer owns its own relation and clears exactly its own
partition (which the oracle-lifecycle gates pin), and it drags the ordinal allocator and the
provenance table along as living machinery.

That choice dissolves the wrinkle the Backlog body flagged. With the synthesized application out of
stratum one there is no ordinal interleaving to preserve: `FactSchemaGateTest.applicationOrdinalsAreDense`
and `federationKeyProjectionsAgree` hold over authored rows alone and need no edit, and a future
composed reader that wants a total order over authored plus synthesized keys orders authored rows by
ordinal and appends the derived one in the composing query.

## Capture surgery

- `MacroCapture` loses `expandFederationKeys`, `federationLinked`, `isFederationLink`, `hasIdKey`,
  `idKeyDirective` and the `MACRO_FEDERATION_KEY` constant; `expand()` drops both parameters; the
  class handles `CONNECTION` alone and its javadoc rewrites to say so.
- `SdlFactCapture`: the `baseSites` field loses its last reader and dies; the `ElementOrdinals`
  javadoc drops its macro sentence; the package-private `captureTypeDirective` overload taking a
  caller-supplied ordinal folds back into the private loop, its only outside caller being the macro.
  The field javadoc above the two maps covers them jointly ("Both outlive the walk because macro
  expansion runs after it") and is rewritten rather than trimmed: with `baseSites` gone,
  `ordinalsByType` keeps its own unrelated reason, which is that a repeatable type directive split
  across a base and an extension has to number 0 and 1 instead of colliding at 0.
- `NodeDeclaration` leaves the capture API outright: the `nodes` parameter comes off
  `FactCapture.run`, `runWithDetections` and every `capture` overload, off `SdlFactCapture.capture`,
  and off `MacroCapture`'s constructor. Both production call sites in `GraphQLRewriteGenerator`
  construct the predicate from the same catalog they pass beside it, so the removal is free there;
  the roughly forty-five test call sites update mechanically. `CapturedStore` drops its
  `nodeDeclaration` plumbing and the "there is deliberately no inference-off catalog arm" javadoc
  paragraph, whose reasoning the gate below replaces. The class survives for its pipeline consumers
  (`SchemaReachability`, `ArrivalIndex`, `KeyNodeSynthesiser`, `CatalogBuilder`, `BuildContext`).
- `graphitron_type_directive_synthesis` retires: drop the table from the DDL and its `CONTAINMENT`
  registration from `FactCaptureAgreementTest`. This is a deliberate scope refinement against the
  Backlog body, which parked all three synthesis relations: this one's `CHECK` closes its macro
  vocabulary to exactly the macro that moves, so after the move it has no possible writer, and
  keeping it would pin a permanently empty relation through `everyRelationIsRegistered`. In the same
  sweep, `graphitron_type_declaration_synthesis`'s `CHECK` drops its `FEDERATION` value: nothing
  mints it today, nothing federation-shaped remains in `MacroCapture` after this item, and a closed
  vocabulary with an unmintable member is inventory. That relation's own table comment goes with the
  value, its extension-site case being "the Query fields federation adds from `@link`", which is
  exactly what the narrowed vocabulary no longer admits; the case a `CONNECTION` extension site
  actually has, a later carrier touching shared machinery, is already the sentence after it.
- The catalog pairing stays a construction fact. Today `FactCapture`'s javadoc obligates callers to
  build `nodes` from the same catalog they pass as `jooq`; removing the parameter removes the
  visible pairing, so nothing may reintroduce a second catalog-shaped input beside `jooq`. The
  production sites already hold one catalog, and `CapturedStore` derives everything from the one
  catalog argument its arms take, so a fixture cannot hand the walk and the store different
  catalogs.
- DDL comment sweep for the sentences the move falsifies: `graphql_type_directive.declaration_line`
  ("per its own provenance relation below"), `graphitron_federation_key.source_name` ("a synthesized
  key inherits the causing authored site"), the "Macro synthesis provenance" family header, and the
  `graphitron_federation_key` table comment's twin sentence, each rewritten to the post-move truth.
  The new views' comments owe the inherited-window warning: standing on `intent_bound_table` they
  inherit `intent_spelled_table`'s window function, so an outer predicate cannot prune them, per the
  convention that each view states that cost where the call site cannot see it.

## Readers

`ReachabilityRows.seed` is the only production reader of the synthesized rows. Its `graphitron_node`
arm and its over-approximating `@table`-plus-`implements Node` arm are replaced by one
`intent_node_type` arm, retiring the stopgap its javadoc names ("over-approximating node inference
until the jOOQ node-metadata constants are captured"; they now are). The `graphitron_federation_key`
arm stays and now reads authored keys alone, every synthesized-key carrier being a node type the new
arm already seeds. The tightening narrows `intent_type_domain` by exactly the types whose table
publishes no or malformed metadata, aligning the transcription with `SchemaReachability`'s seed scan,
which uses the same predicate; the domain shadow is the check on that alignment at implementation
time.

`graphitron-mcp`'s `SchemaQueries` and the LSP readers (`NodeTypeCompletions`, `Hovers`,
`DiagnosticFacts`) read `graphitron_node`, the `@node` decode this item does not touch. No SQL view
reads `graphitron_federation_key` or the provenance table.

## The gate that keeps it fixed

"Each crawler is responsible for a corpus that exists independently" is testable: run capture with
the other corpora absent and with them present, and the rows it writes about one corpus must be
identical. A new pipeline-tier test beside `FactCaptureAgreementTest` (working name
`CaptureCorpusIsolationTest`) captures one registry twice through `FactCapture.capture`, once with a
null catalog and once with the node-metadata-bearing fixture catalog, over an SDL that is
federation-linked and carries an inferred-node shape, then asserts every `graphql_` and `graphitron_`
relation identical across the two stores. The relation set is enumerated generically off the
generated model by family prefix rather than by list, so the next capture-time cross-corpus read
fails the gate without being named in it. The assertion fails today on the synthesized federation-key
rows, which makes it this item's regression test; after the move it holds nearly by construction,
since `SdlFactCapture` no longer receives any catalog-derived input at all.

The differential gate is not the only enforcer this item owes, because it creates a second live
spelling of nodehood beside `NodeDeclaration`, which stays for its four walk consumers. The
`intent_type_domain` shadow is not a binder for that pair: a transitive closure is not injective on
its seeds, so a nodehood disagreement on a type reachable through any field edge is invisible to it.
The derived arm therefore carries the fact model's full anchor set, named here rather than left to
the implementer: a seeded rule-edge test in `graphitron-model` beside `ColumnMatchClaimTest` and
`ClassMemberSlotTest` (rows in, verdicts out, per arm of both views), a crawler-side anchor
asserting the fixture catalog's real inputs reach `intent_inferred_node_type`, and a direct
membership shadow comparing `intent_node_type` against `NodeDeclaration.isNodeType` over a captured
schema's object types. The shadow retires when the walk consumers re-source onto the store, which
the out-of-scope list names as the follow-on. Beside these, the small pin holding the
federation-link prefix literal to `FederationSpec.SPEC_PREFIX`.

`fact-model.adoc` gains the rule in its stratum section with this gate as the named enforcer. It is a
third rule beside the two that section already carries, not a closure of either: the "Not
mechanically enforced" paragraph discloses the recompute test and the family assignment, and this
gate fires only on the cross-corpus subclass of a recompute violation. A capture-time derivation
whose inputs all sit inside one corpus, which is exactly the surviving `CONNECTION` half, still
passes every gate in the suite. That paragraph is therefore amended to name the subclass now covered
rather than to shorten its list, which is what keeps the page's disclosed-gap discipline honest. The
macro-inversion sentences there are amended to record that the federation half of the inversion is
corrected and the `CONNECTION` half remains, and the "moving the rows is a schema change" paragraph
gets its first instance.

## Test migration

- `MacroCaptureTest`'s six federation cases re-aim at the derived relation: membership for the
  synthesized case, absence for the authored-id-key stand-down (including the `resolvable: false`
  opt-out) and for the unlinked-graph case, and the two-arm catalog test
  (`nodeInferenceDecidesWhetherAnInferredNodeGetsAKey`) keeps its shape with the inferred arm now
  flowing through captured `sql_node_metadata` rows. Two pins retire with their mechanism: the
  ordinal-interleaving case (`anOtherFieldKeyLeavesSynthesisToNumberAfterIt`) becomes an assertion
  that an authored non-id key and a derived row coexist on one type, and the position-inheritance
  case dissolves because a derived row has no position of its own, the type's declaration site being
  one join away. One case survives the move whole and loses its stated reason:
  `repeatedApplicationsNumberAcrossSites` is authored-only (`@audit` on a base and an extension), so
  it keeps testing the cross-site counter, but its javadoc motivates that counter as "the ordinal a
  synthesized application numbers after ... has to survive the site boundary". The reason becomes
  the collision the authored pair itself would hit, which the javadoc's own second sentence already
  states.
- `FactCaptureAgreementTest.federationKeySynthesisAgreesWithTheRewrite` keeps its expectation side
  (the registry `KeyNodeSynthesiser` mutated) and compares it against `intent_federation_key`, one
  relation rather than a composition assembled inside the test. The provenance assertion
  retargets to the derived relation and its rationale rewrites, though not because the failure mode
  it guarded went away: capture reading the post-synthesis registry matters more after the move, not
  less, since a synthesized key transcribed as an authored one now lands wrong in stratum one
  directly. What changes is how the retargeted assertion catches it. A capture that read the
  rewritten registry would land Film's and Language's `id` keys in `graphitron_federation_key` as
  authored rows; `intent_synthesized_federation_key`'s no-authored-id-key condition would then
  decline on both, so the expected membership comes up empty while the first assertion still agrees.
  The rewritten rationale says exactly that: the anchor pins the derivation and the rewrite agreeing
  on membership, and the provenance half is what keeps that agreement from being reached by capture
  reading the wrong registry. The `source_line` assertion dissolves with the stratum-one row
  it read. `CapturedStore.ofPipeline` today hands the walk a catalog-bearing `NodeDeclaration` while
  capturing no catalog; once the store is the only channel, the helper passes the catalog through to
  capture, which is also the honest arrangement.
- Registrations: `intent_inferred_node_type`, `intent_node_type`, `intent_synthesized_federation_key`
  and `intent_federation_key` register `DERIVED`, their anchors the tests named above.
  `graphitron_federation_key` and its children stay `CONTAINMENT`, and the arm becomes honest: until
  now it held synthesized rows under a containment claim about authored SDL.
- Call sites that pass a real catalog beside a catalog-free `NodeDeclaration` today (the mcp
  `StoreFixture`, `WarmStartRefreshTest`, the two shadow tests) change observable state only through
  the derived views and the tightened domain; implementation verifies their assertions rather than
  assuming them.

## Sequencing

The sibling item recording the stated node metadata has shipped (its entry is in
`roadmap/changelog.md`): `sql_node_metadata`, `sql_node_key_column` and
`intent_node_metadata_defect` exist and are exactly the join targets the inferred arm needs, so
nothing blocks implementation. The naming item that gave this body its stratum vocabulary has also
shipped, which is why `depends-on:` is empty. This item is the pilot for a
general rule (capture states, derivation expands) and not the rule itself: the other synthesis
macro, `CONNECTION`, is nodehood-free and pure SDL, so it neither blocks this nor is fixed by it.

## Out of scope

- Reclassifying the `graphitron_` family at large. Those relations are decodes of the generic
  directive applications `graphql_` transcribes, so the family is a derivation over captured facts
  and not a second corpus; the 48 foreign keys from it into `graphql_` are a derivation's edges to
  its inputs. `intent_node_type` standing beside `graphitron_node` is the first instance of that
  reading rather than a special case; the rest of the family is its own item.
- The `CONNECTION` macro, and the general stratum correction across every family. Its two synthesis
  relations (`graphitron_type_declaration_synthesis`, `graphitron_field_synthesis`) and
  `graphitron_field_synthesis.authored_type_sdl` stay untouched; only the federation macro's
  provenance table retires here, for the reason given above.
- Resolved node identity (which `typeId`, which key columns, in which precedence order). This item
  derives membership; the identity axes stay where they are, in the classifier and in the relation
  comments that already call their fallbacks derivations. The inferred relation's witness columns
  are the join that item starts from.
- Extracting the well-formed-stated-metadata conjunction into its own relation on `sql_table`'s key,
  so `intent_inferred_node_type` and `intent_resolved_node_key_column`'s `JOOQ_METADATA` tier join
  one spelling instead of carrying two. Argued above: it retrofits a shipped tier and its seeded
  anchor for a rule this item does not change, and the two comments naming each other is what holds
  the pair until it lands.
- Re-sourcing `NodeDeclaration`'s four walk consumers (`SchemaReachability`, `ArrivalIndex`,
  `KeyNodeSynthesiser`, `CatalogBuilder`) onto `intent_node_type`. This item creates the second
  spelling of nodehood and binds the pair with the membership shadow above; retiring the Java
  spelling is the follow-on that closes it, and the shadow retires with it.
- Splitting the capture transaction per crawler.

## Retired vocabulary

- `expandFederationKeys`
- `MACRO_FEDERATION_KEY`
- `graphitron_type_directive_synthesis`
