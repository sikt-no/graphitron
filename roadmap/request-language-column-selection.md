---
id: R1096
title: "Select a translated text column by the request language (Accept-Language)"
status: Spec
bucket: feature
priority: 5
theme: classification-model
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# Select a translated text column by the request language (Accept-Language)

## Goal

A field of a `@table` type can resolve to one of several columns of that table, chosen per request from the client's preferred languages, with a fallback language, and returns the language actually used next to the text. Clients stop fetching every language and re-implementing the same choice and fallback, and the selection stays schema-first and reusable across subgraphs. This is the single-shape counterpart of `@pivot` (a directive that exposes rows of a child table under one fixed object shape): `@pivot` exposes all languages, this picks one.

The schema author writes (the table shape is from the Sikt request; the column names are theirs):

```graphql
type OversattTekst {
  sprakkode: String   # language actually returned, so the client can detect fallback
  verdi: String
}

type Studieprogram @table {
  navn: OversattTekst @localized(
    columns: [
      {language: "nb", column: "STUDIEPROGNAVN"}
      {language: "nn", column: "STUDIEPROGNAVN_NYNORSK"}
      {language: "en", column: "STUDIEPROGNAVN_ENGELSK"}
      {language: "se", column: "STUDIEPROGNAVN_SAMISK"}
    ]
    defaultLanguage: "nb"
    codeField: "sprakkode"
    valueField: "verdi"
  )
}
```

A client sending `Accept-Language: nn, en;q=0.5` and selecting `navn { sprakkode verdi }` receives `{sprakkode: "nn", verdi: ...}`, or `{sprakkode: "en", ...}` where the Nynorsk column is null for that row.

Source: a feature request from the Sikt team, relayed by the user.

## Behaviour

**Language preference.** The directive reads the client's ordered language list from a *context argument*: a typed per-request value the application passes to the generated `Graphitron.newOwnedExecutionInput(...)` / `newExecutionInput(...)` factory, the same channel `@service` and `@condition` use for `contextArguments`. The directive names the argument with `languagesContextArgument: String = "preferredLanguages"`; its type is fixed at `List<String>` (language tags, most preferred first). Consequences:

- A missing or wrong-typed value is a compile error in the consumer's call to the factory, and an empty list is a legal "no preference" value that selects the default language. Nothing in the generator touches HTTP.
- Non-HTTP callers and tests set the value directly.
- Parsing `Accept-Language` is the transport layer's job. For graphitron-jakarta-rest, R1098 supplies the request headers at the `newExecutionInput` seam and an `AcceptLanguage.parse` helper that produces this list.

**Resolution.** For one row, build one ordered candidate list and take the first candidate whose column value is non-null:

. each requested tag, in order, that matches a `columns` entry (case-insensitive; a requested `nb-NO` matches `nb` by primary subtag; a tag matching no entry is skipped);
. `defaultLanguage`;
. the remaining `columns` entries in declaration order.

The result is `{codeField: <that entry's language>, valueField: <its value>}`. If every column is null the object is still returned, with both fields null, matching the `@pivot` rule that a present parent never yields a null projection object. Step 3 is the answer to the "next mapped language, or null" question: a row with any text at all shows some text, and the language code tells the client it is not the one it asked for.

**One SQL expression, no extra round trip.** The candidate list is computed in Java from the request's language list, then rendered as two select-list expressions on the parent row: `COALESCE(c1, c2, ...)` for the value and `CASE WHEN c1 IS NOT NULL THEN 'nb' WHEN c2 IS NOT NULL THEN 'nn' ... END` for the code. The field therefore works in root queries, nested selections, and batched `@splitQuery` fetches exactly like a column field, with no join and no per-row Java choice.

**Output type.** A plain (non-`@table`) object type with exactly the two nullable `String` fields named by `codeField` and `valueField` (defaults `code` and `value`). Nothing else in this item tries to map arbitrary fields of arbitrary types.

**Out of scope, decided.**

- `orderBy` and filters on such a field do not follow the selected language. They stay available on the underlying columns, as today. If a consumer later needs a language-following sort, that is a new item built on the candidate-list logic shipped here.
- No `Content-Language` response header. The per-field code is the signal; a response-wide header is ambiguous when one response carries several such fields.
- The general "map fields of a type from outside the type, chosen from request context, with literal values" mechanism is not designed here. This item is its first instance; a second instance is the evidence that decides its shape. `@pivot`'s vocabulary mapping stays separate: its keys are row discriminator values, this one's are request-time language tags, and sharing a spelling would couple two unrelated lookups.

## Implementation

