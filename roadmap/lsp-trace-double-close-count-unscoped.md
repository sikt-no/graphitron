---
id: R860
title: "The LSP trace double-close case counts every close line in a JVM-global sink"
status: Backlog
bucket: testing
priority: 3
theme: lsp
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The LSP trace double-close case counts every close line in a JVM-global sink

`LspTraceTest.doubleCloseIsIgnored` opens one span, closes it twice, and asserts that the captured
output holds exactly one line containing `lsp-trace <`. The count ranges over every close line in the
sink, and the sink is not the test's own: `LspTrace` holds its enabled flag and its sink in static
fields, and `redirectSink` rebinds that global for the whole JVM. So any other emitter alive in the
test JVM while this case runs contributes a close line and the count is two.

Observed once, in a full `mvn install -Plocal-db` on 2026-08-27, as `expected: 1L but was: 2L` at
`LspTraceTest.java:142`. The same class passes in isolation and the whole `graphitron-lsp` module
passes on a re-run, 646 tests green, so the second line comes from outside the case rather than from
`close()` emitting twice.

The other cases in the class are already immune, and by the right means: they read
`lineContaining(marker, name)` and so range over the span they named. This one filters on the marker
alone. The narrow fix is to scope the count the same way, to the `phase` span the case opened, which
makes the assertion about this span's closes rather than about what the JVM happened to be doing.

Whether the narrow fix is enough is the question for a Spec, and it turns on where the other line
came from. The class mutates process-global state and nothing isolates it, which the
`graphitron` module's own `junit-platform.properties` names as the case that a per-class fixture
boundary cannot contain, and it handles with `@Isolated` on the two classes that rebind
`ClassificationTrace`. `graphitron-lsp` declares no parallel execution at all today, so the more
likely source is a sibling class leaving live LSP machinery (a diagnostics drain, a debounce
executor) running into the window where this class turns tracing on. If that is what it is, scoping
the count fixes this case and leaves every other assertion over the shared sink exposed to the same
thing, and the seam wants a per-test isolation of its own.

Cheap to leave alone and worth not leaving alone: a test that fails on state it never touched is the
one failure mode that teaches a contributor to re-run the build rather than read it.
