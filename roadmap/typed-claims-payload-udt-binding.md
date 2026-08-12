---
id: R639
title: "Type the claims payload against a catalog UDT instead of an opaque String"
status: Backlog
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# Type the claims payload against a catalog UDT instead of an opaque String

The claims payload is stringly typed end to end, and the string is not merely the Java carrier: it is also how the payload reaches the database, so a consumer whose connect routine takes a user-defined type has no supported way to call it. `GraphitronFacadeGenerator` hardcodes the owned factory's leading parameter as `ClassName.get(String.class)` (`GraphitronFacadeGenerator.java:115`), the instrumentation reads it back as `String` from the `graphQLContext` under `CLAIMS_KEY` (`GraphitronConnectionInstrumentationGenerator.java:172`), the emitted `SessionHook.connect` signature is `(Connection, String claims)`, and the function-hook implementation binds it with `cs.setString(1, claims)` into `{ call <fn>(?) }` (`ConnectionRuntimeClassGenerator.java:1951`-`:1960`). Nothing casts, so the parameter arrives typed `varchar` and a routine declared `connect(app.claims)` does not resolve against it; whether it works at all depends on the pgjdbc `stringtype` setting deciding to send the parameter as unspecified and letting Postgres infer the composite from its text representation. That is a fragile accident, not a contract, and it inverts the project's own posture everywhere else on this boundary: the tenant key's Java type is read off the catalog so a mis-typed map is a compile error, and contextArguments are typed from the consumer's declared parameter type so a wrong value is a compile error at the factory call site, while the one payload that carries identity, the security-load-bearing value, is an untyped blob that fails (if at all) inside the database at a cast. The `<variables>` sugar is not affected in the same way (it explicitly casts to `jsonb`, and a claims *document* is genuinely JSON there), so the gap is specific to the function-hook form, which is also the form the integrity gradient recommends for a privilege fence.

What a fix has to establish: how the UDT is named (a `<sessionState>` element naming the catalog type, versus deriving it from the routine signature), how graphitron resolves it (jOOQ's `Schema.getUDTs()` reaches generated `UDTRecord` classes, but `JooqCatalog` has no UDT resolution at all today, and R234 is the adjacent unbuilt work on UDT records as input backings), how the value binds (a jOOQ `UDTRecord` through jOOQ's own binding, versus `PGobject`/`setObject` with the type name over the current raw `CallableStatement`), and what the factory parameter becomes so that a consumer constructing the payload gets compile-time field checking rather than string assembly. Keeping the `String` form working is a hard constraint: it is the shipped signature, the compact-JWS integrity-fence pattern depends on passing a raw token, and the `<variables>` sugar and the dev-goal `<claims>` config both feed strings, so the typed form is an additional shape, not a replacement. The Oracle side has its own binding story (`STRUCT`), which this item should scope explicitly rather than assume.

