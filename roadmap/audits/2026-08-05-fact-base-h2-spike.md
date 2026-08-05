# Fact-base materialization spike: H2 + jOOQ over the claim relations

A working document, not a roadmap item. It lives in `audits/` so the roadmap-tool (which scans
`roadmap/*.md` non-recursively and wants `id:` front-matter) ignores it. It plans and records a
spike commissioned during R589's design rounds (`roadmap/validation-adds-facts.md`); the spike's
verdict feeds that item's materialization question, and the spike code itself is disposable and is
not committed anywhere.

## The question

R589 models classification as claim relations, validation as key constraints and detection
queries over them, and planning as a join into command records. The model is relational either
way; what is undecided is the *materialization*: relations as Java (coordinate-keyed record
components on `GraphitronSchema`, joins as streams, the existing eight components are already
this shape) versus an actual embedded relational store holding the fact base for a build.

Candidate stack for the store: **H2 in-memory** (pure JVM, no native libraries, embeds in the
Maven plugin and the LSP without platform concerns; not currently a reactor dependency) queried
through **jOOQ 3.20.11** (already pinned in the root pom), with the fact schema written as DDL
and run through jOOQ codegen so the generator queries its own fact base through the same typed
stack it generates for consumers.

## Why the store is attractive

- **The constraint split becomes mechanical.** The design distinguishes generator invariants
  from author errors. In a store the base-relation key `(coordinate, classifier)` is a literal
  `PRIMARY KEY`: a duplicate insert is a generator bug and throwing is correct. The author-error
  rules (authored conflict, structural ambiguity, unclassifiable) are detection queries
  (`GROUP BY coordinate HAVING COUNT(*) > 1`, the demand anti-join) whose result sets mint
  diagnostic rows. Prose distinction today; engine behaviour under the store.
- **Dogfooding.** The fact schema is a reviewable DDL artifact, jOOQ codegen gives typed queries,
  and the team's deepest expertise applies to the generator's own internals.
- **Derivation is the model.** Authored claims are the join of applied-directive facts with axis
  declarations; inferred claims join field facts with catalog facts; reachability and
  input-occurrence paths are `WITH RECURSIVE`. No classifier code holds hidden state, so purity
  stops being a discipline and becomes a property.
- **A SQL surface for agents.** The MCP module could expose read-only SQL over the fact base,
  replacing a fixed tool vocabulary with arbitrary questions; the guard-drift census becomes a
  query.

## The three empirical questions

The spike exists because three frictions are empirical, not architectural. Each gets a concrete
exercise and a recorded answer.

1. **Rich-value encoding.** Decoded slot facts include join paths (an ordered list of FK steps),
   sealed provenance variants, and path-keyed occurrence rows. Exercise: model each in DDL
   without escape hatches (a serialized blob standing in for queryable structure fails the
   test), and write the consuming queries. Answer: the DDL patterns that worked, and where the
   flattening pressure helped versus hurt.
2. **Deterministic ordering.** Generator output must be byte-stable, and SQL result order is
   unspecified without `ORDER BY`. Exercise: run capture plus full derivation twice and
   byte-compare ordered outputs; deliberately drop one `ORDER BY` and observe whether anything
   catches it. Answer: the ordering discipline's real cost, and whether a seam (for example a
   wrapper that refuses to iterate an unordered result into emission) can turn the discipline
   into a mechanism.
3. **Latency.** The dev loop reruns on save and H2 has no incremental view maintenance, so every
   rerun is capture plus derive from scratch. Exercise: measure end-to-end (create schema, load
   facts, run all derivations and detections) at two scales, roughly sakila-sized
   (~100 types / ~1,000 fields) and a 10x stress shape, warm JVM, median of repeated runs.
   Answer: the numbers, against a dev-loop budget of tens of milliseconds warm.

## Method

Out-of-tree prototype (session scratchpad; a throwaway Maven project). Pinned versions where the
reactor pins them (jOOQ 3.20.11); H2 at its current stable. Synthetic fact generation rather than
real SDL parsing: capture's infallibility is a design result (existence and application facts
involve no interpretation), so the spike tests the store, not the visitor.

Representative fact-schema slice:

- Base relations: `graphql_type`, `graphql_field` (coordinate as `(type_name, field_name)`,
  source location), `applied_directive` (raw decoded arguments), `directive_axis` (the
  per-directive axis declaration), `input_field_edge` and `argument_use` (the input-occurrence
  substrate), `catalog_table`, `catalog_column`, `catalog_fk` plus a normalised `fk_step`
  child table (the join-path encoding exercise).
- Derived relations, as `INSERT .. SELECT` or views: `authored_claim` (applied directives joined
  with axis declarations; `PRIMARY KEY (coordinate, classifier)`), `inferred_claim` (field facts
  joined with catalog columns), the reduced claim view (authored unioned with inferred at
  authored-free coordinates), `reachable` (`WITH RECURSIVE` from root seeds over field-return and
  input edges), `demand` (reachable intersected with requiring rules), input occurrence paths
  with the override cascade (`WITH RECURSIVE` over `argument_use` and `input_field_edge`,
  path-valued key).
