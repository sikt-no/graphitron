---
id: R702
title: "Catalog-vs-catalog name comparisons in Java fold case as a hedge; make them exact"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Catalog-vs-catalog name comparisons in Java fold case as a hedge; make them exact

## Problem

The generator compares catalog names case-insensitively in places where both operands come from the
same jOOQ catalog reading and are therefore already canonical. Case-insensitive matching is a
semantic only where an author's written name meets the catalog (an author may write `film` for
`FILM`); between two values the catalog itself produced, the fold is a hedge inherited from the
legacy generator, which folded at every comparison because it never established which strings were
canonical. A hedge is not harmless: on a database using quoted identifiers, two genuinely distinct
columns can differ only by case, and a folded comparison silently equates them.

The census, all comparing `ColumnRef.sqlName()` (or a catalog table name) against another value from
the same catalog:

- `FieldBuilder` (key-column agreement in the reverse-join arm)
- `TypeBuilder` (column-list equality)
- `BuildContext` (three key-column filters and one start-vs-target table-name check)
- `NodeIdLeafResolver` (three key-column alignment loops; the count is exact at `:357`, `:522`
  and `:561`, and the habitat is moving, see the note below)
- `JoinedTableReprojection` (source-side slot lookup)
- `MutationField` and `ProducerBinding` (source-vs-target column-pair checks)

One census entry changes habitat rather than disappearing (2026-08-20). The `@nodeId` decode
resolution became store relations on trunk, and the alignment those three loops perform moves into
them, where the fold happens in SQL. The relation-move item states the same crossing from the other
side, that every reader matching against the resolved key column's spelling folds case at the
crossing. So a census keyed to Java call sites will silently lose this entry once the resolver
becomes a reader of rows; re-point it at the decode relations then rather than deleting the row.
Detail: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`, Finding 7.

Not in scope, named so silence does not read as a claim: `OrderByResolver`'s `"DESC"` token is an
authored value with its own vocabulary; `TenantBindingIndex`'s two-tier match compares an authored
configuration name against the catalog, which is the resolution stratum's job (R697) and retires
there; `ConnectionHelperClassGenerator` emits a runtime lookup into generated code, an author-facing
runtime semantic that changes independently if at all.

## Relationship to the filed case-drift defects

R358 (table names, shipped) and R359 (its column sibling, Backlog) document the drift this hedging
already caused: exact and folded comparisons of the same identity string coexisting, with the live
`.equals` site the odd one out. R359 leaves the unification direction open (a shared predicate, or a
canonical-identity pass). This item states the direction: where both operands are catalog-canonical,
the shared predicate is exact equality, and the fold is removed rather than standardized. Whether
R359 folds into this item or lands first as the guard is a Spec question; the two must not land
opposite conventions.

## Shape of the fix

Per-site reachability call first, in R359's own manner: some operand pairs may share provenance and
be non-divergent, in which case the change is provably behavior-preserving; where a site is
reachable with divergent-case operands, the change is a bug fix on quoted-identifier catalogs and
wants a test. Then one sweep converting the census to exact comparison, plus whatever guard R359's
Spec settles on so the next hedge fails review mechanically.
