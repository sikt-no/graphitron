---
id: R1189
title: "An applied @condition whose code reference names no class or method is rejected, not silently dropped"
status: Backlog
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# An applied @condition whose code reference names no class or method is rejected, not silently dropped

## Goal

A schema author who applies `@condition` (the directive that adds a consumer-written jOOQ predicate to a field's generated `WHERE`) on a field, an argument or an input field gets one of two outcomes and never a third: the reference resolves to a condition method that the generated query calls, or the build fails with an error at that coordinate. Today an incomplete reference (no `condition:` argument at all, as in a bare `@condition` or `@condition(override: true)`; a `condition:` without `className`; or one without `method`) is read as if the directive were absent, the build stays green, and the generated fetcher returns rows the condition was written to exclude. Where the condition is a row-level restriction that is a disclosure to any API client, so this is a fail-open validation defect (CWE-636), latent today: no `@condition` in the sakila example or the known consumer schemas omits either part.

## What happens now

- `BuildContext.readConditionDirective` returns `null` both when the directive is absent and when `condition:`, `className` or `method` is missing, and its javadoc says so.
- `ConditionResolver.resolveField` and `resolveArg` turn that `null` into `None`; `BuildContext.buildInputFieldCondition` turns it into `Optional.empty()` without appending an `InputFieldConditionFailure`. `FieldBuilder` then classifies the field as an ordinary SQL field with no `fieldCondition`.
- Nothing downstream notices. `GraphitronSchemaValidator` has no rule tying an applied `@condition` to a produced filter, and `graphitron_code_reference_entry_defect_rule` judges only references that name a method (`named_method IS NOT NULL`), so a reference with no method raises no defect either.
- The path-step site `@reference(path: [{condition: {...}}])` is not affected: `resolveConditionRef` returns `ConditionResolution.Unresolved` and the caller rejects it.
- The manual contradicts itself: `docs/manual/how-to/external-code.adoc` says the method name of an `ExternalCodeReference` is optional and defaults to the GraphQL field name, while `docs/manual/reference/directives/condition.adoc` § Constraints says `className` and `method` are both required and a missing one surfaces as an error. Neither describes the implementation, which drops it.

## Done looks like

- Each incomplete form (no `condition:`, no `className`, no `method`) on each of the three sites (field, argument, input field) either fails the build with a located error or, if the method-defaults-to-the-field-name rule is meant to hold for `@condition`, actually applies that default and resolves. Which of the two is a design decision for Spec; the manual pages then state the one chosen behaviour.
- "Absent" and "present but incomplete" are distinct results of the reader, so a caller cannot conflate them; `override: true` on an incomplete directive is never silently read as `false`.
- A build-time check independent of the classifier's happy path reports any coordinate where `@condition` is applied but no condition filter was produced, so a future reader that again returns "absent" cannot bring the silent drop back. Whether that check is a validator rule or a defect rule in the fact store (closing the `named_method IS NOT NULL` gap) is likewise a Spec question; the store-side shape is the direction of travel.
- Pipeline tests cover the nine form-by-site combinations.

## Related

R869 is a neighbouring but distinct drop: an argument-level `@condition` that *does* name a class and method but fails to resolve on a multitable field. This item is about references that never reach reflection.
