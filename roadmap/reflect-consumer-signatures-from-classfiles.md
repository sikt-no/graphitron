---
id: R1004
title: "Read consumer method signatures from classfiles so an unrelated sibling method cannot fail reflection"
status: Backlog
bucket: architecture
priority: 4
theme: service
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Read consumer method signatures from classfiles so an unrelated sibling method cannot fail reflection

## Goal

A consumer's `@service`, `@condition` or `<sessionState>` class is accepted or rejected on the
methods the schema and the pom actually name, never on an unrelated sibling method. Today graphitron
reads a consumer class through `java.lang.reflect`, and any reflective view of a class
(`getDeclaredMethods`, `getDeclaredMethod`, `getMethods`) loads the parameter and return types of
every method it declares. One sibling helper whose signature names a type graphitron cannot load
fails the whole class. On a commercial jOOQ edition, a helper returning a UDT array record is enough:
its `ArrayRecordImpl` superclass splits across graphitron's jOOQ and the consumer's.

## Context

R951 makes this failure legible: a typed rejection naming the class, the type and the workaround of
moving the helper elsewhere. This item removes the need for the workaround. The classpath census
(`ClassfileCensus`, which reads public members off classfiles with the ClassFile API without linking
anything) already holds each method's descriptor, declared generic signature, parameter names and
declared exceptions. Selecting the picked method from census rows, and loading only that method's own
types, means sibling signatures are never resolved. A picked method whose *own* signature names an
unloadable type still fails, and should, through R951's rejection.

Overlaps R72 (slim `ServiceCatalog` down to a lookup primitive): both want one lookup site in place
of the duplicated reflection scaffolding. Sequence them knowingly.
