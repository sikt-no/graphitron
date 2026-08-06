# Fact-base impact sweep: which roadmap items the adopted architecture touches

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it.
It records a whole-board sweep run the day R595 (`graphitron-model-captures-facts`) reached
Ready: which active items are subsumed, reshaped, or consumed by the fact-base architecture
that R595 and R589 (`validation-adds-facts`) define, and what update each needs. It is the
architecture-drift complement to the same-day staleness audit
(`2026-08-06-roadmap-staleness-audit.md`), which tracks symbol drift; the two overlap on R333
and the overlap is marked there and here.

The architecture, in one paragraph, as the impact predicate: R595 creates a `graphitron-model`
reactor module holding a fact-schema DDL over an H2 in-memory store (families: `graphql_`
existence, `applied_` fidelity, `intent_` decoded semantics, `catalog_` jOOQ, `extension_`
classpath), jOOQ codegen over the DDL, and two infallible capture loads running beside the
existing pipeline (shadow window, agreement tests, no behavior change). R589 then makes
classification a derivation: authored claims are a view over the `intent_` relations keyed
`(coordinate, classifier)`, inferred claims are per-classifier derivation views carrying join
witnesses, validation is detection queries minting located violation facts (tombstoning
retired), demand and reachability and exemptions are explicit derived relations, planning
joins claims plus slot facts into command records, and `GraphitronSchema` is strangled
consumer by consumer, with new facts landing only in the store during the two-model window.

## R589 itself: re-slice

The body is current; the six scope slices predate the architecture the design rounds arrived
at, which the item states. The re-slice narrows the item to the classification-stage
migration piece over the R595 substrate: claim views, the demand relation with censused
exemption rows, the conflict detection plus the recognized-combinations rule, the
occurrence-path derivation, the Conflicted projection arm with its edges, and the MCP/hover
first client. Slice 2's interim (the monolithic classifier minting claim rows) is mooted by
the item's own closed-questions section.

## R333: single-pass refresh, role survives

The materialization decision is reversed, not drifted. R333's section "We are data modeling:
the relational discipline, not a database engine" (~:1394-1435, echoed at :352-355,
:1997-2002, :2040-2045) resolves against exactly the store R595 adopted, and each of its
three anti-engine arguments has a dated counter: jOOQ codegen over the DDL answers
stringly-typed (exhaustiveness returns to javac via module ordering), the store was chosen
for constraint mechanics and dogfooding rather than scale (latency measured by the
`2026-08-05-fact-base-h2-spike.md` spike), and "an engine freezes a still-discovered model"
inverted into the main benefit (changing the model is editing the DDL and following the
compiler). These regions need re-arguing, not annotating.

Re-baseline regions beyond the reversal (line anchors at the 2026-08-06 revision):

| Region | What changes |
|---|---|
| :293-355 three consumers | "One base, many consumers" survives; the re-sourcing mechanism is now the strangler frame (consumer-by-consumer migration gated on output identity), and the consumer list gains the agreement-test reader and the read-only SQL surface. |
| :51-184 model + fact catalog | Not contradicted (this is what the DDL reifies) but needs the reification statement, the family correspondence, and the claim base relations added (R589 slice 1). |
| :418-456 derived reads | Vehicle is SQL views and INSERT..SELECT strata with recursive CTEs; reachability and demand are new derived relations; the located-violation relation is extended by the three diagnostic strata. |
| :457-477 snapshot lifecycle | Restate onto the store lifecycle (created at startup, dies with the process, no migrations) plus R597's warm-start cache. |
| :479-496 location joined-not-stored | The rule splits by namespace: SDL positions are captured columns and key parts; Java and Javadoc positions stay out for the cadence reason. |
| :974-1027 input coordinates | Re-key onto R589's derived, value-keyed occurrence path; also fixes the internal contradiction with :232-243 (the dotted path is "a derived traversal"). |
| :373-392, :1004-1021 | The authored/inferred precursor pattern; say it generalizes into the claim relations. |
| :186-251 natural keys | Strengthened by the DDL; reconcile the single-table-inheritance argument at :213-224 with R595's one field table keyed `(type_name, field_name)`. |
| :1806-1852 relationships, :2075-2085 exit note | Add R595/R589; the Done condition gains "the model migrates into the DDL" as a live candidate. |

The role survives: R333 keeps the *why* (key discipline, fact deep-dives, the emit-side back
half at :498-1804, the directive-coverage audit) while the *what* migrates to the DDL relation
by relation, and the refresh should state that division so two homes for one model cannot
silently disagree; where they do, the DDL wins. Symbol-drift overlap: :341, :302-307,
:1512-1514, :529, :1605-1628, per the staleness audit's two R333 rows; fix in the same pass.

