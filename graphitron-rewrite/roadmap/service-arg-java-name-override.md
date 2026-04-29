---
id: R41
title: "@field(name:) on @service method args"
status: In Progress
bucket: architecture
priority: 5
theme: service
depends-on: []
---

# `@field(name:)` on `@service` method args

## Problem

`ServiceCatalog.reflectServiceMethod` matches Java parameters to GraphQL arguments by exact name. Today, when names diverge (a schema author preferring singular `input` and a service author preferring plural `inputs` for a `List<…>`, say), the only options are renaming one side or the other; the new error message landed in commit `7666282` ("parameter 'inputs' does not match any GraphQL argument or context key — available: [input]; …") names the cause directly but offers no escape hatch.

The `@field` directive is the natural place to add one: `@field(name:)` already reads as "the underlying name this GraphQL slot binds to" in the column-mapping arm, and it's already declared on `ARGUMENT_DEFINITION` (`directives.graphqls:23`).

While we're there: `@field(javaName:)` is dead weight. It's read into a boolean (`javaNamePresent`) carried on `ColumnField` / `ColumnReferenceField` exclusively so two validator branches can reject record-output usage (`GraphitronSchemaValidator.java:327–342`); the *value* is never consumed by codegen. The "graphitron-specific constraints (e.g. javaName deprecation) will be added here" placeholder at `GraphitronSchemaValidator.java:237` already telegraphs the planned removal. Bundling the cleanup with this work avoids two passes over `directives.graphqls` and the validator.

The directive's current docstring (`directives.graphqls:15–22`) is also stale: it scopes `@field` to "jOOQ table record fields" and "Java records", which never matched the existing `ARGUMENT_DEFINITION` use, and won't match the new one either.

## Proposal

Three coupled edits to `@field`:

1. **Add: `@field(name:)` on a service-method argument names the Java parameter to bind to.**

   ```graphql
   opprettRegelverksamling(
     input: [OpprettRegelverksamlingInput!]! @field(name: "inputs")
   ): OpprettRegelverksamlingPayload!
     @service(service: { className: "…RegelverksamlingMutations", method: "opprettRegelverksamling" })
   ```

   Reads as: bind the GraphQL `input` arg to the Java `inputs` parameter. Absent the directive, the existing name-equality rule applies.

2. **Remove: `@field(javaName:)`.** Drop `javaName` from the directive declaration, the `ARG_JAVA_NAME` constant, the corresponding arg in `assertDirective(ctx, DIR_FIELD, ARG_NAME, ARG_JAVA_NAME)` (`GraphitronSchemaBuilder.java:592`), the `javaNamePresent` boolean on `ColumnField` / `ColumnReferenceField`, the matching validator arms (`GraphitronSchemaValidator.java:327–342`), and the placeholder comment at line 237 (which already telegraphs this work). graphql-java rejects any leftover `@field(javaName: …)` use at SDL parse time with its standard "Directive @field has unknown argument 'javaName'" error — sufficient signal; this item explicitly does not bundle a custom migration message.

3. **Update: `@field` docstring.** Drop the stale "jOOQ table record fields" / "exclusively for Java records" framing and the `javaName:` line. State the actual contract for `name:` in one paragraph: "names the underlying-binding target; the axis is determined by the directive site. On `ARGUMENT_DEFINITION` of an `@service` / `@tableMethod` field, the target is a Java method parameter (this item). On other sites the existing axes apply." Out of scope: rewriting the docstring to enumerate every existing axis (column on `@table`-backed output / filter args / input fields, db-string / Java-enum-constant on `ENUM_VALUE`); the existing axes work today and a full audit is its own piece of work.

## Plan sketch

1. **Carry both names on `MethodRef.Param.Typed`.** Today `Typed(String name, String typeName, ParamSource source)` uses `name` as both the Java identifier (for emitter call sites) and the GraphQL arg key (for `enrichArgExtractions`). With overrides the two diverge. Split into `javaName` (used by emitters; matches the reflected parameter name) and `graphqlArgName` on `ParamSource.Arg` (used by `enrichArgExtractions` and any future GraphQL-name-keyed lookup). For non-Arg sources, `graphqlArgName` is irrelevant.

2. **Build the override map in `FieldBuilder.resolveServiceField`.** It already collects `argNames` from `fieldDef.getArguments()`; alongside it, build `Map<String,String> javaNameToArgName` populated from `@field(name:)` on each arg, defaulting to identity when absent. Same in the `@tableMethod` resolution path.

