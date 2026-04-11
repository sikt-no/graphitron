# Plan: Dual-pipeline validate goal

## Context

`mvn graphitron:validate` currently uses only the legacy pipeline (`ProcessedSchema.validate()` via `ValidationHandler`). Error messages from this pipeline are plain strings with no candidate hints or source locations.

The new pipeline — `GraphitronSchemaBuilder` + `GraphitronSchemaValidator` — produces `ValidationError` records with source locations and candidate-hint messages (e.g. "available: film, actor, language"). It is not yet at full parity with the legacy pipeline.

The goal is to run **both** pipelines simultaneously during an interim migration period:
- **Legacy pipeline errors** → `[ERROR]`, build fails (unchanged behaviour)
- **New pipeline errors** → `[WARNING]`, build passes (non-breaking, but visible)
- When both pipelines flag the same broken schema, the richer new-pipeline message appears alongside the legacy error in the same failed build output — this is the "join" behaviour

When the new pipeline reaches parity, flip the switch: new pipeline errors → `[ERROR]`, remove legacy pipeline.

---

## How the two pipelines work

### Legacy pipeline
```
GeneratorConfig.loadValidatorProperties(this)
  → GraphQLGenerator.getProcessedSchema(true)    // parses schema files
  → processedSchema.validate()                   // ProcessedDefinitionsValidator
      → ValidationHandler.addErrorMessage(...)   // accumulates into static Set<String>
      → ValidationHandler.throwIfErrors()        // throws InvalidSchemaException
```

`InvalidSchemaException` bubbles up through `execute()`, is caught, and rethrown as `MojoExecutionException`.

### New pipeline
```
GeneratorConfig.generatorSchemaFiles()
  → SchemaReadingHelper.getTypeDefinitionRegistry(files)   // same schema files
  → GraphitronSchemaBuilder.build(registry)                // classification + JooqCatalog
  → new GraphitronSchemaValidator().validate(schema)       // returns List<ValidationError>
```

`ValidationError` has `message()` and `location()` (file, line, column). Already used in `GraphQLRewriteGenerator.generate()` with compiler-style formatting.

Both pipelines read from the same schema files via `GeneratorConfig` which is initialised by `GeneratorConfig.loadValidatorProperties(this)`.

---

## Implementation

### `ValidateMojo.java`

Replace the single-pipeline `execute()` with a dual-pipeline implementation:

```java
@Override
public void execute() throws MojoExecutionException {
    ValidationHandler.resetErrorMessages();
    ValidationHandler.resetWarningMessages();
    GeneratorConfig.loadValidatorProperties(this);

    // Step 1 — legacy pipeline (errors → fail build)
    Exception legacyFailure = null;
    try {
        GraphQLGenerator.getProcessedSchema(true).validate();
    } catch (Exception e) {
        legacyFailure = e;
    }

    // Step 2 — new pipeline (errors → warnings, non-blocking)
    try {
        var registry = getTypeDefinitionRegistry(GeneratorConfig.generatorSchemaFiles());
        var schema = GraphitronSchemaBuilder.build(registry);
        var errors = new GraphitronSchemaValidator().validate(schema);
        for (var error : errors) {
            var loc = error.location();
            if (loc != null) {
                getLog().warn(loc.getSourceName() + ":" + loc.getLine() + ":" + loc.getColumn()
                    + ": " + error.message());
            } else {
                getLog().warn(error.message());
            }
        }
        if (!errors.isEmpty()) {
            getLog().warn("New pipeline found " + errors.size() + " issue(s) — treated as warnings during migration");
        }
    } catch (Exception e) {
        getLog().debug("New pipeline validation skipped: " + e.getMessage());
    }

    // Step 3 — fail if legacy pipeline errored (join: new warnings already logged above)
    if (legacyFailure != null) {
        ValidationHandler.logWarnings();
        throw new MojoExecutionException("\n" + legacyFailure.getMessage(), legacyFailure);
    }

    getLog().info("Schema validation completed successfully");
}
```

New imports:
```java
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;
```

**File**: `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/ValidateMojo.java`

---

## Behaviour matrix

| Legacy result | New pipeline result | Build outcome | What user sees |
|---|---|---|---|
| Pass | No issues | ✅ Pass | "Schema validation completed successfully" |
| Pass | Has issues | ✅ Pass | `[WARNING]` lines for each new-pipeline issue |
| Fail | No issues | ❌ Fail | `[ERROR]` from legacy pipeline only |
| Fail | Has issues | ❌ Fail | `[WARNING]` lines first, then `[ERROR]` — "join" case |

---

## Exit criterion

When `GraphitronSchemaValidator` covers all constructs that `ProcessedDefinitionsValidator` covers, promote new pipeline errors to `[ERROR]` and remove the legacy pipeline call from `ValidateMojo`.
