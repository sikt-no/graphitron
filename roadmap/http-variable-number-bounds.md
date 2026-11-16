---
id: R1190
title: "Bound JSON numbers in GraphQL-over-HTTP variables and extensions before the engine coerces them"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# Bound JSON numbers in GraphQL-over-HTTP variables and extensions before the engine coerces them

## Goal

A consumer serving GraphQL over HTTP through `GraphqlHttpHandler` (in `graphitron-jakarta-rest`) is no longer exposed to a one-request denial of service through numbers in `variables` or `extensions`. Today any client, anonymous or authenticated, can send a few bytes such as `1e999999999` as an `Int` variable and pin a worker thread on big-number arithmetic while allocating hundreds of megabytes of heap. When this item lands, every number in `variables` and `extensions`, at any nesting depth and on both POST and GET, is bounded at the HTTP boundary before the engine sees it. An oversized one is answered promptly with a small `4xx` request error, and ordinary integer, decimal and exponent values coerce exactly as before.

## Finding

Reported by a security scan against `2e38c91` (CWE-789, excessive memory allocation, severity high); the mechanism checks out against the tree and the graphql-java 25.0 jar.

1. `GraphqlHttpHandler.doPost` binds the body to `GraphqlRequest` with the shared `JSONB` binder, which has no number handling configured. Every number inside the untyped `Map<String, Object> variables` / `extensions` therefore becomes a `BigDecimal` that keeps a huge exponent as its scale, costing the attacker only a few bytes.
2. `doGet` decodes the `variables` and `extensions` query parameters through `parseMapParam` and the same binder, so GET has the same weakness as POST.
3. `execute` passes both maps unchanged to `ExecutionInput.Builder.variables(...)` / `.extensions(...)`. The call is synchronous, has no deadline, and nothing checks precision, scale or range first.
4. graphql-java's `GraphqlIntCoercing.convertParseValueImpl` runs `new BigDecimal(value.toString()).toBigIntegerExact()` before its 32-bit range check (confirmed in the 25.0 bytecode). That materialises the full power of ten as a `BigInteger`, and the rejection message then renders it with `BigInteger.toString()`, which costs a second super-linear conversion and is serialised into the response.
5. The same unbounded `BigDecimal` also reaches `Float`, `ID`, custom scalars (extended-scalars `Long` / `BigInteger` coercion, for example), and consumer `@service` code that reads arguments.

## Acceptance (from the finding's fix criteria)

* Numbers in `variables` and `extensions` are bounded at any nesting depth (objects and lists) on both POST and GET before `ExecutionInput` is built.
* A value whose exponent, scale or digit count exceeds a small fixed limit is either rejected as a `4xx` request error (the handler's existing `requestError` shape, `200` under the legacy media type), or mapped to a representation that cannot be inflated. The Spec picks one; the second option means a `long` when the value is integral and in range, and a `double` otherwise.
* Work and memory are proportional to request size.
* A test gives an `Int`, a `Float`, an `ID` and a custom-scalar variable a number with a very large positive and a very large negative exponent, over POST and GET, and asserts a prompt, small request-error body.
* Ordinary integer, decimal and exponent values still coerce as before. The existing `graphitron-jakarta-rest` tests stay green, plus a test pinning the ordinary cases.

## Constraints

`graphitron-jakarta-rest` compiles at `<release>17</release>` (CLAUDE.md, Technology constraints), so the fix cannot use Java 25 syntax. The binder is resolved from whatever JSON-B provider the consumer ships (Yasson, `quarkus-jsonb`), so a fix that walks the decoded maps holds across providers, where one that depends on a provider-specific number-handling option does not.

## Related

`R1188` (from the same scan) bounds the emitted `ColumnValues` runtime class against the same huge-exponent shape on federation keys and tenant values. The two are complementary, not overlapping. This item closes the hole at the HTTP boundary for every consumer of `graphitron-jakarta-rest`, built-in and custom scalars included. `R1188` closes it in generated code, whatever transport sits in front. Neither depends on the other.
