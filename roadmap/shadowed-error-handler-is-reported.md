---
id: R1168
title: "An error handler that an earlier handler shadows is reported, as a warning or an error"
status: Spec
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

## Glossary

- *Handler*: one entry of an `@error` type's `handlers:` argument; its *kind* is `GENERIC`, `DATABASE` or `VALIDATION`.
- *Errors container*: the union, interface or single `@error` type that a payload's errors field lists. A producer field (a `@service` field, or a payload-returning `@mutation` or `@routine` write) catches into it.
- *Claims*: a handler claims a throw when its predicate holds for some throwable in the throw's cause chain.
- *Defect*: a row of the fact store (the per-graph database the generator fills from the SDL, the classpath and the jOOQ catalog) saying a schema breaks a declared rule. Each rule is one code in `graphitron_defect_type` with a severity; an `error` code stops `validate` and `generate` through the store's verdict (`docs/architecture/explanation/defects-as-facts.adoc`).

## Today

Two handlers with identical criteria in one container are refused (`ChannelRuleChecks.checkDuplicateMatchCriteria`, "rule 8", with a copy in `FieldBuilder` for the DML path). Nothing else is checked: a broad handler declared before a narrower one of the same kind leaves the narrower one dead, silently. The reference page tells the author to order handlers by hand ("Nothing sorts by specificity").

## The rule

R1167 fixes the order this rule judges by. Handlers of one kind are tried in declared order (the container's `@error` types in member order, then each type's `handlers:` in array order), and which kind goes first depends on the throw: `DATABASE` first when the outer exception is a jOOQ `DataAccessException`, `GENERIC` first otherwise.

**Handler A is shadowed in a container when a handler B of the same kind, declared before A in that container, claims every throw A claims.** That is decidable from what the author wrote and the class hierarchy:

- `GENERIC` behind `GENERIC`: A's class is B's class or a subtype of it, and B has no `matches:` or A's `matches:` text contains B's.
- `DATABASE` behind `DATABASE`: B has no `sqlState`/`code` or A carries the same one, and the same `matches:` rule holds.
- `VALIDATION` never enters dispatch and takes no part.

Identical criteria are the case where the containment runs both ways, so this rule covers rule 8 and replaces it.

