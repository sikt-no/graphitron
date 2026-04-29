---
id: R41
title: "@field(name:) on @service method args"
status: Backlog
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

2. **Remove: `@field(javaName:)`.** Drop `javaName` from the directive, the `ARG_JAVA_NAME` constant, the `javaNamePresent` boolean on `ColumnField` / `ColumnReferenceField`, the matching validator arms (`GraphitronSchemaValidator.java:327–342`), and the placeholder comment at line 237. graphql-java will then reject any leftover `@field(javaName: …)` at SDL parse time with its standard unknown-argument error; add a custom assertion in `GraphitronSchemaBuilder.assertDirective` (currently `assertDirective(ctx, DIR_FIELD, ARG_NAME, ARG_JAVA_NAME)` at line 592) so the error message points at this item ("@field(javaName:) was removed in R41 — use @field(name:) instead").

3. **Rewrite: `@field` docstring** to describe the actual contract: `name:` names the underlying-binding target, with the target axis determined by the directive site:
   - on a filter argument or output field of a `@table`-backed type → jOOQ column;
   - on an argument of an `@service` / `@tableMethod` field → Java method parameter.

## Plan sketch

1. **Carry both names on `MethodRef.Param.Typed`.** Today `Typed(String name, String typeName, ParamSource source)` uses `name` as both the Java identifier (for emitter call sites) and the GraphQL arg key (for `enrichArgExtractions`). With overrides the two diverge. Split into `javaName` (used by emitters; matches the reflected parameter name) and `graphqlArgName` on `ParamSource.Arg` (used by `enrichArgExtractions` and any future GraphQL-name-keyed lookup). For non-Arg sources, `graphqlArgName` is irrelevant.

2. **Build the override map in `FieldBuilder.resolveServiceField`.** It already collects `argNames` from `fieldDef.getArguments()`; alongside it, build `Map<String,String> javaNameToArgName` populated from `@field(name:)` on each arg, defaulting to identity when absent. Same in the `@tableMethod` resolution path.

3. **Plumb the map through `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod`.** In the per-parameter loop, resolve `pName` to its GraphQL arg name through the map, then run the existing `argNames.contains(...)` check on the resolved name; on match, store both names on `Param.Typed`. In `reflectTableMethod`, the override map must skip the `Table<?>` parameter (line 303) — that slot has no GraphQL counterpart by design.

4. **Update `enrichArgExtractions` (FieldBuilder.java:1374) to key off `graphqlArgName`** instead of `p.name()`. Without this, text-mapped enum extraction would silently miss for any overridden arg.

5. **Validation**, in `FieldBuilder.resolveServiceField` (before reflecting):
   - reject when the same Java name is targeted by two args (collision);
   - reject when `@field(name:)` on an arg references a Java parameter that doesn't exist on the service method (typo guard, requires the reflected parameter list, so the check piggybacks on a successful `reflectServiceMethod` and re-emits as an `UnclassifiedField`).

6. **Retire `javaName:` per Proposal §2.** Mechanical edits across `directives.graphqls`, `BuildContext.ARG_JAVA_NAME`, `ColumnField` / `ColumnReferenceField` records, the two validator arms, and `GraphitronSchemaBuilder.assertDirective`. Add the friendly error message there so users still on `javaName:` get pointed at R41.

7. **Context arguments out of scope.** `@service(contextArguments: [...])` keys by name into the runtime context; an override would have to propagate to the registry (`R31` `service-context-value-registry`'s territory). File a follow-up if a real case appears; do not extend this work.

## Tests

- `ServiceCatalogTest`: param `inputs` with `argNames={input}` and override `{inputs→input}` classifies as `ParamSource.Arg`, with `javaName="inputs"` and `graphqlArgName="input"` on `Param.Typed`.
- `ServiceCatalogTest`: identity case (no `@field(name:)` directive) still classifies via name equality, `graphqlArgName == javaName`. Regression guard for the default path.
- `ServiceCatalogTest`: collision (two args target the same Java name) → clear error.
- `ServiceCatalogTest`: `@field(name:)` references unknown Java param → error names the directive site and the available parameter names.
- `ServiceCatalogTest`: same suite of cases for `reflectTableMethod`, including: override on a non-`Table<?>` param works; override targeting the `Table<?>` slot rejects.
- `GraphitronSchemaBuilderTest`: SDL-driven happy path verifying the directive is read off the argument and the resulting service field classifies cleanly.
- `GraphitronSchemaBuilderTest`: SDL with `@field(javaName: …)` → clear error pointing at R41 (Proposal §2).
- Text-enum-mapping coverage: pipeline test with a `String`-typed Java param matched to a GraphQL enum arg via override; assert the generated text-map field is wired up (catches a regression in step 4).
- Pipeline-tier fixture: one new schema fixture under existing `@service` mutation coverage that uses the override end-to-end.

## Notes

- The argument-context resolution (column on `@table`-backed filter args, parameter on `@service` / `@tableMethod` args) holds because `FieldBuilder.classifyArgument` only routes to column binding for fields without `@service` / `@tableMethod` on the implicit-query path; the contexts don't share an argument site. Reference for reviewers: `FieldBuilder.classifyArgument` and the column-binding entry at line 827.
- Co-considered with `R1` (`BatchKey` lifter directive): both extend the binding language for service method parameters but are orthogonal — R41 is about *naming*, R1 is about *DTO-to-key conversion*.
