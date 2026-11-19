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

An `@outbox` message is a typed event declared in the sending schema: the event's name is the name of a GraphQL object type in that schema, and the message's body is an instance of that type, built from the mutation's input and result. Today (R1113, the `@outbox` directive) the event name defaults to the field's coordinate (`Mutation.createFilm`), which names the event as if it were a field, and the body is a flat JSON object assembled key by key with `argMapping`, so nothing in the schema says what a consumer receives. When this lands, an event is a type: it is named like one (`FilmCreated`), its shape is declared and versioned in the SDL beside the mutation that emits it, and graphitron checks at build that the mutation can produce it. Each emitted message carries a subject, the identifier a relay routes it by (to a topic, a partition), named by the directive rather than left for the relay to dig out of the payload.

## Design direction

The points below are the decisions taken so far; the mapping is the open part and where the Spec round will spend its time.

- **The event is a type in the sending schema.** An `@outbox` names its event, and a type of that name must exist in the schema; a name with no matching type is a build error. The coordinate default goes, since an event has to be named as a type. Whether the type is visible to clients, and how a consumer outside the service gets hold of it (published SDL, a generated JSON Schema), is for the Spec.
- **The outbox constructs the event from the mutation's input and result.** This is what `argMapping` does today, with a schema added: the target of the mapping is the event type's fields rather than free-form JSON keys, so the build can check every field is filled from a source whose type fits, and the payload's shape follows from the type rather than from the order of keys in a string. The sources stay what R1113 already offers (arguments, `$result`, `$session`, `$scope`, `$error`).
- **Cardinality follows from the mapping.** One call emits one or more events of a type: a mapping that ranges over a list (a batch input, a list result) emits one event per element, and a mapping over single values emits one. The directive carries no separate cardinality setting; it is read off how the input and result map onto the event type.
- **Mapping to the event type is the design work.** How an author says which input or result value fills which event field, how nested event types and lists are mapped, what is inferred by name and what must be spelled out, and how the per-element case is expressed, is the bulk of the Spec. The current `argMapping` string is the starting point, not a constraint.
- **Every event has a subject.** A new `subject` argument on `@outbox` names the field of the event type that identifies the event's subject, the value a relay routes it by. It is required (or, if a convention is chosen, inferred and checked); the field must exist on the event type and be a scalar or id the relay can use as a key.
- **The current payload argument stays for now.** `argMapping` in its present form probably dissolves into the typed mapping, but it is kept until consumers have moved to typed events; its retirement is a follow-up, not part of this item.

## Open questions for Spec

- The mapping syntax: an extension of the `argMapping` string, a directive on the event type's fields, or an input-shaped mapping argument.
- Whether the event type is a plain object type, an object type marked with a directive of its own, or something else the generator can tell apart from the API's own types.
- How `subject` is stored in the message: a column of the outbox table or a parameter of the outbox function beside `event` and `payload`, which changes the `<outbox>` configuration.
- How an event type is versioned (`FilmCreatedV2` beside `FilmCreated`) given that today a version is a new `event` string.
- What `on: ERROR` messages look like as typed events, where `$result` is absent and `$error` is present.
