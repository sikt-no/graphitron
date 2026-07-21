---
id: R507
title: "Documentation staleness prevention: prose-truth rules, retirement sweep, retired-vocabulary guard"
status: In Progress
bucket: architecture
priority: 4
theme: docs
depends-on: []
created: 2026-07-20
last-updated: 2026-07-21
---

# Documentation staleness prevention: prose-truth rules, retirement sweep, retired-vocabulary guard

The repo has repeatedly paid for stale, misleading documentation: prose that was
true when written and silently falsified as the code moved. The cleanup arc is
long (R483 javadoc-implementation drift audit, R484 string-literal purge, R126
and R504 retired-vocabulary scrubs, R301/R307 `@record` retirement, R179, R15,
R495, R497) and the periodic staleness audits under `roadmap/audits/` keep
finding the same drift classes. The structural gap: existing enforcement covers
symbol existence and structural membership (the javadoc `{@link}` reference
gate, `RoadmapReferenceGuardTest`, the `*DocCoverageTest` family, the
roadmap-tool `--verify` regen gates) but nothing guards prose truth, whether a
sentence's factual claim about the code it names is still correct. R497 is the
canonical instance: a hand-maintained caller census in javadoc passed the
`{@link}` gate while being factually wrong, so it read as enforced while
drifting. Every cleanup episode above closed a prose-truth drift; none of the
surviving gates covers it.

Drift comes in two shapes needing different treatment. Token drift: prose names
a retired identifier (mechanically detectable). Mechanism drift: prose
paraphrases a retired design with no surviving token, e.g. "deferrals point at
a roadmap item" after `Rejection.Deferred` lost its plan pointer (not
mechanically detectable; preventable only by not writing mechanics into prose).

