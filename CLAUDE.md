# Graphitron Project - Claude Code Reference

Rules and constraints for working in this repo. The whole repo is the rewrite reactor and in scope. `/docs/` is the source for the public documentation site at `graphitron.sikt.no`; see [Documentation site](#documentation-site) below.

Links elsewhere in this file point at deep references. **Don't fetch them up front;** open one only when its stated trigger applies to the task at hand. The rules and commands here are meant to be self-contained for routine work.

## What graphitron-rewrite is

The next-generation Graphitron generator: a Maven-based code generator that turns GraphQL schemas + jOOQ-generated database models into Java resolvers. The repo root is the reactor, with its parent pom (`graphitron-rewrite-parent`, `10-SNAPSHOT`). Modules: `graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-mcp`, `graphitron-jakarta-rest`, `graphitron-maven-plugin`, `graphitron-sakila-example`, `graphitron-lsp`, `roadmap-tool` (plus the `graphitron-tree-sitter-natives` subdirectory). Developed by Sikt.

## Technology constraints

- **Java 25** for generator code (`<release>25</release>` in the root `pom.xml` and most child modules); **Java 17** for generated output (`graphitron-sakila-example` compiles with `<release>17</release>` to verify this). Generator implementation may use Java 25 features freely. Generated source files must target Java 17, consumers may still be on 17, and we control what syntax appears in those files. The parent pom's `requireJavaVersion` enforcer rule fails the build with a clear message when run on a JDK older than 25.
- **jOOQ 3.20.11**, **GraphQL-Java 25.0**, **JUnit 6.0.3 + AssertJ 3.27.7**, **PostgreSQL 42.7.10** (Testcontainers 2.0.4). Versions are pinned in the root `pom.xml` properties; don't add dependencies without checking that pom first.

## Environment (agent sessions)

Maven 3.9.11 at `/opt/maven`; Java 25 default. Pre-configured, no installation needed.

Verify before building:

```bash
mvn -version  # expect "Java version: 25.x"
```

If `mvn -version` reports Java 21, the session inherited a stale `JAVA_HOME`; see `.claude/web-environment.md` for the JDK 25 install + `JAVA_HOME` export. The parent pom's enforcer fails loudly with "Detected JDK … 21", so this is not silent.

**Claude Code Web** uses a web-sandbox setup (no Docker, native PostgreSQL via `-Plocal-db`). The SessionStart hook runs asynchronously and warms the full reactor build in the background (R439); a PreToolUse guard holds your first `mvn`/`mvnd` command until the warm-up finishes, so an unusually long first invocation is the guard waiting, not a hang. For PostgreSQL/Testcontainers errors there, see `.claude/web-environment.md`.

**Prefer `mvnd` over `mvn` in web sessions.** The hook installs mvnd (Maven Daemon) and warms its daemon, so repeat Maven commands skip the 3-8 s JVM start that plain `mvn` pays each time. Every `mvn` command in this file works verbatim as `mvnd`; behaviour is identical (mvnd runs modules in parallel by default, which the test suite supports and CI enforces with `-T 1C`). If `mvnd` is absent its install failed; plain `mvn` is always correct. One quirk: don't combine `mvnd` with `-q` when you need a tool's stdout (it can be swallowed); details in `.claude/web-environment.md`.

## Common commands

```bash
# Build the rewrite (always include -Plocal-db; see footgun below)
mvn install -Plocal-db

# Regenerate roadmap/README.md from item front-matter
mvn -pl roadmap-tool exec:java -q
```

The full install is fast; prefer it over targeted `-pl` builds. If you do need `-pl`, always pair it with `-am` (also-make) or `-amd` (also-make-dependents); a bare `-pl` skips dependent modules and produces stale results.

## Building and testing

The `mvn install -Plocal-db` command above runs the full pipeline (build-fixtures → test → compile-spec → execute-spec). Reach for the deeper docs only when the task requires it:

- Adding/structuring tests, or unsure which tier (unit vs pipeline vs compilation vs execution) to put a test in: `docs/architecture/explanation/development-principles.adoc`.
- Navigating the sealed variant hierarchy, classification taxonomy, or runtime extension points: `docs/architecture/index.adoc`.
- Diagnosing build/test failures specific to the web sandbox: `.claude/web-environment.md`.

**Footgun: catalog-jar clobber.** Omitting `-Plocal-db` silently empties the jOOQ catalog jar; pipeline tests then fail with `UnclassifiedType` / `NoSuchElement` / "table … could not be resolved" cascades. Recovery: rerun with `-Plocal-db`.

**Sub-module gotcha (handled for standard layouts): `graphitron:dev` sibling scan.** Running `mvn graphitron:dev` from inside one module of a multi-module reactor used to see only that module's classes, so services / conditions / records in sibling modules silently produced empty completions. The dev goal now walks the parent pom's `<modules>` and folds siblings' `target/classes` and sources into the scan automatically (standard layout only; a sibling must have been compiled once). See R99 (`lsp-submodule-sibling-classpath`) and the "Multi-module projects" note in `docs/architecture/how-to/dev-loop-internals.adoc`.

## Writing style

Do not use em dashes (—) in documentation. Use a comma, semicolon, colon, or restructure the sentence instead.

In `.adoc` files, use AsciiDoc table syntax (`[cols="..."]` + `|===` block) for tables; the markdown form (`| col | col |` header followed by a `|---|---|` separator) renders as paragraph text with literal pipes. The roadmap-tool `check-adoc-tables` step fails the build on any such row outside a structural block.

## Javadoc conventions

Comments and javadoc name live things, never transient ones. A roadmap item id (`R<n>`) or a `roadmap/<slug>` path is transient: items get renumbered, ship and leave a numbering gap, or get discarded, so a comment that leans on one is stale the moment the item moves. Do not cite roadmap items in javadoc or implementation comments. Instead, reference a live symbol with `{@link}` (compiler- and javadoc-checked, so it cannot silently rot), reference the published docs (the user manual for author-facing behavior, `docs/architecture/` for contributor-facing rationale), or just state the fact and drop the citation. Prefer terse over verbose. The three permanent roadmap artifacts (`roadmap/changelog.md`, `roadmap/workflow.adoc`, `roadmap/README.md`) are not transient items and may be cited by path.

This is enforced mechanically: `RoadmapReferenceGuardTest` (a `graphitron` test-tier meta-test) fails the build if an `R<n>` or `roadmap/<slug>` citation appears in a comment or javadoc region of an in-scope module. The guard is lexically scoped, so it inspects only comment/javadoc regions and ignores string literals; a roadmap id in a user-facing message *string* is a separate habitat the guard does not police. If the guard fires, rewrite the comment per the above; do not suppress it.

## Interaction style

Do not use the `AskUserQuestion` tool for open-ended design dialog or spec exploration. The structured multi-choice format constrains a conversation that should be free-flowing prose, and the user has explicitly flagged it as friction. Surface design forks inline: state the choice, give your recommendation and the reasoning, invite redirect. The user answers with a sentence, pushes back on the framing, or pivots, none of which fit the question/options shape. Reserve `AskUserQuestion` for genuinely bounded decisions where the option set is closed and prose framing would be wasteful (for example "discard the merge conflict or keep your local copy"); even there, prose first is usually fine.

## Editing large files

Prefer many small `Edit` calls over one large `Write` when trimming or rewriting a long file. Full-file writes on plans/docs of ~300+ lines tend to time out mid-response and leave the file half-written. Sequence of targeted `Edit` calls (remove section A, remove section B, replace section C) is both safer and faster.

## Documentation site

`/docs/` is a Maven module (`graphitron-docs`, packaging `pom`) that renders an AsciiDoc site to `docs/target/generated-docs/` and gets deployed to GitHub Pages by the `docs-build` / `docs-deploy` jobs in `.github/workflows/rewrite-build.yml` (trunk pushes only). The contributor-facing architecture docs under `/docs/architecture/` and the roadmap under `/roadmap/` also render into the site. PR preview builds run through `.github/workflows/preview-docs.yml` and upload `target/generated-docs/` as a workflow artifact. Doc changes ship through the same trunk-based flow as code; PR breakage on `.adoc` files fails CI.

To skip the AsciiDoctor render in a local build (saves ~10s of JRuby startup):

```bash
mvn install -P!docs -Plocal-db
```

## Development Workflow

**Before editing the reactor, confirm a roadmap item covers the change.** If none exists, file a Backlog item first via the `roadmap` skill; this applies even to "fix this error" / "make it accept X" framings. The default when unsure is to file a Backlog stub and ask whether to skip the gate, not to implement and ask later; the case for bypassing the pipeline is the user's to make.

**Size is not an exemption.** The documented workflow applies to *every* change in the reactor, including one-line patches, "trivial" bug fixes, and obvious-looking corrections. Small or urgent-feeling does not mean skip the roadmap item, the Spec → ... → Done states, or the reviewer-rule gates; a bug fix moves through the same pipeline as a feature. If the workflow genuinely should be short-circuited for a given fix, that is the user's call to make explicitly, not a default you reach for because the change looks simple.

Every change moves Backlog → Spec → Ready → In Progress → In Review → Done. Each item has its own file in `roadmap/` carrying YAML front-matter (`status:`, `bucket:`, etc.); `roadmap/README.md` is the rendered roll-up, regenerated by `mvn -pl roadmap-tool exec:java -q`. Reviewer must be a different party than the author (for Spec → Ready) and the implementer (for In Review → Done). Any session can add Backlog items.

**Fast-forward trunk before and after every transition.** Because we work trunk-based and many sessions edit `roadmap/` concurrently, bracket each state change with a trunk sync on both ends: fast-forward trunk into your branch *before* you make the transition (so you transition from the roadmap's true current state and don't clobber another session's edit or miss a sign-off/reopen), and push your branch and fast-forward trunk *after* you commit it (so the new state is visible before anyone acts on it). See the Publishing section of `roadmap/workflow.adoc`.

When transitioning a roadmap item between states, or unsure of the file conventions for a new item, consult `roadmap/workflow.adoc` for the full state table and canonical paths.

Consult the `principles-architect` subagent while drafting (Backlog → Spec, design forks in In Progress, or as a self-check before `srp`); it's read-only and produces no verdict. Reviewer-rule gates stay with `srp` / `reviewer-prompt`, which hand off to a *different* Claude Code session; identity is the `https://claude.ai/code/session_<id>` trailer on each commit, not git author.

## Git Workflow

Trunk-based development against `claude/graphitron-rewrite`.

**Standing permission.** The user authorizes pushing committed work from your feature branch to `claude/graphitron-rewrite` (fast-forward only) without asking each time; do not force-push trunk; do not push to other branches (e.g. `main`) without asking. `claude/graphitron-rewrite` is the integration trunk for all rewrite work, so if your session was started on a different feature branch, that is expected: keep developing on that branch and additionally fast-forward `claude/graphitron-rewrite` as the final step of the session flow below.

**Every commit ships to trunk** (including review fixes, docs-only commits, plan updates) unless flagged not-for-trunk by one of:

- `wip:` / `draft:` / `spike:` commit-message prefix, or
- a branch named `wip/...`, `draft/...`, or `spike/...`, or
- the user saying "don't ship this to trunk yet".

**Session flow:** sync → work + commit → push own branch → fast-forward trunk. Trunk is fast-forward only; never force-push it. Force-push-with-lease on your own branch is fine. If trunk moved during work, rebase and repeat.

```bash
git fetch origin claude/graphitron-rewrite
git rebase origin/claude/graphitron-rewrite
# ... work, commit ...
git push -u origin <your-branch>
git push origin <your-branch>:claude/graphitron-rewrite
```
