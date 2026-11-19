---
id: R1201
title: "@outbox events are types in the sending schema, built from the mutation input and result"
status: Backlog
bucket: feature
priority: 5
theme: runtime-connection
depends-on: [transactional-outbox-directive]
created: 2026-11-19
last-updated: 2026-11-19
---

# @outbox events are types in the sending schema, built from the mutation input and result

## Goal

An `@outbox` message is a typed event declared in the sending schema: the event's name is the name of a GraphQL object type in that schema, and the message's body is an instance of that type, built from the mutation's input and result by a new `construct` argument. Today (R1113, the `@outbox` directive) the event name defaults to the field's coordinate (`Mutation.createFilm`), which names the event as if it were a field, and the body is a flat JSON object assembled key by key, so nothing in the schema says what a consumer receives. When this lands, an event is a type: it is named like one (`FilmCreated`), its shape is declared and versioned in the SDL beside the mutation that emits it, and graphitron checks at build that `construct` fills it from sources whose types fit. Each emitted message carries a subject, the identifier a relay routes it by (to a topic, a partition), named by the directive rather than left for the relay to dig out of the payload.

## Design direction

- **The event is a type in the sending schema.** An `@outbox` names its event, and a type of that name must exist in the schema; a name with no matching type is a build error. The coordinate default goes, since an event has to be named as a type.
- **`construct` builds the event.** A new `construct` argument maps the mutation's input and result onto the event type's fields, written in Apollo Connectors' `JSONSelection` syntax (the syntax `argMapping` was borrowed from), with the sources `argMapping` admits today: arguments, `$result`, `$session`, `$scope`, `$error`. What changes is the target: the event type's fields rather than free-form JSON keys, so the build checks that every field is filled from a source whose type fits, and the payload's shape follows from the type. The syntax is settled; the work is a `JSONSelection` parser of graphitron's own, driven by this case, and checking a selection against a target type, including nested event types and lists.
- **The mapping is a scalar, not a `String`.** `construct` is declared with a selection scalar, as Apollo Connectors declares its `selection` arguments with `JSONSelection`. Today every `argMapping` is a `String` in `directives.graphqls`, and the LSP finds them by listing each coordinate in `LspVocabulary`; a scalar lets the build and the editor recognise a mapping by its type, so a new directive argument that carries one needs no new entry. The concept is the same as Apollo's: a selection over JSON, where the sources are variables bound by context. Apollo binds `$args`, `$this` and `$config`; graphitron binds `$result`, `$session`, `$scope` and `$error`, and different variables do not make a different language. If the input to the selection is the schema's own JSON, with a node id read as a JSON object of its key columns, then a key-column decode such as `input.bookingId.customer_id` is an ordinary path, and graphitron's mappings sit close to `JSONSelection`. The question is therefore not whether the two are different, but how far graphitron's grammar drifts from Apollo's, and whether staying within it is worth holding to; the scalar's name follows from that answer, `JSONSelection` itself if we hold to it.

  An unnamed path is the case that looked like drift and is not. In `JSONSelection` a path with no leading variable reads from `$`, "the current object under selection": `author.name` is `$.author.name`, and `$` is bound to the input at the top level and to the value a `{ ... }` subselection received inside one. Graphitron's bare paths (`in.title`) read the field's arguments, which is the same rule with `$` bound to the arguments object; Apollo binds it to the response body in a `@connect` selection and reads arguments through `$args`. So the difference is what `$` is bound to, not the grammar. The same rule gives `construct` its per-element case: a subselection over a list maps over each element with `$` rebound to it (`friends: friend_ids { id: $ }`), which is how one call can emit one event per element without a cardinality setting.
- **`argMapping` keeps a different job.** It maps onto the table or function that receives the event (its columns or parameters), not onto the event itself. `construct` says what the event is; `argMapping` says how it lands in the outbox.
- **Cardinality follows from the mapping.** One call emits one or more events of a type: a `construct` that ranges over a list (a batch input, a list result) emits one event per element, and one over single values emits one. The directive carries no separate cardinality setting.
- **Every event has a subject.** A new `subject` argument on `@outbox` names the field of the event type that identifies the event's subject, the value a relay routes it by. The field must exist on the event type and be a scalar or id the relay can use as a key.
- **The common `<payload>` dissolves later.** The plugin's `<outbox><payload>` block, the argMapping every message carries, probably has no place once the event is a type, but it stays until consumers have switched to typed events; its retirement is a follow-up, not part of this item.

## `JSONSelection`, and how far argMapping is from it

Measured against Apollo's grammar (`apollo-federation/src/connectors/json_selection/README.md` in `apollographql/router`) with its methods left out: `->` method calls, `$(...)` expression paths, `@` (which only means something inside a method's arguments), and, as the same kind of expression machinery, the `??` and `?!` operators. Graphitron does not aim to support those. Within what is left the grammar is close, and every argMapping written today parses as a `JSONSelection` except one whose left side is `$tenant`.

