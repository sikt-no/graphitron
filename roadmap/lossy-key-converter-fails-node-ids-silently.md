---
id: R1079
title: "A node-key column whose converter does not round-trip makes its ids resolve to null with no diagnostic"
status: In Review
bucket: dx
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-20
last-updated: 2026-10-21
---

# A node-key column whose converter does not round-trip makes its ids resolve to null with no diagnostic

## Goal

When a node id that Graphitron itself minted fails to decode because the key column's jOOQ
converter does not round-trip the value, the consumer can find out why: the manual states that a
key column's converter must round-trip, and the server logs a warning naming the column, so an id
that resolves to `null` is diagnosable instead of indistinguishable from a deleted row.

## Evidence

From the sis Graphitron 9 against 10 differential test, retested on trunk after R1072 (2026-10-20).
sis declares six node-key `DATE` columns with a custom `Binding<LocalDate, OffsetDateTime>` whose
real read and write path keeps the time part, but whose `converter()` goes through `LocalDate` and
drops it (jOOQ consults `converter()` mainly for the classes, so the lie is invisible to jOOQ
itself). The generated `ColumnValues.exact` accepts a decoded slot only if the value survives
`type.getConverter()` to the database type and back with its text unchanged. For a value with a
time part it does not, so the id answers `null`: no error, no log line.

Three of the six columns hold a time part in sis's data. One backs a stable `@key` entity that is also
a `Node`; on v10 only 9 of the first 20 ids a list field returns resolve through `_entities`, where v9
resolved all 20. A production client calls both that list field and `node(id:)`. The consumer has
agreed to fix its declarations (a datetime type with a lossless converter), so this item is not
about making the lossy declaration work; it is about the next consumer with such a declaration being
able to see it.


## What triggers the warning

A node id carries one text *slot* per key column. Decoding a slot runs it on the *column trip*, the
generated `ColumnValues.exact` check that parses the text, takes the value through the column's
converter to the database type and back, and accepts it only if what comes back prints as the text
it started from. R1072 made the parse step follow the column: a slot over a converter whose user
type jOOQ has a text form for (`SlotParse.UserType`, which carries that type as the slot's *parse
class*) is parsed as the user type, a slot with no converter (`SlotParse.ColumnType`) as the column's
own type, and a slot over a converter to the consumer's own class (`SlotParse.DatabaseType`) as the
database type.

The warning fires when a `UserType` slot parses, the trip succeeds, and the converter changed the
parsed value: `from(to(parsed))` is not `parsed`. It compares values, not text. "The text parsed and
the round trip changed the text" also holds for a padded `01` or a reformatted `1.0` on an integer
key, which involve no converter and which the manual already calls malformed (`global-id.adoc` §
Decode-side errors); a warning on those would point an operator at a converter that is fine. With no
converter the trip runs jOOQ's identity converter, so a value comparison cannot fire there.

The comparison is restricted to `UserType` slots because only there is equality meaningful. Their
parse class is one of `SlotParse.TEXT_FORM_CLASSES`, jOOQ's built-in value types, which all have value
equality; `Objects.deepEquals` covers the one array member (`byte[]`). On a `DatabaseType` slot both
sides come out of the consumer's `from`, into a class that need not override `equals`, so the
comparison would fire on every refused id; that arm keeps the manual's existing rule (the converter's
database-side text must match the class's `toString()`) and gets no warning. sis's declaration is a
`UserType` slot: its user type `OffsetDateTime` is `SQLDataType.TIMESTAMPWITHTIMEZONE`'s type.

What the trigger cannot tell apart from a lossy converter is a hand-built id carrying a value the
column never holds but the converter folds: a time of day on a `DATE` column whose converter is
lossless for the midnights the column actually holds, or `"0120"` on a `String` user type over a
`bigint` mapped by `Long::valueOf` / `String::valueOf`. The message therefore states a fact true in
both cases (the converter does not give that value back), and the once-per-column limit bounds what a
client can put in the server log.

## Implementation

- `ColumnValuesClassGenerator`. The private `Trip` gains the parsed user value as a third field,
  `parsed`, set in `trip` from the first step's `user`. A new public static method
  `<T> T folded(DataType<T> type, Class<?> parseClass, String text)` answers the canonical value when
  `text` and `parseClass` are non-null, the trip succeeds, and `!Objects.deepEquals(trip.parsed,
  trip.canonical)`; otherwise `null`. Like `exact` and `coerce` it never throws for a value that is
  not a value of the column. `exact` is unchanged in contract and body: such a slot still answers
  `null`, as R1067 decided for the lookup surfaces and arguments alike. The class javadoc gains a
  third bullet for `folded`.
- `NodeIdEncoderClassGenerator`. `decodeColumns` is the one place every decode surface reaches a key
  slot (the per-type `decode<Type>`, `RecordDecodeFragments`, `HandleMethodBody`,
  `JooqRecordInstantiationEmitter`), and the one that holds the column as a `Field`, so the warning
  lives there and `ColumnValues` stays free of column identity and logging. In the loop, when
  `decoded[i]` is `null` and the slot's parse class is non-null, call a new private static
  `reportFold(Field<?> column, Class<?> parseClass, String text)` before `return null`. It calls
  `ColumnValues.folded`; on a non-null answer it logs, at `WARN` the first time a column is seen and
  at `DEBUG` after that, keyed on `column.getQualifiedName().toString()` in a static
  `ConcurrentHashMap.newKeySet()`. The set is bounded by the key columns the generated code names,
  since every `decodeColumns` caller passes generated table constants. The encoder gains an slf4j
  `LOGGER` field, as the generated `ErrorRouter` already carries one. The
  accept path is unchanged; a refused `UserType` slot pays one more trip.
