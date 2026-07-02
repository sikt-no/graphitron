---
id: R423
title: "redaction reference id derives from OTel trace_id (via MDC) when present"
status: Backlog
bucket: enhancement
priority: 3
theme: mutations-errors
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# redaction reference id derives from OTel trace_id (via MDC) when present

## In one paragraph

Both redaction sites, the generated `ErrorRouter.redactBody()` (per-fetcher, inside graphql-java
execution) and the hand-written `GraphqlResource.execute()` guard (R421, pre-execution seam faults),
mint a fresh `java.util.UUID.randomUUID()` as the client-facing reference and log it alongside the real
cause. That UUID correlates exactly one thing: the client's `Reference: <uuid>` string and the single
server `ERROR` log line carrying the same uuid. It is **not** the OpenTelemetry `trace_id`/`span_id` and
is attached to no span, so in an OTel-instrumented deployment an operator cannot pivot from the client
error into the trace backend; they grep logs by UUID instead (a two-hop path if the log line also
carries `trace_id` via the consumer's MDC instrumentation). This item makes the reference id **derive
from the ambient trace when one is present**: read `org.slf4j.MDC.get("trace_id")` (SLF4J-only, the
neutral bridge OTel's log instrumentation already populates, so **no OpenTelemetry dependency** and no
breach of the module's vendor-neutral constraint, R416), use it as the reference when non-blank, and
fall back to a random UUID when absent (no OTel, plain logging). The result: a one-hop pivot from a
client error straight to the trace when OTel is running, graceful degradation to today's behaviour when
it is not.

## Why it matters

R421 established a genuine but basic correlation (client reference ↔ log line). Sikt's gov/edu
consumers running an observability stack want the stronger property: the reference a client quotes in a
support ticket resolves directly to a distributed trace. Doing this without an OTel dependency keeps the
library shippable to consumers who do *not* run OTel, so the same artifact serves both.

## Scope sketch (fill in at Spec)

- **Cross-cutting: both sites must move together** or the "single wire shape" contract R421 pinned
  (`GraphQLOverHttpConformanceTest.redactionShapeMatchesFetcherPath`) drifts. The generated
  `ErrorRouterClassGenerator.redactBody()` is generator territory; the resource guard is hand-written
  runtime. A Spec must decide whether the shared helper is emitted, duplicated, or lifted.
- **Vendor neutrality holds:** `MDC` is `org.slf4j` (already a `provided` dep after R421); no new
  dependency, no `io.opentelemetry` import.
- **Key `trace_id` is the OTel logback/log4j MDC convention;** confirm the exact MDC key(s) the target
  consumers' instrumentation uses (`trace_id` vs `traceId`) and whether to probe more than one.
- **Test:** extend the R421 conformance assertions so the reference equals the active `trace_id` when a
  trace is present, and remains a UUID when MDC is empty; keep message-identity across both redaction
  sites.

## Depends on

R421 (the resource-side redaction site this generalises); the generated `ErrorRouter` redaction contract.
