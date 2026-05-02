---
id: R2
title: "Checked exceptions on `@service` / `@tableMethod` for typed GraphQL errors"
status: Done
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: [error-handling-parity, mutations]
---

# Checked exceptions on `@service` / `@tableMethod` for typed GraphQL errors

**Status: subsumed by [`error-handling-parity.md`](error-handling-parity.md) §4 — landed.**

The original direction has shipped as part of R12. Concretely:

- `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod` now read
  `Method.getExceptionTypes()` and store the FQNs on
  `MethodRef.Basic.declaredExceptions()`.
- `CheckedExceptionMatcher.unmatched` implements the §4 match rule (handler
  class assignability for `ExceptionHandler`; any `SQLException` for
  `SqlStateHandler` / `VendorCodeHandler`); `InterruptedException` and
  `IOException` are exempt per the "Special cases" subsection.
- `FieldBuilder.checkDeclaredCheckedExceptions` runs at every method-backed
  field construction site (root + child `@service`, root + child
  `@tableMethod`); unmatched declared exceptions surface as
  `UnclassifiedField` with a reason that names the offending FQNs and points
  the schema author at the two fixes (declare an `@error` whose handler
  covers each, or remove the throws clause). The runtime path is unchanged:
  the per-fetcher `catch (Exception e)` arm routes through
  `ErrorRouter.dispatch` (channel present) or `ErrorRouter.redact` (channel
  absent), with no second runtime mechanism.

The `@LoadBearingClassifierCheck(key =
"service-method.declared-exceptions-covered")` annotation on
`buildServiceField` declares the producer; direct unit coverage in
`CheckedExceptionMatcherTest`, classifier integration coverage in
`CheckedExceptionClassificationTest`, and declared-exception capture cases in
`ServiceCatalogTest` close the test loop.

Tablemethod variants and child `@service` variants don't currently carry an
`errorChannel` slot, so the §4 check runs with `Optional.empty()` channel
for those paths and rejects any non-exempt declared checked exception.
Lifting `errorChannel` onto those variants is a separate enhancement; the
existing rejection contract is the right shape until that lift lands.