## R222: re-spec and demote, not re-baseline

Two of three load-bearing claims are inverted by the architecture: producers do not read
graphql-java primitives (capture decodes once; no AST node crosses into the relations, so the
`Walker<S, C>` vocabulary has no home), and validity does not ride the wrapper
(`WalkerResult.Ok/Err` is replaced by violation rows; planning never re-checks). The
slots-over-cross-product thesis won one layer below where it was looking: axes are per-directive
relations and the claim view's arm list. One direct vocabulary clash: R222 insists absence is a
`No<Family>` arm, never `Optional`; the store's convention is a NULL column or an absent row,
with effective-value defaulting a derivation.

Already discharged before this round: the operation axis (R563, delivered as the
`OperationMember` multiset rather than the proposed sealed `Operation`), the input-side
`TableInputType` root (R519), `SourceKey` (R431), the leaf merges (R290/R305/R432/R508), and
`@value` (deleted). The leaf census at :62 (51 leaves) is ~40 at this HEAD.

Unique residue, and its disposition:

1. **Unified diagnostic stream** (:99-103, :407, :503): the `ValidationReport.walkerDiagnostics`
   slot, the errors/warnings/walker collapse, `Diagnostic.tags`, the lsp4j projection boundary.
   No other owner; R595 leaves diagnostics out by design and R589 only consumes the channel.
   Cut as its own item.
2. **Stage 5/6 legacy-permit deletion inventory** (:438-452): ownership already routed to the
   umbrella; migrate the rows into R333's "What dissolves" with live/retired status, restating
   the Stage 6 end state as deletion, not the rename to `GraphitronField`.
3. **Directive-location narrowing conflict** (:454-456): R222 asks `@table`/`@record` to narrow
   out of `INPUT_OBJECT`; R519/R566 deliberately kept the location under the retired-location
   convention and R595's `intent_table` codifies that state. A one-decision item, filed against
   the convention rather than R222's framing. `@record on INPUT_OBJECT` is the genuinely open
   half.
4. **`MethodBackedField` retirement trigger** (:501): can never fire as written, because the
   `ConditionCall`/`ExternalFieldCall` siblings will never be built (capture supplies their
   content as relations). Restate wherever the retirement lands.

Everything else retires to lineage: the absorption ledger (:458-471) and the rejected design
attempts (:517-529, whose `Invalid<Family>` and Optional-slot rejections are arguments R589
re-runs) are worth keeping in an audits/ lineage record when the item file is discarded.

## Board sweep: affected items by tier

Statuses as of this sweep. "Subsumed" means the architecture delivers the ask; "reshaped"
means the mechanism should be re-specified against the store; "consumer" means the item
becomes a store reader or migration piece.

### Ready / In Progress (refresh before pickup)

| Item | Status | Impact | Update |
|---|---|---|---|
| R580 infer-node-from-implements-node-and-metadata | Ready | Reshaped, heavily | The rule is an inferred claim with the jOOQ node metadata as witness; the reachability seed (directive-presence read) becomes a row in the derived reachability relation; `KeyNodeSynthesiser`'s pre-classification registry pass is capture-adjacent. |
| R427 relevance-ranked-search | Ready | Reshaped, light | Supply-side facts are `catalog_` regions and a decoded directive is an `intent_` relation; add a placement note since new facts land only in the store during the two-model window. |
| R555 deprecate-externalfield-fold-into-service | Ready | Reshaped, light | Merging two directives is now also a DDL edit (one `intent_` relation absorbing another) and moves the claim view's arm list; add a ride-along note. |
| R347 lsp-structural-consolidation | In Progress | Reshaped, slice 5 only | The atomically-swapped five-field holder is the store handle (one stamped snapshot); slice 4's result builder should not hard-code the hover header R589 slice 5 changes. |

### Spec (re-point)

| Item | Impact | Update |
|---|---|---|
| R473 explicit-nodeid-grammar | Reshaped | Express the shadowing rules as claim-view arms plus a shadowing detection; the masked structural reading survives as hover data. |
| R569 mcp-aggregated-diagnostics | Consumer | Forward note only: pivot dimensions become violation-relation columns; the schema channels' aggregation becomes GROUP BY post-migration. (Applied 2026-08-06, with one correction: the compile channel is javac output over generated Java, which the store leaves out on cadence grounds, so the three-channel union seam and the wire contract outlive the migration; only the grouping engine retires.) |
| R585 input-field-resolution-typed-rejections | Reshaped / land-first | Confirm the land-before-R589 sequencing; do not harden the `Resolved` wrapper, which is being strangled. |
| R92 catalog-check-constraint-validation | Reshaped, light | The check scan is a `catalog_` relation filled by capture load two; expression recognition is a derivation over it. |
| R112 operation-driven-test-corpus | Reshaped | Re-source the classification/classifier-call dimensions off the store; `ClassificationTrace` JSONL stops being their source. |
| R381 lsp-reference-path-authoring | Reshaped | The two hop implementations collapse onto captured catalog facts plus the derived path relation; the drift test narrows. |