Since filing, both vocabulary scrubs completed (R126 and R504 are Done; see
their `changelog.md` entries). Two consequences for this spec: the guard test
below has no blocking dependency and can seed its registry immediately with
the scrubbed vocabulary, and R504's outcome left two facts the guard design
had to reckon with: historical "former/collapsed/dissolved" narrative in
comments was deliberately kept (the seed bullet below decides its fate
site-by-site), and test-class/method names carrying retired vocabulary were
deliberately kept as scenario names (out of the guard's scope). The Backlog body's
"likely splits at Spec" expectation is resolved: single item, the text work
and the guard being independent seams within it.

## Design

One escalating pipeline (declare, sweep, promote), landing in three places.
Validated against the principles by principles-architect consults on
2026-07-20 (overall shape, and separately the guard design below).

1. **Rewrite the "Documentation names only live tests/code" corollary in
   `docs/architecture/explanation/development-principles.adoc`,
   affordance-first** (in-place edit; the doc is budget-capped by
   `DocSizeBudgetTest`). Every prose claim about code is (a) anchored on a
   checked reference (`{@link}`), (b) pinned by a named test or type, or
   (c) written at intent altitude (the why and the shape) so it survives
   mechanical refactors. The smell is narrowly line-by-line narration of a
   body or mechanism, not orientation javadoc: the class-level on-ramp docs
   R35 wants (scope, entry points, model output, doc pointer;
   `GraphitronSchemaBuilder`'s top comment as exemplar) are the blessed
   (c) case and must be named as such, or this rule becomes the stick that
   kills them.

2. **Prefer generated docs for anything enumerable** (one clause folded into
   "Principles are stated at altitude"). Arm lists, coverage tables, and
   inventories should be materialized views regenerated and `--verify`-gated
   on every build (the `supported-directives.adoc` /
   `inference-axis-coverage.adoc` precedents). This is the only move that
   removes the drift class instead of policing it; hand prose falls through
   to rule 1 only when it is genuinely rationale.

3. **Retirement sweep in `roadmap/workflow.adoc`.** Item-file convention: an
   item that retires a symbol or mechanism lists the retired vocabulary in
   its body (safe from the inventories-rot critique precisely because the
   file is deleted at Done, and load-bearing because the fresh-context Done
   reviewer otherwise has no grep query). In Review -> Done checklist line
   beside the existing user-facing-doc check: the reviewer greps all prose
   surfaces (javadoc, comments, `.adoc`, user manual, fixture prose, test
   names, roadmap bodies) for the declared vocabulary and skims for
   paraphrases of the retired mechanism. Plus one agent-facing trigger line
   in `CLAUDE.md` pointing at the workflow rule.

4. **`RetiredVocabularyGuardTest`, populated on demonstrated recurrence
   only.** A registry-driven guard generalizing `RoadmapReferenceGuardTest`,
   scoped to prose habitats nothing compiles (comment and javadoc prose,
   string literals, authored `.adoc`, fixture SDL; a `{@link}` tag itself
   is already covered by the reference gate, so only untagged prose
   mentions need this). A term enters the registry when an audit finds it
   surviving a cleanup, not at every rename (the `Record*`/`Split*` family
   re-drifted three windows running and earns an entry; a one-off rename
   does not). The roster gets a reverse-enforcer: the test also asserts each
   registered term resolves to no live symbol, so a stale entry or a
   re-introduced name fails the build. The registry lives in the test, never
   in the principles prose.

5. **Honest residue.** Mechanism-paraphrase drift has no forcing function;
   the answer is rule 1 (do not narrate mechanics; prefer deletion over
   rewrite, per R504's mandate), swept by rule 3. R207 already proposes the
   "design doc says X, no test pins X" conformance audit; this item should
   cross-reference it, not duplicate it.

## Implementation status

All four landing places shipped in one commit; sections below kept as the
Done reviewer's map of what was agreed. Decisions taken at implementation,
within the spec's stated latitude:

- The region lexer was extracted to a shared `JavaSourceRegions` class (the
  scanner's internal state machine tracked code regions but exposed no code
  view; the reverse-enforcer needed one). `JavaSourceRegionsTest` pins the
  new code projection; `RoadmapReferenceScannerTest` continues to pin the
  comment and string projections through the scanner.
- The module walk scope (in-scope module list + repo-root anchor) was
  extracted to `GuardScope`, shared by both guards, so a new module cannot
  silently join one guard's scope and not the other's.
- Seeding outcome: every one of the 14 surviving lineage mentions (5 main-
  source, 9 test-source, all comment/javadoc; zero in strings, `.adoc`, or
  `.graphqls`) was deleted or rewritten to present-tense prose per the
  deletion-first mandate. **The allowlist ships empty**; the guard's
  allowlist-hygiene test pins entry shape for future additions.
- The guard's own javadoc describes the registry without naming any retired
  token (its comment regions are in its own scan scope); tokens live only in
  string literals, which are out of test-source scan scope by design.

## Implementation (as specified)

### `docs/architecture/explanation/development-principles.adoc` (rules 1-2)

Word-neutral in-place rewrites; the doc sits at 3,466 words against
`DocSizeBudgetTest`'s 3,500 cap, so additions must displace within the touched
corollaries.

- Rewrite the "Documentation names only live tests/code" corollary
  affordance-first with the three-way rule (anchored / pinned / intent
  altitude), bless orientation javadoc as the (c) case with
  `GraphitronSchemaBuilder`'s top comment as exemplar, and narrow the smell
  to line-by-line body narration and mechanism paraphrase. Extend its
  *Enforced by:* line with `RetiredVocabularyGuardTest`.
- Strengthen "Principles are stated at altitude"'s existing "guarded tests or
  generated reports" clause to name the regenerate-and-`--verify` pattern
  (`supported-directives.adoc` precedent) as the first choice for any
  enumerable doc surface, hand prose being the fallback for rationale only.

### `roadmap/workflow.adoc` (rule 3)

- New bullet under "Item file conventions": an item that retires a symbol or
  mechanism lists the retired vocabulary in its body (a `## Retired
  vocabulary` section). State the rationale in the bullet: the list is
  ephemeral by Done-deletion so it cannot rot, and it is the fresh-context
  Done reviewer's grep query, which the reviewer rule otherwise denies them.
- New "Retirement sweep" check paragraph beside the existing
  "User-facing-doc check": before approving In Review -> Done on an item that
  declares retired vocabulary, the reviewer greps all prose surfaces
  (javadoc, comments, `.adoc`, fixture prose and SDL descriptions, test
  names, roadmap bodies) for the declared terms and skims the item's touched
  areas for paraphrases of the retired mechanism. Items retiring nothing
  skip the check.

### `CLAUDE.md`

One agent-facing trigger line in the "Javadoc conventions" section, after the
guard paragraph: when a change retires a symbol or mechanism, declare the
vocabulary in the item and run the retirement sweep per
`roadmap/workflow.adoc`.

### `RetiredVocabularyGuardTest` (rule 4)

Generalize the `RoadmapReferenceScanner` region lexer (it already separates
comment/javadoc regions from string/char/text-block literals) so both guards
share it; `RoadmapReferenceScannerTest` continues to pin the lexer contract.
New `RetiredVocabularyGuardTest` beside `RoadmapReferenceGuardTest` in the
`graphitron` unit tier, mirroring its module walk and scanned-file floors.

- **Registry:** a static list in the test (the pinned-inventory pattern),
  each entry an exact identifier token plus its live successor for the
  failure message. Matching is whole-identifier-token over the Java
  identifier character class (letters, digits, `_`, `$`), so live compounds
  containing a retired substring (`SplitRowsMethodEmitter`, scenario-named
  test classes) never match. Terms enter on demonstrated recurrence; tokens
  too generic to be unambiguous are omitted.
- **Habitats, one tolerance rule everywhere:** zero occurrences except
  where a structured `(file, term)` provenance allowlist entry covers the
  site, exactly mirroring `RoadmapReferenceGuardTest`'s `ALLOWED_SLUGS`
  shape. The allowlist replaces an earlier historicity-marker heuristic
  draft ("term is fine if the comment says 'formerly'"), rejected at
  consult: whether a mention is lineage or a live claim is a semantic fact
  a lexer cannot decide (one "formerly" in a large javadoc region would
  exempt every token in it), and a per-habitat tolerance split (comments
  exempt, `.adoc` not) scatters one concept. With the allowlist, each kept
  lineage mention is a reviewed entry, uniformly in any habitat. Scanned:
  Java comment/javadoc regions (main and test) and main-source string
  literals via the shared region lexer; authored `docs/` `.adoc` and
  fixture `.graphqls` files via a separate raw-text token pass (the Java
  lexer only covers `.java`; the sharing claim is scoped to that). Out of
  scope: test identifiers (scenario names are deliberate per R504, and
  token matching cannot hit compounds anyway) and `roadmap/` (items are
  transient; the changelog and staleness audits must be able to name
  retired terms, and `changelog.md` is the single permanent home for
  retirement lineage).
- **Reverse-enforcer:** each registered token must not appear as an
  identifier token in any main-source *code* region (the lexer already
  isolates code from comments and literals). This is uniform across entry
  kinds, deliberately: a declaration check alone would no-op on
  member-shaped entries like `planSlug` (no type declaration to find),
  while the code-region rule fails on a reintroduced type, field, or
  local alike. A stale registry entry or a revived name fails the build;
  legitimately reviving a name means dropping its registry entry in the
  same commit, which is correct friction (token reuse makes old prose
  ambiguous by construction).
- **Seed:** the R504 `ChildField` leaf family (`SingleRecordTableField`,
  `RecordTableField`, `SplitTableField`, `RecordLookupTableField`,
  `SplitLookupTableField`, `RecordTableMethodField`, `LifterLeafKeyed`) and
  the token-unambiguous R126/R484 vocabulary (`AccessorKeyedSingle`,
  `AccessorKeyedMany`, `LifterPathKeyed`, `MappedRowKeyed`,
  `MappedRecordKeyed`, `MappedTableRecordKeyed`, `planSlug`). Omit
  `Cardinality` (too generic; its successor `Arity` is prose-distinguishable
  only semantically). The recurrence bar is already met for this set (the
  `Record*`/`Split*` family re-drifted three consecutive audit windows).
  Seeding is coupled to the fate of R504's kept residue: the first green
  run requires visiting every kept lineage mention and either deleting it
  (preferred, per R504's deletion-first mandate, with `changelog.md` as the
  surviving home) or recording it as a reviewed allowlist entry. The guard
  thereby ships with its allowlist enumerating exactly the lineage the
  project affirmatively decided to keep.

## Tests

- `RetiredVocabularyGuardTest` is itself the deliverable meta-test; scanner
  contract additions land in `RoadmapReferenceScannerTest` (or a sibling if
  the lexer extraction warrants its own contract test).
- Existing gates stay green: `DocSizeBudgetTest`,
  `JavadocReferenceGateTest`, `RoadmapReferenceGuardTest`,
  `check-adoc-tables`.
- No production-model or generated-output change; no pipeline or execution
  tier involvement.

## Out of scope

- R207's design-doc conformance audit (cross-referenced, not duplicated).
- Roadmap item body currency (owned by the periodic staleness audits under
  `roadmap/audits/`).
- Mechanism-paraphrase drift beyond the prose discipline of rule 1; it has
  no forcing function and the spec does not pretend otherwise.
- Retroactive prose rewrites beyond what the seeded guard flags (the scrubs
  already ran; the guard's first green run is the proof they held).

## Acceptance

- Full reactor green under `-Plocal-db`, including the seeded guard.
- `development-principles.adoc` at or under the word budget.
- The workflow bullet, the sweep check, and the CLAUDE.md trigger line all
  landed and cross-referencing each other.

## Roadmap entries

- On Done: delete this file, one-line `changelog.md` entry with the landing
  commit.
