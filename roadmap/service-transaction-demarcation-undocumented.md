---
id: R721
title: "The transaction demarcation difference between @mutation and @service is undocumented"
status: Ready
bucket: docs
priority: 3
theme: service
depends-on: []
created: 2026-08-19
last-updated: 2026-08-31
---

# The transaction demarcation difference between @mutation and @service is undocumented

A generated `@mutation` write runs inside a transaction graphitron opens. A `@service`
invocation does not: the generated fetcher calls the developer's method on a bare
`DSLContext` and the service owns its own boundaries. Both facts are true and both are
deliberate. Neither is stated on the pages an author reads while choosing between the two
directives, so a schema can carry a generated batch mutation and a service batch mutation
side by side with different integrity guarantees and no visible cue. This item writes the
demarcation contract down. It changes no emitted code.

## Evidence

The wrap side, per DML shape:

* Bulk DML (`INSERT`/`DELETE` record carriers and payload-returning bulk `UPDATE`) goes
  through `TypeFetcherGenerator.buildBulkRecordTwoStepFetcher`
  (`TypeFetcherGenerator.java:5329`), which emits one `dsl.transactionResult(tx -> { ... })`
  with the per-row loop inside it. Its comment states the rollback contract: "on any per-row
  throw ... the transaction rolls back", so a batch is all-or-nothing.
* Single-row two-step DML and the mutation `@routine` write path emit the same boundary
  through `emitKeysTransaction` (`TypeFetcherGenerator.java:4569`).
* The empty-input short-circuit is the one arm that opens no transaction, because it runs no
  DML (`TypeFetcherGenerator.java:5359`).

The no-wrap side:

* All four service-backed root fetchers (query table, query record, mutation table, mutation
  record) share `TypeFetcherGenerator.buildServiceFetcherCommon`
  (`TypeFetcherGenerator.java:1647`). The body is a `try`, an optional `dsl` local, the
  invocation, and a `catch (Exception e)` routing into the error channel. No transaction
  appears, and the method's own javadoc already states the contract in words: "the
  developer-supplied method owns the transaction scope" (`TypeFetcherGenerator.java:1627`).
* The child service arms match. `ServiceRowsFragments.delegateBody` and
  `ServiceRowsFragments.liftBody` declare `dsl` only when the borrowed call shape needs one
  and then delegate.

So the asymmetry is not an accident of one arm, it is uniform on both sides, and the
generator already documents it to contributors. What is missing is the author-facing
statement.

What the manual says today:

* `docs/manual/reference/directives/mutation.adoc:5` states the generated write "runs inside
  a transaction". The `@mutation` half of the contract is published.
* `docs/manual/reference/directives/service.adoc:5` mentions transactions only as a *reason
  to reach for* `@service` ("multi-statement transactions"), which reads as if the framework
  provides one. The Constraints list (`service.adoc:121-128`) covers `className`, `method`,
  `argMapping`, mutual exclusion with `@mutation`, the `@splitQuery` requirement on non-root
  fields, and that DB-customisation directives are ignored. It says nothing about
  transactions.
* `docs/manual/how-to/error-channel.adoc` Pitfalls (`error-channel.adoc:134-142`) is where an
  author reading about partial failure lands. It covers source order, `description:`, `path:`,
  pre-execution `VALIDATION`, cause-chain unwrap, redaction and carrier requirements. It does
  not say that an error payload from a `@service` field carries no rollback promise, while one
  from a DML `@mutation` carrier does.
* `docs/manual/how-to/handle-services.adoc` has no transaction mention at all.
* The single place the asymmetry is stated is `docs/manual/how-to/mcp-agent-context.adoc:105`,
  and it states it for the *query* path, as a caveat on the MCP `execute` rollback guarantee:
  "a *query*-path `@service` or `@routine` that writes runs outside it, exactly as it runs
  outside graphitron's transaction demarcation in production." Right fact, wrong page, and
  scoped to the half the reporter did not hit.

## Field report

Reported on 10.0.0-RC31 at https://github.com/sikt-no/graphitron/issues/530. A `@service`
batch mutation whose contract was all-or-nothing (documented in the service javadoc, and
matching what the sibling generated `@mutation` batches in the same schema do) partially
committed: in a two-element batch where element 2 raised, element 1 was already committed
while the error payload told the client nothing had been created. Their fix was
`ctx.transactionResult(...)` inside the service, which they consider the correct permanent
home for it rather than a workaround.

Their framing is the one to adopt: **the asymmetry is the footgun, not the behaviour.** They
are explicitly not asking for auto-wrapping. The opt-in directive they suggest as an optional
follow-up is `roadmap/service-opt-in-transaction-wrap.md` (R722); this item is the docs half
and does not depend on it.