- **Directive declaration.** `directives.graphqls`: `directive @localized(columns: [LocalizedColumn!]!, defaultLanguage: String!, codeField: String = "code", valueField: String = "value", languagesContextArgument: String = "preferredLanguages") on FIELD_DEFINITION` with `input LocalizedColumn { language: String!, column: String! }`. A list of pairs, because GraphQL has no map literal with arbitrary keys. `DeclaredDirectivesTest` pins the declaration.
- **Classification.** A new `FieldBuilder.classifyLocalizedField`, modelled on `classifyPivotField` and the `NestingField` path (a plain object type projected off the parent's row, no join). New model variant `ChildField.LocalizedField` carrying the ordered `(language, ColumnRef)` entries, the default, the two slot names and the context-argument name; the object type's two fields become slot fields read by name off the parent record, the way `PivotSlotField` is. Admission rejections are a new sealed `LocalizedError` in `graphitron-model/.../diagnostics/` in the `PivotError` style: parent has no table context; return type is a list, is `@table`, or does not have exactly the two nullable `String` fields; a column unresolved on the parent table or not a string type; empty `columns`; duplicate language; `defaultLanguage` not in `columns`; `codeField`/`valueField` not fields of the return type or equal to each other; the projection type also reached as a nesting or pivot type (the shared-type read-name divergence recorded against `@pivot` would apply here too, so reject rather than inherit it).
- **Context argument.** `ContextArgumentClassifier` gains a directive-declared `Site` beside `Site.Method`, `Site.Carrier` and `Site.SessionMount`: the site contributes `ResolvedContextArg(name, List<String>)`, and the existing cross-site fold rejects a conflicting type declared elsewhere under the same name. `GraphitronFacadeGenerator` then adds the parameter to both factories with no further change. The emitted read is the existing `(T) graphitronContext(env).getContextArgument(env, "preferredLanguages")`.
- **Emission.** First select-list term in the codebase that depends on a per-request value; every existing context-argument read sits in a WHERE condition. `ProjectionCommands.contributionFor` maps `LocalizedField` to a `Contribution.Project` with a new `SelectTerm.LocalizedChoice` (two aliased expressions); `ProjectionUnitRenderer` renders it using the `env` its `$project` method already receives, calling a small hand-written static helper (candidate-list construction, the pure function that step 1-3 above define) shipped in the generated runtime support classes. Fetcher wiring follows `NestingReach` so the slot fields read the aliased values off the parent record.
- **Validator.** `GraphitronSchemaValidator` case for `LocalizedField` and its slot fields, as for the pivot arms (admission checks fire at classify time; the validator keeps cross-field checks only).

## Tests

- *Unit.* `LocalizedClassificationTest` (SDL to model; each `LocalizedError` arm has a rejecting case) and a pure unit test of the candidate-list helper: ordering, primary-subtag match, case-insensitivity, skipped unknown tags, default, remaining-in-declaration-order, duplicates in the request list, empty list.
- *Corpus.* A `localized.graphqls` spec-by-example file next to `pivot.graphqls`, per the `classified-corpus` skill.
- *Pipeline.* Rendering pins in the `ProjectionUnitRendererTest` style: the `COALESCE`/`CASE` shape for a given candidate list is produced from the runtime list, not baked in; the factory gains exactly one `List<String>` parameter.
- *Execution (the acceptance evidence).* The sakila fixtures hold translations as rows (`film_translation`), not columns, so this item adds a wide table with one text column per language, rows covering: requested language present; requested absent, falls back to default; requested and default null, falls back to a remaining column; all null; plus an empty preference list. Assertions run against a real database at the root, nested under a parent, and under `@splitQuery`, and verify that two requests with different language lists on one schema return different columns. A compile-tier check is the sakila example module building the generated code.

## User documentation (first-client check)

New reference page `docs/manual/reference/directives/localized.adoc`, with the sample above, and a how-to paragraph showing the consumer side for graphitron-jakarta-rest:

```java
@Override
public ExecutionInput.Builder newExecutionInput(HttpHeaders headers) {
    return Graphitron.newOwnedExecutionInput(AcceptLanguage.parse(headers) /* , other context args */);
}
```

If that does not read in two lines, the design is wrong. This depends on R1098 only for the jakarta-rest convenience; the directive itself works for any caller that fills the context argument.

## Other solutions we've considered

- *Read the raw `Accept-Language` header in generated code.* Rejected: couples the generator to HTTP, leaves non-HTTP callers and tests without a way in, and gives no compile-time check that the value is supplied.
- *Choose the column in Java after fetching all language columns.* Rejected: fetches every language for every row and cannot apply the non-null fallback in a batched fetch without extra code; the select-list `COALESCE` does both in the query.
- *The general type-mapping mechanism first.* Deferred as above; one instance does not fix the shape.

## Reviewer findings
