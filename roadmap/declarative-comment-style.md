---
id: R838
title: "Agents write archeological, deliberation-narrating javadoc; the conventions and guards only cover roadmap citations"
status: Ready
bucket: dx
priority: 3
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# Agents write archeological, deliberation-narrating javadoc; the conventions and guards only cover roadmap citations

Agent-authored javadoc in this tree keeps coming out dense and rambling, and the density has a recognizable shape: the author narrates its own working session. Having just weighed alternatives and debugged pitfalls, the agent writes the deliberation and the war story into the comment, addressing the reviewer of the change ("here is why my choice is sound") instead of the next reader of the file. Call this *archeological* style: prose that is only true relative to a previous version, a rejected draft, or the session that produced the code. `InlineMultiplicityCheck`'s class javadoc is a live specimen ("Two parsing details are load-bearing and both were learned the hard way", and the sentences defending "Reports rather than gates" against an imagined objection; the report-versus-gate claim itself is enforcer declaration and stays, only its defense is the smell), and "rather than / instead of" rationale clauses, the tell of documenting alternatives that are not in the code, appear 40+ times in roadmap-tool main sources alone. The wanted style is *declarative*: state what the code guarantees and the constraint that forces its shape, as if the code had been born this way. Document why; do not document why-not.

Three mechanisms keep the archeological style alive despite existing guidance:

1. **The rule is three words.** "Prefer terse over verbose" sits mid-paragraph in a CLAUDE.md section that is otherwise entirely about roadmap-citation hygiene. Agents read that section as "don't cite R&lt;n&gt;" and move on.
2. **Mimicry backfires.** Agents match surrounding comment density, and the surrounding density is already bad; every dense class teaches the next agent. The existing corpus is a stronger style signal than any rule.
3. **The principles doc half-blesses it.** `development-principles.adoc` endorses intent-altitude prose ("the why and the shape") and names only narrow smells (line-by-line narration, hand-maintained caller censuses). Deliberation-narration passes that smell test, so agents write four paragraphs of "why" and feel principled. The doc distinguishes what-narration from why, but not why from deliberation.

Everything in this repo that is mechanically enforced (roadmap citations, adoc tables, module enumeration) got fixed by being enforced; comment style has no gate, so it drifts.

## Plan

Layer the fix the same way the roadmap-citation fix was layered: a written convention, a principles hook the reviewer gates can cite, and a narrow build-time guard whose job is to make the rule fire where agents actually see it.

### 1. CLAUDE.md: a comment-style section of its own

Split comment *style* out of the citation-focused "Javadoc conventions" section into its own named section, so it cannot be skimmed away as citation hygiene. The rules, stated testably rather than as adjectives:

