---
id: R394
title: "roadmap-tool verify tripwires throw BuildFailure, not System.exit"
status: Backlog
bucket: docs
priority: 5
theme: tooling
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# roadmap-tool verify tripwires throw BuildFailure, not System.exit

The roadmap-tool verify-phase tripwires (README drift in `Main.runVerify`, front-matter
validation in `Main.validate`, leaf-coverage drift/no-traces in `LeafCoverageReport.run`,
and markdown-table findings in `AdocMarkdownTableCheck.run`) signal failure with
`System.exit(1)` or a non-zero return that the dispatcher turns into `System.exit`. These
checks run via exec-maven-plugin's `java` goal, which executes in the Maven JVM, so
`System.exit` terminates Maven directly: the contributor sees the friendly diagnostic line,
then the shell prompt with `$? = 1`, no `BUILD FAILURE` banner and no `[ERROR]` line. The
build looks like Maven crashed rather than failed a check. Replace those verify-mode
tripwire exits with a thrown `BuildFailure` (a `RuntimeException` whose `fillInStackTrace`
no-ops, since the failure surface is the printed diagnostic, not a Java stack) so
exec-maven-plugin wraps it as a `MojoExecutionException` and produces the normal
`BUILD FAILURE` summary. CLI/usage errors (`usage()`, argument errors, the `create`
file-exists path) stay on `System.exit`, since those are dev errors outside any verify phase.
This adapts the change from the stale `claude/fix-maven-build-failure-yjL4J` branch onto the
current roadmap-tool structure, which has grown the `check-adoc-tables` verify tripwire since.
