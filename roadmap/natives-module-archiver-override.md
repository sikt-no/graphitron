---
id: R573
title: "graphitron-tree-sitter-natives keeps the maven-archiver 3.6.4 hard-fail: it is deliberately not a child of the parent pom, so the pluginManagement override does not reach it"
status: Backlog
bucket: cleanup
priority: 5
theme: tooling
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# graphitron-tree-sitter-natives keeps the maven-archiver 3.6.4 hard-fail: it is deliberately not a child of the parent pom, so the pluginManagement override does not reach it

The root pom's `pluginManagement` entry for `maven-javadoc-plugin` overrides the plugin's bundled
`maven-archiver` to 3.6.5, because 3.6.4 range-checks `project.build.outputTimestamp` against a
`1980-01-01T00:00:02Z` floor and throws on `SOURCE_DATE_EPOCH=315532800`, the ZIP epoch NixOS exports
from `stdenv`. `graphitron-tree-sitter-natives/pom.xml` is deliberately not a child of
`graphitron-rewrite-parent` (its header comment gives the reason: inheriting a `10-SNAPSHOT` parent
with no `<snapshotRepository>` would publish a natives jar whose parent reference no Central consumer
can resolve, and would lock the natives release cadence to the next 10.x rewrite release). It
therefore inlines its own `maven-javadoc-plugin` 3.12.0 declaration with an `attach-javadocs` `jar`
execution, and the override does not reach it: that goal makes the same
`MavenArchiver.parseBuildOutputTimestamp` call and still hard-fails. `failOnError=false` on that
declaration does not help, because the throw happens before javadoc is invoked.

Latent rather than live: GitHub-hosted runners do not export `SOURCE_DATE_EPOCH`, and
`tree-sitter-natives-release.yml` does not set it, so CI is unaffected. The exposure is a maintainer
cutting a natives release from a shell that exports the variable, where `mvn -f
graphitron-tree-sitter-natives/pom.xml deploy` dies on the javadoc jar. The fix is to copy the same
`<dependencies>` block onto that module's own plugin declaration, or to set an in-range
`outputTimestamp` there (this module publishes to Central, so unlike the reactor it plausibly *wants*
a reproducible-builds timestamp, which would make the choice a policy decision rather than a
workaround). Either way it retires when the plugin version on both declarations moves to a release
pinning `maven-archiver` >= 3.6.5, so whoever does that bump should be the one to notice; the two
declarations drifting is itself part of the problem.

