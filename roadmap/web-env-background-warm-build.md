---
id: R439
title: "Background dev-environment warm-up for web sessions"
status: Backlog
bucket: tooling
depends-on: []
created: 2026-07-07
last-updated: 2026-07-07
---

# Background dev-environment warm-up for web sessions

The SessionStart hook (`.claude/scripts/session-start-web-env.sh`) runs synchronously and only establishes prerequisites (JDK 25, PostgreSQL seed, Maven settings, libtree-sitter); it does not warm the Maven build. Every fresh web session therefore pays the full cold `mvn install -Plocal-db` (empty `~/.m2`, no `target/` directories) in the foreground before the first productive build or test run, which dominates iteration time at session start. Proposal: switch the hook to async mode in web sessions and add a background warm build (`mvn install -P 'local-db,!docs' -DskipTests`) that logs to a status file, plus a PreToolUse Bash guard that makes `mvn`/`psql` commands wait for the warm-up to finish instead of racing it; a concurrent agent build against a half-warmed `~/.m2` would otherwise reintroduce the catalog-jar clobber footgun. Dev-tooling only; no generator code affected.

Note: the implementation landed in the originating session (user-requested dev-environment improvement, validated end-to-end there) ahead of the Spec/Ready gates; the item stays in Backlog until a different party retro-reviews it, at which point it can move through the states or be closed out.
