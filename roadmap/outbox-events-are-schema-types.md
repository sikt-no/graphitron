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
- **`construct` builds the event.** A new `construct` argument maps the mutation's input and result onto the event type's fields, in the mapping syntax graphitron already uses in `argMapping` (the selection syntax borrowed from Apollo Connectors), with the sources `argMapping` admits today: arguments, `$result`, `$session`, `$scope`, `$error`. What changes is the target: the event type's fields rather than free-form JSON keys, so the build checks that every field is filled from a source whose type fits, and the payload's shape follows from the type. The syntax is settled; the work is checking a selection against a target type, including nested event types and lists.
- **The mapping is a scalar, not a `String`.** `construct` is declared with a scalar of its own for the selection syntax, as Apollo Connectors declares its `selection` arguments with `JSONSelection`. Today every `argMapping` is a `String` in `directives.graphqls`, and the LSP finds them by listing each coordinate in `LspVocabulary`; a scalar lets the build and the editor recognise a mapping by its type, so a new directive argument that carries one needs no new entry. The leaning is a graphitron scalar rather than reusing the name `JSONSelection`: the dialect adds sources Apollo's does not have (`$result`, `$session`, `$scope`, `$error`, key-column decoding out of a node id), so borrowing the name would promise a compatibility that is not there. Whether the existing `argMapping` arguments move to the same scalar in this item or a follow-up is for the Spec.
- **`argMapping` keeps a different job.** It maps onto the table or function that receives the event (its columns or parameters), not onto the event itself. `construct` says what the event is; `argMapping` says how it lands in the outbox.
- **Cardinality follows from the mapping.** One call emits one or more events of a type: a `construct` that ranges over a list (a batch input, a list result) emits one event per element, and one over single values emits one. The directive carries no separate cardinality setting.
- **Every event has a subject.** A new `subject` argument on `@outbox` names the field of the event type that identifies the event's subject, the value a relay routes it by. The field must exist on the event type and be a scalar or id the relay can use as a key.
- **The common `<payload>` dissolves later.** The plugin's `<outbox><payload>` block, the argMapping every message carries, probably has no place once the event is a type, but it stays until consumers have switched to typed events; its retirement is a follow-up, not part of this item.

## Open questions for Spec

- The scalar's name, and whether `argMapping` on `@service`, `@routine`, `@condition` and `@outbox` moves to it here or later.
- How `construct` expresses the per-element case, so one call emits one event per element of a list.
- Whether the event type is a plain object type, an object type marked with a directive of its own, or something else the generator can tell apart from the API's own types; and whether it is visible to clients.
- How `subject` reaches the receiver: a role of its own in the `<outbox>` configuration beside `type` and `payload`, or an `argMapping` entry onto a column or parameter.
- How an event type is versioned (`FilmCreatedV2` beside `FilmCreated`) given that today a version is a new `event` string.
- What `on: ERROR` messages look like as typed events, where `$result` is absent and `$error` is present.
