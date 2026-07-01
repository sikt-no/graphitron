# Release planning — first release (2026-06-30)

A working document for deciding what ships in graphitron-rewrite's **first release**
and what defers. Optimised for the stated release goals, in priority order:

1. **Good first impression** — polish and ease of use over feature count.
2. **As few stubbed features as possible** — every code path we *recognise* should
   either work or fail loudly at build time, never compile green and break in
   production.

This is an analysis artifact (Markdown, in `audits/`, so the roadmap-tool ignores
it), not a roadmap item. It is meant to be edited in place as we iterate. Where it
recommends generator changes, those still need roadmap items before any code moves.

Companion reading: [`2026-06-29-roadmap-staleness-audit.md`](2026-06-29-roadmap-staleness-audit.md)
(per-item freshness), [`changelog.md`](../changelog.md) (193 shipped items).

---

## TL;DR recommendation

The generator core is **already release-grade**: 193 items shipped, the worked
example is a gold standard, error messages are best-in-class (Levenshtein "did you
mean" hints, typed rejections), and the LSP/dev loop is mature. The first impression
will be made or broken not by missing features but by **two things**:

- **A small set of code paths that compile green and break at runtime** (the
  "stubbed feature" concern). There are ~5 of these. They are the release's real
  risk and the top of the MUST list below.
- **A few cheap polish gaps on the on-ramp** (no rewrite README, Maven config split
  across two docs, no published "what graphitron does" list).

**Almost none of the 30 active-pipeline items are release blockers.** The big ones
(R222 dimensional pivot, R333 data model, R7 decompose) are *internal refactors*,
invisible to consumers; shipping mid-refactor is fine. The ambitious net-new
features (R399 JAX-RS lib, R398 SDL lint, R212 IntelliJ plugin, R45 multi-tenant,
R13 faceted search) are **scope we should defer** to keep the first release small
and polished, unless one is a hard customer requirement.

So the recommended release is: **close the runtime-stub safety gaps + cheap on-ramp
polish + repo coherence (retire legacy, squash) + the "capability catalog" so people
can see what it does.** Everything else defers and ships continuously afterward.

---

## Dimension 1 — Stubbed / incomplete code paths (the "no stubs" goal)

Good news first: the **variant-level stub surface is essentially closed**.
`TypeFetcherGenerator.STUBBED_VARIANTS` (the single source of truth) holds exactly
**one** entry — `CompositeColumnReferenceField` (R24) — and the validator
(`validateVariantIsImplemented`) rejects it at build time. A `GeneratorCoverageTest`
invariant proves every field leaf is in exactly one of
{implemented, not-dispatched, projected, stubbed}. That is the right design.

The risk is **not** the tracked stub. It is the paths that slip past the validator
and fail at runtime. Ranked by danger:

### A. Compiles green → breaks at runtime (RELEASE-CRITICAL to gate or fix)

| # | Path | Trigger | Runtime failure | Roadmap | Disposition |
|---|---|---|---|---|---|
| 1 | Nullable to-one record accessor | nullable FK accessor on record-backed parent, DB row has NULL | `NullPointerException` | **R269** (Spec) | **Fix for release** — guard `GeneratorUtils.buildAccessorKeySingle/Many` (sibling path already guards) |
| 2 | Wire-coercion cast | `@service` input bean member whose graphql-java coercion ≠ declared Java type (`(SakRecord) raw.get(...)`) | `ClassCastException` on first request | **R261** (Backlog, blocked by R256/R222) | **Gate for release** — add a classify-time type-check even if the full fix waits on R256/R222 |
| 3 | `@nodeId` on non-ID coordinate | `@nodeId` on a non-`ID` field/arg (directive permits it; decode gated on `"ID"`) | raw base64 bound → SQL bind/type error or silent never-match | **R262** (Backlog, Validation) | **Gate for release** — `GraphitronSchemaValidator` rejection (zero `@nodeId` checks today) |
| 4 | Multi-hop / `ConditionJoin` `@tableMethod` child | child `@tableMethod` / `RecordTableMethodField` whose resolved path is empty, multi-hop, or first-hop `ConditionJoin` | `UnsupportedOperationException` on first request | (see `stub-interface-union-fetchers.md`; partially gated) | **Gate for release** — pinch the classifier to reject these shapes at build time |
| 5 | Dialect guards (Oracle UPSERT, non-PG listed-`@table` UPDATE) | UPSERT on Oracle / listed UPDATE on non-Postgres | `UnsupportedOperationException` from generated `postDslGuard` at runtime | **R63** (Spec, UPSERT dialect on model) | **Decide** — see open question on multi-dialect. If single-dialect deploys, this is acceptable; if not, lift to classify time |

### B. Fails at the consumer's compile (annoying, not silent — lower severity)

| Path | Trigger | Failure | Roadmap |
|---|---|---|---|
| Nested backing class `Outer$Nested` | record-backed parent whose backing is a nested class | generated cast doesn't **compile** (`ClassName.bestGuess` mis-splits `$`) — caught by consumer's build | **R370** (Backlog) |

These do not reach production silently (the generated code won't compile), so they
are a worse-error-message problem, not a trust problem. Fix when convenient.

### C. By-design deferrals — document, don't block

| Path | Behaviour | Roadmap |
|---|---|---|
| `TableInterfaceField` / `TableMethodField` child fetchers | one SQL query **per parent row** (N+1) | **R288** (Backlog) |
| `@service` list-payload | N+1 on list-returning service carriers | **R308** (Spec) |

N+1 is a real performance footgun for early adopters. Not a v1 blocker, but it
**must be documented** in a "known limitations" page so nobody is surprised.

### D. Visible directive surface that reads as half-finished

**Decided (2026-06-30):** the advertised directive surface is trimmed under **R400** (Spec;
surface-only, no behaviour change). R400 also **absorbed and superseded a parallel upstream
proposal to *remove* `@tableMethod` outright** — the decision is to withhold + rethink, not delete:

* **Withheld from the v1 surface** (not in use): `@tableMethod`, `@sourceRow` (implemented but
  unused) and `@experimental_constructType` (declared, no emitter, R69). Dropped from the generated
  "supported" list (Stage 1, landed); R400 Stage 2 removes their reference pages, the dedicated
  `@sourceRow` recipe, index entries, and teaching `xref`s. Recovery is ticketed: **R403** (rethink
  + reintroduce `@tableMethod`, deferred), **R404** (reintroduce `@sourceRow`, deferred), **R69**
  (`@experimental_constructType`, gated on implementing it).
* **Moved to "Removed / rejected"**: `@notGenerated` and `@multitableReference` are rejected on
  use (hard build error), so the generated list now tells migrating consumers to delete them
  instead of calling them supported.
* **`@record` kept as-is**: deprecated + silently ignored (not dropped, not reclassified), per
  user decision.

Remaining decisions on the directive set:

| Directive | State | Action |
|---|---|---|
| `@enum` | honored/live today; **R360** proposes future retirement (infer backing from producers) | Keep advertised for v1; R360 is post-release |
| `@table` on `INPUT_OBJECT` | works, but slated for removal (R97); **R332** adds the deprecation signal | **Ship R332 in release** so the deprecation window opens at v1, not after |
| How-to recipes for `@tableMethod` / `@sourceRow` | R400 Stage 2 strips them | Resolved: Stage 2 removes the teaching content (was previously left open); restore via R403 / R404 |

---

## Dimension 2 — Polish & ease of use (first impression)

The on-ramp is strong. Full audit detail is below; the gaps worth closing for v1 are
all cheap:

| Area | State | Gap to close for v1 | Cost |
|---|---|---|---|
| Worked example (`graphitron-sakila-example`) | **Excellent** (9/10) | none (note: its HTTP layer diverges from real consumers — R399 territory, defer) | — |
| Error messages | **Excellent** (9/10) | none | — |
| LSP / dev loop | **Polished** (8/10) | native IntelliJ plugin missing (R212) — document as known limitation, defer the plugin | low |
| Getting-started docs | **Good** (7/10) | **add a `graphitron-rewrite/README.md`** with consumer-vs-contributor entry points (today the first doc reads "contributor-facing" and looks internal) | low |
| Maven plugin UX | **Usable** (7/10) | **embed a complete copy-paste POM snippet** in quick-start (config is currently split across quick-start + mojo-configuration.adoc) | low |
| "What does it do?" | **Missing** | **ship R115 capability catalog** — there is no published list of graphitron's capabilities anywhere; it's "thinking, not engineering" (a dir of stub files) and high first-impression payoff | low |

---

## Dimension 3 — Feature scope: IN vs DEFER

Active-pipeline items (Spec / Ready / In Progress), bucketed by release disposition.

### MUST — in the release (polish, safety, coherence)

| Item | Status | Why it's in |
|---|---|---|
| **R269** nullable to-one NPE | Spec | runtime NPE (Dim-1 #1) |
| **R115** capability catalog | Spec | the "what does it do" gap; cheap |
| **R332** deprecate `@table` on input | Spec | open the deprecation window at v1 |
| **R400** withhold not-in-use directives | Spec | trim the advertised directive surface (`@tableMethod`, `@sourceRow`, `@experimental_constructType`, `@notGenerated`, `@multitableReference`) for a clean v1; absorbed the parallel remove-`@tableMethod` proposal |
| **R346** supported-directives regen guard | Ready | doc integrity; fixes the `@experimental_constructType` overstatement path |
| **R19** rebase/squash branch onto main | Ready | clean public history (numbers stale — regenerate at execution time) |
| **R26** retire `graphitron-maven-plugin` + `schema-transform` | In Progress | a single coherent generator in the repo, not legacy + rewrite side by side |
| **R182** retire legacy reactor + unnest to repo root | Backlog (blocked by R26) | finish the unnest so the repo root *is* the generator |
| **New** runtime-stub gates: R261-guard, R262, multi-hop `@tableMethod` reject | (need items) | Dim-1 #2/#3/#4 |
| **New** docs polish: rewrite README + Maven POM snippet | (need item) | Dim-2 |

### SHOULD — include if time allows (correctness / DX polish)

| Item | Status | Why |
|---|---|---|
| **R99** LSP misses sibling modules from sub-module dev goal | Ready | dev-loop bug, hurts the LSP first impression |
| **R109** how-to recipe + Sakila fixture for grouped collections | Spec | fills a docs gap on a real pattern |
| **R396** `@reference` FK-connection qualified-table-name validation | Ready | correctness/diagnostics |
| **R384** multitable interface filters (converted/@nodeId/@condition) | Spec | interface-union correctness |
| **R242** DML payload positional alignment | Spec | mutation correctness |
| **R273** nodeId metadata + mismatch semantics | Spec | largely shipped via R378; finish the framing |

### DEFER — post-release (ambitious net-new scope)

Each is real and wanted, but adds surface area that works against a small, polished
first release. Ship continuously after v1.

- **R399** JAX-RS GraphQL-over-HTTP library — net-new module
- **R398** SDL lint engine — net-new subsystem
- **R212** native IntelliJ plugin — net-new tooling (document the LSP bridge instead)
- **R45 / R46** multi-tenant routing & fan-out — net-new runtime surface
- **R13** faceted search on `@asConnection` — net-new feature
- **R389 / R393** discriminated joined-table inheritance — net-new polymorphic shape *(but see open question — it's driven by a real Sikt schema)*
- **R92 / R98** DB CHECK constraint → Jakarta validation — net-new validation pipe
- **R63** UPSERT dialect requirement on the model — couples to Dim-1 #5
- **R381** LSP-guided `@reference` path authoring — LSP enhancement

### DEFER — internal refactors (invisible to consumers; land anytime)

These do **not** gate the release. They improve the generator's internals; a consumer
cannot tell which side of them v1 shipped on. Continue normally.

- **R222** dimensional model pivot · **R333** the Graphitron data model ·
  **R335** fold input/scalar/enum classification · **R256** service-walker substrate ·
  **R308** service list-payload N+1 (also Dim-1 C) · **R180** ResultType column-read helper ·
  **R347** LSP structural consolidation · **R7** decompose `TypeFetcherGenerator` ·
  **R112** operation-driven test corpus

### Roadmap hygiene (do alongside, not release-gating)

Per the 2026-06-29 staleness audit: **delete R30** (stranded Done tombstone),
**discard R146 and R52** (superseded), and **re-spec the 14 §B stale items** before
they next transition. None blocks the release; all keep the board honest.

---

## Open questions for the team (decide these to finalise scope)

1. **Single-dialect or multi-dialect target for v1?** This decides whether the
   dialect guards (Dim-1 #5: Oracle UPSERT, non-PG listed UPDATE) are an acceptable
   runtime guard or must be lifted to a build-time rejection. Postgres-only would let
   us close that gap by simply documenting it.
2. **Public (Maven Central) or internal-Sikt-only first release?** Raises or lowers
   the urgency of the README / on-ramp polish and the "known limitations" page.
3. **Is joined-table inheritance (R389, Ready) a hard v1 requirement?** It's the one
   deferred feature driven by a concrete Sikt schema (`party`/`subjekt`). If a
   launch customer needs it, it moves to MUST.
4. **Retire or keep `@record` / `@enum` / `@experimental_constructType` for v1?**
   Freezing the public directive set at release is cleaner than retiring directives
   in a later breaking change.

---

## Appendix — first-impression audit detail

**Polished / low-risk:** worked example (gold standard; explicit "what to copy",
runnable app + match & approval test patterns), error messages (typed `Rejection`
sealed model, Levenshtein candidate hints, `diagnostics-glossary.adoc` enumerates
every closed-set code with cause + fix), LSP/dev loop (completions, hover,
goto-definition, inlay hints, fix-its; 300 ms debounce; idempotent writes; Quarkus
live-reload caveat documented; macOS WatchService caveat documented).

**Rough edges (all cheap):** (1) no `graphitron-rewrite/README.md` — the first doc a
GitHub visitor hits reads "contributor-facing" and looks internal; (2) Maven config
split across quick-start + `mojo-configuration.adoc`, no single copy-paste POM; (3)
IntelliJ uses a manual LSP4IJ + netcat bridge (works, documented) vs native plugin.

**Stub-taxonomy authority:** `TypeFetcherGenerator.STUBBED_VARIANTS` +
`GraphitronSchemaValidator.validateVariantIsImplemented`, invariant-checked by
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`.
