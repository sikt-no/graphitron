---
id: R542
title: "Correct and sharpen the CLAUDE.md agent-onboarding surface"
status: Ready
bucket: tooling
priority: 3
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Correct and sharpen the CLAUDE.md agent-onboarding surface

> Correct four factual errors in `CLAUDE.md`, repoint three references that aim at the wrong
> file or at a deleted one, fix a `PostToolUse` hook whose output never reaches the agent it
> was written to nudge, and add a `roadmap-tool` check so the transient-citation drift cannot
> silently recur. Confined to `CLAUDE.md`, `.claude/`, and two `docs/architecture` pages
> carrying the same drift. No generator behaviour changes, no test-tier changes.

`CLAUDE.md` is the only document every agent session reads in full and reads first, so a wrong
fact there costs more than the same fact wrong anywhere else: it is believed without
verification and acted on before any file is opened. A read-through against the current
reactor turns up four factual errors, three misaimed pointers, and one ineffective hook. None
are cosmetic; each either misstates a build-enforced constraint or sends a session down a
slower path than the repo already provides.

---

## Decisions taken

*Em-dash rule.* Narrow the wording to newly written prose rather than add a mechanical check
and sweep the tree. The 56 existing occurrences across 23 source `.adoc` files sit mostly in
published manual pages where they read fine; a sweep is churn with no reader benefit, and a
hard gate would fail builds on prose nobody is editing. The sentence becomes a rule about what
you write, which is what it was always meant to be, and stops reading as an invariant the
build enforces.

*Recurrence guard.* Add one, but scope it to the two prose documents that make load-bearing
claims (`CLAUDE.md`, `.claude/web-environment.md`) and leave the skill docs alone. See §3.

---

## 1. The Java-17 floor is stated as one module when it is two

The Technology constraints bullet names `graphitron-sakila-example` as the only
`<release>17</release>` module and frames it as a harness verifying generated output.
`graphitron-jakarta-rest/pom.xml:77` pins its `default-compile` execution to
`<release>17</release>` as well, and the comment above it calls this "a Java-17 floor for this
hand-written runtime artifact"; `docs/architecture/reference/modules.adoc` agrees. An agent
reading only `CLAUDE.md` would write Java 25 syntax into a module that must compile at 17 and
discover the constraint from a build failure.

The two modules are at 17 for different reasons and the replacement text must keep them
distinct: `graphitron-sakila-example` compiles emitted sources at 17 to *verify* the generator's
output floor; `graphitron-jakarta-rest` is hand-written runtime code consumers put on their
classpath, so 17 is a *constraint on what a contributor may type in that module*. Only the
second one changes how an agent writes code, and it is the one currently missing.

## 2. Module list, count, and orientation

`CLAUDE.md` enumerates eleven modules; the root `pom.xml` `<modules>` block declares twelve,
adding `docs`. The file then contradicts itself: the Documentation site section correctly
describes `/docs/` as a Maven module. `docs/architecture/reference/modules.adoc` carries the
same off-by-one in prose ("Eleven modules") and omits the `docs` row from its table.

Beyond the count, the list is twelve bare identifiers with no purpose attached, and
`modules.adoc`, which is a good purpose-per-module table, is never linked from `CLAUDE.md`.
Every session pays an exploration round trip to learn what `graphitron-fixtures-codegen` does
versus `graphitron-sakila-db`.

Work:

* `CLAUDE.md`: keep the enumeration (it is the cheapest possible orientation and costs two
  lines), add `docs`, and append a pointer to `modules.adoc` as the purpose-per-module
  reference.
* `modules.adoc`: correct the count and add a `docs` row (pom-packaged, renders the site,
  deploy-skipped). `docs/architecture/index.adoc` already lists `docs` among the deploy-skipped
  set, so the table is the only place it is missing.

## 3. Transient roadmap citations, and a guard so they stop recurring

The `graphitron:dev` sibling-scan paragraph says "See R99 (`lsp-submodule-sibling-classpath`)".
No such file remains under `roadmap/`; only a `changelog.md` line survives. The Javadoc
conventions section two screens further down forbids exactly this citation form for exactly
this rot, and `RoadmapReferenceGuardTest` enforces it across Java sources. `CLAUDE.md` sits
outside that scan, so it drifted the way the guard exists to prevent. `R439` in the Environment
section is the same pattern, as are `R439`, `R474`, and `R389` in `.claude/web-environment.md`.

In every case the durable reference already sits next to the citation (the sibling-scan
paragraph links `docs/architecture/how-to/dev-loop-internals.adoc`; the web-environment
citations sit beside the scripts and status files they describe), so the fix is deletion, not
substitution.

*The guard.* `RoadmapReferenceGuardTest` cannot be extended here: it parses Java comment and
string-literal regions, and these are markdown. The natural home is `roadmap-tool`, which
already runs `AdocMarkdownTableCheck` (172 lines, `Main.java:96`, bound at
`roadmap-tool/pom.xml:103` to `verify` with `${project.basedir}/..`). Add a sibling mode in the
same shape, scanning for `\bR\d+\b` and `roadmap/<slug>` outside the three permanent artifacts
(`roadmap/changelog.md`, `roadmap/workflow.adoc`, `roadmap/README.md`).

*Scope it to `CLAUDE.md` and `.claude/web-environment.md`.* Do not scan `.claude/skills/`. Those
files are thick with worked examples ("move R24 to Ready", `R7`, `R12` in the roadmap skill;
`R112`/`R115`/`R117` in the capability skills) where the ID is illustrative syntax, not a
provenance claim, and rotting is harmless because the reader is being shown a command shape.
A guard that fired on those would be suppressed within a week. This is the same judgment
`RoadmapReferenceGuardTest` already makes when it excludes test-source string literals: scan
the habitat where a stale ID misleads, not every habitat where the characters appear.