### Backlog, subsumed or largely so

| Item | Update |
|---|---|
| R221 validator-walks-plain-input-unbound-fields | Named in R589 slice 4; closes as subsumed when that slice lands. |
| R565 unclassified-input-arg-cascade-diagnostic | The cascade exists only because failure is replacement; restate as "the arm reads the claim view, the second error cannot mint", keeping only the suppress-or-pair residual. |
| R123 parent-context-aware-schema-coordinates | Fork A's parent-context policy is a join over `applied_`/`intent_` composite keys; close as store-delivered. |
| R34 nodeid-migration-quickfix | The quick-fix text is selectable from the inferred-claim row; re-anchor off the three `BuildWarning` shim sites. |
| R213 input-field-rejection-attribution | Locations ride the raw facts; definition-keyed violations locate at the input field, use-keyed at the occurrence path; check what remains after R589 slice 4. |
| R319 warn-on-pruned-unreachable-types | Capture is total, reachability is derived; the pruned set is an anti-join and the warning can carry the reason. |

### Backlog, reshaped (batch re-anchor)

| Item | Update |
|---|---|
| R533 localcontext-guard-predicate-single-source | The single source is a derivation view both consumers read; the hand-maintained allow-list dissolves rather than moves. |
| R562 synthesised-connection-fields-as-coordinates | Macro expansion happens inside capture with synthesis provenance, so synthesized coordinates are existence facts; the question narrows to rows for the two field coordinates and where the slot facts live. |
| R586 exemption-taxonomy-arm-census | Re-anchor on the demand/exemption relations; arm population becomes "does a row exist". |
| R531 classify-time-registry-read-guard | The read-free invariant is structural for migrated consumers; scope the meta-test to the shrinking un-migrated set with a retirement gate. |
| R544 rejection-spec-by-example | Replace the direction-of-travel paragraph (WalkerResult.Err) with R589's; the assertion target is the violation relation, which also answers the non-coordinate-scoped worry. |
| R577 validation-error-coordinate-sealed | Specify the sealed lift as the Java view over the store's coordinate key columns; sequence so the lift happens once. |
| R209 field-registry-typed-rejection-trace | Fork (b) is a dead end under the store; the item collapses to record widening or retires with the registry. |
| R66 rejection-string-carrier-widening | Split phases into carriers that survive versus carriers whose consumers migrate to the store. |
| R345 lsp-squiggle-schema-parse-failure | Capture turns assembly failures into located detections; narrow the item to genuine syntax errors (the pre-registry half). |
| R523 tenant-fold-rejection-source-locations | Detections over located facts inherit locations; either do the trivial threading now with a moot note, or park behind the tenant relations' migration. |
| R10 drop-assembled-schema-rebuild | The awaited concrete signal arrived: capture walks the registry and owns macro expansion; re-evaluate around which un-migrated consumers still need the assembled schema. |
| R360 retire-enum-directive | Producer-derived backing is an inferred claim with the producer as witness; re-point the model reference at the enum relations. |

### Needs an explicit decision

| Item | Question |
|---|---|
| R117 knowledge-base-programme (+R104/R112) | The DuckDB knowledge base now describes a second relational store over the same facts, which the strangler frame forbids; it becomes a projection or export of the model store, and the sweep's recommendation is to record that relationship explicitly. |

### Already reconciled, no change

R597 (warm-start-model-store) and R599 (remove-stray-directive-declarations) are born on the
architecture; both were verified consistent with R595's family split and lifecycle clauses.

### Checked and judged unaffected (negative on record)

R7, R27, R98/R170/R192 (Jakarta bean validation is a different "validator"), R152, R181,
R193/R219/R220/R72 (ServiceCatalog predicate consolidation), R208/R297, R236, R278, R282,
R411, R430, R466, R467, R528, R532, R536, R537, R557, R558, R572, R578, R588, R590/R348,
R592, R593, R594, R598, and the docs/CI tail (R547, R573, R582, R587, R596).