**A handler of the other kind never shadows.** The report is for a handler that is provably dead, and under R1167 a handler of one kind is never dead because of the other kind: each kind is tried first in one of the two phases, and both phases are reachable for both kinds (a `DATABASE` handler claims a `DataAccessException` thrown by jOOQ before any `GENERIC` handler sees it; a `GENERIC` handler claims a service's own exception before any `DATABASE` handler sees it, whatever that exception wraps). So R1165's cross-kind rows through `java.sql.SQLException`, and its "precedes" pair, are not part of this rule. This is why the item depends on R1167 and is not stated against today's single mixed order: against that order a `GENERIC` handler on `Exception` declared first does shadow every `DATABASE` handler, and the rule would report handlers that R1167 brings back to life.

**The grain is the handler in a container.** The same `@error` type can be a member of two unions, shadowed in one and live in the other, and each is reported or not on its own. A handler shadowed by an earlier handler of its own type is shadowed in every container the type is in, and is reported once per container. For an interface the members are ordered by type name; the report says which earlier handler it judged against, so the author can see the order.

What is not reported, because it is not provable: two handlers that overlap with neither containing the other (two `matches:` texts on one class, a `sqlState` handler and a `code` handler), and a handler on an abstract class whose every current concrete subclass is claimed earlier.

## Decisions that need a ruling

Each has a recommendation the rest of the plan follows. The first three were put to `principles-architect`; its answers are under "Architect consultation".

1. **A defect, not a lint rule.** The lint channel has the configuration seat already (`<lint><disabledRules>`), but it has no path to stop a build, and `fact-model.adoc` says nothing new is added to the family its rules are stated in. The defect family stops the build and puts the finding at the handler in the editor. Recommendation: one defect code.
2. **The default is `error`, and the setting demotes it to `warning`.** The handler is provably dead, and the remedy is to move one line. A schema arriving from Graphitron 9 with such a handler built there, so the opposite default (warning, opt in to error) is defensible under the compatibility ruling; R1167's ruling is about where a throw is routed, not about what is refused, and no page says which way that extends. Identical criteria are already a hard error today, so `error` by default changes nothing for them.
3. **Demotion is a general, small mechanism, used here first.** No consumer setting moves a defect's severity today: `graphitron_defect_type.severity` is "a function of the code and nothing else". Recommendation: a code declares whether it may be demoted (a new boolean column, true for this code and false for every existing one, since most defects leave the generator nothing well defined to emit), the consumer names the codes to demote in the plugin configuration, and naming a code that may not be demoted fails the build. Severity stays a function of the code and the configuration, both facts in the store. The alternative, a setting for this one diagnostic, is smaller and would be rebuilt for the second code that wants it.
4. **JDK ancestry is captured in this item.** The goal's example compares two JDK classes and the store has no ancestry for them (see "Implementation"). R1166 was the item for that capture and was discarded with R1165; this rule cannot be delivered without it, so its content is folded in here. The architect would re-file it as its own item this one depends on, having a second reader in the editor's `className:` completion. That is a one-line split if preferred; what is not on offer is the rule without the capture, or the check done by reflection in the classifier.

## Implementation

**Platform throwables (the capture R1166 described).** `code_throwable` and `code_throwable_supertype` are written by `CodeCapture.throwables` from the classpath census alone, so `java.lang.RuntimeException` appears only as some library exception's `supertype_name` and nothing says `IllegalArgumentException` is one. Capture the platform's throwables into the same two relations under a platform source: both relations key on `store_source`, and `FederationCapture` is the precedent for a source that is not a classpath entry, registered and stamped by version. Stamp this one by the runtime version, so it re-reads when the JDK changes and is swept like any other. Admit the throwables in the exported packages of `java.base` and `java.sql`, which covers what a handler names. The relation comments say the ancestry is that of the JDK the generator runs on, which is what the classifier's reflection reads today. `roadmap/derived-read-cost-is-a-shape-problem.md` plans a reshaping of supertypes in the `code` family; this adds rows and leaves the shape alone.

**The generated client exception.** `<outputPackage>.schema.GraphitronClientException` is emitted by the run and has no `code_throwable` row. `graphitron_runtime_class` is the precedent for a class no classpath declares: add a kind for it and a superclass column (`graphql.GraphqlErrorException`, today the constant `ExceptionClass.GeneratedClientException.SUPERCLASS`), and read its ancestry through that superclass's `code_throwable_supertype` rows. graphql-java's jar is in the captured classpath.

**Two derived relations and the rule.** Names are the implementer's; the grains are not. All three are written in the anchoring phase by a `graphitron` stage that runs after `fetchers` (it reads `graphitron_errors_field`), declared in `meta_stage`, `meta_relation` and `meta_stage_write`, with `touched_at` on every row and a sweep. The `graphitron` gatherer may already read the `code` family (`meta_gatherer_dependency`).

- *Container handler sequence*, grain "one dispatch-capable handler of an errors container, at its declared rank within its kind": keyed by container name, `@error` type name and the handler's declared index, carrying the kind and the rank, with the handler entry's written position as a cascading foreign key. Containers are every union or interface an errors field lists and every `@error` type as a container of itself. R1167 reads this same order through a query in `ErrorChannelRelation` and turned a stored relation down for having one reader; this rule is the second, so the relation is stored here and `ErrorChannelRelation` is repointed at it.
- *Shadowing*, grain "this handler of a container is shadowed by that earlier handler of it": one row per pair for which the rule holds, a statement over the sequence, the handler entries (`graphitron_ast_error_handler_entry` and its `GENERIC` and `DATABASE` subtype relations) and `code_throwable_supertype`, plus a substring test. "A's class is B's class or a subtype of it" is read as the names being equal or B's class being a `supertype_name` of A's; a class present in two classpath entries has two `code_throwable` rows and the rule reads the union of their supertypes.
- *The defect*: one code in `graphitron_defect_type`, written into `graphitron_entry_defect` at the shadowed handler's position, with a detail relation keyed by defect, container and shadowing handler (type name and declared index), never a detail string. Where several earlier handlers shadow one, the detail names the first. Follow "Adding a defect" in `defects-as-facts.adoc`.

**Unresolved classes.** A `GENERIC` handler naming a class with no `code_throwable` row from any source, and which is not the generated client exception, gets no shadowing rows; the classifier's existing refusal of the unresolved name is what fails that build. The shadowing relation's comment says so.

**Rule 8 goes.** Delete `ChannelRuleChecks.checkDuplicateMatchCriteria`, its call in `ErrorChannelWalker`, and the `FieldBuilder.checkDuplicateMatchCriteria` copy with its two callers (`FieldBuilder` and `BuildContext`). Rule 7 (more than one `VALIDATION` handler) is a different rule and stays.

**Demotion.** `graphitron_defect_type` gains the "may be demoted" column. The plugin gains a list parameter naming codes to report as warnings; the configuration gatherer writes it to a `store_graph_` relation keyed by graph and code, as `store_graph_lint_disabled_rule` is written for `<disabledRules>` (`StoreEntries.lintDisabledRules`), and an unknown code or one that may not be demoted fails the build at configuration, as an unknown lint rule id does (`LintConfig.validated`). `EntryDefectVerdict` and the `diagnostic` view read a finding's severity as the code's own unless the graph demotes it. At pickup, confirm that a `warning`-severity defect already reaches the build log, the editor and the MCP `diagnostics` tool; if no existing code exercises that path, this item's tests are what pin it.

## Tests

- A fact document for the platform capture: `java.lang.IllegalArgumentException` has a `code_throwable` row under the platform source and `code_throwable_supertype` rows reaching `java.lang.Throwable`.
- A fact document for the rule: a schema with each row of the rule; two JDK classes (`IllegalArgumentException` behind `RuntimeException`); a library class behind a JDK class; the generated client exception behind `RuntimeException`; identical criteria (rule 8's cases, moved from `ErrorChannelClassificationTest`); a handler shadowed within its own type; one `@error` type in two containers, shadowed in one only; an interface carrier; a handler shadowed by two earlier handlers. Expected sequence rows, shadowing rows, the defect and its detail row. Near misses that raise nothing: the narrow handler declared first; two different `matches:` texts on one class; a `sqlState` handler and a `code` handler; a `DATABASE` handler declared after `GENERIC` on `java.lang.Exception`; a `GENERIC` handler on `java.sql.SQLException` declared after a bare `DATABASE` handler.
- Demotion: with the code demoted the same schema generates, and the finding is a warning in the build's report; naming an unknown code, and naming a code that may not be demoted, each fail at configuration with the valid codes listed. `EntryDefectVerdictTest` gains the demoted case.
- `ErrorChannelWalkerTest` loses its rule 8 arm.
- Execution tier, on the sakila example: none. The rule changes no generated code; R1167's tests own dispatch.

## User documentation (first-client check)

`docs/manual/reference/directives/error.adoc`, Constraints, replacing the duplicate-criteria bullet and following R1167's bullet on order within a kind:

> * A handler that can never be reached is rejected: one declared after a handler of the same kind that matches everything it does. That is a subclass after its superclass, a handler with `matches:` after the same handler without, a `sqlState` or `code` handler after a `DATABASE` handler with neither, or two handlers with the same criteria. The message names the earlier handler; declare the narrower one first. A `GENERIC` handler and a `DATABASE` handler never hide one another, whichever is declared first.

`docs/manual/reference/mojo-configuration.adoc`, a new parameter row and a short section:

> Some of the rules graphitron rejects a schema for can be turned into warnings, where the schema still has a well-defined meaning without the fix. Name the rule's code, as shown in the build failure:
>
> [source,xml]
> ----
> <configuration>
>   <reportAsWarnings>
>     <code>GRAPHITRON_ERROR_HANDLER_SHADOWED</code>
>   </reportAsWarnings>
> </configuration>
> ----
>
> A code that is misspelled, or that cannot be turned into a warning, fails the build with the list of codes that can.

`docs/manual/how-to/error-channel.adoc`: the pitfall on handler order says the build catches it. `docs/manual/how-to/migrating-from-legacy.adoc`: a schema with a handler that was already dead on Graphitron 9 is now rejected, with the `<reportAsWarnings>` entry as the way to migrate first and fix later. `docs/manual/reference/diagnostics-glossary.adoc` gets the code's entry.

## Roadmap entries

- Changelog: a schema with a shadowed handler stops building unless it demotes the code. The commit is `feat(R1168)!` with a `BREAKING-CHANGE:` footer naming the fix (declare the narrower handler first) and the opt-out. If decision 2 goes the other way it is a plain `feat`.
- Not in scope: moving rule 7 into the store; reporting overlap that is not containment.

## Retired vocabulary

- `checkDuplicateMatchCriteria` (both the `ChannelRuleChecks` and `FieldBuilder` members), and "rule 8" / `Rule 8` as the name of the identical-criteria check in javadoc, test names and the how-to.
- "Nothing sorts by specificity" as the reference page's whole statement on handler order.

## Other solutions we've considered

- **Sort handlers narrowest first (the discarded R1165).** Ends the ordering chore, but answers differently from Graphitron 9 wherever a broad handler was declared first.
- **A lint rule escalated by configuration.** Warning by default through the existing `<lint>` block, with a new child naming rules to treat as errors. It reuses the seat, but a lint finding is assembled after the classification walk and cannot stop `validate` before it, so "error" would be a second verdict path beside the store's.
- **Two defect codes, one per severity.** The configuration would then decide which code a row carries, and a code names the rule broken, not how the consumer feels about it.
- **Check in the classifier, by reflection, beside rule 8.** Smallest, and needs no platform capture. It adds a rule to the walk the store is replacing and reads by reflection a fact the store should hold; the defects page lists the Java-judged rule families as what remains to move.
- **State the rule against today's mixed order, with R1165's cross-kind rows, and land before R1167.** It would report `DATABASE` handlers behind a `GENERIC` catch-all, which R1167 makes reachable: a defect on a schema that is about to be valid.

## Architect consultation

`principles-architect` was consulted at take-up on three forks.

- *Lint rule or defect.* It favoured one defect code over a lint rule (no build-stopping path, and the lint family's rule view takes nothing new) and over two codes; the plan follows it. It noted that no architecture page lets a consumer setting override a code's severity or says whether a Graphitron 9 schema must keep building, and that both are for Alf and Filip; they are decisions 2 and 3. It suggested the override be a fact captured from the configuration and read by `EntryDefectVerdict`; the plan follows that. It preferred folding rule 8 into the new defect, with a separate always-error code only if identical criteria must stay an error under demotion; the plan folds it into the one code.
- *Store or classifier, and the JDK capture.* It ruled for the store, one reader or three, and for the platform capture as its own re-filed item with its two readers named. The plan takes the store and keeps the capture inside this item, since R1166 was discarded on Filip's word hours earlier; decision 4 puts the split to him. It asked for the defect at the shadowed handler's position with container and shadowing handler in a detail relation, and for the handler sequence to be a stored relation shared with R1167's `ErrorChannelRelation`; the plan follows both.
- *Phases.* It agreed with depending on R1167 and with "provably dead in every phase" as the test. The plan's finding that this leaves no cross-kind shadowing at all was reached after the consultation and has not been put to it.
