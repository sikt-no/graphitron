---
id: R708
title: "The catalog census holds routines"
status: Backlog
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The catalog census holds routines

The catalog census holds tables, columns, keys and schemas, and no routines. A `@routine` field names
a database routine, and what the generator binds it to is a method on the jOOQ `Routines` class that
codegen produced for that routine. Neither the class nor the method name is derivable from anything
the store holds: `graphitron_routine.routine_ref` carries the routine name as the author wrote it,
which is a name in the database rather than a name in Java, and the step from one to the other is
jOOQ's own generator naming.

Everything downstream of that gap has to go somewhere else for the answer. The language server's
declaration-name resolution reads every other producer binding out of the store and reads this one out
of the classification projection, which is the last thing keeping that projection alive on the
surface, and which is why a routine-backed field's jump to its call surface is the one binding that
needs a completed build behind it. The same absence is why the projection cannot simply be deleted
when the rest of its readers retire.

What it takes: `sql_routine` alongside `sql_table`, carrying the routine's schema and name, the
generated class FQN and the generated method name, captured off the live handle inside the codegen
scope like the three widenings the language-server item already names (the class FQNs are the closest
sibling: resolved by reference identity rather than by formula, so unrecoverable downstream). Then a
view resolving a `@routine` application to that pair, and the language server's producer reader gains
a third arm.

Worth checking while specifying whether the routine's own parameters belong here too. The
argument-mapping and column-mapping relations beside `graphitron_routine` already state what an author
wrote; whether a surface wants the generated method's signature the way it wants a service method's is
an open question rather than an assumed yes.
