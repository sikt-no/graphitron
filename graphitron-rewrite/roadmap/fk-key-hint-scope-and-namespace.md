---
id: R259
title: "@reference FK-key candidate hint is global and SQL-namespaced; should be scoped to the path source table and rendered in the author key namespace"
status: Backlog
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-05-29
last-updated: 2026-05-29
---

# @reference FK-key candidate hint is global and SQL-namespaced; should be scoped to the path source table and rendered in the author key namespace

When a `@reference(path: [{key: "..."}])` element names a foreign key that does not resolve, `BuildContext.parsePathElement` (`BuildContext.java:1286`) emits a "did you mean" hint built from `catalog.allForeignKeySqlNames()` ranked by global Levenshtein distance. Two things make the hint unhelpful on a real schema:

1. **Wrong namespace.** `findForeignKey` resolves both the SQL constraint name (`opptak_samordna_organisasjon_organisasjon_fk`) and the jOOQ Java-constant name (`opptak_samordna_organisasjon__opptak_samordna_organisasjon_organisasjon_fk`, the `TABLE__CONSTRAINT` form). An author who writes keys in the Java-constant namespace gets suggestions in the SQL namespace, so the hint reads as a different namespace than the one they typed.
2. **Wrong scope.** The candidate set is every FK in the catalog. On a large schema the nearest-5-by-edit-distance are dominated by unrelated FKs that share a token (e.g. `organisasjonsepostadresse_organisasjon_fk`, `organisasjonsadresse_organisasjon_fk`), drowning the one or two FKs that are actually valid at this path position.

At the failing element the path source table is known (`parsePathElement`'s `currentSourceSqlName`, threaded from element to element in `parsePath`). The relevant candidate set is the FKs touching that source table (for a join-table hop, exactly the two outgoing FKs), not the whole catalog. Rendering those in the namespace the author used (detect `__` in the attempt) turns the hint from noise into the answer: "this position accepts these FK names."

## Symptom

```
[ERROR] ...schema_opptak.graphqls:175:5: Author error: Field 'Opptak.samordnaOrganisasjoner':
key 'opptak_samordna_organisasjon__opptak_samordna_organisasjon_organisasjon_fk' could not be
resolved in the jOOQ catalog; did you mean: opptak_samordna_organisasjon_opptak_fk,
organisasjonsepostadresse_organisasjon_fk, organisasjonsadresse_organisasjon_fk,
organisasjonsenhetsprak_organisasjonsenhet_fk, organisasjonsrollesprak_organisasjonsrolle_fk
```

The author wrote `..._opptak_fk` / `..._organisasjon_fk` in the `TABLE__CONSTRAINT` form; the suggestions come back in the bare SQL form, and three of the five are FKs on unrelated tables.

## Trace

- `BuildContext.java:1283`-`1288` — `keyName` lookup miss; hint built from `catalog.allForeignKeySqlNames()`.
- `BuildContext.java:978`-`982` — `unknownForeignKeyRejection` (sibling FK-name miss surface) draws from the same global SQL list.
- `JooqCatalog.java:282`-`300` — `findForeignKey` accepts both the SQL constraint name and the Java-constant field name (case-insensitive); the hint only mirrors the former.
- `JooqCatalog.java:351`-`364` — `findForeignKeysBetweenTables` already exists; a "FKs touching table X (either endpoint)" helper does not yet.

## Design sketch

1. Add a `JooqCatalog` helper returning the FKs whose source or key side is a given table (either endpoint), plus a way to render an FK's Java-constant name (`fkJavaConstantName` already exists).
2. In `parsePathElement`, when the key miss has a non-null `currentSourceSqlName`, scope the candidate set to FKs touching that table; fall back to the global list when source is null (non-table-backed forward traversal).
3. Render candidates in the namespace of the author's attempt: if the attempt contains `__`, list Java-constant names; otherwise SQL names. Mixed/both is an alternative; Spec-time decision.

## Sibling context

R236 is the same shape on the nested-input *column* surface (candidate hint drawn from the wrong table). This item is the FK-key surface. The two could share a "scope the hint to the structurally relevant set" principle but touch different call sites.

## Out of scope

LSP completion/hover arms (interactive, per-keystroke; this is the one-shot build-time validator message). The actual FK resolution logic in `findForeignKey` (already correct; only the failure message's candidate list is wrong).
