---
id: R1060
title: "A DATABASE @error handler's matches never sees the SQL text jOOQ wraps around the SQLException"
status: Spec
bucket: bug
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A DATABASE @error handler's matches never sees the SQL text jOOQ wraps around the SQLException

## Goal

A `DATABASE` `@error` handler's `matches` fires when its text appears in the message of any exception from the outermost throw down to the `SQLException` the handler keys on. That includes jOOQ's `DataAccessException`, whose message starts with the SQL statement. `code` and `sqlState` are still read off the `SQLException`. Handlers that key on a column name, which only the SQL text carries, then map to their typed error as they did in Graphitron 9, instead of falling through to redaction (the generic "An error occurred. Reference: <uuid>" field error). Every `GENERIC` handler, and every `DATABASE` handler without `matches`, behaves as before. A `DATABASE` handler with `matches` reaches further than it does today: if its text also appears in the SQL statement (a table or column name, a literal), it now also catches those errors. Dispatch is first-match-wins, so a broad handler declared ahead of a narrow one can take errors that used to go to the narrow one. That is Graphitron 9's rule, and handlers ported from Graphitron 9 were written for it.

The schema that needs it, from the sis port. The two types share one union and one vendor code, so only `matches` tells them apart:

```graphql
type UgyldigBankkontonummer @error(handlers: [
    {handler: DATABASE, code: "1438", matches: "BANKKONTONR",
     description: "Bankkontonummer kan ha høyst 11 sifre"}]) {
    path: [String!]!
    message: String!
}

type UgyldigInput @error(handlers: [
    {handler: DATABASE, code: "1438", matches: "PROSENTTALL",
     description: "Prosenttall kan ikke overstige 100"}]) {
    path: [String!]!
    message: String!
}
```

An over-long `bankkontonr` on Oracle raises `ORA-01438: value 1111111111111111 greater than specified precision (11, 0) for column`, or in a Norwegian session `ORA-01438: verdien er større enn presisjonsangivelsen som er tillatt for denne kolonnen`. Neither text names the column. jOOQ throws `DataException("SQL [update \"KJERNEAPI\".\"PERSON\" set ... \"BANKKONTONR\" = ? ...]; ORA-01438: ...", sqlException)`, so the column name appears only in the wrapper. After this item the payload's `errors` reads `[{__typename: "UgyldigBankkontonummer", message: "Bankkontonummer kan ha høyst 11 sifre", ...}]`. Today the field fails with a redacted error.

## Context

Reported from the sis port (trunk `f611072f5`). Two of the 179 sis handlers audited depend on SQL text: the two above. Graphitron 9's `DataAccessExceptionContentToErrorMapping.matches` took the code from the `SQLException` and checked `matches` against the `DataAccessException`'s message. sis relies on this on purpose; main's `BankkontonummerErrorMappingTest` documents it, and it is also the choice that works whatever the database session's language. The audit found which handlers need the SQL text. It did not check whether the remaining handlers will now match more. The case for them is parity: all of them were written for Graphitron 9, where `matches` already saw the SQL text, so this item gives them back the reach they had when they were written.

Terms used below:

* **Dispatch** is the generated catch arm. It walks the field's `@error` handlers in declaration order and, for each one, the thrown exception's cause chain from the outermost exception inwards. The first (handler, exception) pair that matches wins, and that exception goes into the payload's `errors` list.
* Dispatch is **source-direct**: the matched exception itself is the list element (the "source"), never a wrapper. The error type's extra fields read their accessors off it, and the build checks those accessors against the handler's source class, `SQLException` for `DATABASE`.
* Two **read-side re-checks** run later, against the source alone. The union `TypeResolver` picks `__typename`. `<ErrorType>Fetchers.message` decides whether `description` overrides the source's own message.

