# Graphitron Project - Claude Code Reference

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
Graphitron is a Maven-based code generation tool that creates Java source code by linking GraphQL schemas to underlying database models. It's developed by Sikt – the Norwegian Agency for Shared Services in Education and Research.

## Technology Stack
- **Language**: Java 17 with Jakarta EE 
- **Build Tool**: Maven (multi-module project)
- **GraphQL**: GraphQL Java 24.2 with Apollo Federation support
- **Database**: jOOQ 3.19.18 for database access
- **Testing**: JUnit 5 with AssertJ assertions
- **Database**: PostgreSQL
- **Example Server**: Quarkus framework

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
- **Vision and Goal**: [/docs/VISION-AND-GOAL.md](/docs/VISION-AND-GOAL.md) - What problem Graphitron solves and how it approaches the solution
- **Graphitron Principles**: [/docs/GRAPHITRON-PRINCIPLES.md](/docs/GRAPHITRON-PRINCIPLES.md) - Design philosophy and long-term thinking that shapes architectural decisions
- **Dependencies**: [/docs/DEPENDENCIES.md](/docs/DEPENDENCIES.md) - Why we chose jOOQ and GraphQL-Java as foundational dependencies
- **Code Generation Triggers**: [/docs/CODE-GENERATION-TRIGGERS.md](/docs/CODE-GENERATION-TRIGGERS.md) - Schema patterns → sealed type variants → what gets generated (rewrite pipeline)
- **Security**: [/docs/SECURITY.md](/docs/SECURITY.md) - Security model and database-level enforcement approach

### Technical Reference Documentation
- **Main README**: [/README.md](/README.md) - Project overview and getting started
- **Java Codegen README**: [/graphitron-codegen-parent/graphitron-java-codegen/README.md](/graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference with detailed examples (1500+ lines)
- **Schema Transform README**: [/graphitron-schema-transform/README.md](/graphitron-schema-transform/README.md) - Schema transformation features (feature flags, Federation, Relay)
- **Common Module README**: [/graphitron-common/README.md](/graphitron-common/README.md) - Exception handling framework and shared utilities
- **Example README**: [/graphitron-example/README.md](/graphitron-example/README.md) - Sakila example implementation with quickstart guide
- **JavaPoet README**: [/graphitron-codegen-parent/graphitron-javapoet/README.md](/graphitron-codegen-parent/graphitron-javapoet/README.md) - About the JavaPoet fork

### Active Rewrite
- **Rewrite Roadmap**: [/docs/REWRITE-ROADMAP.md](/docs/REWRITE-ROADMAP.md) - Phase 2/3 plan for retiring ProcessedSchema and improving error messages


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

### Maven

Maven 3.9.11 is at `/opt/maven`. Java 21 is the default JVM. Both are pre-configured — no installation needed.

`JAVA_TOOL_OPTIONS` is pre-set with proxy settings (`http.proxyHost`, `http.proxyPort`, `http.proxyUser`, `http.proxyPassword`, etc.), so `java` commands pick up the proxy automatically.

**Proxy setup (required before first build):**

`~/.m2/settings.xml` must exist with the proxy configuration. If it's missing, create it by extracting credentials from the `http_proxy` environment variable:

```bash
PROXY_USER=$(echo "$http_proxy" | sed 's|http://||' | sed 's|@.*||' | sed 's|:.*||')
PROXY_PASS=$(echo "$http_proxy" | sed 's|http://||' | sed 's|@.*||' | sed 's|^[^:]*:||')

cat > ~/.m2/settings.xml << XMLEOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <proxies>
    <proxy>
      <id>https-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>21.0.0.129</host>
      <port>15004</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>http-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>21.0.0.129</host>
      <port>15004</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
XMLEOF
```

**Resolver transport:** Maven's default `native` transport gets intermittent 407 errors behind this proxy. Use the `wagon` transport instead:

```bash
mkdir -p .mvn
echo "-Dmaven.resolver.transport=wagon" > .mvn/maven.config
```

Or pass it on each invocation: `mvn -Dmaven.resolver.transport=wagon ...`

Run tests normally:

```bash
mvn test -pl :graphitron-java-codegen
mvn install                          # full build, all modules
```

### Docker (TestContainers)

`service docker start` fails in this environment due to ulimit restrictions. Start the daemon directly instead:

```bash
dockerd --host=unix:///var/run/docker.sock > /tmp/dockerd.log 2>&1 &
sleep 3   # wait for daemon to finish initialising
docker ps  # verify it's running
```

Docker must be running before building `graphitron-java-codegen` (jOOQ code generation uses TestContainers to start a Postgres database) and before any integration test that uses TestContainers (e.g. the Sakila database integration tests).

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
  - **Unit tests** (`TypeClassGeneratorTest`, `TypeFieldsGeneratorTest`): verify structural properties only — method names, return types, parameter signatures, which methods are present/absent
  - **Pipeline tests** (`*PipelineTest`): verify SDL schema → generated TypeSpec structure through the full classification pipeline
  - **Compilation tests** (`graphitron-rewrite-test-spec` `mvn compile`): verify generated code compiles against real jOOQ classes — catches type errors, wrong packages, ambiguous overloads
  - **Execution tests** (`graphitron-rewrite-test-spec`): verify generated code produces correct results against a real database
- **Configuration**: `pom.xml` files in each module
- **GraphQL schemas**: `*.graphqls` files
- **Directives**: `graphitron-common/src/main/resources/directives.graphqls`

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