# Graphitron Project - Claude Code Reference

## Project Overview
Graphitron is a code generation tool that creates source code by linking GraphQL schemas to underlying database models. It's developed by Sikt – the Norwegian Agency for Shared Services in Education and Research.

## Tech Stack
- **Language**: Java
- **Build Tool**: Maven
- **Framework**: GraphQL code generation
- **Database**: JOOQ for database interaction
- **Testing**: JUnit

## Project Structure
```
graphitron/
├── graphitron-codegen-parent/     # Java code generation from GraphQL schemas
│   ├── graphitron-java-codegen/   # Main code generator
│   └── graphitron-javapoet/       # Java code generation utilities
├── graphitron-common/             # Shared components
├── graphitron-example/            # Example implementation
├── graphitron-schema-transform/   # Schema transformation utilities
├── graphitron-maven-plugin/       # Maven plugin integration
└── graphitron-servlet-parent/     # Servlet support
```

## Key Components

### Validation System
- **ValidationHandler** (`graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/validation/ValidationHandler.java`): Central validation handler for error and warning messages
- **InvalidSchemaException**: Custom exception for schema validation errors
- **ProcessedDefinitionsValidator**: Validates processed GraphQL definitions

### Current Branch: GG-253-validation
Working on validation improvements with recent commits:
- Added formatting functionality for error messages
- Added documentation in ValidationHandler.java
- Introduced new InvalidSchemaException
- Removed unnecessary maven dependencies

## Build Commands
```bash
# Build the entire project
mvn clean install

# Run tests
mvn test

# Build without tests
mvn clean install -DskipTests

# Generate code (from graphitron-example directory)
mvn graphitron:generate-code
```

## Testing
- Test files located in `src/test/java` directories
- Approval testing used in graphitron-example with `.approved.json` files
- GraphQL test schemas in `src/test/resources`

## Important Files
- Main configuration: `pom.xml` files in each module
- GraphQL schemas: `*.graphqls` files
- Directives: `graphitron-common/src/main/resources/directives.graphqls`

## Development Guidelines
1. Follow existing code conventions and patterns
2. Use the ValidationHandler for error/warning reporting
3. Write tests for new functionality
4. Document GraphQL schema changes

## Useful Maven Commands for Development
```bash
# Clean all target directories
mvn clean

# Compile only
mvn compile

# Package the project
mvn package

# Install to local repository
mvn install

# Check for dependency updates
mvn versions:display-dependency-updates

# Generate dependency tree
mvn dependency:tree
```

## Common Tasks
- **Adding validation**: Use ValidationHandler.addErrorMessage() or addWarningMessage()
- **Schema changes**: Update .graphqls files and regenerate code
- **Testing GraphQL queries**: Add test cases in graphitron-example/src/test/resources

## Notes
- Project uses JOOQ for database record mapping
- Supports both Java records and JOOQ records
- Implements GraphQL Federation support
- Has node ID strategies for GraphQL node interface implementation