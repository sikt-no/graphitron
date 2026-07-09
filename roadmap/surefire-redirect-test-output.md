---
id: R373
title: "Capture test stdout/stderr to per-class files via Surefire redirectTestOutputToFile"
status: Backlog
theme: tooling
bucket: testing
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Capture test stdout/stderr to per-class files via Surefire redirectTestOutputToFile

Several tests emit info/warn/error logs (SLF4J + Logback `ConsoleAppender` → `System.out`) and stack traces during a normal `mvn install` run, drowning the console in noise that is irrelevant when the test passes. There is no per-test "show only on failure" buffering in plain Surefire, but `redirectTestOutputToFile=true` captures each test class's stdout/stderr into `target/surefire-reports/<TestClass>-output.txt`, keeping the reactor console clean while preserving the full output on disk for any class whose tests fail. Apply it once in the parent pom (`graphitron-rewrite/pom.xml`) so every module inherits it.

The `graphitron-mcp` module currently works around the noise differently: its `src/test/resources/logback-test.xml` clamps the root logger to `WARN` to keep Jetty/Reactor chatter off the console, which discards info-level context even when a test fails. With output now redirected to file, that clamp is no longer needed to keep the console quiet, and retaining the logs is more useful for diagnosing failures. Relax the MCP test logging so the captured file keeps the logs rather than suppressing them.

Scope: build/test configuration only; no production or generated code changes.