**Scope.** This item implements `JSONSelection` (less the methods) for `construct`, driven by what the outbox case needs. It does not move the existing argMapping sites onto it: `@service`, `@routine`, `@condition`, `@outbox`'s own `argMapping` and the `<outbox><payload>` block keep their current reader, and migrating them onto the new capability is follow-up work for their own items. The measurements below say what such a migration would meet, and what `construct` needs that argMapping lacks.

**Already true to the syntax.** `key: a.b.c` is Apollo's `Alias KeyPath`, output key on the left and source on the right, as in Apollo. `key: $result`, `$result.film.id`, `$session.userId`, `$scope.alias`, `$error.message`, `$source.col` and `$context.k` are all `VarPath`s; only the variable names are graphitron's. Comma-separated lists and `#` comments match.

**Accepted by argMapping, refused by Apollo.** Matters to a later migration, not to `construct`, which starts on the strict grammar.

- `$tenant: path`. An `Alias` is a `Key`, an identifier or a quoted string, never a variable. The nearest legal spelling is a quoted key, `"$tenant": path`, which reads as a literal key and so changes what the entry means.
- Mixed separators. Apollo makes each list all commas or all whitespace; graphitron's lexer treats a comma as whitespace, so `a: x, b: y c: z` passes here and fails there.
- Lexer leniency: names admit non-ASCII letters, and a dot is folded into the name token, so `a . b` fails here where Apollo allows whitespace around a dot. Cosmetic.

**In `JSONSelection` and missing from argMapping.**

- `{ ... }` subselections, including a path followed by one (`friends: friend_ids { id: $ }`). This is what `construct` needs, for nested event types and for one event per element. Today an `@outbox` payload's keys are flat by rule.
- A bare key on its own (`title`, meaning `title: title`); graphitron requires the colon.
- `$` named explicitly (`$`, `$.in.title`), rejected today as an unknown variable. With `$` bound to the arguments it should parse; admitting `$args` beside it is a choice, not a requirement.
- Literal values (`version: 2`, `kind: "film"`, `true`, `null`), quoted keys and quoted path segments.
- Optional chaining (`a?.b`) and spread (`...in`).

**Where the distance actually is: the implementation.** What the new parser does not inherit, and what a later migration would have to replace.

- *Three readers, none a parser of the grammar.* `ArgMappingSigil.scan` and `ArgMappingSigil.entries` split the raw string on commas before lexing, lift every entry whose right side starts with `$`, and hand the rest to `GraphQLSelectionParser.parseEntries`, which refuses a `$` token outright. The LSP's `ArgMapping` slices the text on commas a second time, on its own. A comma split cannot survive subselections, quoted strings or a comma inside a comment. `construct` therefore gets a parser of the grammar, with `VarPath` a token of it rather than a lift ahead of it, rather than an extension of these; the build, fact capture and the LSP read `construct` through it.
- *The store is flat.* `graphitron_ast_argmapping_pair_entry` holds one row per entry: the key, the right side as a string, and a position. `construct`'s selection is a tree (a parent entry, or a path to the key), so it gets a relation of its own rather than growing this one, and that relation owes the modeling discipline (`docs/architecture/explanation/modeling-discipline.adoc`).
- *R249 (nested `@argMapping` syntax) is a consumer, not a rival.* Its `request: { customerId: input.customer.id, ... }` form for a `@service` record parameter is `Alias SubSelection`, legal `JSONSelection`; the parser this item brings is one R249 could adopt when it is taken up, which is R249's call, not this item's.
- *`@experimental_constructType(selection: ...)` is not a precedent.* It shares `parseEntries` and its argument is named `selection`, but the directive is not graphitron's, has no consumer, and its declaration is removed by R599 (remove the stray `@notGenerated` and `@experimental_constructType` declarations).

## Open questions for Spec

- Whether to commit to staying within `JSONSelection` (and so name the scalar `JSONSelection`). Which parts of the grammar `construct` brings beyond the subselection it needs: the bare key, an explicit `$`, literals, quoted keys, optional chaining, spread. The outbox case decides; a part no event needs can wait.
- The store shape of `construct`'s selection: the relation a tree of entries lands in, its grain, and how its entries point at the event type's fields.
- Whether the per-element case is a subselection over a list at the top of `construct`, and what the emitted events are when it is: one per element of that list, each built with `$` bound to its element.
- Whether the event type is a plain object type, an object type marked with a directive of its own, or something else the generator can tell apart from the API's own types; and whether it is visible to clients.
- How `subject` reaches the receiver: a role of its own in the `<outbox>` configuration beside `type` and `payload`, or an `argMapping` entry onto a column or parameter.
- How an event type is versioned (`FilmCreatedV2` beside `FilmCreated`) given that today a version is a new `event` string.
- What `on: ERROR` messages look like as typed events, where `$result` is absent and `$error` is present.
