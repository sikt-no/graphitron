---
id: R543
title: "Corpus asserts a coordinate's facts and commands, not one verdict triple"
status: Spec
bucket: testing
theme: testing
depends-on: []
created: 2026-07-26
last-updated: 2026-07-30
---

# Corpus asserts a coordinate's facts and commands, not one verdict triple

Spec, rewritten 2026-07-30 against the post-slice-7 reality (the original 2026-07-26 body rested on
`MethodCommand` and `RowsMethodBody`, both since retired, and on a slot-row census that has moved).
Consult-checked (principles-architect, 2026-07-30); this body encodes the census and the verdict.

## What the item is now

The corpus asserts, per coordinate, one three-axis classification verdict (`@classified`) and, since
R549 slice 7b, a synthesis declaration (`@synthesises`) checked against the connection-synthesis
relation on declared-equals-produced agreement. The emit side has no corpus voice at all: which
launcher row a coordinate commits is asserted only by test-code re-walks of the model
(`LauncherRelationClosureTest.isCoveredFamilyMember`, a twelve-arm leaf switch that must agree with
`LauncherCommands`' production membership switch, with nothing binding them). This item gives the
command half its corpus voice, in the `@synthesises` shape: a sibling directive, declared at the
coordinate, agreement-checked against the launcher relation's produced row, with coverage counted
only on agreement.

## The spine: three criteria, replacing the old identity/payload cut

What the corpus may assert is governed by three criteria, each with its own discharge condition,
not by a single "identity and wiring, never payload" rule (which, read literally, would admit the
join path it was meant to exclude):

1. **Grain.** A 1:N family (join hops, filters, pivot slots, operations) is corpus-declarable only
   once it is a relation with its own key, and then in the `@synthesises` shape: declared set
   against produced rows. Until then it has no assertable identity, only payload of something else.
2. **Legibility.** Even after the relation exists, the corpus asserts a coordinate's
   *distinguishing* facts, never its complete facts. A directive that spells out a whole method
   body's structure is not a spec anyone reads. This criterion permanently keeps the bulk of the
   truth table's slot rows (84 `joinPath()` assertions and kin) where they are.
3. **Reconstructibility.** SQL expression structure and column-level term algebra are never corpus
   content; javac and PostgreSQL own them (the existing `DimensionTuple` ground, kept).

Consequence, stated positively: the ~132 output-side slot-asserting truth-table rows in
`GraphitronSchemaBuilderTest` are **correctly tiered**, not quarantined. They stay the pipeline
tier's obligation. The one narrow future exception: a minimal-pair corpus example whose *lesson* is
a reference chain could declare hop identity once join hops are a relation; that waits on its grain
condition and is not this item's work.

## Deliverable: the launcher commitment directive

A sibling directive (working name `@commits`; siblings compose, one mega-directive fuses fact-base
and plan assertions at one key), `FIELD_DEFINITION`-targeted, keyed exactly as `LauncherRelation`
is keyed: by coordinate alone. It declares the arm tokens of the coordinate's launcher row, and
absence of the directive on a covered-family coordinate is a corpus error only where the enforcer
below says so.

- **Axes by measured independence, not by enumeration.** Before fixing the directive's arguments,
  run the landed axis-pair census instrument over the launcher relation's arms
  (`LaunchSource`/`Invocation`/`TenantStrategy`/`ResultShape`) across the corpus. Declare only axes
  that vary independently; axes locked by the `LauncherCommand` compact constructor's biconditionals
  (service source iff `LoaderDelegated`; reentry iff `ReturningKeyed`) are one fact, declared once.
- **Arm tokens, never emitted names.** No `(owner, method)` declaration: launcher method names are
  formula-derived, and the relation's case-folded census plus the closure test's row-to-emit leg
  already enforce them. A corpus copy of a derived name is a maintenance site with no certainty.
  (`@synthesises` declaring type names is not a counter-precedent: those names are author-facing
  schema surface; a launcher method name is emit-internal.)
- **Membership division, stated as the point.** The corpus declaration owns membership for the
  shapes it demonstrates: `LauncherRelationClosureTest`'s model-to-row direction re-sources onto
  the corpus (or deletes) for the demonstrated families, retiring the hand-maintained
  `isCoveredFamilyMember` restatement of the producer's switch. The row-to-emit leg and the
  coordinate-named env-method identity pin stay: they cross into the render, where the corpus
  cannot reach. Two statements of the membership invariant, not three.
- **Coverage obligation, registered.** A new `ExemptionRegistry` obligation row in the existing
  shape: domain = the sealed arms of the declared command axes, covered = arms reached by a
  declared-and-agreeing corpus row, exemptions = typed reasons. Registered in `obligations()` so
  the reflective discovery guard sees it; without this the command half is an unguarded census.
- **Run-grain scoping.** The plan is produced per run (`federationLink`, `usesOneOf`, session
  state, output package). The harness fixes one canonical run configuration and states it;
  run-grain facts (`carrierDsl`, the federation/oneOf gates) are explicitly out of the coordinate
  directive's reach. A fixture needing a different configuration is a pipeline-tier test, not a
  corpus example.
- **Doc-render hygiene.** The item adds a test-only directive, and `QueryViewRenderer`'s
  `INTERNAL_DIRECTIVES` strip roster is hand-maintained (it already misses `@synthesises`,
  latent only because the faceted example carries no doc query). Derive the strip set from the
  parsed prelude's directive definitions so a test directive cannot leak into the published
  triggers page by construction.

## In-scope corrections

- **Re-anchor the `Operation.Count`/`Operation.Facet` exemptions.** Their stated blocker (the
  connection launcher's `ConnectionResult` carrier fork) is discharged: `ResultShape.Connection`
  carries the helper, carrier and facet plan today. The live reason those arms are unreachable is
  that a synthesised connection type's `totalCount`/`facets` fields are not classified coordinates
  in the fact base at all. Rewrite both reasons to that ground and name the owner of the
  synthesised-fields-as-coordinates model question in them.

## Out of scope, with owners

- **Set-capable `operations` vocabulary.** Rejected as vocabulary landed ahead of semantics: the
  model's operation axis is single-valued (one arm per `OutputField`), a list argument whose
  cardinality is always one has no enforcer for the "always one" fact, and the plural form's real
  cost sits elsewhere (the coordinate-grain axis-pair census would re-grain to one row per
  operation, changing what a programme instrument measures, and `OPERATION_ARMS` would re-source).
  The plural directive lands together with the model's operation relation, the census re-grain and
  the obligation re-sourcing as one coherent edit, owned by whichever item makes operations a
  relation.
- **The condition sibling** (`(coordinate, resolvedTable)`-keyed, one coordinate declaring several
  rows: the first key where the declared-set-versus-produced-rows pattern earns its generality).
  Files as its own item once the launcher sibling has proven the shape.
- **A fetcher-edge sibling.** Not planned: its content would be a list of generated unit names,
  a derived-name restatement with no author-visible content.
- **R387's migration** is NOT gated on this item: its destination is the per-family pipeline-tier
  row assertions (two already landed and populated); this item later generalises those into
  author-declared form. R387 proceeds family by family today.
- **The coverage-obligation re-typing** (obligations keyed by `Class<?>` leaves) bites only when
  the leaf zoo dissolves, a model change this item does not make; the slice-6 obligation-keyed
  registry is the shape that absorbs it then.
- The rejection rows and input-side rows (unchanged from the original filing).

## Review notes (Spec -> Spec revise, 2026-07-30)

Independent review pass, consult-checked against principles-architect. Every named symbol
verified live (the closure test's switch, the command biconditionals, the axis-pair census, the
obligation registry, the strip roster's `@synthesises` gap, the 84 `joinPath()` rows, the
Count/Facet blocker staleness). The arm-tokens-never-names bullet, the run-grain scoping, the
strip-roster derivation and the exemption re-anchoring ground are all confirmed and need no
change. Three revisions block Ready; all sit in the membership-division bullet and Acceptance.

1. **Membership is leaf-grain; a `FIELD_DEFINITION` directive is coordinate-grain, so the corpus
   can only sample membership, never own it.** "The corpus declaration owns membership for the
   shapes it demonstrates" is not achievable in the quantified sense: produced-but-undeclared is
   invisible at coordinate grain (the `@synthesises` harness builds agreement cases only at
   coordinates carrying the directive), and the spec itself, correctly, declines to require a
   declaration on every covered-family coordinate (legibility). The sibling family already landed
   the right shape for this exact invariant, blessed in `development-principles.adoc`:
   `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` (a producer-side leaf-set declaration beside
   the dispatch) bound bidirectionally by
   `ProjectionMembershipTest.censusMatchesObservedMintingInBothDirections`. Recommended
   resolution: the launcher producer declares its minting leaf set as data, a membership census
   test binds declaration to observed minting in both directions, and `@commits` carries arm
   tokens only, making no membership claim. Alternative resolution: keep the corpus-only route
   but rewrite the bullet and Acceptance to claim only what a coordinate-grain sample delivers.
2. **"Exactly two statements of the membership invariant" is false before any retirement.**
   `LauncherCommands.produceWithoutSchema` is a third statement (an unguarded `instanceof`
   ladder over the same leaf set, silent fall-through, nothing binding it to `rowOf` /
   `childRowOf`), and `mintedMethodOf` is a fourth at name grain (that one at least argues the
   constructor backstop catches drift). Retiring the test-side restatement takes the count from
   four-plus-a-binding to four-minus-the-binding. Either fold `produceWithoutSchema` into the
   census of note 1, or narrow the Acceptance sentence to the schema-grain invariant and name
   `produceWithoutSchema` as a known extra statement with an owner.
3. **The "(or deletes)" branch loses the only enforcer of an existing arm's verdict.** Producer
   totality makes a new leaf a compile error (decidedness), but flipping an existing null arm to
   minting compiles, and the generator routes on row presence, so the row-to-emit leg passes
   too; today only the model-to-row equality fails, and the closure test's javadoc treats the
   batched-polymorphic-pair negative as load-bearing. The spec must require the successor
   negative in the same slice: the model-fact-then-`rowFor(...).isEmpty()` shape the root
   `@service` passthrough pin already has. That is a spec sentence, not implementer latitude.

Non-blocking note for the same bullet: after retirement nothing quantifies over coordinates in
the positive direction either (a family member silently ceasing to produce a row). The census of
note 1 answers it; if the corpus-only route is kept, name where that direction lands.

## Acceptance

The launcher sibling directive exists in the corpus prelude; the corpus's covered launcher families
carry declarations that agree with the produced relation; the membership invariant has exactly two
statements (producer switch, corpus declarations) with the closure test's model-to-row direction
re-sourced or deleted for demonstrated families; the new obligation row is registered and honoured;
the strip roster is derived; the two exemption reasons are re-anchored. Full reactor green; the
slice stays emit-neutral (test-tree and corpus only, zero main-source emission changes expected;
any main-source change is limited to what the directive's agreement check needs to read and must
not alter output).
