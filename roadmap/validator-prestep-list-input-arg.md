---
id: R1053
title: "Validation pre-check casts a list input argument to Map and crashes outside the error channel"
status: Backlog
bucket: bug
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Validation pre-check casts a list input argument to Map and crashes outside the error channel

## Goal

A `@service` field whose payload error channel carries a `VALIDATION` handler validates a list-typed input argument such as `input: [X!]!` element by element. Each element's violations are reported under that element's path (`input`, `<index>`, ...), and a null or empty list yields none. A failure in the pre-check reaches the error channel instead of escaping the fetcher. Today every call to such a field fails, the happy path included, so a consumer cannot put `VALIDATION` on the channel of any mutation that takes a list input.

## Context

Reported from the sis port (trunk `02b6a7f3d`). Shape: `opprettStudieprogrammer(input: [OpprettStudieprogrammerInput!]!): OpprettStudieprogrammerPayload @service(...)`, with an errors union holding `UgyldigInput @error(handlers: [{handler: VALIDATION}, ...])`. The generated fetcher reads:

```java
Map<String, Object> arg_input_raw = env.getArgument("input");
OpprettStudieprogrammerInput arg_input = arg_input_raw == null ? null : OpprettStudieprogrammerInput.fromMap(arg_input_raw);
```

At runtime the argument is an `ArrayList`, so the fetcher throws `ClassCastException: class java.util.ArrayList cannot be cast to class java.util.Map`. The pre-check runs before the service call's `try`, so the exception bypasses the channel and redaction, and graphql-java's default handler surfaces the raw cast message. On sis, adding the handler broke 43 ITs across every `@service` mutation with a list input; sis has dropped the handler until this lands.

Cause: `TypeFetcherGenerator.validatorPreStep` resolves the argument's input class through `resolveInputArgClass` and always emits the `Map` read plus `fromMap`, without looking at the argument's list wrapping. The `ValueShape` on the `MappingEntry.FromArg` it walks already carries `ListOf`, so the list case is visible at that site.

Expected, per the reporter: validate each element and collect the violations, the way the DML column-length check already does for list inputs; null or empty gives no violations; and the pre-check placed so a failure in it cannot escape the error channel.

Consumer motivation: v9 parity for Bean Validation on `@service` inputs. sis's `MutationValidationIT` expects the DML length check's "size must be between 0 and 20" on `registrerStudentkort`, and `registrerMeldingerOm*ForLangMerknadGirFeil` expect "size must be between 0 and 500" from `@Size` on a service input; all need `VALIDATION` on the channel. Neighbours: R170 (execute-tier fixture for the validation channel) and R747 (generated input records carry no constraints); a sakila execution case with a list input under a `VALIDATION` channel belongs with this fix whichever lands first.
