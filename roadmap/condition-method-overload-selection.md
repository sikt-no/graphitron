---
id: R675
title: "@condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# @condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible

An author writing a filter input for a query that returns a multitable interface wants one predicate method per participant, since each participant is a different table with differently-named columns. The natural Java expression of that is an overload set: three methods named `navn`, one per participant table type. The rewrite rejects it at build time:

> [author-error] input field 'navn' @condition: method 'navn' in class '...' is overloaded (3 declarations with parameter counts [2, 2, 2]) - graphitron cannot pick one; rename or remove overloads so exactly one method named 'navn' exists

Reported against 10.0.0-RC30 as one half of a filter-input report on multitable queries. The author's fallback is a single method taking `org.jooq.Table<?>` and resolving columns by name off it, which works and is what the multitable filter design intends, but gives up jOOQ's generated column typing on a surface where the whole point of jOOQ is that the columns are typed.

## Why it happens

`ServiceCatalog.pickMethod` is the single method-resolution point for every directive that names a Java method. It filters `cls.getDeclaredMethods()` by name and then judges: zero matches produce `Rejection.AuthorError.UnknownName`, more than one produce `ReflectionError.AmbiguousMethod` carrying each candidate's parameter arity. Nothing consults the *coordinate* the method is being resolved for, so a name shared by several declarations is ambiguous by construction, regardless of whether the surrounding context would pick one unambiguously.

The machinery for narrowing already exists and is one argument away. `pickMethod` has a second form taking a `SeamFilter`, which narrows same-named declarations before ambiguity is judged and produces its own two rejections (`SeamParameterMissing`, `SeamCandidateAmbiguous`). Only the session-hook path passes one; the three directive reflect helpers pass null and keep exact-name behaviour. So the question is not whether narrowing is possible but what the narrowing key should be for a per-participant `@condition`.

## What has to be decided

The multitable filter design deliberately chose the `Table<?>`-generic form. `FieldBuilder.lowerParticipantFilters` reflects the `@condition` method once per participant and calls it against each branch's stage-1 alias, and its own documentation states the contract: "a `Table<?>`-typed first parameter serves every branch, while a concrete participant-table parameter surfaces a mismatched branch at the consumer's javac". Per-participant overloads were never in scope; they are not an oversight so much as an unbuilt alternative.

So the item owns a decision, not a repair:

1. **Support per-participant dispatch.** `pickMethod` gains a participant-table-typed selector for the multitable coordinate, so the overload whose table parameter matches the branch's generated table type is chosen and the author keeps typed columns. Costs a resolution rule that varies by coordinate, and needs an answer for the partial case (overloads covering some participants but not all).
2. **Confirm `Table<?>` as the terminus.** Then the rejection is right and the gap is that it reads as a limitation rather than a signpost: the message says "rename or remove overloads" when what the author needs to hear is "on a multitable filter, one method takes `Table<?>` and serves every branch". The manual documents the `Table<?>` form generally, but not at this coordinate, and the reporter arrived here from the legacy README's per-type-overload pattern.

## Notes for whoever specs this

- The report claims per-type overloads work for `@condition` on a query field and fail only on an input field. That should not be true in the rewrite: all three directive reflect helpers route through the same name-keyed `pickMethod` with a null filter, so a query-field `@condition` with three same-named declarations should hit the same rejection. Most likely the claim is carried over from the legacy generator. Confirm it with a cheap pipeline test before writing any coordinate-specific rule, because if the two coordinates really do differ today, that difference is itself a defect and changes the shape of this item.
- Whichever option ships, the multitable filter documentation is in scope: the reporter called the `Table<?>` form "undocumented", and while `add-custom-conditions.adoc` does show it, nothing at the multitable coordinate tells an author it is the intended form there.

Reported at https://github.com/sikt-no/graphitron/issues/525 (first half; the `@nodeId` half is its own item).
