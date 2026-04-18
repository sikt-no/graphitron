# Graphitron Project - Claude Code Reference

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Graphitron is a Maven-based code generation tool that creates Java source code by linking GraphQL schemas to underlying database models. It's developed by Sikt – the Norwegian Agency for Shared Services in Education and Research.

## Technology Stack
- **Language**: Java 21 (the generator itself) / Java 17 (generated output — see note below)
- **Build Tool**: Maven (multi-module project)
- **GraphQL**: GraphQL Java 24.2 with Apollo Federation support
- **Database**: jOOQ 3.19.18 for database access
- **Testing**: JUnit 5 with AssertJ assertions
- **Database**: PostgreSQL
- **Example Server**: Quarkus framework

> **Java version policy:** Graphitron is a code generator. The version used to *build* the generator
> is independent of the version of the code it *produces*. Generator implementation code may freely
> use Java 21 features. Generated source files must target Java 17 — consumers may be on Java 17,
> and Graphitron controls what syntax appears in those files, not what the consumer compiles with.

## Project Structure
```
graphitron/
├── graphitron-common/              # Shared utilities and exception handling
├── graphitron-codegen-parent/      # Java code generation from GraphQL schemas
│   ├── graphitron-java-codegen/   # Main code generator
│   └── graphitron-javapoet/       # Java code generation utilities
├── graphitron-maven-plugin/        # Maven plugin for code generation and schema transformation
├── graphitron-schema-transform/    # GraphQL schema transformation (feature flags, Federation, Relay)
├── graphitron-servlet-parent/      # Servlet implementations (javax and jakarta)
└── graphitron-example/             # Complete working example using Sakila database
```

## Documentation

### Conceptual Documentation (Start Here)
The `/docs` folder contains conceptual guides explaining Graphitron's design, philosophy, and how it works:
- **Documentation Guide**: [/docs/README.md](/docs/README.md) - **START HERE** - Navigation and reading order for all documentation
- **Vision and Goal**: [/docs/vision-and-goal.md](/docs/vision-and-goal.md) - What problem Graphitron solves and how it approaches the solution
- **Graphitron Principles**: [/docs/graphitron-principles.md](/docs/graphitron-principles.md) - Design philosophy and long-term thinking that shapes architectural decisions
- **Dependencies**: [/docs/dependencies.md](/docs/dependencies.md) - Why we chose jOOQ and GraphQL-Java as foundational dependencies
- **Code Generation Triggers**: [/docs/code-generation-triggers.md](/docs/code-generation-triggers.md) - Schema patterns → sealed type variants → what gets generated (rewrite pipeline)
- **Rewrite Model**: [/docs/rewrite-model.md](/docs/rewrite-model.md) - Visual Mermaid diagrams of the sealed type hierarchy (fields, types, support/composition types)
- **Security**: [/docs/security.md](/docs/security.md) - Security model and database-level enforcement approach

