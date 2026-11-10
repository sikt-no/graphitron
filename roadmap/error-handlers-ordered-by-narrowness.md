---
id: R1165
title: "Error handlers are matched narrowest first, and an unreachable one fails the build"
status: Backlog
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

## Today

Dispatch walks the channel's handlers in declaration order (the channel's `@error` types in order, and within a type its `handlers:` in array order) and the first match wins. Three consequences:

- A broad handler declared before a narrow one leaves the narrow one unreachable, with no diagnostic. Only handlers with identical criteria are rejected (the duplicate-criteria check, `ChannelRuleChecks.checkDuplicateMatchCriteria`).
- The order crosses types, so the member order of the errors union matters too. For an interface carrier the members are ordered by type name, which the author cannot control except by renaming types.
- The reference page says overlap across variants is harmless because `GENERIC` and `DATABASE` inspect orthogonal fields. That is not so: a `GENERIC` handler on `java.lang.RuntimeException` declared first claims jOOQ's `DataAccessException` wrapper, and a later `DATABASE` handler never gets the `SQLException` inside it.

## Starting point for the spec

Each handler is a small fixed predicate, so "A is narrower than B" (everything A matches, B matches) is decidable at build time:

- `GENERIC` against `GENERIC`: A's class is a subtype of B's, and B has no `matches:` or A's `matches:` text contains B's.
- `DATABASE` against `DATABASE`: B has no `sqlState`/`code` or A carries the same one, and the same `matches:` rule holds.
- `DATABASE` against `GENERIC`: B's class is `java.sql.SQLException` or a supertype of it, and B has no `matches:`.
- `VALIDATION` never enters the dispatch loop and is not ordered.

The spec has to check each row against the cause-chain walk (a mapping is offered every throwable in the chain, and a `DATABASE` `matches:` reads the messages from the outermost throw down to the matched `SQLException`), since "narrower" is a claim about whole chains, not single throwables.

Three rules follow:

1. **Narrower first.** The channel's handlers are ordered by a stable topological sort over the relation above, at build time, so the generated mapping table is already in match order and the runtime `claim` loop is unchanged.
2. **Unreachable is an error.** After the sort the only handler that cannot be reached is one that matches exactly what another does (each narrower than the other). That is today's duplicate-criteria rule widened from identical criteria to equivalent ones, for example a `DATABASE` handler with no criteria against `GENERIC` on `java.sql.SQLException`. The message names both handlers.
3. **Declaration order breaks ties.** Two handlers that overlap without either containing the other (two `matches:` texts on one class, a `sqlState` handler against a `code` handler, a wrapper class against its cause's class) keep their declared order, with no diagnostic: neither is dead, and whether a throwable ever matches both is a fact about runtime messages the SDL does not state.

## Open questions

- The default `message:` is chosen by walking the matched type's own handlers in declaration order for the first match that carries a `description:`. It should read the same sorted order, or the claim and the message can disagree about which handler matched.
- A schema that today puts a broad handler first starts routing to the narrow one. The narrow handler is dead today, so this reads as a `feat`, but the changelog entry should say it.
- A handler naming an abstract class whose every current concrete subclass is claimed earlier is dead in practice and not provably so, since a subclass can be added. Out of scope unless the class is sealed.
- The reference page (`docs/manual/reference/directives/error.adoc`, Constraints) and the how-to (`docs/manual/how-to/error-channel.adoc`, Handler dispatch) both state the declaration-order rule and the orthogonal-variants claim, and both change with this item.

## Other solutions we've considered

- **Keep declaration order, reject a shadowed handler.** The same containment check, reported as "handler X can never be reached because Y, declared earlier, matches everything it does". It gives the diagnostic but keeps the ordering chore, and for an interface carrier the author can only satisfy it by renaming types.
- **Warn on every overlap.** Any two `matches:` texts can occur in one message, so the warning would fire on nearly every pair of handlers on comparable classes.