Two details from the report are worth keeping in the docs prose because they are what made
the failure invisible rather than merely surprising. The service's own javadoc claimed
all-or-nothing, so the author believed the contract was satisfied somewhere. And the error
payload was well-formed, so the client had no signal that a partial commit had happened.

## Position

Graphitron demarcates transactions for the writes it generates, and does not for the writes
it delegates. That is the correct division: `@service` is the escape hatch, and owning the
implementation includes owning the commit point. The defect is purely that an author has to
read the generator to learn it.

The contract is symmetric enough to state in one paragraph and worth stating in the same
words on both pages, so that whichever page an author lands on carries the whole fact rather
than half of it. Say what graphitron wraps, say what it does not, and say what that asks of a
service author. Do not hedge it as a limitation or a planned improvement: it is the designed
contract, and R722 would add an opt-in convenience on top of it, not replace it.

One scope call: this item states the contract for the mutation path and the query path
together. The query path is where a writing `@service` or `@routine` is *also* untransacted,
the fact `mcp-agent-context.adoc` already publishes, and splitting the statement by operation
type would leave the same gap one page over.

## What to write

Three edits, no new pages.

* **`docs/manual/reference/directives/service.adoc`.** A Constraints bullet, in the voice of
  the existing bullets: graphitron opens no transaction around a `@service` invocation, so the
  method owns its own boundaries, and a service whose contract is all-or-nothing must open one
  (`ctx.transactionResult(...)` on the injected `DSLContext`). Name the contrast explicitly,
  because the contrast is the footgun: a generated `@mutation` write, batch included, runs
  inside a transaction graphitron opens. Amend the page's opening sentence too
  (`service.adoc:5`), whose "multi-statement transactions" currently reads as a promise that
  the framework supplies one; it should read as the reason you need the escape hatch. Add an
  xref to the error-channel pitfall.
* **`docs/manual/how-to/error-channel.adoc`.** A Pitfalls bullet, since Pitfalls is where the
  partial-failure question is asked: an error payload from a `@service` field says the call
  failed, not that nothing was written, because graphitron opened no transaction around it. A
  DML `@mutation` carrier's error payload does carry the rollback promise
  (`buildBulkRecordTwoStepFetcher`'s boundary), and a mutation `@routine` write carries it for
  statement 1 only, the caveat `routine.adoc:172` already publishes. State that a service that
  needs the promise must open the transaction itself.
* **`docs/manual/reference/directives/mutation.adoc`.** One clause where the transaction is
  already claimed (`mutation.adoc:5`), pointing at the `@service` statement, so the two pages
  are reciprocal rather than one-directional.

Wording constraint for all three: they must not promise `@service(transactional:)`. R722 is
undecided, and a docs page that trails a directive we have not committed to is worse than the
silence it replaces.

## Verification

The claims are checkable against the tree, and should be checked rather than transcribed from
this item, which will age:

* Re-derive the wrap census from `TypeFetcherGenerator` before writing: `dsl.transactionResult`
  occurrences in main sources, and confirm no service arm has acquired one.
* Confirm `buildServiceFetcherCommon` still carries the "developer-supplied method owns the
  transaction scope" sentence, and align the docs wording with it so the two do not drift.
  This is the one place a contributor-facing and an author-facing statement of the same
  contract will sit side by side.
* `mvn install -Plocal-db` for the AsciiDoc render, since `.adoc` breakage fails CI. No test
  tier is in play: this item adds no behaviour and therefore no enforcer.

There is no build gate that would catch this class of omission, and this item does not add
one. A gate comparing emitted transaction boundaries against doc prose is not a thing we know
how to build cheaply, and the honest record is that the next such gap will also be found by a
consumer.

## Out of scope

* Any change to what is emitted, including the opt-in wrap (`roadmap/service-opt-in-transaction-wrap.md`).
* Auto-wrapping a `@service` invocation, which the reporter argues against and we agree with;
  the reasoning is recorded in R722's Position so it survives if this item ships alone.
* The read-only enforcement question for delegated paths, owned by
  `roadmap/query-read-only-enforcement.md`.
* Changing `mcp-agent-context.adoc`, whose statement of the fact is correct for the guarantee
  it is qualifying.

## Related

* `roadmap/service-opt-in-transaction-wrap.md` (R722), the opt-in directive half of the same
  report. Independent: either can ship first.
* `roadmap/query-read-only-enforcement.md`, which reasons about what graphitron may assume
  about a path it does not control (`@routine`, `@service`). Nearest neighbour for the framing,
  no overlap in the edits.
* `roadmap/upsert-docs-match-dispatch-refusal.md` and
  `roadmap/lookupkey-per-input-field-doc-claim.md`, the two live instances of the same class:
  a manual page whose claim and the generator's behaviour disagree. This one is an omission
  rather than a false claim, which is why it is a bullet rather than a rewrite.
