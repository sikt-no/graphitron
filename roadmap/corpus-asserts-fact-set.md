---
id: R543
title: "Corpus asserts a coordinate's facts and commands, not one verdict triple"
status: In Review
bucket: testing
theme: testing
depends-on: []
created: 2026-07-26
last-updated: 2026-07-31
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
is keyed: by coordinate alone. It declares the arm tokens of the coordinate's launcher row and
makes no membership claim; which arms must be exercised somewhere in the corpus is the coverage
obligation's business, never any single coordinate's.

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
- **Membership: producer-declared leaf census; the corpus makes no membership claim (review
  round 1, recommended resolution adopted).** Membership is leaf-grain and a `FIELD_DEFINITION`
  directive is coordinate-grain, so a corpus declaration can only sample membership, never own
  it: produced-but-undeclared is invisible at coordinate grain, and legibility rightly forbids
  requiring a declaration on every covered coordinate. The landed sibling shape carries the
  invariant instead: the launcher producer declares its minting leaf set as data beside its
  dispatch (the `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` shape), and a membership census
  test binds declaration to observed minting in both directions
  (`ProjectionMembershipTest.censusMatchesObservedMintingInBothDirections` is the model).
  `LauncherRelationClosureTest`'s model-to-row leg re-sources its covered set from the
  producer's declared leaf data, retiring `isCoveredFamilyMember`'s hand-maintained restatement;
  the census also owns the positive direction after that retirement (a family member silently
  ceasing to produce a row is a census mismatch, not an unquantified gap).
  `LauncherCommands.produceWithoutSchema`'s leaf ladder folds into the same census (bound to the
  declared set or re-derived from it), so it stops being an unbound restatement;
  `mintedMethodOf` is named as the remaining name-grain statement, held by the relation
  constructor's case-folded census. The row-to-emit leg and the coordinate-named env-method
  identity pin stay: they cross into the render, where neither producer data nor the corpus can
  reach.
- **Deliberate absences keep their negative pins (review round 1, adopted).** Producer totality
  makes a new leaf a compile error, but flipping an existing null arm to minting compiles and
  passes row-to-emit, so the model-to-row equality plus the explicit negatives are the only
  enforcers of an arm's decided absence. Every deliberate absence the closure test pins today
  (the batched polymorphic pair, the root `@service` passthrough) keeps a successor negative in
  the same slice, in the model-fact-then-`rowFor(...).isEmpty()` shape the passthrough pin
  already has. A spec requirement, not implementer latitude; there is no delete-without-successor
  branch.
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
- **The per-family pipeline-tier row assertions** (landed and populated for the launcher and
  condition families) are not gated on this item; this item later generalises them into
  author-declared form. The code-string-migration item this bullet used to name (R387) completed
  with R552 when its subject, `TypeConditionsGeneratorTest`, retired; nothing of it remains open.
- **The coverage-obligation re-typing** (obligations keyed by `Class<?>` leaves) bites only when
  the leaf zoo dissolves, a model change this item does not make; the slice-6 obligation-keyed
  registry is the shape that absorbs it then.
- The rejection rows and input-side rows (unchanged from the original filing).

## Review round 1 (2026-07-30): three blocking findings, all adopted

Independent review pass (full text in the file's history at the Spec-revise commit) verified
every named symbol live, confirmed the arm-tokens-never-names bullet, the run-grain scoping, the
strip-roster derivation and the exemption re-anchoring, and blocked Ready on three findings, all
in the membership-division bullet and Acceptance. Resolutions, folded into the body above:
(1) membership is leaf-grain and a coordinate-grain directive can only sample it, so the
recommended producer-declared leaf census (the `CONTRIBUTION_MINTING_LEAVES` shape, bound in both
directions) replaces the corpus-owns-membership claim and `@commits` makes no membership claim;
(2) the "exactly two statements" count was false (`LauncherCommands.produceWithoutSchema` was a
third, unbound statement; `mintedMethodOf` a fourth at name grain), resolved by folding
`produceWithoutSchema` into the census and naming `mintedMethodOf`'s constructor-backstop hold;
(3) the "(or deletes)" latitude would drop the only enforcer of a decided absence, replaced by
the required successor negative pins in the same slice. The reviewer's non-blocking note (the
positive direction after retirement) is answered by the census.

## Acceptance

The launcher sibling directive exists in the corpus prelude and carries arm tokens with no
membership claim; the corpus's covered launcher families carry declarations that agree with the
produced relation; the launcher producer declares its minting leaf set as data, bound to observed
minting in both directions by a membership census that also absorbs
`LauncherCommands.produceWithoutSchema`, with the closure test's model-to-row leg re-sourced onto
the declared data and every deliberate absence keeping a
model-fact-then-`rowFor(...).isEmpty()` negative pin; the new obligation row is registered and
honoured; the strip roster is derived; the two exemption reasons are re-anchored. Full reactor
green; the slice stays emit-neutral (test-tree, corpus and producer-side declaration data only;
any main-source change is limited to what the census and the directive's agreement check need to
read and must not alter output).
