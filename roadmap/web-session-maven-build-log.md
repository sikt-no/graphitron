---
id: R623
title: "Redirect Maven output to a log file in web sessions via .mvn/maven.config"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-10
last-updated: 2026-08-10
---

# Redirect Maven output to a log file in web sessions via .mvn/maven.config

A full reactor build emits far more output than an agent session can hold, so the
harness truncates it. When a build fails, the failure detail an agent needs (the
surefire assertion, the compiler error, the module that broke) is frequently in the
part that got cut, and the only recovery is to re-run the build, paying the wall
clock a second time. Nothing currently preserves the untruncated output: it exists
only in the tool result. The warm build is the one exception, and only because the
SessionStart hook redirects it to `/tmp/graphitron-web-env.log`; every foreground
`mvn` / `mvnd` invocation loses its output the moment the tool result is trimmed.
An agent can add `| tee` by hand, but that only helps on the runs where it thought
to do so in advance, which is never the run that surprises it.

Maven already solves this. `-l/--log-file` is a stock flag on both `mvn` and `mvnd`,
and `.mvn/maven.config` makes it always-on with no wrapper, no `PATH` surgery, and
no change to what the build does. This item is only about switching that on in web
sandboxes and documenting the consequences.

## Approach

Add one idempotent step to `.claude/scripts/session-start-web-env.sh` that writes
`.mvn/maven.config` at the repo root containing `-l <fixed path under /tmp>`
(`/tmp/graphitron-maven.log`, alongside the existing `/tmp/graphitron-web-env.log`).
Web sessions only, like every other step in that hook. `.mvn/` is already gitignored,
so the file is sandbox-local and cannot reach CI or a teammate's checkout.

The log is a single fixed path, truncated per run: the last build's output, not a
history. Deliberate, and the reason the file lives under `/tmp` rather than `target/`:
`mvn clean` deletes the reactor root's `target/` at the start of the build and would
unlink a log opened there, leaving Maven writing to an inode nothing can reach.
(Verified empirically, not assumed.)

The warm build shares the same log. Install the config with the other config steps,
*before* the warm build, so the session's longest and most informative build lands in
the same place every later build does and there is only ever one path meaning "the
last Maven build". Nothing has to be sequenced around it: the PreToolUse guard already
holds foreground `mvn` / `mvnd` until the warm build finishes, so the two can never
write the file concurrently.

The one case that needs handling is a *failed* warm build, whose output the first
foreground command would truncate away, and that first command is often a narrow
`-pl` that will not reproduce the failure. On the failure path only, copy the log
aside (`/tmp/graphitron-warm-build-failure.log`) before the hook exits, and repoint
the existing "Warm build FAILED ... Log: $LOG_FILE" message at the copy, since
`$LOG_FILE` will no longer hold the Maven detail.

Then document the behaviour in `.claude/web-environment.md` and `CLAUDE.md`.

## Consequences to document

`-l` is a redirect, not a tee: with it set, Maven prints **nothing** to the console.
That is the point (a green full build costs roughly zero tokens of tool output) but
it inverts the usual read, so the docs have to be explicit:

- A build's result is read from the **exit code**, then from the log. Silence is not
  success on its own, though a non-zero exit still surfaces normally, so a failed
  build is never invisible.
- Commands whose stdout you actually need (`roadmap-tool exec:java` printing what it
  wrote, `help:evaluate`, anything scripted against Maven output) must opt out.
  A command-line `-l` overrides the one in `maven.config`, so `-l /dev/stdout`
  restores console output for a single invocation. Both facts verified.
- This subsumes the existing "don't combine `mvnd` with `-q` when you need stdout"
  quirk in `web-environment.md` under a more general rule; that note should be
  rewritten rather than left to contradict this.
- `web-environment.md` currently says to `tail -f /tmp/graphitron-web-env.log` to
  watch warm-up progress. That file keeps the prerequisite step messages and the
  warm-build start/finish lines but no longer the Maven detail, so the
  progress-watching instruction has to move to the maven log.
- Two concurrent Maven invocations would overwrite each other's log. The PreToolUse
  guard already serializes foreground Maven against the warm build, and agents issue
  builds one at a time, so this is a note and not a mechanism.

## Explicitly out of scope

Copying or archiving logs into `target/`, per-run log history, and any `tee`-style
wrapper that keeps console output live while also writing the file. Maven has no
built-in tee, so that would mean a `PATH`-shadowing wrapper script; the redirect is
free and this item takes the free thing.
