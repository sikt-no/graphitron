---
id: R776
title: "An agent cannot run SQL against the fact store, only the queries we anticipated"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# An agent cannot run SQL against the fact store, only the queries we anticipated

The MCP server answers from the fact store through a fixed set of tools: `catalog.tables`,
`catalog.describe`, `catalog.search`, `schema`, `code`, `diagnostics`, `diagnostics.aggregate`,
`status`. Each is a query somebody wrote in advance. An agent debugging a generator verdict has a
question none of them asked, and 215 relations that hold the answer, so it either gives up or asks its
human to go look. The fixed tools are the right shape for the questions they cover; the gap is that
there is no way to ask anything else.

When this item lands, an agent sends read-only SQL and gets rows back as structured content, over the
same facts the session is answering from. It is the agent-side counterpart to the psql console for
humans, and it needs nothing from that console: no port, no wire protocol, no linked database.

## What was measured

Against H2 2.4.240, on a store booted from the real fact schema.

- **One statement grants a read-only reader.** `GRANT SELECT ON SCHEMA PUBLIC TO <user>` is accepted,
  so the grant does not have to be issued per relation, and it covers relations created *after* the
  grant, which means nothing has to re-grant when the schema grows.
- **The rights are real, and they close the write paths.** As that user: `SELECT` on tables and views
  is allowed, `information_schema` stays readable (so introspection still works), while `INSERT`,
  `UPDATE`, `DROP TABLE` and `CREATE TABLE` are refused with "Not enough rights", and
  `CREATE LINKED TABLE` (the writable back door onto another database) is refused with "Admin rights
  are required". The store's rows were unchanged after all of it.
- **This enforcement is available here but not on the psql door.** H2's `PgServerThread` issues
  `SET DEFAULT_NULL_ORDERING HIGH` at connect, which requires admin, so the wire protocol refuses a
  rights-limited user outright. A plain in-process JDBC connection has no such constraint, which is why
  the tool can hold its contract structurally while the console cannot.

## The design

**A `store.query` MCP tool over a rights-limited connection onto the store itself.** No second
database and no links: the tool holds a connection authenticated as a `SELECT`-only H2 user, so
read-only is enforced by the engine on every statement rather than by anything this codebase parses.

The point of the grant is not defence against an attacker, since this is a dev tool on a developer's
machine with no listener of its own. It is that "read-only" becomes a property of the connection rather
than a promise this codebase keeps by inspecting SQL: a model-generated `DELETE` is refused by H2
instead of by a filter we would have to keep correct as the tool grows.

The store mints that connection, as it mints `reader()` and the console, and for the same reason: the
url is private. The shape to extend is `StoreReader`, which already answers exactly the way a query
tool should (its own connection so it never blocks behind a capture, one transaction per answer so a
multi-query answer cannot straddle two commits, and a rollback at the end so nothing it does can reach
the writer's rows). What this item adds to that shape is the rights-limited user and a statement
surface that takes SQL from outside.

**One statement per call, as a response-shape rule rather than a guard.** A call returns one
`columns`/`rows` pair, so a payload carrying several statements has no well-defined answer and is
refused for that reason. Writes are already impossible by grant; this is about the tool having one
contract.

**Bounded answers.** A tool response is not a cursor: the tool caps rows (a `limit` argument with a
default, plus a hard ceiling) and reports truncation explicitly in its structured content, so an agent
learns that it saw part of the answer instead of concluding the tail does not exist. A query whose
result exceeds the ceiling is where an agent should reach for the psql console instead, and the
truncation message says so.

## Implementation

`graphitron-model`, `no.sikt.graphitron.model.boot`:

- `GraphitronModelStore.queryReader()` (name to settle at implementation), minting a `StoreReader`-
  shaped handle on a connection authenticated as the read-only user, creating the user and issuing
  `GRANT SELECT ON SCHEMA PUBLIC` on first use. The password is fixed and internal, never printed or
  handed out: nothing outside the store opens this connection, and the tool holds no listener. It is a distinct
  member from `reader()` rather than a flag on it: the existing readers are the session's own
  machinery and must keep full read access to relations a grant might not cover, and a caller should
  not be able to get the constrained thing by accident or the unconstrained thing by typo.
- The handle exposes one door: run one SQL string, return rows plus column metadata, roll back. It
  does not expose `DSLContext`, for the same reason `StoreReader` does not.

`graphitron-mcp`, `no.sikt.graphitron.mcp`:

- New `StoreQueryTool` beside `ExecuteTool`, registered by `GraphitronMcpServer` on the same terms as
  the store-backed tools: `{sql, limit?}` in, `{status, columns, rows, rowCount, truncated}` out,
  refusals as structured content (`status: invalid` with the reason) rather than as exceptions, which
  is what `catalog.search` already does for a bad argument.
- Ambient instructions gain a routing sentence, in the conditional-composition shape
  `instructions-execute.txt` uses, so an agent is told the tool exists and told that it is read-only
  and row-capped.

## User documentation (first-client check)

`docs/manual/how-to/mcp-agent-context.adoc`, beside the other store-backed tools:

> **Ask the fact store a question nobody anticipated.** `store.query` takes one read-only SQL statement
> and returns `columns` and `rows` as structured content, over the same facts every other tool answers
> from. The connection is `SELECT`-only, enforced by the database rather than by a filter, so a
> statement that writes is refused rather than rolled back, so a generated `DELETE` cannot touch the
> session's rows. Answers are capped: pass `limit` to widen
> up to the ceiling, and check `truncated` before concluding an empty tail means no rows. For a result
> too large to carry, or an interactive session, use the psql console instead.

If that draft does not read simply at implementation time, the design is wrong and changes first.

## Tests

`graphitron-model` unit tier, `StoreQueryReaderTest`:

- A `SELECT` over a booted store returns rows and column metadata; a view is readable; a query over
  `information_schema` is readable.
- `INSERT`, `UPDATE`, `DELETE`, `DROP TABLE`, `CREATE TABLE` and `CREATE LINKED TABLE` are each
  refused, and the store is unchanged afterwards. This is the item's central claim, so every arm is
  pinned rather than sampled.
- A relation created after the grant is readable, the property that keeps the grant from needing
  maintenance as the fact schema grows.
- The session's own `reader()` keeps full read access while the query reader is constrained, so the
  new member cannot have narrowed the old one.

`graphitron-mcp`, MCP-handler tier, `GraphitronMcpServerTest`, structured-content assertions only:

- `store.query` is advertised in `tools/list`; a `SELECT` returns columns and rows; a write statement
  returns `status: invalid` with the refusal reason rather than throwing.
- A multi-statement payload is refused.
- A result past the cap comes back truncated with `truncated: true` and a row count at the cap, the
  arm that stops an agent from reading a clipped answer as a complete one.
- `ServerInstructionsTest`: the routing sentence appears exactly when the tool is registered.

## Out of scope

- Write access of any kind, including a "confirm" argument. The store is the session's, and an agent
  that wants rows changed should change the schema and let a round capture them.
- Query plans, `EXPLAIN` shaping, or cursors. If an answer does not fit a capped response, the psql
  console is the door for it.
- Anything about the psql console itself, which is specified in its own item; this one shares no code
  with it beyond the store's minting convention.

## Open questions

- The row ceiling's value, and whether `limit` should be allowed to exceed it for a caller that
  explicitly asks. Settle from what a tool response can carry comfortably rather than from taste.
