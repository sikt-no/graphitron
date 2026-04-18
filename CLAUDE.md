# Graphitron Project - Claude Code Reference

Rules and constraints for working in this repo. Background and architecture live in [`docs/README.md`](docs/README.md).

## What Graphitron is

Maven-based code generator that turns GraphQL schemas + jOOQ-generated database models into Java resolvers. Developed by Sikt.

## Technology constraints

- **Java 21** for generator code; **Java 17** for generated output. Generator implementation may use Java 21 features freely. Generated source files must target Java 17 — consumers may still be on 17, and we control what syntax appears in those files.
- **jOOQ 3.19.18**, **GraphQL-Java 24.2** (with Apollo Federation), **JUnit 5 + AssertJ**, **PostgreSQL**. Don't add dependencies without checking `pom.xml` first.

## Environment (agent sessions)

Maven 3.9.11 at `/opt/maven`; Java 21 default. Pre-configured — no installation needed.

**Claude Code Web:** see [`docs/claude-code-web-environment.md`](docs/claude-code-web-environment.md) for the web-sandbox setup (no Docker, native PostgreSQL via `-Plocal-db`).

## Common commands

```bash
mise r build-all             # Full build + install
mise r start                 # Start example server in dev mode
mise r sakila                # Start Sakila example DB
mise r jooq                  # Regenerate jOOQ classes
mvn clean install -Pquick    # Fast build, skips tests + javadocs
```

## Rewrite generator tests — rules

Do NOT write code-string assertions that check generated method bodies (e.g. `assertThat(code).contains("TABLE.COL.eq(...)")`). They test implementation, not behaviour, and break on every refactor. Use instead:

- **Unit tests** (`*GeneratorTest`): structural properties only — method names, return types, parameter signatures, which methods exist.
- **Pipeline tests** (`*PipelineTest`): SDL → generated `TypeSpec` through the full classifier.
- **Compilation tests** (`graphitron-rewrite-test-spec` compile): catch type errors and wrong packages against real jOOQ classes.
- **Execution tests** (`graphitron-rewrite-test-spec`): generated code against a real database.

## Development Workflow

Every change moves Unplanned → Draft → Approved → In Progress → Pending Review → Done, tracked per item in `docs/rewrite-roadmap.md` with inline `[Status]` markers. Plans live at `docs/plan-<slug>.md` and carry a `> **Status:** ...` front-matter that mirrors the roadmap. Reviewer must be a different party than the author (for Draft → Approved) and the implementer (for Pending Review → Done). Any session can add `[Unplanned]` items to the roadmap.

Full spec — state table, file conventions, canonical path: [`docs/workflow.md`](docs/workflow.md). Read it once; it's short.

## Git Workflow

Trunk-based development against `claude/graphitron-rewrite`.

**Sync before starting any work:**
```bash
git fetch origin claude/graphitron-rewrite
git rebase origin/claude/graphitron-rewrite
```

**Trunk is fast-forward only.** Never force-push it. After every push to your own branch, immediately fast-forward trunk:
```bash
git push origin <your-branch>:claude/graphitron-rewrite
```
A push to your branch not followed by a trunk fast-forward is unfinished.

**Your own branch:** rebase on trunk frequently, force-push freely after rebasing (`git push --force-with-lease origin <your-branch>`).

**Session flow:** sync → work + commit → push own branch → fast-forward trunk. If trunk moved while you were working, rebase and repeat.
