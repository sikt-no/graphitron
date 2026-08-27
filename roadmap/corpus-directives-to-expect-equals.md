---
id: R851
title: "Retire @classified, @classifiedType and @commits: the corpus asserts relations, not leaves"
status: In Progress
bucket: testing
priority: 3
theme: classification-model
depends-on: [planners-read-facts-emitters-read-commands]
created: 2026-08-27
last-updated: 2026-08-27
---

# Retire @classified, @classifiedType and @commits: the corpus asserts relations, not leaves

The spec-by-example corpus (`graphitron/src/test/resources/corpus/`, 57 documents plus the
prelude) carries two assertion mechanisms for one job. `@expectEquals` states what a fact-store
relation holds for a document as a CSV block and is checked by one generic reader
(`CorpusExpectations`): set equality by anti-join in both directions, relation and column names
resolved against the store's own catalog, value vocabularies validated against the columns' CHECK
constraints, well-formedness floors on the block itself. Beside it, three coordinate-level
directives assert in-memory products of the classification walk through bespoke Java in
`ClassifiedHarness`: `@classified` (111 applications) declares an output field's dimensional
tuple and operation-member multiset against the field model's adapter, `@classifiedType` (46)
declares the `GraphitronType` sealed leaf a type classifies to, and `@commits` (69) declares the
arm tokens of the coordinate's launcher command row against `LauncherCommands.produce`. Each of
the three drags a prelude enum that mirrors a sealed arm set, held in sync by `ClassifiedDslTest`
mirror floors, and every row authored in them is written in a vocabulary R682
(`planners-read-facts-emitters-read-commands`) deletes: the R840 Done entry already defers the
per-axis `@classified` retirement to R682's relations. This item owns finishing that move: the
three directives, their enums, and their harness halves leave the tree, and the facts they
asserted are stated as `@expectEquals` blocks over store relations instead.

## Why one mechanism, and why this one

The trio's declared-versus-produced comparison is not wrong; it is a second implementation of what
`@expectEquals` already does, with a worse cost model. Every new assertable fact under the trio
needs a directive, an SDL enum, a resolution loop, a comparison method and a mirror floor; under
`@expectEquals` it needs a relation, which the fact model wants to exist anyway (R682's law: a
verdict a leaf carries and no relation states is a missing relation, and the relation is the
deliverable). The generic reader also gives assertions the trio structurally lacks: a misspelled
value fails against the CHECK vocabulary, a misspelled column against the catalog, and an
unlisted row fails because equality is two-sided, where `@classified` asserts only the
coordinates an author remembered to annotate. The documentation path is already indifferent:
`CorpusFragmentRenderer` renders fragments from `@expectEquals` blocks and does not read the trio,
so no rendered page changes when they go.

## Per-directive migration

The unit of migration is one fact, not one directive: each axis converts across all documents
when a store relation states it, and a directive is deleted when its last axis has converted.

- **`@classified`.** The tuple axes (source wrapper, target wrapper, source shape, target shape)
  and the operation-member multiset each need a relation at the right grain. Some facts are
  assertable today (the corpus already asserts `intent_resolved_field_claim`,
  `intent_bound_table`, `intent_authored_field_claim`); the walk-side folds that own the rest
  (`DeliveryFactRelation`, `OperationMemberRelation`) are exactly the folds R682 re-sources into
  the store. As each relation lands, the corresponding axis becomes ordinary blocks and the axis
  argument leaves the directive. One grain rule, stated as the rule rather than a workaround: a
  block that asserts a relation at the relation's own key cannot produce duplicate rows, so if a
  member block needs extra columns to keep duplicate arm tokens distinct (the `operations:` list
  is a multiset, and `CorpusExpectations` compares sets over the projected columns), that need is
  the signal the block is projecting below the relation's grain. Each migrating commit derives
  the block's columns from the relation's key instead of remembering a caveat.
- **`@classifiedType`.** The verdict it declares is a sealed leaf's simple name, and the leaf zoo
  is what R682 deletes, so the replacement is deliberately not a store enum restating the leaf
  taxonomy: a `type_verdict` relation would be named for the walk's question, at the walk's
  grain, with the code being replaced as its only oracle. The default replacement is the relation
  a consumer actually forks on: where R682 lands a closed detection-verdict relation because a
  planner reads one, the document asserts that relation's rows. Asserting the deciding facts
  individually (type backing, nodehood, synthesis, interface and union membership) is the
  fallback for types where no consumer composes them into a verdict; if the corpus reader would
  have to compose five facts into a verdict in their head, and a planner will evaluate the same
  predicate, the composite belongs in the model and the document should be asserting it there.
