---
id: R655
title: "Residue from the @service phase split: unpinned Scalar trigger, dropped Table filter, stale catalog test names"
status: Backlog
bucket: cleanup
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Residue from the @service phase split: unpinned Scalar trigger, dropped Table filter, stale catalog test names

Three small things the `@service` coordinate-precedence phase split left behind. Filed at its Done gate as follow-ups because none of them breaks a contract; each is a place where the split's new shape is not yet fully pinned or fully honest. Independent of each other, so this can ship in pieces or be split.

**The `RecordParent` classify arm's second trigger is half-pinned.** `ServiceDirectiveResolver.classifySourcesCoordinate`'s record-parent arm rejects on either of two triggers: a SOURCES candidate, or a `Result` / `Scalar` resolved return type with no candidate in sight. The second trigger is the over-fire guard that keeps a `Sources`-less record-parent `@service` from silently becoming legal a phase early, and only its `Result` half is pinned (`ServiceCoordinatePrecedenceTest.recordParent_noBatchParameterWithRecordReturn_isStillRejected`, whose fixture returns a record-backed type). A `Scalar`-return sibling with no candidate would close it. One fixture, same mould as its neighbours.

**`inferBindingsByType`'s decoded overload dropped the `org.jooq.Table` eligibility filter.** The reflection-based overload (still live on the `@condition` path) skips `Table<?>` parameters when choosing inference candidates; the decoded overload the `@service` claim reduction calls does not, because `ServiceCatalog.DecodedParam` carries an `isDslContext` flag and no is-a-Table flag. Harmless today: a `Table<?>` parameter on a `@service` method is rejected either way, and only the message differs (an inferred binding's extraction rejection instead of the argument-name mismatch). Worth closing anyway, because the two overloads now read as equivalent forms of one rule and are not. Either add the flag to `DecodedParam` and filter on it, or state in the decoded overload's javadoc why the axis is absent there.

**`ServiceCatalogTest`'s method names still say `reflectServiceMethod`.** 41 test methods carry the `reflectServiceMethod_` prefix naming an entry point that no longer exists; they drive the class's own `reflect(...)` helper, which composes `decodeServiceMethod` + `reduceClaims` + `bindServiceMethod`. The javadoc on the helper explains the composition, so nothing misleads a reader who looks, but the names are a prose surface naming a dead symbol. Deliberately left out of the split's own commit: 41 renames is a wide mechanical diff with real rebase-conflict cost against concurrent sessions, and it buys clarity rather than correctness. Rename them as a standalone commit when the file is otherwise quiet, splitting the prefix by what each case actually drives (`decode_`, `bind_`, or `decodeAndBind_`).