- The message, with the column, the slot text and the value it came back as:
  `Node id key column {} has a converter that does not give back the value {} (it comes back as {}),
  so node ids carrying that value decode as malformed. A key column's converter must take every
  value the column holds to the database type and back unchanged.` Both values are client-supplied
  text, so they pass through a small private `logSafe(Object)` in the encoder that escapes control
  characters and caps the rendering at 64 characters; a slot can carry a newline once it is
  base64-decoded, and a `String` user type parses anything.
- `docs/manual/how-to/global-id.adoc`, the converter bullet in § Constraints and pitfalls, after the
  existing two sentences: a key column's converter must take every value the column holds to the
  database type and back unchanged; a converter narrower than the column's read path, such as a
  jOOQ `Binding` whose `converter()` goes through `LocalDate` while its read keeps the time of day,
  makes the ids of the rows whose value it changes decode as malformed (`null` on the lookups, a
  client error on an argument); the server logs a warning naming the column the first time it
  refuses such an id.

No generation-time change: whether a converter loses anything depends on the values in the column,
which the generator never sees.

## Tests

- `ColumnValuesTest` (unit tier, `graphitron-sakila-example`), reusing its fixtures: `folded` answers
  `2007-08-22T00:00` for `DATE_ONLY` with parse class `LocalDateTime` and text `2007-08-22T02:24`
  (the sis shape: a time part the database type cannot hold), and `null` for `DATE_ONLY` at
  `2007-08-22T00:00` (round-trips), `DATE_WITH_TIME` at `2007-08-22T02:24` (lossless), `INTEGER`
  with text `01` and no parse class (a padded number, no converter), `WRAPPED_CODE` with no parse
  class (a `DatabaseType` slot), and a text that does not parse (`abc` on `ORG_CODE`).
- A new unit-tier `NodeIdFoldWarningTest` beside it, capturing the encoder's logger with logback's
  `ListAppender` as `graphitron-lsp`'s `StoreOutOfBudgetTest` does. Over a `Field` built with
  `DSL.field(DSL.name("fold", "<column>"), DATE_ONLY)` (a distinct column name per test, since the
  seen-set is static): an id minted by `NodeIdEncoder.encode` from `2007-08-22T02:24` decodes to
  `null` through `decodeColumns` and logs exactly one `WARN` naming the column and both values; a
  second decode of the same id logs no further `WARN`; an id carrying `00:00` decodes and logs
  nothing; an id whose slot is `01` over an `INTEGER` field decodes to `null` and logs nothing; and
  over a `String` user type on `bigint` whose converter strips whitespace before `Long.valueOf` (the
  forged-id case above), a slot `7` followed by a newline logs one `WARN` with the newline escaped.

Completeness rests on those two classes: every surface reaches the slot through `decodeColumns`, and
R1067's execution cases already pin that a refused slot answers `null` on the lookups and an error on
an argument, which this item does not change. No execution fixture is added; a lossy key converter in
the sakila schema would exist only to be wrong.

## Implementation notes

Shipped as planned, with one deviation in the test harness. `NodeIdFoldWarningTest`
cannot use logback's `ListAppender`: `graphitron-sakila-example` has logback on its test classpath,
but its slf4j binds to the JBoss LogManager that Quarkus brings, so `LoggerFactory.getLogger` hands
back a JBoss logger. The test instead adds a `java.util.logging.Handler` to
`org.jboss.logmanager.Logger.getLogger(NodeIdEncoder.class.getName())`, which receives the record
with the slf4j message already formatted. Because test classes run concurrently and the logger is
shared, each test reads back only the records naming its own column. The emitted `NodeIdEncoder` in
`graphitron-sakila-example/target/generated-sources` shows the `decodeColumns` / `reportFold` /
`logSafe` shape the plan describes.

## Other solutions we've considered

- *Warn on any slot whose trip succeeds but whose text changes.* That was the Backlog sketch's
  condition. It fires on a padded or reformatted number over a plain column, so it would send the
  operator after a converter for what is a hand-built id.
- *Pass the `Field` into `ColumnValues.exact` and log there.* It puts column identity and a logger
  into the class whose contract is one stateless answer per value, and changes the signature every
  `ColumnValuesTest` case calls, for a warning only the node-id decode needs.
- *A startup or test-helper check over a sample of rows.* It finds the fault before a client does,
  but needs a database connection and a sampling policy at a point where generated code has neither,
  and covers only the rows sampled. The consumer who met this fault found it by differential test;
  the warning reaches the next one in production logs without either.
- *A generation-time warning when a converter's user type is wider than its database type.* The
  generator cannot see the values; the same shape is lossless over a column that holds only
  midnights, which would make the warning noise on a correct declaration.
