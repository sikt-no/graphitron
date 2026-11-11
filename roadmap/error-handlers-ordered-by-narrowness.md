---
id: R1165
title: "Error handlers are matched narrowest first, and an unreachable one fails the build"
status: Ready
bucket: dx
priority: 4
theme: error-channel
depends-on: [platform-throwables-are-code-facts]
created: 2026-11-10
last-updated: 2026-11-11
---

# Error handlers are matched narrowest first, and an unreachable one fails the build

## Goal

An author lists the `handlers:` of an `@error` type, and the members of an errors union, in any order: graphitron matches a thrown exception against the narrowest handler first, so a broad handler no longer has to be declared last to keep a narrow one alive, and a handler no exception can ever reach fails the build instead of going silently dead.

The sakila example's errors union ends in a catch-all, and today it has to:

```graphql
union FilmLookupError = FilmLookupNotFound | FilmLookupInvalid | FilmLookupCatchAll

type FilmLookupInvalid @error(handlers: [{
        handler: GENERIC,
        className: "no.sikt.graphitron.rewrite.test.services.FilmLookupInvalidIdException",
        description: "The film id could not be used for a lookup."
    }]) { path: [String!]! message: String! }

type FilmLookupCatchAll @error(handlers: [
    {handler: GENERIC, className: "java.lang.IllegalStateException", description: "The film lookup failed."}
]) { path: [String!]! message: String! }
```

Had `FilmLookupInvalidIdException` extended `IllegalStateException`, writing the union as `FilmLookupCatchAll | FilmLookupInvalid | ...` would send every invalid id to the catch-all, with no diagnostic. After this item both spellings route an invalid id to `FilmLookupInvalid`.

## Today

A channel is what a producer field (a `@service` or `@mutation` field returning a payload) catches into: the payload's errors field and the `@error` types it holds. Dispatch walks the channel's handlers in declaration order (the `@error` types in member order, and within a type its `handlers:` in array order) and the first match wins. Three consequences:

- A broad handler declared before a narrow one leaves the narrow one unreachable, with no diagnostic. Only handlers with identical criteria are rejected (`ChannelRuleChecks.checkDuplicateMatchCriteria`, "rule 8").
- The order crosses types, so the member order of the errors union matters too. For an interface carrier the members are ordered by type name, which the author cannot control except by renaming types.
- The reference page says overlap across variants is harmless because `GENERIC` and `DATABASE` inspect orthogonal fields. That is not so: a `GENERIC` handler on `java.lang.RuntimeException` declared first claims jOOQ's `DataAccessException` wrapper, and a later `DATABASE` handler never gets the `SQLException` inside it.

Three emitted readers walk the handlers, each in its own order taken from a different source, and they have to agree:

- the dispatch table: `ErrorMappingsClassGenerator` emits one `Mapping[]` per `@error` type (`ByType.<TYPE>`, in `ErrorType.handlers()` order) and a channel constant per outcome type as `concat(ByType.A, ByType.B)`, the member order read from the store by `ErrorChannelRelation.read`; `ErrorRouter.claim` walks that table;
- the union or interface type resolver, `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`, which re-matches the claimed throwable (`ErrorRouter.matchesSource`) against `ByType.<TYPE>[i]` in participant order then handler order, both from the Java model;
- `<ErrorType>Fetchers.message`, `ErrorTypeFetcherClassGenerator.messageMethod`, which walks the type's own handlers to find the `description:` to return.

## The rule

A handler *claims* a throw when its predicate holds for some throwable in the throw's cause chain. Handler A is *narrower* than B when every throw A claims, B claims. That is decidable from what the author wrote and the class hierarchy:

- `GENERIC` against `GENERIC`: A's class is B's class or a subtype of it, and B has no `matches:` or A's `matches:` text contains B's.
- `DATABASE` against `DATABASE`: B has no `sqlState`/`code` or A carries the same one, and the same `matches:` rule holds.
- `DATABASE` against `GENERIC`: B's class is `java.sql.SQLException` or a supertype of it, and B has no `matches:`. (A `DATABASE` `matches:` reads the messages of the wrapping exceptions too, so it says nothing about the `SQLException`'s own message.)
- `GENERIC` against `DATABASE`: A's class is `java.sql.SQLException` or a subtype of it, B has no `sqlState`/`code`, and the same `matches:` rule holds.
- `VALIDATION` never enters the dispatch loop and takes no part.

