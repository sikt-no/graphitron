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

### Core Guides
- **Graphitron Principles**: [/docs/GRAPHITRON-PRINCIPLES.md](/docs/GRAPHITRON-PRINCIPLES.md) - Design philosophy and long-term thinking
- **Vision and Goals**: [/docs/VISION-AND-GOAL.md](/docs/VISION-AND-GOAL.md) - What Graphitron is and isn't
- **Dependencies**: [/docs/DEPENDENCIES.md](/docs/DEPENDENCIES.md) - Dependency management principles
- **Security**: [/docs/SECURITY.md](/docs/SECURITY.md) - Security policy

### Technical Documentation
- **Main README**: [/README.md](/README.md) - Project overview and getting started
- **Example README**: [/graphitron-example/README.md](/graphitron-example/README.md) - Sakila example implementation
- **Schema Transform README**: [/graphitron-schema-transform/README.md](/graphitron-schema-transform/README.md) - Schema transformation features
- **Java Codegen README**: [/graphitron-codegen-parent/graphitron-java-codegen/README.md](/graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- **JavaPoet README**: [/graphitron-codegen-parent/graphitron-javapoet/README.md](/graphitron-codegen-parent/graphitron-javapoet/README.md)

### Architecture & Future Direction (GEPs)
- **GEP-001.1: Mental Model**: [/geps/GEP-001.1 Graphitron Mental Model.md](/geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- **GEP-002: Parse-and-Validate**: [/geps/GEP-002 Parse-and-Validate Architecture.md](/geps/GEP-002%20Parse-and-Validate%20Architecture.md)
- **GEP-003: Selection-Set-Driven Query Generation**: [/geps/GEP-003 Selection-Set-Driven Query Generation.md](/geps/GEP-003%20Selection-Set-Driven%20Query%20Generation.md)
- **What Graphitron Generates**: [/geps/WHAT-GRAPHITRON-GENERATES.md](/geps/WHAT-GRAPHITRON-GENERATES.md) - Vocabulary and taxonomy of generated code
- **Code Generation Triggers**: [/geps/CODE-GENERATION-TRIGGERS.md](/geps/CODE-GENERATION-TRIGGERS.md) - Reference for what schema patterns trigger what
- **Code Generation Patterns**: [/geps/CODE-GENERATION-PATTERNS.md](/geps/CODE-GENERATION-PATTERNS.md) - Patterns and best practices
- **Complete Taxonomy**: [/geps/COMPLETE-TAXONOMY.md](/geps/COMPLETE-TAXONOMY.md) - Comprehensive analysis from test cases

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