Open question for the reviewer: whether the guard earns its keep at all, or whether §1, §2, and
the deletions here are a one-time correction that does not need a ratchet. The argument for it
is that this is the one failure mode in the item that already recurred without anyone noticing.
The argument against is that a check over two files is a lot of machinery for a small habitat.
I lean toward building it, at roughly 80 lines reusing `BuildFailure`, but will not defend it
hard.

## 4. The em-dash prohibition reads as an invariant it is not

"Do not use em dashes in documentation" is stated absolutely, but 23 source `.adoc` files under
`docs/` carry 56 of them and 48 files under `roadmap/` carry more. Unlike the sibling AsciiDoc
table rule, which names its enforcing check (`check-adoc-tables`), this one has no gate, so an
agent cannot tell whether it is live, aspirational, or dead.

Per the decision above: rewrite the sentence to bind what the session writes, and state plainly
that it is style guidance with no build gate and that existing occurrences are not a cleanup
backlog. That removes the ambiguity in the direction that costs nothing.

## 5. The test-tier pointer aims at the file that explicitly defers the question

"Unsure which tier to put a test in" points at
`docs/architecture/explanation/development-principles.adoc`, whose own line 267 ends with
"Tier names, locations, and the decision rubric: `how-to/testing.adoc`". Repoint to
`testing.adoc`, which is the rubric and also carries the build commands.

## 6. There is no documented way to run less than everything

Common commands offers a full `mvn install -Plocal-db` and the roadmap regen, then discourages
`-pl`. The four scoped commands in `testing.adoc` (single-module test, compile-only, full,
execution-excluded) are the daily inner loop and appear nowhere an agent reads by default.
`-Pquick` exists but is buried mid-paragraph in the Javadoc-gate discussion, where nothing
signals it is the general fast-loop lever.

Add the two that actually change session behaviour to Common commands:
`mvn test -pl :graphitron -Plocal-db -DexcludedGroups=execution` and `-Pquick`, each with one
clause on when it is the right choice. Do not copy all four; `testing.adoc` stays the full
reference and the existing "prefer the full install" nudge stays, since it is correct for
verification runs and only wrong as a claim that nothing narrower exists.

## 7. `CLAUDE.md` hand-rolls the git flow the `publish` skill performs

The Git Workflow section ends in a four-line command block. `.claude/skills/publish/SKILL.md`
does the same push plus trunk fast-forward with a dirty-tree check, a wip/draft/spike guard, a
trunk-divergence pre-check, and network retry. A session following `CLAUDE.md` literally runs
the raw commands and gets none of that, which is how a divergent trunk turns into a failed push
mid-session instead of a pre-flight warning.

Name `publish` as the default path; keep the commands as the documented fallback for when the
skill is unavailable or the flow needs to deviate. `CLAUDE.md` currently names `roadmap`, `srp`,
`reviewer-prompt`, and `principles-architect` but none of the other five project skills; skill
descriptions surface on their own, so a full inventory is not needed, but `publish` is
workflow-critical and belongs in the prose.

## 8. The trunk-reminder hook talks to the wrong audience

`.claude/settings.json`'s `PostToolUse` hook fires on `Bash(git push *)` and emits
`{"systemMessage": "Trunk fast-forward not done. ..."}`. Per the hooks contract `systemMessage`
is user-facing and the agent never sees it; the channels that reach the agent are
`hookSpecificOutput.additionalContext` and stderr with exit 2. The hook's entire purpose is to
nudge the session that just pushed, and the nudge lands on the human, who then has to relay it.

Move the payload to `hookSpecificOutput` with `hookEventName: "PostToolUse"` and the text under
`additionalContext`. The `if: "Bash(git push *)"` scoping on the hook entry is valid and
correctly targeted; only the output shape is wrong. Keep the existing `grep` for
`claude/graphitron-rewrite` that suppresses the message on the fast-forward push itself.

---

## Non-goals

* No sweep of existing em dashes, and no em-dash build check (§4 decision).
* No scan of `.claude/skills/` for roadmap IDs (§3 rationale).
* No restructuring of `CLAUDE.md`'s section order or length budget. Every change here is a
  correction, a repoint, or at most two added lines; the file's shape is working.
* No generator, classifier, emitter, or test-tier changes. Nothing under `graphitron/src/main`
  is touched except, if §3's guard is built, new sources under `roadmap-tool`.

## Verification

* Every factual claim in the rewritten passages traceable to a file and line: the two
  `<release>17</release>` executions, the twelve `<modules>` entries, `testing.adoc`'s command
  block, `publish/SKILL.md`'s procedure.
* `grep -nE '\bR[0-9]+\b' CLAUDE.md .claude/web-environment.md` returns nothing outside the
  three permanent artifact paths.
* If §3's guard ships: a unit test in the shape of `AdocMarkdownTableCheckTest`, covering a
  citation of a live-looking ID, a permitted `roadmap/workflow.adoc` path reference, and the
  scanned-file floor that catches a walk reaching nothing (the vacuous-pass failure mode
  `RoadmapReferenceGuardTest` guards against with its own floor).
* `mvn install -Plocal-db` clean, including the `verify`-phase `check-adoc-tables` sibling and
  the Javadoc reference gate.
* The hook change verified by observation, not inspection: push from a feature branch without
  the trunk fast-forward and confirm the reminder text reaches the agent's context.
