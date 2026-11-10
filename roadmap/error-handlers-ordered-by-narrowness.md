---
id: R1165
title: "Error handlers are matched narrowest first, and an unreachable one fails the build"
status: Spec
bucket: dx
priority: 4
theme: error-channel
depends-on: []
created: 2026-11-10
last-updated: 2026-11-10
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

1. **Narrower first.** The handlers of an errors container (the union, interface or single `@error` type an errors field lists) are matched in the order a stable topological sort gives: repeatedly take the earliest-declared handler that no remaining handler is narrower than or precedes. Declared order is member order, then `handlers:` array order, as today.
2. **Equivalent handlers are an error.** Two handlers each narrower than the other match exactly the same throws, so whichever comes second is unreachable. After the sort this is the only way a handler is unreachable. It covers today's identical-criteria rule and one pair it misses: a `DATABASE` handler with no `sqlState`, `code` or `matches:` against `GENERIC` on `java.sql.SQLException` with no `matches:`.
3. **Declared order breaks ties.** Two handlers that overlap with neither narrower (two `matches:` texts on one class, a `sqlState` handler against a `code` handler, a wrapper class against its cause's class) keep their declared order, with no diagnostic: neither is dead, and whether a throw ever matches both is a fact about runtime messages the SDL does not state.

The message follows the same rule at its own grain: `message:` reads the `description:` of the first handler *of the matched type*, in that type's own match order, that matches the claimed exception. An `@error` type is a container of one member, so its own order is rule 1 applied to it alone.

That the readers agree is an invariant of this shape, and the tests pin it. The resolver walks the container's order, and every handler ahead of the claiming one declined the whole chain at dispatch, so the resolver stops on the claiming handler and returns its type. Within that type the message walk can stop on a different handler than the one that claimed only when two of the type's handlers both match the claimed exception and neither is narrower; that is rule 3's tie, and the type's own declared order settles it, which is what the author wrote.

## Implementation

The order and the equivalence check are derived in the fact store (the per-graph database the generator fills from the SDL, the classpath and the jOOQ catalog, and emits from), not in the classifier. The handler entries as written are already there (`graphitron_ast_error_handler_entry` and its `GENERIC` and `DATABASE` subtype relations), and so is exception ancestry (`code_throwable_supertype`); the `graphitron` gatherer may already read `code`. Three readers ask for one order, which is the point at which a derivation earns a relation.

**Two stored relations and a stage.** Names are the implementer's; the grains are not.

- *Handler precedence*, grain "this handler is narrower than, or precedes, that handler": one row per ordered pair of dispatch-capable handlers in the graph for which the rule above holds, with a column saying which basis (containment, or the jOOQ wrap). A rule view states it (joins over the handler entries and `code_throwable_supertype`, plus a substring test) and the stage stores it, so the sort reads a table.
- *Match order*, grain "one dispatch-capable handler of an errors container, at its match position": keyed by container name, `@error` type name and the handler's declared index, with the match position unique within the container, and the handler entry's written position carried beside the names as a cascading foreign key (the container and the handler can be in different files). Containers are every union or interface an errors field lists and every `@error` type, the latter as a container of itself. The stage's Java computes the sort over the stored precedence rows; a topological sort is anchor-phase work a view cannot state.
- The stage is a new `graphitron` stage declared in `meta_stage`, with its relations in `meta_relation` and `meta_stage_write`, `touched_at` on every row and a sweep. It reads `graphitron_errors_field`, so it runs after `fetchers`.

**The generated client exception.** `<outputPackage>.schema.GraphitronClientException` is emitted by the run and has no `code_throwable` row on a clean build. `graphitron_runtime_class` is the precedent for a template class no classpath declares: add a kind for it, state its superclass (`graphql.GraphqlErrorException`, today the constant `ExceptionClass.GeneratedClientException.SUPERCLASS`), and have the precedence rule read its ancestry through that superclass's `code_throwable_supertype` rows. Check at pickup that graphql-java's jar is in the captured classpath; if it is not, the template's ancestors are stated as constants beside the kind. No `code_` row is invented: that family transcribes the classpath.

**The defect.** Equivalence is one code in `graphitron_defect_type`, written by the same stage into `graphitron_entry_defect` at the later-declared handler's position, with a detail relation naming the other handler by type name and declared index (not in the detail string). Follow the steps under "Adding a defect" in `docs/architecture/explanation/defects-as-facts.adoc`. It replaces rule 8 instead of sitting beside it: delete `ChannelRuleChecks.checkDuplicateMatchCriteria`, its call in `ErrorChannelWalker`, and the `FieldBuilder.checkDuplicateMatchCriteria` copy with its two callers (`FieldBuilder` and `BuildContext`, the DML path). Rule 7 (more than one `VALIDATION` handler) is a different rule and stays where it is.

**The readers.** Each reads the order from the store through one query in the `emit` package, extending `ErrorChannelRelation` from member grain to handler grain; no emitter joins store positions to `ErrorType.handlers()` by a bare index without the key being named and tested.

- `ErrorMappingsClassGenerator`: each `ByType.<TYPE>` array is emitted in the type's own match order, and each channel constant becomes the container's handlers in match order, written as references to `ByType` elements. `concat` goes.
- `GraphitronSchemaClassGenerator.buildErrorPolymorphicResolver`: the `matchesSource` statements are emitted in the container's match order.
- `ErrorTypeFetcherClassGenerator.messageMethod` (and `lastOverrideIndex`): indexes follow the type's own match order.
- `ErrorRouter.claim` is unchanged apart from its javadoc, which says "source-declaration order" (`ErrorRouterClassGenerator.buildClaimMethod`); the `concat` javadoc and the `Mapping` interface javadoc say the same and change with it.

`ErrorType.handlers()` stays in declared order and is no longer read for order by any emitter.

**Unresolved classes.** The order is defined over handlers whose class resolves (a `code_throwable` row or the generated client exception). A `GENERIC` handler naming a class that does not resolve gets no precedence rows; the classifier's existing refusal of the unresolved name is what fails that build. Say so in the precedence relation's comment.

## Tests

- A fact document (the store-tier fixture format the defect steps name) for the stage: a schema exercising each row of the rule, the jOOQ-wrap pair, a tie, an interface carrier, one `@error` type in two containers, and the generated client exception; expected precedence rows, match-order rows, the equivalence defect with its detail row for both the identical-criteria case and the `DATABASE`-against-`GENERIC` `SQLException` case; and a near miss that raises nothing (same class, two different `matches:` texts).
- Pipeline tier: `ErrorMappingsClassGeneratorTest` and the resolver and message emitter tests assert the emitted order for a union declared broad-first, and that the three readers emit the same order for one container.
- Execution tier, on the sakila example: an errors union declared catch-all first, with a narrow handler whose exception extends the catch-all's class, routes the narrow throw to the narrow type and returns the narrow handler's `description:`; a `DATABASE` handler declared after `GENERIC` on `java.lang.RuntimeException` receives a constraint violation raised through jOOQ.
- `ErrorChannelClassificationTest`'s rule 8 cases move to the fact document; `ErrorChannelWalkerTest` loses its rule 8 arm.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the two bullets on declaration order and "Nothing sorts by specificity", and correcting the cross-variant bullet:

> * The narrowest handler wins. When two handlers both match a thrown exception and everything one matches the other also matches, the narrower one is tried first, wherever each is declared: a subclass before its superclass, a handler with `matches:` before the same handler without, a `sqlState` or `code` before a `DATABASE` handler with neither. A catch-all can be declared anywhere.
> * A `DATABASE` handler is tried before a `GENERIC` handler without `matches:` on `DataAccessException`, `RuntimeException`, `Exception` or `Throwable`, since jOOQ wraps every database error in a `DataAccessException`.
> * Where two handlers can match the same exception and neither is narrower (two different `matches:` texts on one class, a `sqlState` handler and a `code` handler), the one declared first is tried first: union members in order, then each type's `handlers:` in order. If that matters, make one narrower.
> * Two handlers that match exactly the same exceptions are rejected, since the second could never be reached: identical criteria, or a bare `DATABASE` handler beside `GENERIC` on `java.sql.SQLException`.

The `handlers` parameter row ("The first entry whose criteria match ... wins") and the how-to's "Handler dispatch: source order, cause-chain unwrap" section and "Source order is significant" bullet (`docs/manual/how-to/error-channel.adoc`) are rewritten to the same rule, the how-to's "orthogonal fields" claim is dropped, and its `message:` paragraph says "in match order" where it says "in declaration order". The new defect code gets its entry in `docs/manual/reference/diagnostics-glossary.adoc`.

## Roadmap entries

- Changelog: a schema that declares a broad handler before a narrower one starts routing to the narrower one. The narrow handler is dead today, so the commit is a `feat`, not a breaking change, but the entry says it.
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
- **Record the claiming handler at dispatch** so the resolver and `message` read it instead of re-matching. It would make "the readers agree" true by construction, but the claim record is keyed on the source alone, one source can be claimed through two channels, and a null context records nothing, so the re-match has to stay as the fallback and the order with it. A candidate for its own item.

## Architect consultation

`principles-architect` was consulted on where the order lives. It favoured the store over the classifier, the split into a stored precedence relation and a Java-sorted order relation, the defect in `graphitron_entry_defect` replacing rule 8, and `graphitron_runtime_class` for the generated client exception; the plan follows all four. It also held that `message` cannot read a per-type order, because a type's handlers sorted alone can come out differently from their order inside a container, and proposed recording the claiming handler instead. The plan keeps the per-type order for `message` and states why the outcome is still well defined (end of "The rule"); recording the claim is listed above as considered. That disagreement is the first thing for the reviewer to weigh.