- **The day-one test.** Write every comment as if the code had been born this way, and let content outrank surface form. A sentence whose only content is the authoring session ("learned the hard way", "historically", "this change") gets cut. A real constraint phrased archeologically ("an earlier version of this case asserted over two empty partitions and survived an unscoped DELETE") gets restated in present tense ("the non-emptiness assertions are load-bearing; a case asserting over an empty partition passes vacuously"): the constraint is live, only the phrasing is history.
- **Why, not why-not.** State the constraint that forces the code's shape; do not narrate rejected implementations. "X rather than Y, because Z" collapses to "X, because Z" when Y is merely a draft the author considered. The line is drawn by subject, not by grammatical form: why-not about the code's own boundary is a fact about the code and stays. That covers disclosed coverage gaps (a guard's javadoc owning what it does not catch), the meaning of a silence, a measured counter-result (the fact model's priced rejected rewrites), and the genuine trap where the obvious alternative is wrong in a way that will tempt a future editor to regress it.
- **Paragraph admission, not a length budget.** Class javadoc opens with one sentence of what. Every paragraph after that must state a constraint a reader could violate (a boundary, a floor's purpose, a habitat exclusion, a trap); a paragraph that defends the choice against an objection gets cut. Stated as content rather than length, the rule needs no exception list: `GraphitronSchemaBuilder`'s two-paragraph on-ramp passes (each paragraph carries a boundary or call-order claim), `InlineMultiplicityCheck`'s "learned the hard way" paragraph fails. Rationale bigger than constraint paragraphs belongs in `docs/architecture/`, linked.
- **Break the mimicry loop explicitly.** The dense javadoc already in the tree is precedent for nothing; do not match it. Without this line the admission rule loses to the match-surrounding-style instinct every time. Pair it with the em-dash stance: the existing corpus is not a cleanup backlog, rewrite on touch only.

### 2. development-principles.adoc: name the smells

Extend "Documentation names only live tests/code" so archeology and rejected-implementation narration join line-by-line narration and caller censuses as named smells, scoped explicitly to the comment/javadoc habitat, and draw the line the principle currently misses: *why* is the constraint that makes the code correct; *deliberation* is the process that found the constraint. Document the first, discard the second. The smell must be stated by subject so it cannot swallow the two why-not forms the repo's own method requires: disclosed coverage gaps and measured counter-results (`fact-model.adoc` prices its rejected rewrites in seconds precisely so the next agent does not re-attempt them). The doc is size-budgeted by `DocSizeBudgetTest` and sits 14 words under the budget (3,486 against 3,500), so the addition *will* displace existing words: hold it to clauses on the existing smell sentence, and choose the displaced words deliberately at implementation time, which is the policy the document's own Constraints entry already states. The smell's *Enforced by* line names a pair: the phrase guard below for the named subclass, review for the rest. The Spec → Ready and In Review → Done reviewer gates already cite this principle, so this gives them something concrete to point at.

### 3. A narrow mechanical guard

A sibling of `RoadmapReferenceGuardTest` (same comment-region lexing as `RoadmapReferenceScanner`, whose `scanSource(Path, String)` shape is already unit-testable over in-memory strings) that fails the build on unambiguous archeological tells in comment and javadoc regions across main and test sources. Seed phrase list: "learned the hard way", "historically", "in an earlier version", "in a previous version". Admission criterion: a phrase qualifies only if every plausible comment use narrates repository history rather than program behavior. That keeps "previously", "no longer", "used to", and "rather than" off the list; each has legitimate present-tense or dataflow readings ("a module the pom no longer declares", "used to resolve the trace glob"), and "rather than" is sometimes legitimate why. This is deliberately a lower admission bar than `RetiredVocabularyGuardTest`'s demonstrated-recurrence rule: the seeds enter on the convention's launch evidence; later additions follow the recurrence bar. The guard's failure message teaches the day-one rule, mirroring how the citation guard's message teaches its rewrite rule.

Three honesty requirements, each following an in-tree guard precedent:

- **Disclosed residue.** The guard's javadoc states its scope precisely: it catches the named phrase subclass and nothing else; declarative style at large stays review-enforced (the `CaptureCorpusIsolationTest` / `CollectionValuedColumnGateTest` pattern). Without the disclosure, a green build reads as "the tree is declarative", which it does not establish.
- **Anti-vacuity pin.** Once the current hits are rewritten the guard has zero live positives, so a lexer projection bug or an over-tightened phrase pattern would pass forever. A both-directions test over synthetic strings pins it (`RetiredVocabularyGuardTest`'s pattern): wording that must fire, including a phrase broken across a javadoc continuation line, and near-miss wording that must not. Plus the scanned-file floor the sibling guards carry.
- **Scope by axis split, not roster fork.** `GuardScope`'s roadmap-tool exclusion is a property of the roadmap-citation *subject* (an item id in roadmap-tool sources is a legitimate reference), not of the module roster, and `GuardScope`'s one-definition invariant must survive this guard. So: `GuardScope` keeps the single module roster, the roster gains `roadmap-tool`, and each guard subtracts what its own subject requires, with the reason stated at the subtraction. Three guards read the roster today, not two, and the widening lands differently on each. `RoadmapReferenceGuardTest` takes the one subtraction: the module's entire domain is roadmap items, so the exemption that currently lives as roster javadoc moves to the guard, stated as its subject. `RetiredVocabularyGuardTest` widens with no subtraction, which is by-subject correct (retired vocabulary is as stale in roadmap-tool as anywhere) and benign in fact: its Java, SDL and SQL scans over roadmap-tool find nothing today. `StoreFixtureGuardTest` widens and gains exactly one finding, `SchemaReferencePagesTest` opening `GraphitronModelStore` directly; that site adopts `FactStores` inside this item (roadmap-tool already depends on graphitron-model, so only the test-jar dependency is added), because the guard's subject argues for coverage, not around it: `roadmap-tool-store-site-and-guard-scope` names covering the site as the wanted outcome, and `StoreFixtureGuardTest.Why`'s two permanent reasons fit neither a subtraction nor a new `EXEMPT` entry. The phrase guard itself has no roadmap-tool exemption, so it scans the module where the style is thickest. The guards' anti-vacuity floors only gain files under the widening, so they hold as-is, and `GuardScope`'s class javadoc drops the stale "prose guards" framing for the roster's actual readers. This settles the fork the Backlog item `roadmap-tool-store-site-and-guard-scope` poses, for every guard on the roster rather than only the prose pair; update that item's body when this lands (the store-site question and the javadoc bullet close here, leaving its floor-value note to stand or fall on its own).

The tree currently has three hits (`InlineMultiplicityCheck`, `RewriteSchemaLoaderTest`, `TestFilmDetailsDto`); rewriting them ships with the guard, each restating any real constraint in present tense per the day-one rule (in `InlineMultiplicityCheck`, the report-versus-gate declaration stays; only its defense goes). `BuildOutputReportPipelineTest` is deliberately not on the list: its only archeological wording is a "no longer" comment, which the admission criterion above keeps off the seed list, so it sits in the guard's disclosed residue, review-enforced like the rest of the style.

### 4. Reviewer-prompt hook, srp untouched

Add a comment-style entry to the `reviewer-prompt` skill's "What to look for" list, beside "Stale references", so that review shape reads the diff's comments against the convention. The `srp` templates get nothing: their design-intent section forbids re-accreting checklists and names phrasing as out of the gate's scope, and the workflow gates are covered anyway, since they cite the principles doc this item extends and the build guard runs regardless.

### Out of scope

- A mechanical density gate (maximum javadoc lines). Any threshold is arbitrary, the blessed orientation javadoc legitimately exceeds it, and it teaches compression tricks rather than selectivity.
- A corpus-wide rewrite of existing dense javadoc. Rewrite on touch. The one eager exception: the handful of files agents read constantly, since those are the de facto style teachers; picking them is an implementation-time judgment, not a list this spec maintains.

## Acceptance criteria

- CLAUDE.md carries the comment-style rules as a section separate from citation hygiene: the day-one test with the present-tense-restatement remedy, the subject-scoped why-not rule, the paragraph admission test, and the mimicry-loop breaker.
- `development-principles.adoc` names archeology and rejected-implementation narration as comment/javadoc-habitat smells, states the why-versus-deliberation line, leaves disclosed gaps and measured counter-results conforming, stays within its `DocSizeBudgetTest` budget, and the smell's *Enforced by* line names the guard-plus-review pair.
- A build-run guard test fails on the seed phrases in comment regions of in-scope sources including roadmap-tool, discloses its residue in its own javadoc, carries a both-directions synthetic phrase pin and a scanned-file floor, and states the rewrite rule in its failure message; the current hits are rewritten with real constraints restated in present tense, and the build is green.
- `GuardScope` still has one module roster, now including `roadmap-tool`, and its class javadoc names the roster's actual readers; `RoadmapReferenceGuardTest` carries the roadmap-tool subtraction with its subject reason stated; `SchemaReferencePagesTest` takes its store from `FactStores` so `StoreFixtureGuardTest` scans roadmap-tool with neither a subtraction nor a new exemption; `RetiredVocabularyGuardTest`'s widened walk stays green; `roadmap-tool-store-site-and-guard-scope`'s body reflects the settled fork.
- The `reviewer-prompt` skill instructs the reviewer to check diff comments against the convention; the `srp` templates are unchanged.

## Reviewer findings

### Round 1 (2026-08-26, Spec -> Ready, reviewer session 019rsBkkANNVxMVAiBaCdV5L)

Verdict: withhold. One blocking finding on question two, one factual finding on question one. The
goal comes across without reconstruction from the phase list, the diagnosis is well evidenced, and
nearly every claim the spec makes about the tree checks out. The blocker is a single phase whose
scope argument is built on a picture of `GuardScope` that is one guard out of date.

**Finding 1 (question two: architecture fit). Phase 3's "Scope by axis split, not roster fork"
breaks `StoreFixtureGuardTest`, and the spec does not say what to do about it.**

Three guards walk `GuardScope.IN_SCOPE_MODULES` today, not two. Besides
`RoadmapReferenceGuardTest` and `RetiredVocabularyGuardTest`, which the phase names,
`StoreFixtureGuardTest.noTestStandsAStoreUpOutsideAHarness` iterates the same roster.
`GuardScope`'s own class javadoc still calls it the "Shared walk scope for the prose guards", which
is where the two-guard picture comes from, and `roadmap-tool-store-site-and-guard-scope`'s third
bullet already records that sentence as stale.

Under a single roster, subtraction can only remove, so "the phrase guard scans roadmap-tool" means
the roster gains `roadmap-tool`. `StoreFixtureGuardTest` then walks
`roadmap-tool/src/test/java`, where `SchemaReferencePagesTest.rendersEveryRelationOfTheLiveCatalogExactlyOnce`
calls `GraphitronModelStore.open()` in a code region. `StoreFixtureScanner` recognises that type as
a whole identifier token in code regions, the file is in neither `HOMES` nor `EXEMPT`, so the build
goes red on a file this item never touches.

The acceptance criterion "`GuardScope` still has one module roster; per-guard subject subtractions
carry their reasons" is therefore not satisfiable as written. `StoreFixtureGuardTest`'s subject
argues against a subtraction rather than for one: `roadmap-tool-store-site-and-guard-scope` names
covering that site as the wanted outcome, and `StoreFixtureGuardTest.Why` carries exactly two
permanent reasons, the store's own lifetime being the subject and the class being a capture oracle,
neither of which fits. A subtraction written anyway would state "there is an unfixed violation
here", which converts a declared scope gap into a suppression.

What would satisfy this: pick an arm and write it into the phase. Adopt `FactStores` at the one
roadmap-tool site inside this item, which is small and closes that Backlog item's first question;
or give the phrase guard an additive scope of its own and say what becomes of the one-definition
invariant the phase currently protects; or write the `StoreFixtureGuardTest` subtraction with its
real reason and record in that Backlog item that the gap became declared rather than closed.
Whichever arm, say what happens to `RetiredVocabularyGuardTest`, which also widens. Its Java, SDL
and SQL scans over roadmap-tool find nothing today, so the widening is benign, but that is a fact
the spec should state rather than one the implementer discovers. The phase's claim to settle the
fork "for prose guards" is the framing that hides the third guard, so it needs revisiting too.

**Finding 2 (question one: a checkable claim that does not hold). The tree has three seed-phrase
hits, not four.**

Scanning comment regions for the four seed phrases, with javadoc continuation lines rejoined, finds
`InlineMultiplicityCheck`, `RewriteSchemaLoaderTest` and `TestFilmDetailsDto`.
`BuildOutputReportPipelineTest`'s only archeological wording is "no longer" at line 43, and the
admission criterion in the same paragraph deliberately keeps "no longer" off the seed list. So
either the file list is wrong or the seed list is, and I cannot tell which from the spec. Left
as-is, the acceptance criterion "the current hits are rewritten" disagrees with what the guard
will actually report.

**Non-blocking.**

`DocSizeBudgetTest`'s headroom is 14 words: `development-principles.adoc` is 3,486 words against
the 3,500-word budget. Phase 2's "if it cannot fit" reads as a contingency when displacement is
certain. The document's own Constraints entry already makes displacement the policy, so this is
phrasing rather than a design gap, but the implementer should not read the conditional as an
option.

Everything else checked. `RoadmapReferenceScanner.scanSource(Path, String)` exists with that shape
and is unit-testable over in-memory strings; `RetiredVocabularyGuardTest` carries the
demonstrated-recurrence bar, the scanned-file floors, and a both-directions synthetic phrase test
whose `matchPhrases` already rejoins the projected habitat so a phrase broken across a javadoc
continuation line matches, which is the exact mechanism phase 3 asks for;
`CollectionValuedColumnGateTest` carries the disclosed-gap javadoc the phase cites, and
`fact-model.adoc` names `CaptureCorpusIsolationTest` in its "Not mechanically enforced" list and
does price its rejected rewrites in seconds; `GraphitronSchemaBuilder`'s class javadoc is one
sentence of what plus a boundary paragraph plus a call-order paragraph, so it calibrates the
paragraph-admission rule exactly as the phase claims; `InlineMultiplicityCheck`'s javadoc is the
specimen described, with the report-versus-gate declaration separable from its defense;
"rather than" and "instead of" appear 54 times in roadmap-tool main-source comments, so "40+"
holds; `development-principles.adoc`'s "Documentation names only live tests/code" names exactly the
two smells the spec says it names and carries the *Enforced by* line; the `reviewer-prompt` skill's
"What to look for" list has the "Stale references" entry to sit beside; and the `srp` skill's
template design intent does forbid re-accreting checklists and does name phrasing as out of the
gate's scope.

### Round 2 (2026-08-26, Spec -> Ready, reviewer session 019rsBkkANNVxMVAiBaCdV5L)

Verdict: sign off. Both round-1 findings are answered, and the adoption arm the rework picked is
the one that leaves no suppression behind.

Phase 3 now names all three roster readers and says what the widening does to each.
`FactStores.inMemory()` is `GraphitronModelStore.open()` plus a boot count, so
`SchemaReferencePagesTest`'s swap is behaviour-preserving, and holding the handle in a `var` leaves
no `GraphitronModelStore` identifier token in a code region for `StoreFixtureScanner` to find. The
test-jar dependency is a trodden path rather than a new one: `graphitron-model`'s pom already
republishes its tests for exactly this harness, and `graphitron`, `graphitron-lsp`,
`graphitron-mcp` and `graphitron-maven-plugin` all consume it with the shape the phase describes,
so roadmap-tool adds a `<type>test-jar</type>` entry beside a compile dependency it already
declares. `StoreFixtureGuardTest.Why` does carry exactly `LIFETIME` and `ORACLE`, which is what
rules out the subtraction and the `EXEMPT` entry. The anti-vacuity floors are lower bounds and the
widening only adds files, so "they hold as-is" is right. `RetiredVocabularyGuardTest`'s widening is
benign as stated: neither registry finds anything in roadmap-tool's Java sources, its one
`.graphqls` fixture, or the reverse-enforcer's main-source identifier scan.

The hit-list correction went further than either arm round 1 named, and better: three files listed,
with `BuildOutputReportPipelineTest` placed explicitly in the guard's disclosed residue rather than
dropped in silence, which is the same honesty the phase asks of the guard's own javadoc.

One convention note, not a finding: the round-1 findings carry no per-finding response notes
beneath them. The commit message records the responses in full, so nothing is lost here; the
adjacency convention exists so the Done-gate reviewer audits a delta instead of re-reading, and it
is worth keeping to on the next round.