- Detections, as queries minting diagnostic rows: authored conflict, recognized combinations,
  structural ambiguity, unclassifiable (demand anti-join), and one assembly-stratum specimen
  (a command join with a deliberately unfillable slot).
- One jOOQ-codegen pass over the DDL and at least one derivation rewritten against the generated
  classes, to prove the typed loop end to end.

## Exit criteria

The findings section below is filled with the three answers and the numbers, plus a verdict
paragraph recommending one of: adopt the store for the R589 relations, keep relations-as-Java,
or a hybrid (store behind the `GraphitronSchema` component seam so the model stays
engine-agnostic). The verdict is input to R589's open question on slot-fact home and
materialization; the decision itself is made in that item's design dialogue, not here.

## Findings

Run 2026-08-05, H2 2.3.232 + jOOQ 3.20.11, codegen via `DDLDatabase` over the fact DDL, Java 25,
fresh in-memory database per iteration, warm-JVM medians. The prototype implemented the full
slice: both capture loads, claims as `INSERT .. SELECT` joins, provenance variants, reachability
and demand, occurrence paths, all detections across the three strata, and the DML command join.
Every synthetic defect population was recovered at exactly its constructed count at both scales.

**1. Rich-value encoding: passed, no escape hatches.** The join path landed as an ordered child
table (`fk_step`, keyed `(fk_name, position)`) and reconstructs with
`LISTAGG(...) WITHIN GROUP (ORDER BY position)`; no blob. Sealed provenance variants landed as
one table per variant (`inferred_trigger_column`, `inferred_trigger_nesting`) with a foreign key
to the claim row; no kind-dependent nullability anywhere, so the flattening pressure enforced the
decoded-slot-facts discipline rather than fighting it. The occurrence path worked as a
`VARCHAR` canonical encoding minted by the recursive CTE and used directly as the primary key;
the key held including the shared-input-type case (one definition reached under two use-site
paths yields two rows, distinct keys). One coordination footgun surfaced and cost minutes, not
hours: the codegen's identifier case must agree with the runtime URL's case folding (the
`defaultNameCase=as_is` property did not take effect against `DATABASE_TO_LOWER=TRUE`; H2's
default upper folding on both sides resolved it).

**2. Deterministic ordering: the discipline is real and its violation is silent.** The fully
`ORDER BY`-ed canonical dump was byte-stable across every iteration at both scales. The
deliberately unordered dump was *also* byte-stable across every iteration at both scales: in
single-threaded embedded H2, dropping an `ORDER BY` produces no observable drift until some
unrelated change reorders a scan. Nothing catches the violation by testing; the discipline has
to be a mechanism, most plausibly a query seam that refuses to iterate an unordered result set
into emission. This is the strongest argument the spike found for hiding the store behind a
typed facade rather than handing `DSLContext` around.

**3. Latency: comfortable for build and on-save, not keystroke-grade.** Total medians,
capture through planning: **~60 ms** at 100 types / ~1,300 fields / ~1,200 claims, **~578 ms**
at 1,000 types / ~13,000 fields (linear; per-phase at scale 1: DDL 3.6, SDL capture 8.9,
catalog capture 4.1, claim derivation 9.0, reachability + demand 19.6, occurrences 3.1,
detection 7.2, planning 0.7). The heaviest phase is the reachability recursion plus the demand
join, with obvious untouched headroom (no secondary indexes, naive path multiplicity in the
recursive CTE). Verdict against the budgets: well inside a build, fine for an on-save dev loop
at realistic schema sizes, and an editor keystroke loop would want the SDL-only stratum alone
(capture plus authored-claim derivation plus SDL detections is under 20 ms at scale 1) or
incremental work the store does not provide.

**Incidental confirmations.** The purity key is mechanical: a duplicate
`(coordinate, classifier)` insert into the authored relation throws, while the author-error
rules detect through queries, exactly the constraint split the design predicted. The typed
jOOQ loop works end to end: the conflict detection (grouping, `LISTAGG`, having) ran against
codegen classes generated from the fact DDL. And the demand anti-join caught a modeling gap in
the spike's own synthetic data (block-end nesting fields returning a scalar acquired no claim
and surfaced as unclassifiable), which is the zero-claims rule doing in the spike precisely
the job R589 wants it to do in production.

**Verdict.** Adopt-leaning: the three empirical costs came in low (encoding clean, ordering
real but mechanizable, latency comfortable for the loops that matter). Recommended shape is
the hybrid: the store lives behind the `GraphitronSchema` component seam with a typed facade
owning the `ORDER BY` mechanism, so the model stays engine-agnostic and relations-as-Java
remains a drop-in fallback. The decision itself goes back to R589's materialization question.
