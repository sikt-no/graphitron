---
id: R53
title: "ExternalCodeReference.argMapping for Java-param binding"
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
---

# `ExternalCodeReference.argMapping` for Java-param binding

## Problem

`@field(name:)` on a GraphQL argument or input field has two distinct semantic
roles depending on what the slot binds to:

- **Table binding** — the column on the underlying jOOQ table to bind for the
  auto-equality predicate (or the `@reference` path).
- **Method binding** — the Java parameter on a developer method (`@service`,
  `@tableMethod`, `@condition`) that the slot's value should be passed to.

R41 introduced the method-binding interpretation on `@service` / `@tableMethod`
arg sites. Single-bound sites (root `@service` / `@tableMethod` args, with no
table-binding context) work cleanly under R41: the directive site disambiguates.
But an arg or input field can be bound to *both* a table and a method
simultaneously: filter args carrying `@condition`, `@table` input fields
carrying `@condition`, or any future construct that combines the two. On these
dual-bound sites, a single `@field(name:)` directive cannot carry both
overrides. R41 defers the cross-axis case (the `@condition` arms route through
`identityFor*` factories that ignore overrides), so the method-side override is
unavailable at exactly the sites where the column-side meaning is locked.

The architectural mismatch is that the override is colocated with the *slot*
(the arg / input field) when it is fundamentally a property of the *method*
(which Java parameter receives this slot's value). The slot doesn't know about
the method's parameter names; the method does. Pulling the override onto the
method reference resolves the dual-axis problem structurally, not by overload.

## Proposal

Extend `ExternalCodeReference` with a string-DSL field naming the GraphQL→Java
parameter binding, modelled after `@experimental_constructType.selection`:

```graphql
input ExternalCodeReference {
    name: String @deprecated(reason: "...")
    className: String
    method: String
    """
    Maps Java method parameters to GraphQL argument names. Each entry is
    `javaParam: graphqlArg`; multiple entries are comma-separated. Unmentioned
    parameters bind to a GraphQL arg of the same name (identity).

    Examples:
    - `argMapping: "inputs: input"` — Java parameter `inputs` receives GraphQL arg `input`.
    - `argMapping: "city: cityNames, country: countryId"` — two overrides.
    """
    argMapping: String
}
```

Reading: target on the left, source on the right (mirrors
`@experimental_constructType` and the existing internal
`Map<javaTarget, graphqlSource>` shape).

### Semantic separation

| Slot bound to                  | `@field(name:)` axis | `argMapping` axis |
|--------------------------------|----------------------|-------------------|
| table only (filter arg, plain input field) | column           | n/a               |
| method only (root @service / @tableMethod arg) | n/a              | Java param        |
| both (filter arg + @condition; @table input field + @condition) | column   | Java param        |

`argMapping` is the single mechanism for Java-parameter override on every
method-backed call: `@service`, `@tableMethod`, every `@condition` site
(field-level, argument-level, input-field-level, path-step). `@field(name:)`
reverts to its pre-R41 meaning: column on table-backed sites, db-string
mapping on `ENUM_VALUE`. R41's per-arg `@field(name:)` override semantic is
retired in the same change.

### Validation

- **Parse-time on the mini-DSL.**
  - Malformed entries (missing `:`, empty key/value): rejected with the entry
    text in the message.
  - Duplicate Java target: rejected with the target name (R41's collision case
    moves here, surfaced earlier).
- **Pre-reflection on the field.**
  - Override value names a GraphQL arg that doesn't exist on the field:
    rejected with the arg name and the available list. (R41 surfaces this only
    indirectly via the post-reflection typo guard; the new path catches it
    sooner.)
- **Post-reflection (unchanged from R41).**
  - Override key names a Java parameter that doesn't exist on the resolved
    method: rejected with the directive site, the Java-target, and the
    available parameter list.
  - On `@tableMethod`: override key targeting the `Table<?>` slot: rejected.
- **Validator backstop for the retired R41 syntax.**
  - `@field(name:)` on a `@service` / `@tableMethod` argument site is rejected
    with a migration message naming `argMapping`. (These args have no
    column-binding axis, so the directive there is no-op after the bundle —
    rejecting beats silent breakage for any schema author who used R41's
    short-lived per-arg form.)

## Plan sketch

1. **SDL: extend `ExternalCodeReference`.** Add the optional `argMapping:
   String` field with the docstring above. No directive-fanout change is
   required; the new field threads through every directive that already takes
   `ExternalCodeReference!`, and the field is unused on `@externalField`,
   `@enum`, and `@record` (no arg-bound parameters); validator polish later if
   needed.

2. **Parser.** Add `ArgBindingMap.parseArgMapping(String) -> ParsedArgMapping`
   (record carrying either a validated `Map<String, String>` or an error
   message). Strict format: comma-separated `javaParam: graphqlArg` entries.
   Reject malformed entries and duplicate Java targets.

3. **Carry the parsed mapping on `ExternalRef`.** Today
   `FieldBuilder.ExternalRef` is `(className, methodName, lookupError)`. Add
   `argMapping: Map<String, String>` (or push the parse error onto
   `lookupError` when malformed).
   `FieldBuilder.parseExternalRef` reads `argMapping` from the directive map,
   parses it, and either populates the mapping or surfaces the parse error.
   `BuildContext.resolveConditionRef` and `BuildContext.buildInputFieldCondition`
   do the same for their non-`parseExternalRef` paths.

4. **Refactor `ArgBindingMap`.** Replace `forField(GraphQLFieldDefinition)`,
   `identityForField(...)`, `identityForSingleArg(...)`, and
   `identityForSingleInputField(...)` with a single factory:

   ```java
   static Result of(Set<String> graphqlArgNames, Map<String, String> overrides)
   ```

   It builds identity for every name in `graphqlArgNames`, applies overrides,
   and returns `Ok` or `Collision`. The new factory is axis-agnostic — every
   call site funnels through it. The collision case now covers two distinct
   shapes the old code handled separately (parser duplicate-key vs. forField
   value-collision); both reduce to "two Java targets bind to the same GraphQL
   slot".

5. **Wire through every reflect call site.** `FieldBuilder.resolveServiceField`,
   the two `@tableMethod` arms (root + child), `buildArgCondition`,
   `buildFieldCondition`, `BuildContext.resolveConditionRef`, and
   `BuildContext.buildInputFieldCondition` each pull the argMapping from the
   `ExternalRef` (or condition-map equivalent) and pass it to
   `ArgBindingMap.of`. Stop reading `@field(name:)` per-arg in `ArgBindingMap`.

6. **Validator: reject R41's retired form.** `@field(name:)` on a
   `@service` / `@tableMethod` argument site is now no-op; surface a clear
   "use ExternalCodeReference.argMapping instead" error rather than silently
   ignoring it. Place the rejection in `GraphitronSchemaValidator` next to the
   existing schema-shape checks; the test belongs in
   `GraphitronSchemaBuilderTest` alongside the other invalid-schema cases.

7. **Migrate R41 fixtures.**
   - `graphitron-rewrite/graphitron-test/src/main/resources/graphql/schema.graphqls`:
     `filmsByServiceRenamed` switches from
     `(ids: [Int!]! @field(name: "filmIds"))` to
     `@service(service: {className: "...", method: "filmsByServiceRenamed", argMapping: "filmIds: ids"})`.
     `GraphQLQueryTest.queryServiceTable_filmsByServiceRenamed_*` adjusts (its
     execution assertions don't change; only the schema annotation does).

8. **Migrate R41 unit tests.**
   - `ServiceCatalogTest`: the override / typo / collision / table-slot-rejection
     cases keep their structure but pass the override map directly to the new
     `ArgBindingMap.of` rather than going through the per-arg `forField`
     reader.
   - `GraphitronSchemaBuilderTest`:
     `SERVICE_MUTATION_FIELD_NAME_OVERRIDE_TEXT_ENUM`,
     `SERVICE_MUTATION_FIELD_NAME_OVERRIDE_ON_ARG`,
     `SERVICE_FIELD_NAME_OVERRIDE_COLLISION`, and
     `SERVICE_FIELD_NAME_OVERRIDE_TYPO_GUARD` rewrite their SDL fragments to
     use `argMapping` on the `@service` directive. Behaviour assertions stay.

9. **Add cross-axis coverage.** New test cases that R41's design couldn't
   express:
   - `@condition` arg-level: `argMapping` overrides while the same arg's
     `@field(name:)` independently drives column binding for auto-equality.
     Both axes coexist on the same arg.
   - `@condition` field-level: `argMapping` on a field-level `@condition`
     binds one Java parameter to a differently-named GraphQL arg.
   - `@condition` input-field-level: `argMapping` on an input-field
     `@condition` binds the input-field's value to a differently-named Java
     parameter.
   - Pipeline: one schema fixture exercising the dual-bound case end-to-end
     (table input field + `@condition` with `argMapping`).
   - Parser-error cases: malformed entry, duplicate Java target, override
     value naming a non-existent GraphQL arg.

10. **Update R41 roadmap doc + directives.graphqls docstring.** R41's spec
    describes the per-arg `@field(name:)` override that this item retracts;
    add a "Superseded by R52" note rather than rewriting history. The
    `@field(name:)` docstring in `directives.graphqls` reverts to its pre-R41
    form (column / db-string axes only).

## Tests

Coverage by tier:

- **Unit (`ArgBindingMap` / parser)**: malformed entries, duplicate Java
  target, override value not in argument set, identity-when-empty.
- **Unit (`ServiceCatalogTest`)**: override binds Java param to differently-named
  GraphQL arg (`@service` and `@tableMethod`); typo guard fires when override
  key targets a non-existent Java parameter; `@tableMethod` Table<?>-slot
  rejection.
- **`GraphitronSchemaBuilderTest`**: end-to-end SDL → classified field with
  `argMapping`; cross-axis case (`@field(name:)` column + `argMapping` Java
  param on the same dual-bound arg); validator rejects `@field(name:)` on
  `@service` arg with migration message.
- **Pipeline**: one fixture with a dual-bound input field + `@condition`
  using `argMapping`.
- **`graphitron-test` execute**: rewritten `filmsByServiceRenamed` continues
  to round-trip a real query through PostgreSQL.

## Notes

- **Direction of the mini-DSL.** `javaParam: graphqlArg` matches
  `@experimental_constructType.selection` and the internal
  `Map<javaTarget, graphqlSource>` shape. A schema author reading
  `argMapping: "inputs: input"` reads "the Java parameter `inputs` receives
  the value of the GraphQL argument `input`."
- **No additive period.** The R41 per-arg form is replaced wholesale rather
  than landing additively. Migration is a mechanical move from
  `@field(name:)` on the arg to `argMapping` on the directive's
  `ExternalCodeReference`, and the validator surfaces the obsolete form with
  a hint.
- **Co-considered with R1 (`BatchKey` lifter directive)**: orthogonal —
  R1 covers DTO-to-key conversion, R52 covers parameter naming. Both extend
  the binding language for service method parameters from different angles.
- **Parser style.** String mini-DSL chosen over a structured GraphQL list
  for symmetry with `@experimental_constructType`. The cost is slightly more
  parsing logic in graphitron; the benefit is a single cell on the directive
  rather than a list-of-records construction at every call site.
