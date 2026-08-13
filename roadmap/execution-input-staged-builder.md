---
id: R664
title: "A growth-proof staged builder over the generated ExecutionInput factory"
status: Backlog
bucket: dx
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# A growth-proof staged builder over the generated ExecutionInput factory

The generated `Graphitron.newOwnedExecutionInput(...)` (and its escape-hatch sibling) takes one positional parameter per contextArgument, so its arity grows with the number of declared contextArguments plus the `<sessionState>` mount's payload parameters, and every addition is a source-incompatible signature change at the consumer's call sites. A consumer can bundle payload parameters into one carrier type today, but nothing gives the factory a growth-proof named surface. A staged builder over the factory (one named, typed step per contextArgument slot, compile-checked for completeness) would apply to contextArguments generally. Named as a separate concern in the session-identity work's trade-offs; filed here so it survives that spec's deletion at Done.
