---
id: R214
title: "Infer argMapping when the @condition / @service Java signature is unambiguous"
status: Backlog
bucket: dx
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Infer argMapping when the @condition / @service Java signature is unambiguous

`ServiceCatalog.reflectTableMethod` binds each Java parameter to a GraphQL argument by *name*: a parameter matches a same-named field argument, a same-named declared context key, classifies as a SOURCES shape, or is rejected with the long "either rename the Java parameter to match … or bind explicitly via the @service directive's argMapping field" diagnostic. Argument-level `@condition` (and `@service`) inherits the same contract: `ConditionResolver.resolveArg` / `resolveField` feed `argByJavaName` from a same-name default plus `argMapping` overrides. The result is friction in the unambiguous case — a field with one argument and a method with one non-Table, non-Context, non-DSLContext parameter only has one possible binding, but authors must either rename the Java parameter to match the GraphQL argument or write a redundant `argMapping: "javaName: gqlName"`. The user has flagged this as a refusal point in real schemas:

```
opptaksNavn: String @condition(
    condition: {className: "no.sikt.fs.opptak.opptak.OpptakService", method: "opptakNavnSok"},
    override: true
)
# signature: public static Condition opptakNavnSok(Opptak opptak, String whateverWeDontCareBecauseItsObvious)
```

Backlog scope: when a slot is structurally unambiguous, bind by position and skip the name-mismatch diagnostic. Open forks for Spec to decide:

- **Predicate for "unambiguous".** Cheapest: argument-level `@condition` with exactly one field argument and exactly one non-Table / non-Context / non-DSLContext method parameter. Wider: type-unique pairing among unbound slots (single argument whose GraphQL type matches a single remaining Java parameter type, even if the field has more than one argument). Wider still: same rule extended to field-level `@condition` and to `@service`. Each step trades convenience against silent mis-binding risk if a schema author later adds a second argument or parameter.
- **Visibility of the inference.** Silent name-free binding vs. surfacing the inferred pair in the resolved-coordinate report (or as an LSP hint) so authors notice when a previously-inferred binding becomes ambiguous.
- **Interaction with the `-parameters` diagnostic.** Today an unparameterized class file rejects with "parameter names not available". Positional inference for the single-slot case lets that path work without `-parameters`; decide whether that's a desired side-effect or whether the `-parameters` requirement still stands for any reflected method.
- **LSP fix-its.** `Behavior.java` currently synthesizes `argMapping:` quickfixes from the diagnostic. Those fix-its should stop firing in cases that now bind by position; verify they don't regress to suggesting a `argMapping:` that's no longer needed.

Files in play: `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ConditionResolver.java`, `ServiceCatalog.java` (the `argByJavaName` loop near line 240 and the diagnostic builder near line 308), `ArgBindingMap.java`, and the LSP quickfixes in `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/Behavior.java`.