### Technical Reference Documentation
- **Main README**: [/README.md](/README.md) - Project overview and getting started
- **Java Codegen README**: [/graphitron-codegen-parent/graphitron-java-codegen/README.md](/graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference with detailed examples (1500+ lines)
- **Schema Transform README**: [/graphitron-schema-transform/README.md](/graphitron-schema-transform/README.md) - Schema transformation features (feature flags, Federation, Relay)
- **Common Module README**: [/graphitron-common/README.md](/graphitron-common/README.md) - Exception handling framework and shared utilities
- **Example README**: [/graphitron-example/README.md](/graphitron-example/README.md) - Sakila example implementation with quickstart guide
- **JavaPoet README**: [/graphitron-codegen-parent/graphitron-javapoet/README.md](/graphitron-codegen-parent/graphitron-javapoet/README.md) - About the JavaPoet fork

### Active Rewrite
- **Rewrite Roadmap**: [/docs/rewrite-roadmap.md](/docs/rewrite-roadmap.md) - Remaining generator work, design principles, and known gaps
- **Paginated Fields**: [/docs/paginated-fields.md](/docs/paginated-fields.md) - Remaining: document transform coexistence (builder fallback loses `defaultPageSize` when `@asConnection` is stripped)
- **Argument Resolution**: [/docs/argument-resolution.md](/docs/argument-resolution.md) - Unified argument classification, @condition support, lookup VALUES generation
- **Legacy PlatformId**: [/docs/legacy-platform-id.md](/docs/legacy-platform-id.md) - Classifying legacy `id: ID!` mutation input fields via record-level `getId`/`setId`


## Key Architecture

### Code Generation Process
1. GraphQL schemas are processed and potentially transformed
2. jOOQ generates Java classes from database schema
3. Graphitron maven plugin generates resolvers linking GraphQL types to jOOQ classes
4. Generated code integrates with servlet-based GraphQL servers

### Maven Plugin Goals
The graphitron-maven-plugin provides:
- **generate-code**: Generate Java code from GraphQL schemas
- **transform**: Transform schemas (Apollo Federation, Relay connections, feature flags)

## Environment Setup (Agent Sessions)

Maven 3.9.11 is at `/opt/maven`. Java 21 is the default JVM. Both are pre-configured — no installation needed.

**Claude Code Web:** The web environment has specific constraints (no Docker, no proxy). See [/docs/claude-code-web-environment.md](/docs/claude-code-web-environment.md) for the full setup and build instructions for that environment.

---

## Common Development Commands

```bash
mise r clean            # Clean all target directories
mise r build-all        # Full build with install
mise r start           # Start example server in dev mode (hot reload)
mise r sakila          # Start example database (Sakila)
mise r jooq            # Regenerate jOOQ classes from database
mise r rebuild <module> # Rebuild specific module while server is running

# For quick builds without tests/javadocs, use Maven profiles:
mvn clean install -Pquick
```

## Testing & Important Files
- **Testing**: JUnit 5 with AssertJ, approval tests, Quarkus test framework, TestContainers
- **Test locations**: `src/test/java` and `src/test/resources`
- **Rewrite generator tests**: Do NOT write code-string assertions that check generated method bodies (e.g. `assertThat(code).contains("TABLE.COL.eq(...)")`). These test the implementation, not the behavior, and break on every refactor. Instead:
  - **Unit tests** (`TypeClassGeneratorTest`, `TypeFetcherGeneratorTest`): verify structural properties only — method names, return types, parameter signatures, which methods are present/absent
  - **Pipeline tests** (`*PipelineTest`): verify SDL schema → generated TypeSpec structure through the full classification pipeline
  - **Compilation tests** (`graphitron-rewrite-test-spec` `mvn compile`): verify generated code compiles against real jOOQ classes — catches type errors, wrong packages, ambiguous overloads
  - **Execution tests** (`graphitron-rewrite-test-spec`): verify generated code produces correct results against a real database
- **Configuration**: `pom.xml` files in each module
- **GraphQL schemas**: `*.graphqls` files
- **Directives**: `graphitron-common/src/main/resources/directives.graphqls`

## Git Workflow

Trunk-based development against `claude/graphitron-rewrite`.

**Always sync with trunk before starting any work:**
```bash
git fetch origin claude/graphitron-rewrite
git rebase origin/claude/graphitron-rewrite
```

**Trunk (`claude/graphitron-rewrite`):**
- Never force-push. Fast-forward only.
- Push your branch's commits to trunk via a refspec fast-forward:
  ```bash
  git push origin <your-branch>:claude/graphitron-rewrite
  ```
- This only works cleanly if your branch is rebased on top of trunk.

**Your own feature/review branch:**
- Rebase on trunk frequently.
- Force-push your own branch freely after rebasing:
  ```bash
  git push --force-with-lease origin <your-branch>
  ```

**Typical session flow:**
1. Sync: `git fetch origin claude/graphitron-rewrite && git rebase origin/claude/graphitron-rewrite`
2. Do work, commit to your branch.
3. Fast-forward trunk: `git push origin <your-branch>:claude/graphitron-rewrite`
4. Sync your branch to match: `git fetch origin claude/graphitron-rewrite && git rebase origin/claude/graphitron-rewrite && git push --force-with-lease origin <your-branch>`

## Development Guidelines
1. **Always check existing code patterns** in neighboring files before writing new code
2. **Check pom.xml** before adding any dependencies - use what's already available
3. **Write tests** using JUnit 5 and AssertJ for all new functionality
4. **Follow the framework patterns** already established in the codebase

## Common Tasks
- **Schema changes**: Update .graphqls files → run `mvn graphitron:generate-code`
- **Database changes**: Update database → run `mise r jooq` to regenerate classes
- **Unit tests**: Add test cases in `src/test/java` using JUnit 5 and AssertJ
- **Development server**: Use `mise r start` for hot reload with Quarkus

## Integration Testing

### Approval Testing Framework
The example server uses approval testing for GraphQL queries:
- **Test queries**: `graphitron-example-server/src/test/resources/approval/queries/*.graphql`
- **Variables**: Optional `*.variables.json` for parameterized tests
- **Approved results**: `graphitron-example-server/src/test/resources/approval/approvals/*.approved.json`
- Tests automatically run all .graphql files found in queries directory

### Adding Integration Tests
1. Create a `.graphql` file in `queries/` directory
2. (Optional) Add `*.variables.json` for parameterized tests with multiple test cases
3. Run tests to generate approval file: `mvn test -pl :graphitron-example-server`
4. Review and stage the generated `.approved.json` file

### Example Schema (Sakila Database)
Located in `graphitron-example-spec/src/main/resources/graphql/schema.graphqls`
- Based on the Sakila sample database (DVD rental store)
- Main tables: Film, Customer, Payment, Inventory, Staff, Language
- Supports ordering via `@orderBy` directive with index specifications
- Use `@asConnection` for Relay-style pagination
- When adding new types: include `@table` directive and proper field mappings

## Key Features
- jOOQ for type-safe database access (supports Java records and jOOQ records)
- Apollo Federation and Relay support for GraphQL
- Schema transformation with feature flags
- Both javax and jakarta servlet compatibility