---
name: delegate-to-subagents
description: Hand reading and mechanical work to subagents while the session keeps the decisions, the conversation with the user and the judgment calls. Use when a task needs more reading than fits comfortably in context (an inventory, a cross-module survey, a design question with history), or a mechanical change too large to do by hand (converting a test family, moving an emitter). Covers parallel read-only research, one build-running agent at a time, what a self-contained brief carries, the report shape to ask for, and verifying what comes back.
---

# Delegate to subagents

The session holds the user's decisions and the judgment calls. Subagents do the reading and the
mechanical execution, and hand back a conclusion with `file:line` evidence, never file dumps. What
makes this trustworthy rather than merely faster is the brief going out and the verification coming
back; skip either and delegation produces confident wrong answers.

## Research: parallel, read-only

- Split a question that needs a lot of reading across two or three agents in one turn, each told
  plainly: **do not build, do not edit**. Read-only agents are cheap and may run alongside anything.
- Ask for a **verdict table**, one row per item, not an essay. For a test inventory: the behaviour
  guarded, the existing cover (`file:line` and the asserting line), and a verdict (DELETE /
  CONVERT, with the expected values and where they come from). For a design question: the code's
  actual shape with sizes, and the recorded reasons quoted with their paths.
- Take the table to the user for rulings **before** anything changes.

## Execution: one build at a time, and fewer than you think

- **One build-running agent per worktree.** Two Maven runs in one tree collide, and an agent's tree
  must not be reset, rebased or synced while it builds.
- **Builds share the machine.** Several sessions each building the full reactor have driven this
  box to a load average of 27, which is when slowdowns and the store-compaction test failures appeared.
  Hold at most **one full reactor build per session** at a time, and do not launch build-running
  agents in parallel only to go faster while other sessions are building.
- Sequence dependent steps: a fixture change goes first and is verified before conversions build
  on it.

## What a brief carries

The agent sees none of the conversation, so the brief stands alone. **Reference the rules; do not
copy them**: copies go stale, and stale copies are how the slips below happened.

- The worktree's absolute path and branch; what it may do (edit, build, commit) and may not
  (fetch, rebase, merge, push, bare `git stash`).
- The session's build flags verbatim, and the verification owed, by reference to CLAUDE.md
  "Building and testing": the full reactor unless the commits are confined to `roadmap/`.
- Any project rule the change can trip, named by where it lives: the testing rules in
  `docs/architecture/principles/development-principles.adoc`, the modeling discipline, the open
  roadmap item's gates and in-flight register, the catalog bump below.
- The user's rulings for this task, **quoted as binding**.
- The verdict list, and what must not be touched, with the reason.
- "Never make a test pass by copying the actual output without understanding the difference."
- "Negative-test every new assertion: break it, confirm the failure, restore."
- "At a fork the brief does not settle, stop and report your recommendation; do not invent."
- The commit format and trailers.
- The **report shape**: SHAs; counts; per item its fate and evidence; negative tests and their
  failure lines; the build result (modules, tests, failures); choices made beyond the brief; what
  could not be done and why.

## Verify on return

- Check the SHAs exist and the tree is clean; re-run the cheap claims yourself (`grep` for "no test
  reads X", `git show --stat`).
- Read any **loosened assertion** (`containsExactly` to `contains`, a deleted test, a renamed one)
  and decide whether it still asserts what the test means.
- Judge every departure from the brief before it reaches the user; some are better than the brief,
  and saying why is part of the report.
- A green agent build covers the tree the agent built. Check the scope against what changed before
  relaying "verified".

## Where it went wrong

Each of these was a rule that already existed; the brief or the check did not name it.

- A `docs/` edit was verified with the `roadmap-tool,docs` scope and broke trunk:
  `DocSizeBudgetTest` in graphitron-sakila-example reads `development-principles.adoc`. Only a diff
  confined to `roadmap/` takes the scoped build.
- A change to `graphitron-sakila-db`'s `init.sql` went out without bumping
  `jooq.codegen.schema.version` in that module's pom, so other sessions kept building a stale
  catalog. Name the bump in any brief that may touch the fixture.
- A failure was attributed to another session's branch from a non-clean build. Before blaming
  anything outside the diff, reproduce with `mvn clean install` and the session's `no/sikt`
  artifacts wiped.
- A brief asked an agent to capture a value that the directive's own type makes illegal, so the
  case could never arise. Check the declared types before briefing a change to how input is
  captured.
