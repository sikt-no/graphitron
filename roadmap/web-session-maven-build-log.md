---
id: R623
title: "Redirect Maven output to a log file in web sessions via .mvn/maven.config"
status: Spec
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-10
last-updated: 2026-08-11
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

## Reviewer findings (Spec → Ready gate, 2026-08-11)

Independent reviewer session, status stays `Spec`. Everything below was checked in a
web sandbox against Maven 3.9.11 (`/opt/maven`) and mvnd 1.0.6 (embedded Maven 3.9.16).
The mechanism does not hold up as specced, on three counts.

1. **A repo-root `.mvn/maven.config` carrying `-l` breaks the invoker integration tests,
   so `mvn install` fails in every web session.** Maven searches *upward* from the
   working directory for `.mvn`, and maven-invoker-plugin clones its projects to
   `graphitron-maven-plugin/target/it/<name>`, inside the repo, then forks a real `mvn`
   there. Each forked IT therefore inherits `-l` and writes its Maven output to the shared
   log instead of the stdout the invoker captures into `build.log`.
   `dependency-version-lag/verify.groovy` and `missing-schema-inputs/verify.groovy`
   assert on `build.log` content, so both fail: with the config installed,
   `mvn -pl :graphitron-maven-plugin verify -Plocal-db` exits 1 with `Passed: 1, Failed: 2`
   and `Expression: buildLog.contains(jooq-version-lag)`; with the config removed, the same
   command reports `Passed: 3, Failed: 0`. `basic-generate` is the quieter half of the same
   problem: its assertions are negative (`assert !buildLog.contains(...)`), so an empty
   `build.log` satisfies them vacuously and that IT's coverage is silently voided rather
   than failing.

2. **mvnd honours `-l` only from the command line, not from `maven.config`.** With `-l` in
   `maven.config`, `mvnd` creates the log file, leaves it at 0 bytes, and prints the whole
   build to the console; all three argument spellings behave identically, and there is no
   `mvnd.properties` knob for a build log. `-l` on the mvnd command line does redirect. So
   "stock on both `mvn` and `mvnd`" is true of the flag but not of the always-on mechanism,
   and mvnd is both the tool `CLAUDE.md` tells agents to prefer and the one the warm build
   uses when present. As written, the item would deliver nothing on the recommended path
   while the docs promise the last build is in the log; the warm build would never share
   that log, and the copy-aside-on-failure step would copy an empty file. This is the
   premise the wrapper design was dropped on, so that fork is worth reopening: a `PATH`
   shadow puts `-l` (or a `tee`) on the actual command line, which is the form mvnd
   respects, and is not inherited by the invoker's forked `${maven.home}/bin/mvn`, so it
   sidesteps finding 1 as well.

3. **`.mvn/maven.config` is line-based, and the natural spelling silently does nothing.**
   `-l /tmp/graphitron-maven.log` on a single line produces no log file, no warning, full
   console output, and exit 0. `-l` and the path on separate lines, `-l/tmp/...`, and
   `--log-file=/tmp/...` all work. Whichever mechanism survives, pin the exact file bytes
   in the plan rather than describing them.

Two smaller points for the author, neither blocking:

- The gate wants naming. "Web sessions only, like every other step in that hook" reads as
  precedent, but steps 1 to 5 are gated on sandbox markers rather than on web-ness; only the
  warm build tests `$ASYNC` / `CLAUDE_CODE_REMOTE`. This step has no natural marker, and its
  local-dev failure mode is a contributor's console silently going dark in their own
  checkout, so the plan should name the `$ASYNC` gate outright.
- Getting-started recommends consumers add `.mvn/jvm.config`, so whatever writes the file
  should `mkdir -p` and write that one file, never replace the `.mvn` directory.

