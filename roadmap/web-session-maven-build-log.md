---
id: R623
title: "Capture full Maven build output to a log file under target/ in web sessions"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Capture full Maven build output to a log file under target/ in web sessions

A full reactor build emits far more output than an agent session can hold, so the
harness truncates it. When a build fails, the failure detail an agent needs (the
surefire assertion, the compiler error, the module that broke) is frequently in the
part that got cut, and the only recovery is to re-run the build with narrower scope,
paying the wall clock a second time. Nothing currently preserves the untruncated
output: it exists only in the tool result. The warm build is the one exception, and
only because the SessionStart hook redirects it to `/tmp/graphitron-web-env.log`;
every foreground `mvn` / `mvnd` invocation loses its output the moment the tool
result is trimmed. An agent can add `| tee` by hand, but that only helps on the runs
where it thought to do so in advance, which is never the run that surprises it.

Make the capture automatic in web sessions, so the full output of every Maven
invocation is on disk under `target/` and greppable after the fact, without the agent
having to opt in and without changing what the build does.

## Sketch (to be firmed up at Spec)

- A wrapper script on `PATH` ahead of both real binaries, dispatching on `$0` so one
  script serves `mvn` and `mvnd`. It execs the real binary with the arguments
  untouched, pipes through `tee`, and propagates the real exit status. No pom
  changes, no injected flags, identical build behaviour.
- Installed by a new idempotent step in `.claude/scripts/session-start-web-env.sh`,
  which also drops a `/etc/profile.d/` fragment prepending the wrapper directory.
  Note `/opt/maven/bin` currently precedes `/usr/local/bin` on the sandbox `PATH`, so
  a `/usr/local/bin/mvn` shim would be shadowed and the profile fragment has to sort
  after `maven.sh`. Web sessions only; the step no-ops on local development like
  every other step in that hook.
- The live sink is a file under `/tmp`, copied into
  `<repo-root>/target/maven-logs/` on exit, because `mvn clean` at the reactor root
  deletes `target/` at the start of the build and would unlink a log opened there.
  `target/` is gitignored, so nothing leaks into commits.
- The wrapper prints the log path so it is visible in the tool result even when the
  middle of the output is trimmed, keeps a bounded number of recent logs, and guards
  against re-entry so a nested Maven (invoker, `mvnd` delegating) does not double-wrap.

## Open questions

- Whether the copy lands in the reactor root's `target/` unconditionally or in the
  `target/` of the directory the build was launched from.
- Whether the console still streams live (tee) or the wrapper uses Maven's own `-l`
  and shows only a tail on failure. Streaming keeps current behaviour; `-l` would cut
  context spend but hides progress on long builds.

