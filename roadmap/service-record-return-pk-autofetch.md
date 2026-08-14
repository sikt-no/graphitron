---
id: R674
title: "Reconcile @service record projection: a monomorphic record return passes through while the polymorphic route auto-fetches by PK"
status: Backlog
bucket: bug
priority: 3
theme: service
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Reconcile @service record projection: a monomorphic record return passes through while the polymorphic route auto-fetches by PK

A root `@service` returning `[Miljo]`, where `Miljo` is a `@table`-bound type and the service selects only the primary key, resolves every non-key field to `null` with no build-time or runtime signal. Reported against 10.0.0-RC30: `{ mineSynligeMiljoer { kode navn } }` returns `[{"kode":"demo","navn":null}, ...]` although `NAVN` holds non-null values in the database. The service body is the shape the reporter expected to be enough:

```java
public List<MiljoRecord> mineSynligeMiljoer() {
    return ctx.select(MILJO.MILJOKODE).from(MILJO)
            .fetch(r -> { var rec = new MiljoRecord(); rec.setMiljokode(r.value1()); return rec; });
}
```

The legacy README promised the opposite: populate the primary key and the framework fetches all requested fields by it, batched for a list. That promise is what the reporter wrote the service against.

## The state of play

This is not an accidental regression. The rewrite manual documents pass-through deliberately, in `docs/manual/how-to/handle-services.adoc`, twice: once as behaviour ("the framework treats the records as already-projected rows and hands them straight to graphql-java ... the per-field column fetchers walk the record without an additional SELECT") and once as a pitfall ("`Result<TableRecord>` returns skip framework projection. The service is responsible for selecting every column the schema may eventually ask for"). So the reported behaviour is the documented contract of the rewrite, contradicting the documented contract of the version consumers are migrating from.

What makes it worth an item rather than a documentation pointer is that the rewrite **already implements the promised auto-fetch**, on the neighbouring route. `MultiTablePolymorphicEmitter.emitServiceMethods` handles a root `@service` returning a multitable interface or union by taking the "concrete PK-populated `TableRecord`s directly", dispatching on each record's runtime class, and reusing `buildPerTypenameSelect` "verbatim to auto-fetch the selected columns by PK". The `@mutation` read-back does the same thing off the `RETURNING` key. So the exact contract the reporter expected is live in the generator, and the plain single-table `@service` return is the one route that does not use it.

The consumer-visible outcome of that asymmetry is the worst available: same authoring intent, same record shape, silently different results depending on whether the field's return type happens to be polymorphic, and a `null` rather than an error when the guess is wrong.

## The decision this item owns

Two coherent ends, and the Spec has to pick one rather than splitting the difference:

1. **The monomorphic route grows the same by-PK auto-fetch.** Restores the legacy contract, makes the two `@service` routes agree, and costs a follow-up SELECT per field (batched `WHERE pk IN (...)` for a list) that the pass-through shape avoids today. Needs an answer for what happens when a record arrives with columns already populated: fetch anyway, or fetch only the missing ones.
2. **Pass-through stands as the contract.** Then the silent `null` is the thing to fix: a service returning a sparse record is an author error the generator can see coming, so it wants a diagnostic rather than a data outcome, and the polymorphic route's auto-fetch becomes the surprising special case worth documenting as such. The legacy promise also needs an explicit retraction in the migration path, since today a migrating consumer reads the old README, writes a PK-only service, and gets nulls.

## Notes for whoever specs this

- The polymorphic route's drop contract is a precedent for what "the service violated its side" looks like: a record whose PK matches no live row yields nothing and the payload is silently shorter. If option 1 ships, the monomorphic route inherits that question and should answer it the same way.
- Whichever option wins, the migration documentation is in scope. The reporter's path into this was the legacy README, not the manual.
- A regression test wants the sparse-record shape specifically: a service selecting only the key, a schema asking for a non-key column, and an assertion on which of a value, a null, or a build failure is correct once the decision is made.

Reported at https://github.com/sikt-no/graphitron/issues/524.
