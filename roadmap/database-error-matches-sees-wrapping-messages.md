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

One other answer changes, a fix rather than a widening. Some exceptions are also `GraphQLError`s: Graphitron's own `GraphitronClientException`, and any author exception extending `graphql.GraphqlErrorException`. If such an exception is caught by a `GENERIC` member of an errors union, and a `VALIDATION` member is listed ahead of that member, the client today reads the `VALIDATION` member's `__typename`. After this item it reads the `GENERIC` member's, which is the type dispatch actually chose.

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

The `TypeResolver` is an obstacle in its own right, and this item removes it too (see Implementation, "TypeResolver"). The resolver is a hand-rolled `instanceof` ladder (`GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`) that re-implements the three discriminators instead of walking the `ErrorRouter.Mapping[]` tables that dispatch and `message` use. Left alone, it would need a third spelling of the new rule. The spellings have already drifted apart in two ways:

* **Order.** The ladder tests a `VALIDATION` member's `src instanceof GraphQLError` in member order, beside the other members' handlers. `ErrorTypeFetcherClassGenerator.messageMethod` deliberately walks every `Throwable` mapping before its `GraphQLError` arm (its class javadoc says why). A source that is both a `Throwable` and a `GraphQLError` therefore resolves differently on the two paths. Take a `VALIDATION` member listed ahead of a `GENERIC` member that names such a class. Dispatch gives the throw to the `GENERIC` member, because `VALIDATION` mints no mapping, but the resolver reports the `VALIDATION` member. That is a live wrong-`__typename` bug.
* **Class naming.** Given a malformed exception class name, `ErrorMappingsClassGenerator.bestGuessOrObject` falls back to `Object`, while the ladder calls `ClassName.bestGuess` directly and the generator crashes.

The model has another obstacle, which this item also removes (see Implementation, "Model"). `{handler: DATABASE, matches: X}`, with no `code` and no `sqlState`, classifies as an `ExceptionHandler` on `java.sql.SQLException`. A `GENERIC` handler naming that class produces the same thing. So the rule here could not reach that spelling, and the docs would have to say `matches` behaves differently depending on which `DATABASE` discriminators an author wrote.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, the `matches` row:

> `matches` | no | Substring the exception message must contain. For `GENERIC`, the message of the exception whose class matched. For `DATABASE`, the message of the `SQLException` or of any exception wrapping it, so a handler can key on the SQL text jOOQ puts in its `DataAccessException` (`SQL [update ... "bankkontonr" = ? ...]; ...`), which is often the only place a column name appears. The flip side is that a `DATABASE` text which also occurs in the SQL (a table or column name) matches every error of that code raised by such a statement, so pick a text specific to the error you mean. `code` and `sqlState` are always read off the `SQLException` itself. Useful to narrow a broad `className`, `code` or `sqlState` to one specific error.

In the same file, the `handler` row's `DATABASE` sentence gets a clause: `matches` reads the messages from the outermost exception down to the matched `SQLException`, so it sees jOOQ's SQL text.

`docs/manual/how-to/error-channel.adoc` gets matching edits:

* The dispatch-loop snippet and the two facts beneath it: dispatch calls `ErrorRouter.claim`. The second fact names the `DATABASE` reach.
* The `description:` section's bullet "A `union` carrier resolves `__typename` by the source's own class". The resolver now runs the type's own dispatch mappings against the source and against the exceptions that wrapped it when it was claimed. It tries every member's mappings before any `VALIDATION` member's `GraphQLError` test, so an exception that is also a `GraphQLError` resolves to the type dispatch chose.
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

**TypeResolver: walk the mapping tables.** `buildErrorPolymorphicResolver` stops re-implementing the discriminators, and takes `messageMethod`'s order. The emitted resolver has two parts:

1. Under one `if (src instanceof Throwable thr)`: for each participant in member order, and each of its dispatch-capable handlers in declaration order, `if (ErrorMappings.ByType.<T>[i].match(thr)) return <T>;`. This uses the same `ByType` addressing as `messageMethod`, where `i` counts handlers and skips `VALIDATION`.
2. After that block: for each participant carrying a `VALIDATION` handler, in member order, `if (src instanceof GraphQLError) return <T>;`. Rule 7 allows at most one `VALIDATION` handler per channel, so in practice this part holds at most one test.

With this the resolver, `message` and dispatch share one predicate per handler and one order, and the bare `ClassName.bestGuess` call (the crash on a malformed class name) is gone. The step changes one behaviour: a source that is both a `Throwable` and a `GraphQLError`, caught by a `GENERIC` member listed after a `VALIDATION` member, now resolves to the `GENERIC` member (see Goal). Every other source resolves as before. A `VALIDATION`-produced `GraphQLError` is not a `Throwable`, so it skips part 1 and reaches the same member it reaches today. The step can land as its own commit after the model step. After it, the only edit the resolver needs for the new rule is the `matchesSource` swap under "Read side".

`bestGuessOrObject` itself stays in `ErrorMappingsClassGenerator` and is out of scope. Its remaining defects (a lowercase-led simple name falls back to `Object.class`, which does not compile as a `Class<? extends Throwable>` argument, and a binary `Outer$Nested` name yields a class literal javac cannot resolve) do not depend on this item. They belong with R688's nested-class binary-name work.

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

  The second and third cases depend on the failing statement naming only the column the request sent. That holds because an `UPDATE` leaves absent optional fields out of its `SET` clause (`mutation.adoc`, the `UPDATE` rules). So both input fields are nullable, each request sends one of them, and the test selects nothing that would make the failing statement itself name `rental_rate`.

  This proves dispatch, `TypeResolver` and `message` agree. That is the invariant the chain-root record holds, and nothing at build time ties the writer to its readers.
* **Resolver order, execution tier, Sakila.** This case pins the `TypeResolver` step's one behaviour change. It follows the `filmLookupValidated` fixture (`FilmValidatedLookupService`, whose union lists a `VALIDATION` member first):
  * add an author exception in `graphitron-sakila-service` that extends `graphql.GraphqlErrorException`, and a `@service` field whose service throws it;
  * the field's errors union lists a `{handler: VALIDATION}` member first, then a `{handler: GENERIC, className: <that exception>, description: ...}` member;
  * a request that makes the service throw returns `errors: [{__typename: <the GENERIC member>, message: <its description>}]`. Today it returns the `VALIDATION` member's `__typename`.

  The generated `GraphitronClientException` has the same shape, but `graphitron-sakila-service` compiles before it is generated, so an author subclass of its superclass stands in for it.
* **Classification tier.** Existing `ErrorChannelClassificationTest` and `GraphitronSchemaBuilderTest` handler rows retarget to `DatabaseHandler`, one case per `Discriminator` arm. Two new Rule 8 cases: `{GENERIC, className: "java.sql.SQLException", matches: X}` and `{DATABASE, matches: X}` on one channel are both admitted, and two `{DATABASE, matches: X}` entries on one channel are rejected.
* **Generator tier, `TypeSpec` shape only.** Update `ErrorRouterClassGeneratorTest` and `ErrorMappingsClassGeneratorTest` for the new surface: the `DatabaseMapping` nested class and its constructor, `Mapping.match(Throwable, Throwable)`, and the `claim` and `matchesSource` signatures. Add no assertion on the text of an emitted method body; `docs/architecture/how-to/testing.adoc` bans that in the unit tier. The body-text assertions that exist today on `SqlStateMapping` and `VendorCodeMapping` go away with those classes, and nothing replaces them in kind. The router unit tests above pin the walk's behaviour. The `TypeResolver` step gets no generator-tier test. The resolver-order case above pins its one change, and the union execution tests that exist today pin that nothing else moves. The generator crash on a malformed class name goes away because the resolver no longer names the class.
* **sis acceptance.** `BankkontonummerErrorMappingTest.forMangeSifreMappesTilUgyldigBankkontonummer`, `...forMangeSifreMappesUavhengigAvDatabasensSprak` and `...forStortProsenttallMappesFortsattTilProsentmelding` pass. If their harness drives `ErrorRouter.dispatch` they pass unchanged. If it calls `Mapping.match` link by link, it moves to `ErrorRouter.claim` (the public entry for exactly that walk), because the single-argument `match` is retired. Tell the reporter which applies when this ships.

