---
id: R1057
title: "A filter field typed as a list of input objects is read as one Map, so the filter is silently dropped"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# A filter field typed as a list of input objects is read as one Map, so the filter is silently dropped

## Goal

A filter input field typed as a list of input objects (`terminer: [TermInput!]`, whose element fields carry `@field(name:)` onto the target table's columns) narrows the query to rows matching any element. Element fields are compared together as a row, so it reads `row(ARSTALL, TERMINKODE).in(...)`, and a null or empty list applies no filter. That is how a `[String!] @field` list already becomes `.in(...)`, and what v9 did. Where graphitron cannot apply such a shape, it rejects it at build time instead. Today the filter is silently dropped and the query returns too many rows, with no diagnostic and no runtime error. That is the one wrong answer, because it leaks data the caller did not ask for.

## Context

Reported from the sis port (trunk `c28a6cd4d`). Shape, from the stable `Query.publiseringsklareEmner` over `INFOTERMIN_EMNE`:

```graphql
input PubliseringsklareEmnerFilter {
    eierInstitusjonsnummer: String! @field(name: "INSTITUSJONSNR_EIER")
    terminer: [PubliseringsklartEmneTerminInput!]
    emnekoder: [String!] @field(name: "EMNEKODE")
}
input PubliseringsklartEmneTerminInput {
    arstall: Int! @field(name:"ARSTALL")
    terminbetegnelse: String! @field(name: "TERMINKODE")
}
```

Generated in `QueryConditions.publiseringsklareEmnerCondition`:

```java
Integer arstall = (filterMap != null ? (filterMap.get("terminer") instanceof Map<?, ?> map2 ? (Integer) map2.get("arstall") : null) : null);
String terminbetegnelse = (filterMap != null ? (filterMap.get("terminer") instanceof Map<?, ?> map2 ? (String) map2.get("terminbetegnelse") : null) : null);
...
if (arstall != null) condition = condition.and(table.ARSTALL.eq(arstall));
if (terminbetegnelse != null) condition = condition.and(table.TERMINKODE.eq(terminbetegnelse));
```

graphql-java delivers `terminer` as a `List`; a single object in variables is coerced to a one-element list. So the `instanceof Map` test is always false, both locals are null, and the term filter never applies. With `terminer: {arstall: 2017, terminbetegnelse: "VÅR"}` the query returns rows from 2005, 2006, 2007 and so on.

The extraction is `ConditionGlueRenderer.nestedExtraction`, which walks `instanceof Map<?, ?>` ternaries from the outer argument down to the leaf (`WireMapChain`) with no step for a list level in between. The classifier also treats each element field as an independent scalar `.eq`, which cannot express "any element matches" even if the read were fixed. The fix therefore spans classification (a list-of-input filter field becomes a row-`IN` over its element fields) and emission. Element nullability needs a rule as well: some sis element types are nullable (`[X]`), and a null element should probably be skipped.

Scope in sis: 26 generated conditions read a list-of-input field this way. The fields are `terminer`, `terminkoder`, `gjelderFraTerminer`, `gjelderForTerminer`, `kullterminkoder`, `slutterminkoder` and `undervisningsterminkoder`, across Query, Emne, Studieprogram, PubliseringsklartEmne/StudieprogramForTermin, Campus, FagpersonVedLarested and StudentVedLarested conditions. Found via sis `ReadOnlyApprovalIT`, where 6 approvals differ.

Neighbours: R1053 (the `@service` validation pre-check ignores list wrapping, a different site), and R334 (readability of the same extraction code; it does not change behaviour).
