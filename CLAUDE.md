# Graphitron Project - Claude Code Reference

Rules and constraints for working in this repo. **Scope: `graphitron-rewrite/` and `docs/`.** The legacy modules at the repo root (`graphitron-codegen-parent`, `graphitron-common`, `graphitron-example`, `graphitron-maven-plugin`, `graphitron-schema-transform`, `graphitron-servlet-parent`) are out of scope for AI work; do not modify them. `/docs/` is the source for the public documentation site at `graphitron.sikt.no`; see [Documentation site](#documentation-site) below.

Links elsewhere in this file point at deep references. **Don't fetch them up front;** open one only when its stated trigger applies to the task at hand. The rules and commands here are meant to be self-contained for routine work.

## What graphitron-rewrite is

The next-generation Graphitron generator: a Maven-based code generator that turns GraphQL schemas + jOOQ-generated database models into Java resolvers. Lives as a nested monorepo under `graphitron-rewrite/` with its own parent pom (`graphitron-rewrite-parent`, `10-SNAPSHOT`). Modules: `graphitron-javapoet`, `graphitron`, `graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-maven`, `graphitron-sakila-example`, `graphitron-lsp`, `roadmap-tool`. Developed by Sikt.

## Technology constraints

- **Java 25** for generator code (`<release>25</release>` in `graphitron-rewrite/pom.xml` and most child modules); **Java 17** for generated output (`graphitron-sakila-example` compiles with `<release>17</release>` to verify this). Generator implementation may use Java 25 features freely. Generated source files must target Java 17, consumers may still be on 17, and we control what syntax appears in those files. The parent pom's `requireJavaVersion` enforcer rule fails the build with a clear message when run on a JDK older than 25.
- **jOOQ 3.20.11**, **GraphQL-Java 25.0**, **JUnit 6.0.3 + AssertJ 3.27.7**, **PostgreSQL 42.7.10** (Testcontainers 2.0.4). Versions are pinned in `graphitron-rewrite/pom.xml` properties; don't add dependencies without checking that pom first.

## Environment (agent sessions)

Maven 3.9.11 at `/opt/maven`; Java 25 default. Pre-configured, no installation needed.

Verify before building:

```bash
mvn -version  # expect "Java version: 25.x"
```

If `mvn -version` reports Java 21, the session inherited a stale `JAVA_HOME`; see `.claude/web-environment.md` for the JDK 25 install + `JAVA_HOME` export. The parent pom's enforcer fails loudly with "Detected JDK … 21", so this is not silent.

**Claude Code Web** uses a web-sandbox setup (no Docker, native PostgreSQL via `-Plocal-db`). For PostgreSQL/Testcontainers errors there, see `.claude/web-environment.md`.

## Common commands

```bash
# Build the rewrite (always include -Plocal-db; see footgun below)
mvn -f graphitron-rewrite/pom.xml install -Plocal-db

# Regenerate graphitron-rewrite/roadmap/README.md from item front-matter
mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q
```

The full install is fast; prefer it over targeted `-pl` builds. If you do need `-pl`, always pair it with `-am` (also-make) or `-amd` (also-make-dependents); a bare `-pl` skips dependent modules and produces stale results.

## Building and testing

The `mvn install -Plocal-db` command above runs the full pipeline (build-fixtures → test → compile-spec → execute-spec). Reach for the deeper docs only when the task requires it:

- Adding/structuring tests, or unsure which tier (unit vs pipeline vs compilation vs execution) to put a test in: `graphitron-rewrite/docs/rewrite-design-principles.adoc`.
- Navigating the sealed variant hierarchy, classification taxonomy, or runtime extension points: `graphitron-rewrite/docs/README.adoc`.
- Diagnosing build/test failures specific to the web sandbox: `.claude/web-environment.md`.

**Footgun: catalog-jar clobber.** Omitting `-Plocal-db` silently empties the jOOQ catalog jar; pipeline tests then fail with `UnclassifiedType` / `NoSuchElement` / "table … could not be resolved" cascades. Recovery: rerun with `-Plocal-db`.

## Writing style

Do not use em dashes (—) in documentation. Use a comma, semicolon, colon, or restructure the sentence instead.

In `.adoc` files, use AsciiDoc table syntax (`[cols="..."]` + `|===` block) for tables; the markdown form (`| col | col |` header followed by a `|---|---|` separator) renders as paragraph text with literal pipes. The roadmap-tool `check-adoc-tables` step fails the build on any such row outside a structural block.

## Interaction style

Do not use the `AskUserQuestion` tool for open-ended design dialog or spec exploration. The structured multi-choice format constrains a conversation that should be free-flowing prose, and the user has explicitly flagged it as friction. Surface design forks inline: state the choice, give your recommendation and the reasoning, invite redirect. The user answers with a sentence, pushes back on the framing, or pivots, none of which fit the question/options shape. Reserve `AskUserQuestion` for genuinely bounded decisions where the option set is closed and prose framing would be wasteful (for example "discard the merge conflict or keep your local copy"); even there, prose first is usually fine.

## Editing large files

Prefer many small `Edit` calls over one large `Write` when trimming or rewriting a long file. Full-file writes on plans/docs of ~300+ lines tend to time out mid-response and leave the file half-written. Sequence of targeted `Edit` calls (remove section A, remove section B, replace section C) is both safer and faster.

## Documentation site

`/docs/` is a Maven module (`graphitron-docs`, packaging `pom`) that renders an AsciiDoc site to `docs/target/generated-docs/` and gets deployed to GitHub Pages by the `docs-build` / `docs-deploy` jobs in `.github/workflows/rewrite-build.yml` (trunk pushes only). The rewrite-internal docs under `/graphitron-rewrite/docs/` and the roadmap under `/graphitron-rewrite/roadmap/` also render into the site. PR preview builds run through `.github/workflows/preview-docs.yml` and upload `target/generated-docs/` as a workflow artifact. Doc changes ship through the same trunk-based flow as code; PR breakage on `.adoc` files fails CI.

To skip the AsciiDoctor render in a local build (saves ~10s of JRuby startup):

```bash
mvn -f graphitron-rewrite/pom.xml install -P!docs -Plocal-db
```

## Development Workflow

**Before editing under `graphitron-rewrite/`, confirm a roadmap item covers the change.** If none exists, file a Backlog item first via the `roadmap` skill; this applies even to "fix this error" / "make it accept X" framings. The default when unsure is to file a Backlog stub and ask whether to skip the gate, not to implement and ask later; the case for bypassing the pipeline is the user's to make.

Every change moves Backlog → Spec → Ready → In Progress → In Review → Done. Each item has its own file in `graphitron-rewrite/roadmap/` carrying YAML front-matter (`status:`, `bucket:`, etc.); `graphitron-rewrite/roadmap/README.md` is the rendered roll-up, regenerated by `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q`. Reviewer must be a different party than the author (for Spec → Ready) and the implementer (for In Review → Done). Any session can add Backlog items.

When transitioning a roadmap item between states, or unsure of the file conventions for a new item, consult `graphitron-rewrite/docs/workflow.adoc` for the full state table and canonical paths.

Consult the `principles-architect` subagent while drafting (Backlog → Spec, design forks in In Progress, or as a self-check before `srp`); it's read-only and produces no verdict. Reviewer-rule gates stay with `srp` / `reviewer-prompt`, which hand off to a *different* Claude Code session; identity is the `https://claude.ai/code/session_<id>` trailer on each commit, not git author.

## Git Workflow

Trunk-based development against `claude/graphitron-rewrite`.

**Standing permission.** This file authorizes fast-forwarding `claude/graphitron-rewrite` from your feature branch without asking; do not force-push trunk; do not push to other branches (e.g. `main`) without asking.

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
