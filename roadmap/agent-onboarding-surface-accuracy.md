---
id: R541
title: "Correct and sharpen the CLAUDE.md agent-onboarding surface"
status: Backlog
bucket: tooling
priority: 3
depends-on: []
created: 2026-07-26
last-updated: 2026-07-26
---

# Correct and sharpen the CLAUDE.md agent-onboarding surface

`CLAUDE.md` is the only document every agent session reads in full and reads first, so a wrong fact there costs more than the same fact wrong anywhere else: it is believed without verification and acted on before any file is opened. A read-through against the current reactor turns up four factual errors, three pointers aimed at the wrong file or at no file, and one hook whose output never reaches the agent it was written to nudge. None of these are cosmetic; each one either misstates a build-enforced constraint or sends a session down a slower path than the repo already provides.

## Factual drift

* **The Java-17 floor is stated as one module when it is two.** The Technology constraints bullet names `graphitron-sakila-example` as the only `<release>17</release>` module and frames it as a verification harness for generated output. `graphitron-jakarta-rest/pom.xml:77` pins its `default-compile` execution to `<release>17</release>` as well, and its own comment calls it a "Java-17 floor for this hand-written runtime artifact"; `docs/architecture/reference/modules.adoc` agrees. That is a load-bearing constraint on hand-written code, not a generated-output check, and an agent reading only `CLAUDE.md` would write Java 25 syntax into a module that must compile at 17.
* **The module list is short by one.** `CLAUDE.md` enumerates eleven modules; the root `pom.xml` `<modules>` block declares twelve, adding `docs`. `docs/architecture/reference/modules.adoc` has the same off-by-one ("Eleven modules") while `CLAUDE.md`'s own Documentation site section correctly calls `/docs/` a Maven module, so the file contradicts itself.
* **`R99` is cited for a mechanism whose item file no longer exists.** The `graphitron:dev` sibling-scan paragraph says "See R99 (`lsp-submodule-sibling-classpath`)"; no such file remains under `roadmap/`, only a `changelog.md` line. The Javadoc conventions section two screens further down forbids exactly this citation form, for exactly this rot, and `RoadmapReferenceGuardTest` enforces it in Java sources. `CLAUDE.md` is outside the guard's scan, so it drifted the way the guard exists to prevent. The same paragraph already links `docs/architecture/how-to/dev-loop-internals.adoc`, which is the durable reference; the `R99` half is pure liability. `R439` in the Environment section is the same pattern.
* **The em-dash prohibition is stated absolutely and is not true of the tree.** "Do not use em dashes in documentation" reads as an invariant, but 23 source `.adoc` files under `docs/` carry 56 of them and 48 files under `roadmap/` carry more. Unlike the sibling AsciiDoc-table rule, which names its enforcing check (`check-adoc-tables`), this one has no mechanical gate. An agent cannot tell from the text whether it is a live constraint it will be failed on, a forward-looking style preference for new prose, or a dead letter. Resolve it: either add the check next to `AdocMarkdownTableCheck` and sweep, or scope the sentence to newly written prose and say so.

## Pointers aimed at the wrong place

* **The test-tier question is routed to the file that explicitly defers it.** "unsure which tier to put a test in" points at `docs/architecture/explanation/development-principles.adoc`, whose own line 267 ends with "Tier names, locations, and the decision rubric: `how-to/testing.adoc`". `testing.adoc` also carries the four build commands, including `-DexcludedGroups=execution` for a fast inner loop. Point at `testing.adoc` directly.
* **There is no way to run less than everything.** Common commands offers a full `mvn install -Plocal-db` and the roadmap regen, then discourages `-pl`. The four scoped commands in `testing.adoc` (single-module test, compile-only, full, execution-excluded) are the daily inner loop and appear nowhere an agent reads by default. `-Pquick` exists but is buried mid-paragraph inside the Javadoc-gate discussion, where nothing about the surrounding text suggests it is the general fast-loop lever.
* **`CLAUDE.md` hand-rolls the git flow the `publish` skill exists to perform.** The Git Workflow section ends in a four-line command block; `.claude/skills/publish/SKILL.md` does the same push plus trunk fast-forward with a dirty-tree check, a wip/draft/spike guard, a trunk-divergence pre-check, and network retry. A session following `CLAUDE.md` literally runs the raw commands and gets none of that. The section should name the skill as the default path and keep the commands as the fallback. More broadly, `CLAUDE.md` names `roadmap`, `srp`, `reviewer-prompt`, and `principles-architect` but not the other five project skills; the workflow-critical one is `publish`.
* **No orientation before the module names.** The reactor is introduced as a bare list of twelve identifiers with no purpose attached, and `docs/architecture/reference/modules.adoc`, which is a good purpose-per-module table, is never linked from `CLAUDE.md`. Every session pays an exploration round trip to learn what `graphitron-fixtures-codegen` versus `graphitron-sakila-db` do.

## The trunk-reminder hook talks to the wrong audience

`.claude/settings.json`'s `PostToolUse` hook fires on `Bash(git push *)` and emits `{"systemMessage": "Trunk fast-forward not done. ..."}`. Per the hooks contract, `systemMessage` is user-facing and the agent never sees it; the channels that reach the agent are `hookSpecificOutput.additionalContext` or stderr with exit 2. The hook's entire purpose is to nudge the session that just pushed, and as written that nudge lands on the human, who then has to relay it. Switching the payload to `hookSpecificOutput` with `hookEventName: "PostToolUse"` and the text under `additionalContext` makes it work as intended. The `if` field on the hook entry is valid and correctly scoped; only the output shape is wrong.

## Scope note

Confined to `CLAUDE.md`, `.claude/settings.json`, and the two `docs/architecture` files carrying the same module-count and tier-pointer drift. No generator behaviour changes. The em-dash decision is the one fork that needs a call before implementation: enforce and sweep, or narrow the wording.
