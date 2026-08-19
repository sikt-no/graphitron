---
id: R675
title: "@condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible"
status: Spec
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
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

## Notes carried from Backlog

- The report claims per-type overloads work for `@condition` on a query field and fail only on an input field. That should not be true in the rewrite: all three directive reflect helpers route through the same name-keyed `pickMethod` with a null filter, so a query-field `@condition` with three same-named declarations should hit the same rejection. Most likely the claim is carried over from the legacy generator. The parity test below confirms it and stays as the regression pin.
- Whichever option ships, the multitable filter documentation is in scope: the reporter called the `Table<?>` form "undocumented", and while `add-custom-conditions.adoc` does show it, nothing at the multitable coordinate tells an author it is the intended form there.

Reported at https://github.com/sikt-no/graphitron/issues/525 (first half; the `@nodeId` half is its own item).

---

## Decision: confirm `Table<?>` as the terminus

Option 2 wins. `@condition` keeps naming exactly one method; per-participant overload dispatch (option 1) is declined. The gap this item closes is signposting, not resolution: the rejection message tells the author to rename or remove overloads when it should also tell them the intended form, and no documentation at the multitable coordinate says one `Table<?>` method serves every branch.

### Why not per-participant dispatch

1. **The seam-filter precedent does not transfer.** `SeamFilter.SESSION_HOOK` exists because jOOQ's generated `Routines` classes force same-named overloads on the author; a selector was the only way to reference an executing routine at all. A `@condition` class is the author's own code: nothing forces the overload set, and the single-method form is expressible today. An exception to name-keyed resolution needs the session-hook level of necessity, and this case does not have it.
2. **Resolution would stop being coordinate-invariant.** `reflectTableMethod` is table-blind, and input-field `@condition` resolution (`BuildContext.buildInputFieldCondition`) runs at the input-type coordinate with no table in scope at all; an input type is reusable across queries. Threading a participant table into method resolution restructures input classification for every consumer to serve one coordinate, and the same overload set referenced from a single-table coordinate would still reject, so the author-facing rule would vary by where the directive sits.
3. **The dispatch semantics have no clean answer.** Overloads covering two of three participants, a mixed set (one `Table<?>` declaration beside concrete ones), and assignability ties each need an authored rule plus its own rejection arm; every rule is invisible-at-the-SDL behaviour an author has to learn. The in-language alternative below needs none of that.
4. **The typed-columns loss is recoverable in author code.** Inside one `Table<?>` method, `table.field(FILM.NAVN)` is fully typed (`Field<String>`, matched by name against the branch alias) and returns null on a branch whose table lacks the column, so a null-probe chain dispatches per participant with typed column constants. Where the whole concrete table is wanted, `table instanceof Film f` narrowing recovers it (Java 16+, safe for consumers on the 17 floor); the branch emitter passes each participant's concretely-typed stage-1 alias, so the runtime class is the generated table class. Dispatch written this way is visible in the author's own code instead of resolved invisibly by a generator rule.

What would reopen option 1: author demand where in-method narrowing is genuinely insufficient, e.g. a participant set large enough that a null-probe chain is unmaintainable, or condition libraries shared across schemas that cannot name graphitron participants. File a new item citing this section if that materialises; the partial-coverage semantics decided there must cover the three corners in point 3.

## Deliverables

Three deltas, one commit-sized item.

### 1. Signpost in the rejection message

`ReflectionError.AmbiguousMethod` gains the intended-form hint on the `@condition` path: after "rename or remove overloads so exactly one method named 'x' exists", append prose to the effect of "a @condition names exactly one method; a single method with a Table<?> parameter serves every table it is applied against, including every participant of a multitable interface or union". Constraints:

- The arm identity and `lspCode()` (`graphitron.reflect.ambiguous-method`) stay stable; this is a message-level change.
- The hint must not render on the `@service` / `@externalField` / session-hook paths, where a `Table<?>` parameter is wrong or meaningless advice. `reflectTableMethod` is the single entry for every `@condition` coordinate (argument-level, field-level, input-field, path-step), so the discriminator exists there. Preferred shape: an optional hint component on the `AmbiguousMethod` record (rendered only when present), populated by `reflectTableMethod` when re-wrapping the pick rejection, leaving `pickMethod` itself untouched. The implementer may instead pass a caller-context input to `pickMethod` if re-wrapping proves awkward; either way the shared no-hint rendering is byte-identical to today's message.
- `typed-rejection.adoc`'s `AmbiguousMethod` sentence gets the one-line update (the arm's own paragraph; no new permit, so `SealedHierarchyDocCoverageTest` is unaffected).

### 2. Documentation at the multitable coordinate

- `docs/manual/how-to/add-custom-conditions.adoc`: a new section on filtering multitable interfaces/unions. Content: the directive resolves one method; the branch emitter calls it once per participant against that branch's alias; the `Table<?>` first parameter is the form that serves every branch; the typed-column recovery patterns (null-probe via `table.field(<static column constant>)`, `instanceof` narrowing for the whole table); a concrete participant-table parameter compiles only when every branch matches, so on a multitable field it fails the consumer's javac for the mismatched branches, which is the intended guard, not a bug.
- `docs/manual/how-to/polymorphic-types.adoc`: the multitable section gains a short "Filtering" pointer to the new section (the reporter arrived at polymorphic types first and found nothing about filters there).
- `docs/manual/reference/directives/condition.adoc`: state the one-method rule (overloads reject) and cross-reference the how-to section for the multitable form.

### 3. Coordinate-parity pipeline test

A pipeline-tier test (per `docs/architecture/how-to/testing.adoc`; `GraphitronSchemaBuilderTest` neighbourhood, which already drives `TestConditionStub`) pinning that an overloaded `@condition` method produces the typed `ReflectionError.AmbiguousMethod` rejection at both the query-field coordinate and the input-field coordinate:

- Fixture: an overloaded pair on `TestConditionStub` (or a dedicated stub class if the shared stub's javadoc contract resists overloads), e.g. two declarations of `overloadedCondition(Table<?>, String)` / `overloadedCondition(Table<?>, Integer)`.
- Assert the rejection is `ReflectionError.AmbiguousMethod` (typed, not message-matched) at each coordinate, and that the `@condition`-path message carries the deliverable-1 hint.
- This discharges the Backlog note's claim check: the report said the query-field coordinate accepts overloads; code reading says both coordinates route through the same null-filter `pickMethod`, and this test is the executable form of that reading. If writing the test disproves the reading (the coordinates really differ), stop: that difference is a defect, and this item's shape changes; reopen to Spec.

## Out of scope

- Any change to `pickMethod`'s resolution semantics: zero/one/many by name, seam filter only on the session-hook path, all unchanged.
- The `@nodeId` half of issue 525 (its own item).
- Relaying the outcome to the reporter on issue 525 happens when this ships, but the issue reply itself is not a gate for Done.

## Acceptance

- Overloaded `@condition` at query-field and input-field coordinates both reject with `AmbiguousMethod`, message carrying the intended-form hint; non-`@condition` overload rejections render byte-identically to today.
- The three documentation coordinates above name the `Table<?>` form and the typed-column recovery patterns.
- Full `mvn install -Plocal-db` green.