Cause: the generated `ErrorRouter.VendorCodeMapping.match(Throwable)` and `SqlStateMapping.match(Throwable)` accept only `throwable instanceof SQLException`, and they test `matches` against that same throwable's message. Dispatch offers one link of the chain at a time, so no single `match` call ever sees the code and the SQL text together. Fixing dispatch alone is not enough. The source has to stay the `SQLException`: the extra-field accessor contract names it, and its message is what a type without `description` shows the client. The source does not reach the wrapper above it, so both re-checks would still fail. The two sis types sit in one union, and only `matches` separates them, so the `TypeResolver` needs the wrapper's text as much as dispatch does.

The `TypeResolver` is an obstacle in its own right, and this item removes it too (see Implementation, "TypeResolver"). The resolver is a hand-rolled `instanceof` ladder (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`) that re-implements the three discriminators instead of walking the `ErrorRouter.Mapping[]` tables that dispatch and `message` use. Left alone, it would need a third spelling of the new rule. The two spellings have already drifted apart. Given a malformed exception class name, `ErrorMappingsClassGenerator.bestGuessOrObject` falls back to `Object`, while the ladder calls `ClassName.bestGuess` directly and the generator crashes.

The model has another obstacle, which this item also removes (see Implementation, "Model"). `{handler: DATABASE, matches: X}`, with no `code` and no `sqlState`, classifies as an `ExceptionHandler` on `java.sql.SQLException`. A `GENERIC` handler naming that class produces the same thing. So the rule here could not reach that spelling, and the docs would have to say `matches` behaves differently depending on which `DATABASE` discriminators an author wrote.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, the `matches` row:

> `matches` | no | Substring the exception message must contain. For `GENERIC`, the message of the exception whose class matched. For `DATABASE`, the message of the `SQLException` or of any exception wrapping it, so a handler can key on the SQL text jOOQ puts in its `DataAccessException` (`SQL [update ... "bankkontonr" = ? ...]; ...`), which is often the only place a column name appears. The flip side is that a `DATABASE` text which also occurs in the SQL (a table or column name) matches every error of that code raised by such a statement, so pick a text specific to the error you mean. `code` and `sqlState` are always read off the `SQLException` itself. Useful to narrow a broad `className`, `code` or `sqlState` to one specific error.

In the same file, the `handler` row's `DATABASE` sentence gets a clause: `matches` reads the messages from the outermost exception down to the matched `SQLException`, so it sees jOOQ's SQL text.

`docs/manual/how-to/error-channel.adoc` gets matching edits:

* The dispatch-loop snippet and the two facts beneath it: dispatch calls `ErrorRouter.claim`. The second fact names the `DATABASE` reach.
* The `description:` section's bullet "A `union` carrier resolves `__typename` by the source's own class". The resolver now runs the type's own dispatch mappings against the source and against the exceptions that wrapped it when it was claimed.
* The paragraph after the per-type `message:` walk ("the union's type resolver picks the type by source class") gets the same correction. Resolver and `message:` now run the same mappings, so agreeing with the `__typename` follows from that.

The docs read simply, so the rule is the right shape. One thing remains for authors to know: wrappers whose default message is `cause.toString()` (for example `CompletionException` or `new RuntimeException(cause)`) echo text that was already matchable.

## Implementation

**Model: one `DatabaseHandler` arm.** Every `DATABASE` entry lifts to `ErrorType.DatabaseHandler(Discriminator discriminator, Optional<String> matches, ClientMessage clientMessage)`, where the sealed `Discriminator` is `SqlState(String)`, `VendorCode(String)` or `Any`. It replaces `SqlStateHandler` and `VendorCodeHandler`, and the no-discriminator lift to `ExceptionHandler(ClasspathClass("java.sql.SQLException"))` in `TypeBuilder`'s `DATABASE` arm. `ExceptionHandler` becomes `GENERIC`-only. This mirrors the fact store, where `graphitron_ast_error_database_handler_entry` is already one relation per `DATABASE` entry, with `code` and `sql_state` as sparse alternatives under a CHECK ("alternative spellings of one assertion"). The model loses a permit rather than gaining one.

Sites that switch on the handler arms, to re-check at pickup:

* `GraphitronType.ErrorType`, `TypeBuilder`, `FieldBuilder`
* `MappingsConstantNameDedup`, `walker.internal.ChannelRuleChecks`, `CheckedExceptionMatcher`
* `ErrorMappingsClassGenerator`, `ErrorTypeFetcherClassGenerator`, `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver` (until the step below replaces it)

Rule 8 (duplicate criteria) keys a `DatabaseHandler` on `(discriminator, matches)`. Two `{DATABASE, matches: X}` entries on one channel therefore stay rejected, as they are today when both lift to `ExceptionHandler(SQLException)`. Today `{GENERIC, className: "java.sql.SQLException", matches: X}` and `{DATABASE, matches: X}` also lift to the same arm and criteria. After the split they are different variants that reach different exceptions with `matches`, so both are admitted as an overlap across variants. Pin both verdicts with tests.

This step changes no generated output, and the existing suite covers it, so an implementer may land it as its own commit ahead of the behaviour change. During this step `ErrorMappingsClassGenerator` keeps emitting `ExceptionMapping(SQLException.class, ...)` for `Discriminator.Any`.

**TypeResolver: walk the mapping tables.** `buildErrorPolymorphicResolver` stops re-implementing the discriminators. For each union participant in order, and each of its dispatch-capable handlers in declaration order, it emits `if (ErrorMappings.ByType.<T>[i].match(src)) return <T>;`. This uses the same `ByType` addressing as `ErrorTypeFetcherClassGenerator.messageMethod`, where `i` counts handlers and skips `VALIDATION`. A `VALIDATION` handler keeps its `src instanceof GraphQLError` test, because it has no mapping. `src` is cast once under `src instanceof Throwable`. With this the resolver, `message` and dispatch share one predicate per handler, and the bare `ClassName.bestGuess` call (the crash on a malformed class name) is gone. The step changes no behaviour and can land as its own commit after the model step. After it, the only edit the resolver needs for the new rule is the `matchesSource` swap under "Read side".

**Emitted router** (`ErrorRouterClassGenerator`):

* `Mapping.match(Throwable thrown, Throwable candidate)` replaces `match(Throwable)`. `candidate` is `thrown` or a link of its cause chain. `ExceptionMapping` reads only `candidate`, so `GENERIC` behaves as before.
* The `DATABASE` arms become one `DatabaseMapping(String sqlState, String vendorCode, String matches, String description)`, mirroring the model's `Discriminator`; `sqlState` and `vendorCode` both null means any `SQLException`. `candidate` must be an `SQLException` that passes the discriminator. `matches` then succeeds if any link from `thrown` down to `candidate`, inclusive, carries the text. Links below `candidate` do not count. If `candidate` is not reachable from `thrown`, only `candidate` is checked. One private static helper spells that walk.
* `public static Throwable claim(Throwable thrown, Mapping[] mappings, DataFetchingEnvironment env)`. It walks mappings in order and, per mapping, the chain outermost-first. It returns the first `candidate` for which `match(thrown, candidate)` holds, or `null`. "First (mapping, cause) pair wins" keeps its meaning. When `candidate != thrown`, it records `thrown` as a **chain root** of `candidate` (an exception the source was wrapped in when it was claimed).
* Chain roots live in a private nested holder, stored in `env.getGraphQlContext()` under the holder's class, the way `toConfiguredLog` finds `FetcherErrorLog`. The holder maps each source to a set of roots, both keyed by identity (an author's exception may override `equals`), and is safe for concurrent fetchers. Recording is a set union, so the outcome does not depend on fetcher completion order. A null context records nothing.
* `public static boolean matchesSource(Mapping mapping, Throwable source, GraphQLContext context)` is the one read-side entry. It is true if `mapping.match(source, source)` holds, or `mapping.match(root, source)` holds for any recorded root. With a null context or no record it reduces to the first test, which is today's behaviour.
* `dispatch` goes through `claim`. The payload still receives the `SQLException`.

**The second dispatch spelling** (`ErrorDispatchFragments.outcomeArm`, used by `ChannelCatchArmEmitter` and `RoutineWriteEmitter`). The inline double loop becomes `Throwable claimed = ErrorRouter.claim(e, ErrorMappings.<C>, env); if (claimed != null) return ...ErrorList(List.of(claimed)); return <redact arm>;`. That leaves one walk in the tree, on the router.

**Read side:**

* `ErrorTypeFetcherClassGenerator.messageMethod` emits `ErrorRouter.matchesSource(ByType.<T>[i], thr, env.getGraphQlContext())` in place of `ByType.<T>[i].match(thr)`.
* The `TypeResolver`, which walks `ByType` mappings after the step above, makes the same swap using `env.getGraphQLContext()`. Note the graphql-java spelling differs from `DataFetchingEnvironment`'s.

**Mapping mint** (`ErrorMappingsClassGenerator`): emit `DatabaseMapping` for the `DatabaseHandler` arm.

**Javadoc and docs:** update the `Mapping`, `dispatch`, `claim` and `matchesSource` javadoc strings, which the reference gate checks. The two manual pages change per the draft above.

Collision semantics are accepted, not engineered away. The same `SQLException` instance can be claimed by two fetchers in one request through different wrappers, for example in a DataLoader batch failure. Each reader then sees the union of roots, and a type whose `matches` text appears in only one wrapper resolves the same way on both fields. In the realistic case the wrappers share jOOQ's one `DataException`, so the roots agree. A divergence needs one `SQLException` to reach two channels through wrappers that carry different text. Record that in the `claim` javadoc.

## Tests

* **Router behaviour, unit tier in `graphitron-sakila-example`** (`test/internal`, alongside `ErrorRouterDispatchUnmatchedTest`). These use hand-built throwables against the generated `ErrorRouter`, mirroring the sis cases with `new DataException("SQL [update ... \"BANKKONTONR\" = ? ...]; ORA-01438: ...", new SQLException(oraText, "22003", 1438))` and vendor-code `DatabaseMapping`s:
  * the wrapper carries the text: `claim` returns the `SQLException`, and `dispatch` puts it in the payload;
  * the same with the Norwegian driver text;
  * `BANKKONTONR` and `PROSENTTALL` mappings in one table pick by text, and `PROSENTTALL` text maps to its own type;
  * neither message carries the text: `claim` returns `null` and `dispatch` redacts;
  * the text appears only below the candidate, in the `SQLException`'s own cause: no match;
  * `matchesSource` with a null context evaluates the source alone;
  * two roots recorded for one source, in either order, give the same answer;
  * a `GENERIC` `ExceptionMapping` on the wrapper's class still matches the wrapper by its own message (regression).
* **End to end, execution tier, Sakila on PostgreSQL.** PostgreSQL's `22003` text ("numeric field overflow") names no column either, so the bug reproduces for real on the `sqlState` path. Add a payload `@mutation` UPDATE on `film` that writes `rental_rate` (`numeric(4,2)`) and `replacement_cost` (`numeric(5,2)`), with an errors union of two `DATABASE` types `{sqlState: "22003", matches: "rental_rate", description: ...}` and `{sqlState: "22003", matches: "replacement_cost", description: ...}`. Check the jOOQ SQL text's column spelling at pickup and set `matches` to it. Cases:
  * an overflowing `rental_rate` returns `{film: null, errors: [{__typename: <rental type>, message: <its description>}]}`;
  * an overflowing `replacement_cost` resolves to the other type;
  * a second payload `@mutation` with the same input whose errors union carries only the `rental_rate` type: an overflowing `replacement_cost` there returns the redacted field error, because the handler's `matches` text is not in that statement's SQL. (`film`'s other columns that can overflow are `smallint`, and those values pass GraphQL `Int` coercion and jOOQ's `Short` binding before PostgreSQL sees them, so none of them makes a dependable third column.)

  This proves dispatch, `TypeResolver` and `message` agree. That is the invariant the chain-root record holds, and nothing at build time ties the writer to its readers.
* **Classification tier.** Existing `ErrorChannelClassificationTest` and `GraphitronSchemaBuilderTest` handler rows retarget to `DatabaseHandler`, one case per `Discriminator` arm. Two new Rule 8 cases: `{GENERIC, className: "java.sql.SQLException", matches: X}` and `{DATABASE, matches: X}` on one channel are both admitted, and two `{DATABASE, matches: X}` entries on one channel are rejected.
* **Generator tier.** Update `ErrorRouterClassGeneratorTest` and `ErrorMappingsClassGeneratorTest` for the two-argument `match`, `DatabaseMapping` and `claim`, keeping their existing assertion style. For the `TypeResolver` step, assert that the emitted resolver names `ErrorMappings.ByType` and contains no `instanceof` on an exception class. The `bestGuess` drift then closes by construction, since the resolver no longer names the class. The union execution tests that exist today pin that the step changes no behaviour.
* **sis acceptance.** `BankkontonummerErrorMappingTest.forMangeSifreMappesTilUgyldigBankkontonummer`, `...forMangeSifreMappesUavhengigAvDatabasensSprak` and `...forStortProsenttallMappesFortsattTilProsentmelding` pass. If their harness drives `ErrorRouter.dispatch` they pass unchanged. If it calls `Mapping.match` link by link, it moves to `ErrorRouter.claim` (the public entry for exactly that walk), because the single-argument `match` is retired. Tell the reporter which applies when this ships.

## Retired vocabulary

* `GraphitronType.ErrorType.SqlStateHandler`, `GraphitronType.ErrorType.VendorCodeHandler`, and the no-discriminator `DATABASE` lift to `ExceptionHandler`
* `ErrorRouter.Mapping.match(Throwable)` (single argument)
* `ErrorRouter.SqlStateMapping`, `ErrorRouter.VendorCodeMapping`, and the `SQL_STATE_MAPPING` / `VENDOR_CODE_MAPPING` constants on `ErrorRouterClassGenerator`
* the inline mapping-by-cause double loop in `ErrorDispatchFragments.outcomeArm`
* the `instanceof` ladder in `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, including prose saying the union resolver picks a type "by source class"

## Other solutions we've considered

* **Put the outermost throw into the `errors` list and have every `DATABASE` read look through to the `SQLException`.** The read-side re-checks would then see the whole chain with no side table. This was rejected for three reasons. It changes the source of every `code`/`sqlState` handler. It moves the extra-field reads off the `SQLException` the build-time accessor check names. And for a type without `description` the client message would become jOOQ's SQL text, exposing schema and column names that redaction exists to keep out.
* **Carry the chain root in `localContext`.** That slot is owned by the tenant hand-down (`TenantHandDown`, with its ownership guard), so it is not free.
* **Record the decided `Mapping` instead of the chain root.** Readers would skip re-evaluation, but the decision is channel-scoped while the record is keyed on the source alone. Two channels claiming one `SQLException` would read each other's decisions. The chain root is a fact about the throw, true whatever channel reads it.
* **Last claim wins, instead of a set of roots.** The outcome would depend on fetcher completion order, and a direct claim (where the candidate is the outermost throw) would inherit another fetcher's wrapper unless it also recorded.
* **Handing the mapping a `List<Throwable>` of the chain.** This is equivalent to `(thrown, candidate)`, but it allocates per call and states the "down to the candidate" bound less directly.

## Reviewer findings

### Round 1 (2026-10-19, Spec -> Ready, reviewer session 99296a79-eaf3-49e4-af72-73a75dfe0a05)

Verdict: withhold; status stays `Spec`. The architecture (gate question 2) holds: the side table follows `toConfiguredLog`'s `GraphQLContext` lookup, the single `DatabaseHandler` arm mirrors `graphitron_ast_error_database_handler_entry`, and `claim` leaves one dispatch walk where there are two today. The findings are on the goal statement, sequencing and test detail.

1. **The Goal overstates "behave as before" (gate question 1).** "Handlers whose `matches` targets the driver's own message ... behave as before" is not guaranteed. Dispatch is first-match-wins, so a `DATABASE` handler whose `matches` text also appears in the SQL statement (a table, column or schema name, a literal) now matches more throws than before. If it is declared ahead of a narrower handler, it takes throws that used to go to that handler. The Context's "two of 179 sis handlers depend on SQL text" says which handlers *need* the wrapper. It does not say the other 177 are *unaffected* by it. Please (a) qualify the Goal sentence and the `matches` docs row to say that a `matches` text which also appears in the SQL now matches more, and that this is Graphitron 9's rule; and (b) say whether the sis audit checked for this widening, or relies on sis's handlers having been written against Graphitron 9's wrapper matching. In-tree exposure is nil: the example and test schemas have no `DATABASE` handler with `matches`, only three bare `{handler: DATABASE}` entries.

   *Response:* The Goal now states that `GENERIC` handlers and `DATABASE` handlers without `matches` behave as before. It says that a `DATABASE` `matches` text which also occurs in the SQL reaches further, and that first-match-wins order can move errors as a result. It names this as Graphitron 9's rule. The `matches` docs row warns authors to pick a specific text. Context answers (b): the audit identified which handlers need the SQL text and did not check the rest for wider matching. The case for the rest is Graphitron 9 parity.

2. **The prerequisite is unspecified Backlog (sequencing, non-blocking).** R777 is `Backlog` with a one-paragraph body. This item cannot be picked up until R777 has gone through its own Spec, Ready, implementation and Done. R777's scope is small, changes no behaviour and touches one emission site, `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, which this item rewrites anyway. This item already plans to land its model change, which changes no behaviour, as its own first commit. Consider making the R777 change a second such commit here and dropping the dependency, or else get R777 specified so that the wait is visible on the board. The choice is yours.

   *Response:* Folded in. The re-source is now Implementation, "TypeResolver: walk the mapping tables", a commit that changes no behaviour and lands after the model step. `depends-on` is empty, and R777 is discarded as absorbed by this item, with a changelog entry. Context also records the `bestGuess` drift that R777 named. The Tests section pins the new resolver shape, and the retired vocabulary lists the ladder.

3. **Docs scope misses two sentences.** `error.adoc`'s `handler` row ("`DATABASE` matches any `java.sql.SQLException` in the cause chain, narrowed by `sqlState`/`code`/`matches`") should say how far `matches` now reaches. In `error-channel.adoc`, the paragraph after the per-type walk ("the union's type resolver picks the type by source class") goes stale along with the bullet the spec already names. List both under User documentation.

   *Response:* Both are now under User documentation: the `handler` row gets a clause, and the per-type-walk paragraph is corrected along with the bullet.

4. **Rule 8 needs the intra-variant case too.** The spec pins the cross-variant verdict. It should also say that the `DatabaseHandler` criteria key is `(discriminator, matches)`, so that two `{DATABASE, matches: X}` entries on one channel are still rejected as duplicates, as they are today when both lift to `ExceptionHandler(SQLException)`. Add a classification case for it next to the cross-variant one.

   *Response:* The Rule 8 paragraph states the `(discriminator, matches)` key and keeps the duplicate rejection. The classification tier gains the rejection case next to the cross-variant admission.

5. **The third end-to-end case names no column.** `film` has only two `numeric` columns, and the spec gives one to each type. The other columns that could overflow are `smallint` (`rental_duration`, `length`). Before such a value reaches PostgreSQL it passes GraphQL `Int` coercion and jOOQ's binding to `Short`, and it is not established that it arrives as a `22003` rather than failing earlier or being narrowed. Name the column and the value. Alternatively, cover the redaction case with a second mutation whose union carries only the `rental_rate` type, and overflow `replacement_cost`.

   *Response:* Took the alternative. The third case is now a second mutation whose union carries only the `rental_rate` type, with `replacement_cost` overflowing. The `smallint` reasoning is recorded so nobody reaches for those columns.
