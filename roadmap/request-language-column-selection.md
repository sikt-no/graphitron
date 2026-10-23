---
id: R1096
title: "Select a translated text column by the request language (Accept-Language)"
status: Backlog
bucket: feature
priority: 5
theme: classification-model
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# Select a translated text column by the request language (Accept-Language)

## Goal

A field of a `@table` type can resolve to one of several columns, chosen per request from the client's preferred language (the `Accept-Language` header), with a fallback language and the language actually used returned next to the value. Clients stop fetching every language and re-implementing the same choice and fallback, and the selection stays schema-first and reusable across subgraphs. This is the single-shape counterpart of `@pivot`: `@pivot` exposes all items under one type shape, whereas this picks one shape from a request-time value.

Source: a feature request from the Sikt team, relayed by the user; the text below is that request, lightly edited.

## Motivation

Many tables store one text in several columns, one per language, for example `STUDIEPROGNAVN`, `STUDIEPROGNAVN_NYNORSK`, `STUDIEPROGNAVN_ENGELSK`, `STUDIEPROGNAVN_SAMISK`. Today the only schema-first option is to expose all of them:

```graphql
type Studieprogram @table {
  navnAlleSprak: OversatteTekster
}

type OversatteTekster {
  en: String
  nb: String
  nn: String
  se: String
}
```

Every frontend then fetches all languages and reimplements the same choice and fallback (`navn[userLanguage] || navn.nb || navn.en || ...`). The choice differs between applications and the client fetches up to four times the text it shows.

A proof of concept outside Graphitron (a field built programmatically in graphql-java, reading `Accept-Language` from the GraphQL context) showed the pattern works and that frontends get simpler.

## Proposed behaviour

An illustrative surface; the directive name and argument shape are open.

```graphql
type OversattTekst {
  sprakkode: String   # language actually returned, so the client can detect fallback
  verdi: String
}

type Studieprogram @table {
  navn: OversattTekst @i18nField(
    mapping: {
      nb: "STUDIEPROGNAVN"
      nn: "STUDIEPROGNAVN_NYNORSK"
      en: "STUDIEPROGNAVN_ENGELSK"
      se: "STUDIEPROGNAVN_SAMISK"
    }
    defaultLanguage: NB
  )

  navnAlleSprak: OversatteTekster   # unchanged, still available
}
```

Resolution for `navn`:

1. Parse `Accept-Language` (RFC 9110) and sort by q-value.
2. Pick the first language that is in `mapping` and has a non-null value in the row.
3. Otherwise use `defaultLanguage`; if that is null too, try the remaining mapped languages, or return null (open question below).
4. Return `{ sprakkode, verdi }`.

Preferably the choice is made in the generated SQL (a `CASE`/`COALESCE` over the columns in preference order, plus the chosen code), so it works in nested selections and batched fetches without extra round trips.

## Broader framing

This is one instance of a more general gap, which may be the better thing to design for:

1. mapping the fields of a GraphQL type from outside the type (the same `OversattTekst` type backed by different columns on different tables);
2. choosing that mapping from request context or input (here, the preferred language);
3. supplying literal values (here, the language code `"nb"` returned with the value).

A language-specific directive is acceptable if the general mechanism is too far off.

## Open questions

- Where does the language come from: the raw HTTP header only, or a value in the GraphQL context that the application fills (so non-HTTP callers and tests can set it)?
- Fallback when both the requested and default language are null: next mapped language, or null?
- Should `orderBy` and filters on such a field follow the selected language? Consumers currently filter on all language columns by default, with an optional `sokSprak: [NB, NN]` restriction on the filter input.
- Should the response signal the language used (for example `Content-Language`), or is the per-field code enough?
- Output type: a fixed shape (`{ code, value }`) or any type whose fields are mapped via the three points above?
- Relationship to `@pivot`: shared vocabulary for the language-to-column mapping, or kept separate?
