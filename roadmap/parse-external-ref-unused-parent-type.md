---
id: R579
title: "Drop the unused parentTypeName parameter from FieldBuilder.parseExternalRef"
status: Backlog
bucket: cleanup
priority: 4
theme: legacy-migration
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Drop the unused parentTypeName parameter from FieldBuilder.parseExternalRef

`FieldBuilder.parseExternalRef` still takes a `String parentTypeName` first parameter
that its body never reads. The only consumer was the per-field deprecation warning on
the retired `ExternalCodeReference.name` arm; when that arm came out the parameter
stayed. Both call sites (`ServiceDirectiveResolver` and `ExternalFieldDirectiveResolver`)
thread a value that is discarded.

Drop the parameter and update the two call sites. Purely mechanical, no behaviour
change; the compiler covers it.