3. **Plumb the map through `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod`.** In the per-parameter loop, resolve `pName` to its GraphQL arg name through the map, then run the existing `argNames.contains(...)` check on the resolved name; on match, store both names on `Param.Typed`. In `reflectTableMethod`, the override map must skip the `Table<?>` parameter (line 303) — that slot has no GraphQL counterpart by design.

4. **Update `enrichArgExtractions` (FieldBuilder.java:1374) to key off `graphqlArgName`** instead of `p.name()`. Without this, text-mapped enum extraction would silently miss for any overridden arg.

5. **Validation**, in two halves around the reflection call:
   - **Pre-reflection** (purely from the override map, in `FieldBuilder.resolveServiceField` before `reflectServiceMethod`): reject when two args target the same Java name (collision); detectable from value-duplicate in `javaNameToArgName`.
   - **Post-reflection** (uses the reflected parameter list, inside `reflectServiceMethod`): reject when an entry in `javaNameToArgName` references a Java parameter that doesn't exist on the resolved method (typo guard). Surfaces as a `ServiceReflectionResult` failure that `FieldBuilder` then re-emits as an `UnclassifiedField`, matching the existing failure shape.

6. **Retire `javaName:` per Proposal §2.** Mechanical edits across `directives.graphqls` (drop the arg + the stale docstring), `BuildContext.ARG_JAVA_NAME` (delete), `GraphitronSchemaBuilder.java:592` (drop the third arg from `assertDirective`), `ColumnField` / `ColumnReferenceField` (drop the `javaNamePresent` field), `GraphitronSchemaValidator.java:327–342` (delete both branches), and `GraphitronSchemaValidator.java:237` (drop the placeholder comment). No friendly migration message — graphql-java's parse-time error per Proposal §2 is the user-visible signal.

7. **Context arguments out of scope.** `@service(contextArguments: [...])` keys by name into the runtime context; an override would have to propagate to the registry (`R31` `service-context-value-registry`'s territory). File a follow-up if a real case appears; do not extend this work.

## Tests

- `ServiceCatalogTest`: param `inputs` with `argNames={input}` and override `{inputs→input}` classifies as `ParamSource.Arg`, with `javaName="inputs"` and `graphqlArgName="input"` on `Param.Typed`.
- `ServiceCatalogTest`: identity case (no `@field(name:)` directive) still classifies via name equality, `graphqlArgName == javaName`. Regression guard for the default path.
- `ServiceCatalogTest`: collision (two args target the same Java name) → clear error.
- `ServiceCatalogTest`: `@field(name:)` references unknown Java param → error names the directive site and the available parameter names.
- `ServiceCatalogTest`: same suite of cases for `reflectTableMethod`, including: override on a non-`Table<?>` param works; override targeting the `Table<?>` slot rejects.
- `GraphitronSchemaBuilderTest`: SDL-driven happy path verifying the directive is read off the argument and the resulting service field classifies cleanly.
- Schema-construction failure test: SDL with `@field(javaName: …)` fails graphql-java's directive validation with the standard "unknown argument 'javaName'" error (per Proposal §2 — no custom message). Place wherever the existing schema-parse-failure tests live.
- Text-enum-mapping coverage: pipeline test with a `String`-typed Java param matched to a GraphQL enum arg via override; assert the generated text-map field is wired up (catches a regression in step 4).
- Pipeline-tier fixture: one new schema fixture under existing `@service` mutation coverage that uses the override end-to-end.

## Notes

- The argument-context resolution (column on `@table`-backed filter args, parameter on `@service` / `@tableMethod` args) holds because `FieldBuilder.classifyArgument` only routes to column binding for fields without `@service` / `@tableMethod` on the implicit-query path; the contexts don't share an argument site. Reference for reviewers: `FieldBuilder.classifyArgument` and the column-binding entry at line 827.
- Behaviour change for any existing schema that already wrote `@field(name:)` on an `@service` / `@tableMethod` arg: the directive is silently ignored today (the column-binding path is unreachable for these args), and after this change it becomes a binding override. Any pre-existing site is either a no-op that becomes meaningful (intended) or one that now resolves to a parameter that doesn't exist (caught by the post-reflection typo guard from Plan §5). No silent semantic drift.
- Co-considered with `R1` (`BatchKey` lifter directive): both extend the binding language for service method parameters but are orthogonal — R41 is about *naming*, R1 is about *DTO-to-key conversion*.