- **`@commits`.** This is the one axis whose retirement is a design decision rather than a
  scheduling one, and the item says so rather than hiding it in a symmetric fork. The launcher
  command relation is derived by a planner and R682 keeps commands as in-memory plan-tier records
  ("each command relation's declared arm set" is the surviving vocabulary its completeness gates
  re-key onto, not a store relation), so no relation for `@expectEquals` to name is ever
  scheduled to arrive. Two shapes work: (a) the corpus run lands each document's produced command
  rows in the store before the expectation pass; (b) the document asserts the store facts the
  launcher verdict derives from, and the command relation's own arm-census pins carry the
  derivation. Lean: (a), because it keeps the assertion at declared-equals-produced strength on
  the command row itself, per document, and keeps the launcher-production failure roster
  (`launcherProductionFailureRosterIsExact`) expressible; (b) weakens the corpus's claim from
  "this command row exists" to "the inputs to the verdict exist", which output identity does not
  backstop per-document. Choosing (a) carries two obligations the fork would otherwise smuggle
  past the store's gates. First, the rows are a function of the schema, not of captured facts, so
  they are scaffolding in the fact model's sense, the `walk_` shape: if they live as a declared
  relation they owe the full scaffolding charter (a `meta_family` roster row whose header states
  the writer, the cadence, the single reader and the clock it drains on, which here is this
  item's own trio deletion plus R682's command-vocabulary settlement); if that charter is not
  worth paying, the sanctioned per-reader shape is a `LOCAL TEMPORARY` table and the rows are not
  a declared relation at all. Second, `@expectEquals`'s worth rests on rows reaching the store
  only through capture (a document cannot state a shape capture never produces), and
  `expectationsRangeOverTheAssertablePopulationOnly` enforces exactly that. Planner-produced rows
  must stay a distinguishable population, an explicitly named apparatus bucket that floor admits
  as its own third case, never merged into the captured-fact population; a reader of any block
  must be able to tell "this asserts a captured fact" from "this asserts a planner's output".

## What retires

- In the prelude: the three directive declarations and the enums that exist only to type them
  (`SourceWrapper`, `Member`, `TargetWrapper`, `SourceShape`, `TargetShape`, `TypeVerdict`,
  `LauncherSource`, `LauncherResult`). `Mint`, `SynthesisedType` and `@synthesises` stay (see
  scope).
- In the harness: `ClassifiedHarness`'s resolution and comparison halves for the trio
  (`FieldCase`, `TypeCase`, `CommitDeclaration`, `CommitCase`, the enum-argument decoders and
  their unknown-value throws), `ClassifiedDslTest`'s per-document tuple assertion and the mirror
  floors for the retired enums. The mirror obligation does not vanish, and its successor is
  named per vocabulary rather than waved at: for a vocabulary the model owns, a closed CHECK plus
  a membership-binding test on the `rejection_validation_error.lsp_code` pattern; for a
  vocabulary a surviving Java seal owns (the command arms), the mirror stays a Java meta-test
  against the seal and simply outlives the SDL enum it used to type, because the store's own
  convention rejects a CHECK that hand-copies a compiler-enforced taxonomy.
  `CorpusExpectations.membershipViolations` validates blocks against CHECK vocabularies, which is
  a different claim and is not the successor.
- The axis-census tests in `ClassifiedDslTest` (`everyDimensionValueIsExercised`,
  `axisPairCensusIsDerivable`, `memberGrainCensusIsDerivable`, `launcherAxisCensusIsDerivable`)
  recast as queries over the store relations that replace the directives, keeping their
  producer's-arms grain, or retire where the replacing relation's own census pins already state
  the same floor. Which of the two holds is a per-test decision made in the migrating commit, not
  a blanket deletion.

## Scope and sequencing

- **Depends on R682's relations, axis by axis.** This item does not build fact relations for the
  walk's verdicts on its own schedule; that is R682's deliverable and its law. The work here is
  the corpus-side conversion as each relation lands, plus the `@commits` materialization decision,
  plus the deletions. Axes whose relations exist already can convert immediately; the item ends
  when the last directive is deleted, which cannot precede the relations it waits on.
- **Coverage re-keying splits by who owns the vocabulary.** For gates keyed on vocabularies R682
  deletes, re-keying is R682's: `VariantCoverageTest`'s output-and-type obligation reads
  `CorpusDocuments.coveredLeaves()`, which resolves through the trio to sealed leaves, and the
  member-arms obligation is keyed on the walk-model `OperationMember` seal. This item coordinates
  with that work (the trio cannot be deleted while `coveredLeaves()` still reads it) and must not
  run ahead of it. The exception is this item's own deliverable: the `LAUNCHER_COMMITMENT`
  obligation's domain is `LaunchSource` plus `ResultShape`, command vocabulary that survives
  R682, and its covered set is today produced by `corpusCommittedLauncherArms()` reading
  `@commits`. Deleting `@commits` deletes that gate's only witness channel, and nothing in R682
  owes a successor to a gate over a vocabulary it keeps. The successor lands here: the
  covered-arm set derives from the replacing `@expectEquals` blocks, whose two-sided set equality
  per document is what keeps a declared row trustworthy as a witness.
- **`@synthesises` is out of scope.** It has 2 applications, asserts minted type names for
  connection synthesis, and plausibly follows the same path (the store already declares
  `graphitron_type_declaration_synthesis` and `graphitron_field_synthesis`), but the user-visible
  ask is the trio; fold `@synthesises` in only if a migrating commit makes it free, otherwise
  file it separately when the trio is done.
- Documents convert wholesale per axis, never per document: a corpus where the same fact is
  asserted two ways in two documents is harder to read than either endpoint.

## Done means

No corpus document and no prelude line mentions `@classified`, `@classifiedType` or `@commits`;
the harness halves and mirror floors named above are deleted or recast; every fact the trio
asserted is either stated by an `@expectEquals` block over a store relation across the same
documents, or its drop is a typed `Exemption` row in `ExemptionRegistry`'s equality-asserted gap
sets, where the build reads it and a later contributor can retire it (commit prose alone is not
a record); the `LAUNCHER_COMMITMENT` coverage gate reads its covered set from blocks instead of
`@commits`; the corpus's planted-regression floors still fail on a divergence in the migrated
facts.