## Retired vocabulary

* `GraphitronType.ErrorType.SqlStateHandler`, `GraphitronType.ErrorType.VendorCodeHandler`, and the no-discriminator `DATABASE` lift to `ExceptionHandler`
* `ErrorRouter.Mapping.match(Throwable)` (single argument)
* `ErrorRouter.SqlStateMapping`, `ErrorRouter.VendorCodeMapping`, and the `SQL_STATE_MAPPING` / `VENDOR_CODE_MAPPING` constants on `ErrorRouterClassGenerator`
* the inline mapping-by-cause double loop in `ErrorDispatchFragments.outcomeArm`
* the `instanceof` ladder in `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, including its member-order interleave of the `VALIDATION` `GraphQLError` test, and prose saying the union resolver picks a type "by source class" (one instance is in `ErrorTypeFetcherClassGenerator`'s class javadoc)

## Other solutions we've considered

* **Put the outermost throw into the `errors` list and have every `DATABASE` read look through to the `SQLException`.** The read-side re-checks would then see the whole chain with no side table. This was rejected for three reasons. It changes the source of every `code`/`sqlState` handler. It moves the extra-field reads off the `SQLException` the build-time accessor check names. And for a type without `description` the client message would become jOOQ's SQL text, exposing schema and column names that redaction exists to keep out.
* **Carry the chain root in `localContext`.** That slot is owned by the tenant hand-down (`TenantHandDown`, with its ownership guard), so it is not free.
* **Record the decided `Mapping` instead of the chain root.** Readers would skip re-evaluation, but the decision is channel-scoped while the record is keyed on the source alone. Two channels claiming one `SQLException` would read each other's decisions. The chain root is a fact about the throw, true whatever channel reads it.
* **Last claim wins, instead of a set of roots.** The outcome would depend on fetcher completion order, and a direct claim (where the candidate is the outermost throw) would inherit another fetcher's wrapper unless it also recorded.
* **Keep the ladder's member-order interleave of the `VALIDATION` test, and carve the dual-typed source out of the agreement claim.** That would keep the step free of behaviour changes, but it would rebuild a resolver whose whole purpose is to agree with dispatch while knowingly leaving one input where it does not. `messageMethod` already settled the order the other way.
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

### Round 2 (2026-10-19, Spec -> Ready, reviewer session_01F1AhcCpjeZkiomu98PBAMQ)

Verdict: withhold; status stays `Spec`. Question 1 now holds: the Goal states the consumer-visible change, including the wider reach of a `DATABASE` `matches`, and the code claims check out. Question 2 holds for the router, the model arm and the chain-root record. Both findings are in the "TypeResolver: walk the mapping tables" step folded in from R777. Finding 1 was also raised by the session that researched R777; I confirmed it in the code.

1. **The re-sourced resolver keeps a wrong-`__typename` case, so the item's agreement claim does not hold (gate question 2).** The step keeps each member's `src instanceof GraphQLError` test for `VALIDATION` in member order, beside that member's mapping walk. Some sources are both a `Throwable` and a `GraphQLError`: the generated `GraphitronClientException` extends `graphql.GraphqlErrorException` (`ExceptionClass.GeneratedClientException.SUPERCLASS`), and so does any author exception extending it. Take a union whose `VALIDATION` member is listed before a `GENERIC` member naming the client exception. Dispatch gives the throw to the `GENERIC` member, because `VALIDATION` mints no mapping. The resolver returns the `VALIDATION` member, because its `GraphQLError` test fires first. `ErrorTypeFetcherClassGenerator.messageMethod` already puts its `Throwable` walk ahead of the `GraphQLError` arm for this reason (see its class javadoc). Tests says the end-to-end cases prove "dispatch, `TypeResolver` and `message` agree", and the step says it "changes no behaviour". Both cannot be true for this input. The author decides between two options. (a) Order the resolver like `messageMethod`: every member's mapping walk under `src instanceof Throwable` first, then the `GraphQLError` test. State that this changes `__typename` for that input, and add an execution case that pins it. (b) Keep the ladder's order, and state the carve-out in the step and in the Tests invariant. (a) is what the resolver re-source exists for.

   *Response:* Took (a). The TypeResolver step now emits every member's mapping walk under one `src instanceof Throwable` first, then the `VALIDATION` members' `GraphQLError` tests, in `messageMethod`'s order. The step names its one behaviour change, and the Goal states it for consumers. Context records the order drift beside the class-naming one. Tests gains an execution case: an author exception extending `graphql.GraphqlErrorException`, under a union with a `VALIDATION` member listed first. It stands in for `GraphitronClientException`, which `graphitron-sakila-service` cannot name because that class is generated later. Option (b) is recorded under Other solutions, and the interleave is listed under Retired vocabulary.

2. **The generator-tier test asserts on generated body text (gate question 2, testing principles).** "Assert that the emitted resolver names `ErrorMappings.ByType` and contains no `instanceof` on an exception class" matches the text of a generated method body. `docs/architecture/how-to/testing.adoc` bans that in the unit tier ("Banned: code-string body matching"). The instruction to update `ErrorRouterClassGeneratorTest` and `ErrorMappingsClassGeneratorTest` "keeping their existing assertion style" points the implementer at the same style: `ErrorRouterClassGeneratorTest` matches `instanceof` and `getSQLState` in emitted bodies. The `claim` walk, `DatabaseMapping` and `matchesSource` are already pinned behaviourally in the router unit tests the spec adds under `graphitron-sakila-example`. To satisfy this, keep the generator-tier changes to `TypeSpec` shape (types, method signatures, nested classes), and pin the resolver's predicate through execution: the item's union cases, plus finding 1's case if (a) is taken.

   *Response:* The generator tier is now limited to `TypeSpec` shape: the `DatabaseMapping` nested class, `match(Throwable, Throwable)`, and the `claim` and `matchesSource` signatures. It explicitly adds no body-text assertion. The body-text assertions on the retired mapping classes go away with them, and nothing of that kind replaces them. The resolver step has no generator-tier test; its execution case and the existing union tests pin it.

Non-blocking:

* Context says the `bestGuess` drift "closes by construction". The two spellings disagreeing does close. The defects in `ErrorMappingsClassGenerator.bestGuessOrObject` stay. A `className` whose simple name starts lowercase falls back to `Object.class`. In `new ExceptionMapping(Object.class, ...)`, whose parameter is `Class<? extends Throwable>`, that is a compile error in the generated source, not a catch-all. A nested `Outer$Nested` name yields a class literal javac cannot resolve. Neither defect depends on this item. They belong with R688 (nested backing class binary name) or a Backlog item of their own. The Context sentence could say "the generator crash" rather than "the drift".
* The second end-to-end case relies on the `UPDATE` naming only the column the client sent. That holds because absent optional fields are left out of the `SET` clause (`mutation.adoc`, `@mutation` `UPDATE` rules). So the input's two fields must be nullable, each request sends one, and whatever reads the row back must not render `rental_rate` into the failing statement's SQL. "Check the jOOQ SQL text's column spelling at pickup" covers most of this.

*Response (non-blocking items):* Implementation now says the generator crash is what goes away. `bestGuessOrObject`'s two remaining defects are named, with the corrected compile-error symptom, and routed to R688. The end-to-end cases now state the nullable-input, one-field-per-request precondition.