Each row holds for whole cause chains, not only single throwables: in every row the throwable that satisfies A satisfies B, or (for a `DATABASE` `matches:`) the message A found on the path is on B's path too.

One more pair is ordered without containment. A `DATABASE` handler *precedes* a `GENERIC` handler that has no `matches:` and whose class is `org.jooq.exception.DataAccessException` or a supertype of it. jOOQ hands every `SQLException` over wrapped in a `DataAccessException`, so such a `GENERIC` handler in front takes everything the `DATABASE` handler was written for; the pair is not containment only because a service could throw a bare `SQLException`. This is what lets `RuntimeException` serve as a catch-all beside `DATABASE` handlers. It adds no cycle: no `GENERIC` handler on a supertype of `DataAccessException` is narrower than any `DATABASE` handler.

From these:

Handler A *comes before* B when a chain of narrower-than and precedes steps leads from A to B. That relation is transitive by construction and has no cycle apart from equivalent handlers (rule 2): narrower-than is containment, and a precedes step lands on a `GENERIC` handler without `matches:` on `DataAccessException` or above, from which further steps reach only handlers of that same kind, never a `DATABASE` handler.

1. **Narrower first, moved no further than needed.** The handlers of an errors container (the union, interface or single `@error` type an errors field lists) are placed one at a time in declared order: a handler goes immediately in front of the earliest already-placed handler it comes before, and at the end when it comes before none of them. Declared order is member order, then `handlers:` array order, as today. The result respects every comes-before pair: when N is put in front of B, anything that comes before N also comes before B, so it was itself put in front of B or earlier and stays ahead of N.
2. **Equivalent handlers are an error.** Two handlers each narrower than the other match exactly the same throws, so whichever comes second is unreachable. After the sort this is the only way a handler is unreachable. It covers today's identical-criteria rule and one pair it misses: a `DATABASE` handler with no `sqlState`, `code` or `matches:` against `GENERIC` on `java.sql.SQLException` with no `matches:`.
3. **Declared order breaks ties, except where a narrower handler passes.** Two handlers that overlap with neither narrower (two `matches:` texts on one class, a `sqlState` handler against a `code` handler, a wrapper class against its cause's class) keep their declared order unless one of them was moved forward by rule 1, in which case it has passed whatever sat between it and the broader handler it now precedes. No diagnostic either way: neither is dead, and whether a throw ever matches both is a fact about runtime messages the SDL does not state. Keeping declared order for every such pair is not on offer. With `T` on `IllegalStateException`, `V` on `RuntimeException` with `matches: "timeout"` and `U` on a subclass of `IllegalStateException`, declared in that order, `U` must precede `T`, and declared order asks for `T` before `V` and `V` before `U`, which is a cycle. Rule 1 gives `U`, `T`, `V`: the broad handlers stay where they were written and the narrow one moves.

What the placement guarantees is the sentence the manual and the changelog rest on: **a throw is claimed after this item by the handler that claims it today, or by one narrower than that handler.** Let H be today's claimant, the first match in declared order, and N the new one. If N differs from H it sits ahead of H though declared after it, so it was put in front of some B at or ahead of H. B contains N, so B matches the throw; B cannot have been declared ahead of H, since H is the first declared match, so B is H or was itself moved in front of H, and the same step repeats until it ends on H. So N is narrower than H, and for that throw N was dead. The precedes pair is the one stated exception: a `DATABASE` handler moved in front of a `DataAccessException`-or-broader `GENERIC` handler also passes the handlers declared between them, and a bare `SQLException` thrown unwrapped by a service, which the `GENERIC` handler never matched, can thereby move from one of those to the `DATABASE` handler. When the `SQLException` arrives wrapped, which is the premise of the pair, the `GENERIC` handler claims it today and the guarantee holds as stated.

The message follows the same rule at its own grain: `message:` reads the `description:` of the first handler *of the matched type*, in that type's own match order, that matches the claimed exception. An `@error` type is a container of one member, so its own order is rule 1 applied to its handlers alone.

That the readers agree is an invariant of this shape, and the tests pin it. The resolver walks the container's order, and every handler ahead of the claiming one declined the whole chain at dispatch, so the resolver stops on the claiming handler and returns its type. Within that type the message walk can stop on a different handler than the one that claimed only when two of the type's handlers both match the claimed exception and neither is narrower; that is rule 3's tie, and the type's own declared order settles it, which is what the author wrote.

## Implementation

The order and the equivalence check are derived in the fact store (the per-graph database the generator fills from the SDL, the classpath and the jOOQ catalog, and emits from), not in the classifier. The handler entries as written are already there (`graphitron_ast_error_handler_entry` and its `GENERIC` and `DATABASE` subtype relations), and exception ancestry is `code_throwable_supertype`, which the `graphitron` gatherer may already read (`meta_gatherer_dependency` lets it read `code`). That relation holds today only the classes read off a classpath entry: a JDK exception appears as a `supertype_name` and has no rows of its own, so nothing says `IllegalArgumentException` is a `RuntimeException`. R1166 (`platform-throwables-are-code-facts`) captures the platform's throwables into the same two relations, and this item depends on it, since the catch-alls an author writes are JDK classes. This item adds no capture of its own and reads ancestry from nowhere else. Three readers ask for one order, which is the point at which a derivation earns a relation.

**Two stored relations and a stage.** Names are the implementer's; the grains are not.

- *Handler precedence*, grain "this handler is narrower than, or precedes, that handler": one row per ordered pair of dispatch-capable handlers in the graph for which the rule above holds, with a column saying which basis (containment, or the jOOQ wrap). A rule view states it (joins over the handler entries and `code_throwable_supertype`, plus a substring test) and the stage stores it, so the sort reads a table.
- *Match order*, grain "one dispatch-capable handler of an errors container, at its match position": keyed by container name, `@error` type name and the handler's declared index, with the match position unique within the container, and the handler entry's written position carried beside the names as a cascading foreign key (the container and the handler can be in different files). Containers are every union or interface an errors field lists and every `@error` type, the latter as a container of itself. The stage's Java computes the sort over the stored precedence rows; a topological sort is anchor-phase work a view cannot state.
- The stage is a new `graphitron` stage declared in `meta_stage`, with its relations in `meta_relation` and `meta_stage_write`, `touched_at` on every row and a sweep. It reads `graphitron_errors_field`, so it runs after `fetchers`.

**The generated client exception.** `<outputPackage>.schema.GraphitronClientException` is emitted by the run and has no `code_throwable` row on a clean build. `graphitron_runtime_class` is the precedent for a template class no classpath declares: add a kind for it and a superclass column the relation does not have yet (`graphql.GraphqlErrorException`, today the constant `ExceptionClass.GeneratedClientException.SUPERCLASS`), and have the precedence rule read its ancestry through that superclass's `code_throwable_supertype` rows. graphql-java's jar is in the captured classpath (`CodeCaptureTest` reads `graphql.AssertException` from it), and the chain above it reaches `RuntimeException` through those rows. No `code_` row is invented: that family transcribes the classpath.

**The defect.** Equivalence is one code in `graphitron_defect_type`, written by the same stage into `graphitron_entry_defect` at the later-declared handler's position, with a detail relation naming the other handler by type name and declared index (not in the detail string). Follow the steps under "Adding a defect" in `docs/architecture/explanation/defects-as-facts.adoc`. It replaces rule 8 instead of sitting beside it: delete `ChannelRuleChecks.checkDuplicateMatchCriteria`, its call in `ErrorChannelWalker`, and the `FieldBuilder.checkDuplicateMatchCriteria` copy with its two callers (`FieldBuilder` and `BuildContext`, the DML path). Rule 7 (more than one `VALIDATION` handler) is a different rule and stays where it is.

**The readers.** Each reads the order from the store through one query in the `emit` package, extending `ErrorChannelRelation` from member grain to handler grain; no emitter joins store positions to `ErrorType.handlers()` by a bare index without the key being named and tested.

- `ErrorMappingsClassGenerator`: each `ByType.<TYPE>` array is emitted in the type's own match order, and each channel constant becomes the container's handlers in match order, written as references to `ByType` elements. `concat` goes.
- `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`: the `matchesSource` statements are emitted in the container's match order.
- `ErrorTypeFetcherClassGenerator.messageMethod` (and `lastOverrideIndex`): indexes follow the type's own match order.
- `ErrorRouter.claim` is unchanged apart from its javadoc, which says "source-declaration order" (`ErrorRouterClassGenerator.buildClaimMethod`); the `concat` javadoc and the `Mapping` interface javadoc say the same and change with it.

`ErrorType.handlers()` stays in declared order and is no longer read for order by any emitter.

**Unresolved classes.** The order is defined over handlers whose class resolves: a `code_throwable` row from any source, the platform's included once R1166 has landed, or the generated client exception. "A is B's class or a subtype of it" is read as the names being equal or B's class being a `supertype_name` of A's, and `java.sql.SQLException` and `org.jooq.exception.DataAccessException` are compared by name the same way. A `GENERIC` handler naming a class that resolves nowhere gets no precedence rows; the classifier's existing refusal of the unresolved name is what fails that build. Say so in the precedence relation's comment. A class present in two classpath entries has two `code_throwable` rows; the rule reads the union of their supertypes.

## Tests

- A fact document (the store-tier fixture format the defect steps name) for the stage: a schema exercising each row of the rule, the jOOQ-wrap pair, a tie, a pair of two JDK classes (`IllegalArgumentException` under `RuntimeException`), a JDK catch-all declared first, a JDK `SQLException` subclass against a bare `DATABASE` handler, the `T`, `V`, `U` shape of rule 3 with its expected order `U`, `T`, `V`, a `DATABASE` handler passing a handler declared between it and a `RuntimeException` catch-all, an interface carrier, one `@error` type in two containers, and the generated client exception; expected precedence rows, match-order rows, the equivalence defect with its detail row for both the identical-criteria case and the `DATABASE`-against-`GENERIC` `SQLException` case; and a near miss that raises nothing (same class, two different `matches:` texts).
- Pipeline tier: `ErrorMappingsClassGeneratorTest` and the resolver and message emitter tests assert the emitted order for a union declared broad-first, and that the three readers emit the same order for one container.
- Execution tier, on the sakila example: an errors union declared catch-all first, with a narrow handler whose exception extends the catch-all's class, routes the narrow throw to the narrow type and returns the narrow handler's `description:`; a `DATABASE` handler declared after `GENERIC` on `java.lang.RuntimeException` receives a constraint violation raised through jOOQ.
- `ErrorChannelClassificationTest`'s rule 8 cases move to the fact document; `ErrorChannelWalkerTest` loses its rule 8 arm.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the two bullets on declaration order and "Nothing sorts by specificity", and correcting the cross-variant bullet:

> * The narrowest handler wins. When two handlers both match a thrown exception and everything one matches the other also matches, the narrower one is tried first, wherever each is declared: a subclass before its superclass, a handler with `matches:` before the same handler without, a `sqlState` or `code` before a `DATABASE` handler with neither. A catch-all can be declared anywhere.
> * A `DATABASE` handler is tried before a `GENERIC` handler without `matches:` on `DataAccessException`, `RuntimeException`, `Exception` or `Throwable`, since jOOQ wraps every database error in a `DataAccessException`.
> * Where two handlers can match the same exception and neither is narrower (two different `matches:` texts on one class, a `sqlState` handler and a `code` handler), the one declared first is tried first: union members in order, then each type's `handlers:` in order. The one exception is a narrower handler on its way forward: it is placed directly in front of the first broader handler, and so passes anything declared between the two. If the order between two such handlers matters, make one narrower.
> * An exception is never taken away from the narrowest handler that matches it.
> * Two handlers that match exactly the same exceptions are rejected, since the second could never be reached: identical criteria, or a bare `DATABASE` handler beside `GENERIC` on `java.sql.SQLException`.

The `handlers` parameter row ("The first entry whose criteria match ... wins") and the how-to's "Handler dispatch: source order, cause-chain unwrap" section and "Source order is significant" bullet (`docs/manual/how-to/error-channel.adoc`) are rewritten to the same rule, the how-to's "orthogonal fields" claim is dropped, and its `message:` paragraph says "in match order" where it says "in declaration order". The new defect code gets its entry in `docs/manual/reference/diagnostics-glossary.adoc`.

## Roadmap entries

- Changelog: a schema that declares a broad handler before a narrower one starts routing to the narrower one. For every throw that changes route the new handler is narrower than the old one and was dead for that throw, so the commit is a `feat`, not a breaking change, but the entry says it. The entry names the one case outside that: a `DATABASE` handler now tried before a `RuntimeException` or `DataAccessException` catch-all also takes a bare, unwrapped `SQLException` from any overlapping handler declared between them.
- Not in scope, and worth an item only if it bites: a handler on an abstract class whose every current concrete subclass is claimed by narrower handlers is dead in practice and not provably so, since a subclass can be added; and moving rule 7 into the store.

## Retired vocabulary

- `checkDuplicateMatchCriteria` (both the `ChannelRuleChecks` and `FieldBuilder` members), and "rule 8" / `Rule 8` as the name of the identical-criteria check in javadoc, test names and the how-to.
- "source order", "source-declaration order" and "declaration order" as the description of handler dispatch, in emitted javadoc (`ErrorRouter.claim`, `Mapping`, the mappings class) and the two manual pages.
- The `concat` helper of the emitted mappings class (`ErrorMappingsClassGenerator.CONCAT_METHOD`).

## Other solutions we've considered

- **Keep declaration order, reject a shadowed handler.** The same containment check, reported as "handler X can never be reached because Y, declared earlier, matches everything it does". It gives the diagnostic but keeps the ordering chore, and for an interface carrier the author can only satisfy it by renaming types.
- **Warn on every overlap.** Any two `matches:` texts can occur in one message, so the warning would fire on nearly every pair of handlers on comparable classes.
- **Sort in the classifier, on the Java model.** A pure function over the lifted `ErrorType` list with reflection as the subtype oracle, sitting where rule 8 is today. Smaller, but it derives by reflection a fact the store already holds, gives the three readers the order from three model objects, and extends the lifted model the store is replacing.
- **An intrinsic specificity score** (ancestor count of the class, then discriminator, then `matches:` length), which is a plain `ORDER BY` and identical in every container. It orders handlers that are not comparable by an accident of the class hierarchy and takes tie-breaking away from the author.
- **Take JDK ancestry from somewhere other than the `code` family.** The new stage could read the platform's class headers itself for the classes a graph's handlers name, or the DDL could carry a constant table of JDK exception ancestry. The first reads a corpus during anchoring, from a gatherer that does not own it; the second has no provenance and goes stale with the JDK. A missing code fact is captured in the `code` family (R1166), where the editor's `className:` completion needs it too.
- **A stable topological sort that takes the earliest-declared handler nothing remaining comes before.** It respects the same pairs, but it holds a broad handler back behind everything declared after it until its narrower handler has gone, so an overlapping tie declared after the broad handler jumps ahead of it and a live route changes. Placing the narrow handler in front of the broad one moves only the handler that was dead.
- **Record the claiming handler at dispatch** so the resolver and `message` read it instead of re-matching. It would make "the readers agree" true by construction, but the claim record is keyed on the source alone, one source can be claimed through two channels, and a null context records nothing, so the re-match has to stay as the fallback and the order with it. A candidate for its own item.

## Architect consultation

`principles-architect` was consulted on where the order lives. It favoured the store over the classifier, the split into a stored precedence relation and a Java-sorted order relation, the defect in `graphitron_entry_defect` replacing rule 8, and `graphitron_runtime_class` for the generated client exception; the plan follows all four. It also held that `message` cannot read a per-type order, because a type's handlers sorted alone can come out differently from their order inside a container, and proposed recording the claiming handler instead. The plan keeps the per-type order for `message` and states why the outcome is still well defined (end of "The rule"); recording the claim is listed above as considered. The round 1 reviewer accepted the per-type order.

It was consulted again on the two round 1 findings. For JDK ancestry it ruled for capture in the `code` family under a platform source, as an item of its own that this one depends on, and against a header read in the new stage, a constant table, and reflection; the plan follows that as R1166. For the sort it confirmed that keeping declared order for every incomparable pair is unsatisfiable and favoured the placement rule with its guarantee stated in the manual and the changelog, asking that the argument cover the jOOQ-wrap pair; rule 3 now does.

## Reviewer findings

### Round 1 (2026-11-11, Spec -> Ready, reviewer session 01FY5UvrebsVBt3mmPocP2yE)

Verdict: withhold. One blocking finding on question one (viability), one smaller finding on question one (what the consumer is told). The goal comes across on a first read, the rule's containment rows agree with what `GenericMapping` and `DatabaseMapping` actually test, every symbol, relation, stage and manual passage the spec names exists as named, and the store is the right home for the order. On the fork the author asked the reviewer to weigh: the per-type order for `message:` is accepted. The fetcher is emitted once per type and a type can sit in several containers, so a container order is not available to it without the claim record, and the outcome as stated is deterministic.

**Finding 1 (question one: viability; it also decides question two). The store holds no ancestry for JDK exceptions, and the plan's definition of a resolved class leaves the handlers this item is about out of the order.**

`code_throwable` has a row only for a class read off a classpath entry. `CodeCapture.throwables` iterates `census.classes()`, and its javadoc says the chain runs "through classes that are on no classpath entry and have no rows"; `ClassHeaders` reads `jrt:/` only to follow a classpath class's ancestors. So `java.lang.RuntimeException`, `java.lang.IllegalStateException`, `java.lang.Exception`, `java.lang.Throwable` and `java.sql.SQLException` appear in the store only as a `supertype_name` under some classpath throwable, never as a `code_throwable` row with supertypes of their own.

The plan says exception ancestry is already there, and under "Unresolved classes" defines the order over handlers whose class has a `code_throwable` row or is the generated client exception. Read as written, that excludes:

- the catch-all in the Goal's own example (`java.lang.IllegalStateException`), and the `RuntimeException` / `Exception` / `Throwable` catch-alls the manual draft names;
- the `GENERIC` on `java.sql.SQLException` half of the new equivalence defect;
- every pair of two JDK classes (`IllegalArgumentException` under `RuntimeException`, `SQLIntegrityConstraintViolationException` under `SQLException` or under a bare `DATABASE` handler), for which no relation holds the subtype fact at all.

The fallback the section names does not catch these either: the classifier resolves a JDK class by reflection and refuses nothing, so such a handler would silently keep declared order, which is the behaviour the item exists to remove.

A pair whose narrower side is a classpath class and whose broader side is a JDK class is derivable today (the JDK name is a `supertype_name` row), so part of the rule works; the plan does not say that this is the intended reading, and it does not cover the JDK-under-JDK pairs. What would satisfy this: the plan states where the ancestry of a handler's class comes from when the class is the platform's, as a fact the store holds and owns (the modeling discipline's answer for a missing code fact is capturing it in the `code` family, not deriving it by reflection at read time), restates "resolves" so a JDK class resolves, and adds a JDK-under-JDK pair and a JDK catch-all to the fact document's cases. If the answer is a change to what `CodeCapture` admits, say whether that is in this item or a dependency.

*Author, 2026-11-11 (session 01FY5UvrebsVBt3mmPocP2yE):* the ancestry comes from the `code` family, captured by a new item, R1166 (`platform-throwables-are-code-facts`), which this item now depends on. Implementation's opening paragraph says what the store holds today and names the dependency; "Unresolved classes" restates "resolves" to cover a platform row and says how the subtype test reads the rows; the fact document gains a JDK-under-JDK pair, a JDK catch-all and a JDK `SQLException` subclass against a bare `DATABASE` handler. The alternatives are under "Other solutions we've considered".

**Finding 2 (question one: what the consumer is told). The sort as specified can try a later-declared handler before an earlier-declared one that is not broader than it, which rule 3, the manual draft's third bullet and the changelog note all say does not happen.**

Take `union E = T | V | U` with `T`: `GENERIC` on `IllegalStateException`; `V`: `GENERIC` on `RuntimeException`, `matches: "timeout"`; `U`: `GENERIC` on a subclass of `IllegalStateException`. `T` and `V` overlap and neither is narrower. `U` is narrower than `T`, so "take the earliest-declared handler that no remaining handler is narrower than" skips `T`, takes `V`, then `U`, then `T`. An `IllegalStateException("timeout")` routes to `T` today and to `V` after this item. That is a live handler changing its routing, not a dead one coming alive, so "the narrow handler is dead today, so the commit is a `feat`, not a breaking change" does not hold for it, and "the one declared first is tried first" is not what the author gets. With `T` and `V`'s handlers on one type instead, the same shape is the case where dispatch claims through one handler and `message:` reads the other's `description:`.

What would satisfy this: either the sort is stated so that two incomparable handlers keep declared order whatever is narrower than one of them, or rule 3, the manual bullet and the changelog entry say what actually happens, with this shape among the fact document's cases.

*Author, 2026-11-11 (session 01FY5UvrebsVBt3mmPocP2yE):* both. Keeping declared order for every incomparable pair is unsatisfiable (the finding's own shape is a cycle), so rule 1 is restated as a placement that moves the narrow handler in front of the broad one instead of holding the broad one back; the finding's shape now gives `U`, `T`, `V`, and `IllegalStateException("timeout")` stays on `T`. Rule 3 says a moved handler passes what sat between, and states and argues the guarantee that a throw only ever moves to a narrower handler, with the jOOQ-wrap pair as the named exception. The manual draft's third bullet, a new fourth bullet and the changelog entry say the same, and the shape and the wrap case are in the fact document's list.

**Non-blocking.** `graphitron_runtime_class` has no superclass column and a `CHECK` on `kind`, so "add a kind and state its superclass" is a column plus a kind; graphql-java's jar is in the captured classpath (`CodeCaptureTest` reads `graphql.AssertException` from it), so the pickup check the plan asks for will pass.
