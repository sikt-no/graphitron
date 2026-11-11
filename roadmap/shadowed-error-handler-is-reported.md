---
id: R1168
title: "An error handler that an earlier handler shadows is reported, as a warning or an error"
status: Backlog
bucket: dx
priority: 3
theme: error-channel
depends-on: [error-dispatch-matches-graphitron-9]
created: 2026-11-11
last-updated: 2026-11-11
---

# An error handler that an earlier handler shadows is reported, as a warning or an error

## Goal

An author who declares an `@error` handler that can never claim an exception, because a handler tried earlier claims everything it would, is told so when the schema is built, as a warning or as an error according to a setting the consumer chooses. Dispatch itself stays as Graphitron 9 had it (R1167, `error-dispatch-matches-graphitron-9`): handlers are tried in the order the schema declares them, and graphitron does not reorder them. Today such a handler goes dead with no diagnostic unless its criteria are identical to the earlier one's.

```graphql
union FilmLookupError = FilmLookupCatchAll | FilmLookupInvalid

type FilmLookupCatchAll @error(handlers: [
    {handler: GENERIC, className: "java.lang.RuntimeException", description: "The film lookup failed."}
]) { path: [String!]! message: String! }

type FilmLookupInvalid @error(handlers: [
    {handler: GENERIC, className: "java.lang.IllegalArgumentException"}
]) { path: [String!]! message: String! }
```

Every `IllegalArgumentException` is a `RuntimeException`, and the catch-all is declared first, so `FilmLookupInvalid` is never answered. After this item the build says that the `IllegalArgumentException` handler is unreachable behind the `RuntimeException` handler of `FilmLookupCatchAll`, and the author moves the catch-all last.

The decision is Filip's of 2026-11-11: narrowest-first ordering (R1165, discarded) is dropped in favour of Graphitron 9's declared order plus this diagnostic.

## Notes for the Spec

- **The shadow test is R1165's containment rule, kept.** Handler A is shadowed by an earlier handler B when every throw A claims, B claims: `GENERIC` against `GENERIC` when A's class is B's class or a subtype and B has no `matches:` or A's `matches:` text contains B's; `DATABASE` against `DATABASE` when B has no `sqlState`/`code` or A carries the same one, with the same `matches:` rule; the two cross-kind rows through `java.sql.SQLException`. The discarded item's body, with the full rows and the reviewer round that checked them against `GenericMapping` and `DatabaseMapping`, is in git history at `roadmap/error-handlers-ordered-by-narrowness.md` (last present at `c9df7a7`). Its sort, its match-order relation and its "precedes" pair are not wanted.
- **"Earlier" is defined by R1167's order, hence the dependency.** R1167 tries one kind before the other depending on whether the outer exception is a `DataAccessException`, so a handler of one kind is not simply shadowed by a broader handler of the other kind declared before it. The Spec states the test per phase and says what a handler reachable in one phase and dead in the other gets.
- **It subsumes the identical-criteria check.** `ChannelRuleChecks.checkDuplicateMatchCriteria` and the `FieldBuilder` copy reject two handlers with identical criteria; that is the degenerate case of this rule. Whether it stays a hard error whatever the setting says is a Spec decision.
- **JDK ancestry is the open viability question.** The example above compares two JDK classes, and the fact store has no ancestry for them: `code_throwable` and `code_throwable_supertype` hold only classes read off a classpath entry (`CodeCapture.throwables`), so a JDK exception appears only as some library exception's `supertype_name`. R1166 (`platform-throwables-are-code-facts`, discarded with R1165) was the item that would have captured the platform's throwables; its notes are in git history at `roadmap/platform-throwables-are-code-facts.md` (last present at `c9df7a7`). The catch-alls authors write are JDK classes, so the Spec has to say where that ancestry comes from: captured into the `code` family as part of this item, or the check made in the classifier, where `ChannelRuleChecks` sits today and reflection already resolves the class. R1165's architect consultation ruled for the store and against reflection; that ruling was about an order three emitted readers share, and a diagnostic with one reader may weigh differently. Ask again.
- **Configurable severity is new.** `graphitron_defect_type.severity` is stated once per defect code and its comment calls it "a function of the code and nothing else"; no consumer setting moves a finding between warning and error today. The Spec decides the default (the recommendation is error, since the handler is provably dead, with warning as the opt-out for a schema migrating from Graphitron 9 that carries such handlers), where the setting lives (a Maven plugin parameter is the obvious seat), and how it meets the store: one code whose severity a consumer overrides, or two codes. Whether this is a setting for this one diagnostic or the first use of a general severity override is worth settling before the mechanism is built.
- **Interface carriers.** For an interface the members are ordered by type name, which the author controls only by renaming types; the diagnostic should say which order it judged.
- User documentation: the `@error` reference page's Constraints, the how-to's pitfalls, and a `diagnostics-glossary.adoc` entry.