The rest of the plan checks out against the tree: `LOG_FILE=/tmp/graphitron-web-env.log`
and the "Warm build FAILED ... Log: $LOG_FILE" message are where the plan says they are,
`.mvn/` is gitignored, `web-environment.md` does carry both the `tail -f` instruction and
the `mvnd -q` quirk the plan proposes to rewrite, and a command-line `-l /dev/stdout` does
override `maven.config` under `mvn`.

## Second reviewer pass (Spec → Ready gate, 2026-08-11)

A second independent reviewer session, status stays `Spec`. The block above was written
by the previous gate pass and left the `## Approach` section standing unchanged, so the
plan currently prescribes a mechanism its own findings section says does not work. That
is what blocks sign-off: there is nothing here an implementer could build without first
picking between the forks the previous pass opened, and that choice is the author's.

All three findings reproduced independently, on the same toolchain (Maven 3.9.11 at
`/opt/maven`, mvnd 1.0.6 embedding Maven 3.9.16), so they are confirmed rather than
one session's report and should not be re-litigated on the next pass:

- Single-line `-l /tmp/...` in `maven.config`: no log file, full console output, exit 0.
  The two-line spelling redirects correctly under `mvn` (log written, console reduced to
  the JVM preamble).
- `mvnd` with that same working two-line config: creates the log file, leaves it at
  0 bytes, prints the entire build to the console. `-l` on the mvnd command line does
  redirect (log written, console empty), which is what keeps the wrapper fork viable.
- Upward `.mvn` search: a project under `<root>/target/it/nested` picks up the root's
  `maven.config` and goes fully silent. Combined with the invoker's
  `<cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>` in
  `graphitron-maven-plugin/pom.xml`, that is the whole mechanism behind finding 1, and
  the invoker forks `${maven.home}/bin/mvn` rather than resolving `mvn` off `PATH`, so a
  `PATH` shadow genuinely does sidestep it.

Three things to fold in when the Approach is rewritten:

- The warm build's Maven detail already lands in `/tmp/graphitron-web-env.log` today,
  because the hook does `exec >>"$LOG_FILE" 2>&1` before running it. Under the proposed
  mechanism with mvnd present (and the warm build prefers mvnd) it would keep landing
  there, since mvnd prints to a console that is already redirected. So "the warm build
  shares the same log" is false on the default path, and the two proposed doc edits
  invert: moving the `tail -f` progress-watching instruction to the maven log would point
  agents at an empty file, and the copy-aside-on-failure step would copy one.
- The `## Explicitly out of scope` rationale collapses with the mechanism. "The redirect
  is free and this item takes the free thing" is the entire stated reason for excluding a
  wrapper, and the redirect is not free: it costs two invoker ITs, silently voids a
  third's negative assertions, and delivers nothing under mvnd. That section needs
  rewriting alongside the Approach, and `tee` is a live option to weigh rather than
  settled exclusion.
- Worth separating the two benefits explicitly, because the surviving mechanism changes
  which one you get. Recovering untruncated output after a surprise failure is the stated
  motivation; a green build costing near-zero tool output is a second, independent win
  that only a pure redirect buys. A `tee` wrapper gets the first and not the second.

One correction to the previous pass: getting-started does *not* recommend
`.mvn/jvm.config`, because no getting-started doc exists. The only live references are
`roadmap/changelog.md`'s R409 entry, which claims the section, and a Backlog item filed
to fix exactly that dangling pointer. The underlying advice still holds on other grounds
(`.mvn/` is gitignored, so a contributor may have their own `jvm.config` there), so keep
the `mkdir -p` and write-one-file instruction, but do not attribute it to that doc.

## Explicitly out of scope

Copying or archiving logs into `target/`, per-run log history, and any `tee`-style
wrapper that keeps console output live while also writing the file. Maven has no
built-in tee, so that would mean a `PATH`-shadowing wrapper script; the redirect is
free and this item takes the free thing.

(Both reviewer passes above bear on this section; see the note on the collapsed
rationale before treating the exclusion as still standing.)
