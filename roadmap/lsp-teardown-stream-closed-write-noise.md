---
id: R794
title: "LSP connection teardown logs SEVERE stack traces for stream-closed writes"
status: Spec
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# LSP connection teardown logs SEVERE stack traces for stream-closed writes

Disconnecting an editor from the `graphitron:dev` LSP prints a burst of
`SEVERE: Internal error: JsonRpcException: java.net.SocketException: Socket closed`
stack traces to the dev console, one per request that was still in flight when the
editor detached. Nothing is actually broken: the requests are answers nobody is
waiting for any more. But an editor reattach is a routine move in the dev loop, and
every one of them now looks like a crash, so the console stops being a place where a
real failure is visible.

## Two words to define first

**In-flight request.** A request the editor sent that the server has begun answering
but has not yet replied to. Completion, hover and inlay requests fire on almost every
keystroke, so at any given moment several are part-answered.

**Stream-closed write.** An attempt to write a JSON-RPC message onto a connection
whose socket is already gone. lsp4j has a name for this condition,
`JsonRpcException.indicatesStreamClosed`, and consults it on the *read* side to exit
its reader loop quietly. The write side never asks.

## What actually happens

`DevServer.serve` hands one connection's streams to an lsp4j launcher and then blocks
on `launcher.startListening().get()`. That call returns as soon as the editor closes
its end of the stream, and the `finally` block immediately closes the socket. The
in-flight request handlers are still running: `GraphitronTextDocumentService` answers
`completion`, `hover`, `inlayHint` and `codeAction` on `CompletableFuture.supplyAsync`,
so they land on the common ForkJoinPool independently of the reader thread. Each one
completes a moment later, lsp4j tries to write the response onto the socket that
`serve` just closed, and the write fails.

From there the noise is lsp4j's default policy, not ours. The failed write completes
the response stage exceptionally; `RemoteEndpoint` hands the throwable to its
`exceptionHandler`, which defaults to `DEFAULT_EXCEPTION_HANDLER`, which calls
`fallbackResponseError` and logs the whole thing at `SEVERE`. Four in-flight requests,
four stack traces. (lsp4j then tries to send an *error* response on the same dead
socket, which fails again and quietly drops the `receivedRequestMap` cleanup that was
chained after it. The entry leaks for the remaining life of a connection that is
already dead, so it costs nothing, but it does mean the current behaviour is not
merely noisy.)

The stdio launcher in `graphitron-lsp` has the same gap. It is far less visible there,
because one process serves one connection and the process is exiting anyway.

## A second defect on the same path

`Workspace.setRecalculateListener` is a single-slot setter, and nothing clears it when
a connection ends: `GraphitronLanguageServer.exit` says "lsp4j drives process
lifetime; nothing to clean up", which is true for stdio and false for `DevServer`,
where the process and the shared `Workspace` both outlive any one connection. Between
a disconnect and the next reconnect, the workspace still holds the dead connection's
`publishDiagnosticsForRecalculate`. `publishDiagnostics` is a notification, so it
writes synchronously and throws `JsonRpcException` straight into whichever thread
fired the recalculation, which in the dev loop is a Maven thread rather than an lsp4j
one.

Both defects are in scope here, as two ordered deliverables. They share one cause,
teardown leaving per-connection state reachable, and splitting them would leave the
item claiming a console is quiet while a known-wrong reference is still live: the
wrapper makes the stale listener harmless, and only clearing the slot makes it correct.

## What "fixed" means

A developer detaching an editor from `graphitron:dev` and reattaching sees nothing in
the console. A write that fails for any reason other than the peer being gone is still
loud, and no other behaviour changes: every message that can be delivered still is.

## Deliverable 1: the write side asks the question lsp4j already answers

The seam is `Launcher.Builder.wrapMessages`. It is applied to the outgoing
`StreamMessageConsumer` (inside `createRemoteEndpoint`) and to the incoming consumer
(inside `create`), so one wrapper per launcher covers every message in both
directions. Verified against the 0.24.0 bytecode rather than assumed, because a
wrapper applied to only one of the two would fix the visible symptom and leave the
`publishDiagnostics` push still throwing.

The wrapper catches a `consume` failure, consults
`JsonRpcException.indicatesStreamClosed`, and on a true verdict logs at debug and
returns; anything else rethrows unchanged. lsp4j's own predicate is the whole point:
it already enumerates the conditions that mean "the peer is gone" (`Socket closed`,
`Connection reset`, `Broken pipe`, `Stream closed`, `Pipe closed`,
`ClosedChannelException`, `InterruptedIOException`) and recurses through
`JsonRpcException` causes itself, and `ConcurrentMessageProcessor` already trusts it on
the read side. Matching on messages by hand here would be a second, worse copy of a
predicate that ships in the dependency.

One judgement to get right, and the main thing a reviewer should push on: the wrapper
must not widen into a general write-error swallow. The boundary is exactly the
predicate, and the rethrow branch is what keeps a genuine framing or serialisation
failure visible.

Both launcher sites need this, so the policy should not be a line either site can
forget. Introduce one factory in `graphitron-lsp` that builds a configured
`Launcher<LanguageClient>` from a server plus an input and output stream, and have both
`no.sikt.graphitron.lsp.server.Launcher` (stdio) and
`no.sikt.graphitron.rewrite.maven.dev.DevServer.serve` (socket) call it. That also
collapses the builder setup those two currently duplicate, and it means a third
transport added later gets the policy by construction. `graphitron-lsp` is already a
dependency of `graphitron-maven-plugin` and already has `slf4j-api`, so the factory has
both the reach and the logger it needs. Note the asymmetry worth a comment: the noise
being removed is lsp4j's, logged through `java.util.logging`, while the debug line
replacing it goes through slf4j like the rest of our code.

## Deliverable 2: teardown stops leaving a dead connection reachable

`Workspace.setRecalculateListener` is a single-slot setter with a no-op default, so the
minimal correct fix is a compare-and-clear: teardown clears the slot only if the
listener still installed is the one this connection put there. Plain unconditional
clearing is wrong, because a reconnect can install its listener before the old
connection's teardown runs, and an unconditional clear would then silently stop
diagnostics for the live editor. Getting that ordering backwards would turn a cosmetic
bug into a functional one, which is why it is spelled out here rather than left to the
implementation.

The clear belongs where the connection ends rather than in `exit()`. `exit()` is a
client-driven notification that a disconnecting editor may never send, so the `finally`
in `DevServer.serve` is the only place guaranteed to run. `GraphitronLanguageServer`
needs to expose the teardown; its `exit()` comment ("lsp4j drives process lifetime;
nothing to clean up") is true for stdio and false for the shared-workspace server, and
should stop saying so.

## Coverage

Three pins, deliberately split by what each can prove:

1. **The predicate boundary**, in `graphitron-lsp`, with no sockets: hand the wrapper a
   `MessageConsumer` that throws `JsonRpcException(SocketException("Socket closed"))`
   and assert the wrapper returns; hand it one that throws a `JsonRpcException` wrapping
   an unrelated `IOException` and assert it propagates. This is the pin that fails if
   someone later widens the catch.
2. **The end-to-end suppression**, deterministic rather than racy. A test-owned
   launcher pair over a real socket, with a local service method that blocks on a latch:
   the client sends the request, the test closes the socket, the test releases the latch,
   the handler completes, and lsp4j attempts the write into a socket that is already
   gone. Assert no `SEVERE` record reaches a `java.util.logging.Handler` attached to the
   `org.eclipse.lsp4j.jsonrpc.RemoteEndpoint` logger. The latch is what makes this worth
   writing: "send a request and close fast" would pass vacuously whenever the response
   happened to win the race, and a test that can silently prove nothing is worse here
   than no test.
3. **The listener slot**, in `graphitron-lsp` against a `Workspace` directly: a
   registration cleared by its own owner clears, and a registration cleared after a
   second owner has taken the slot does not.

`DevServerTest.multipleClientsShareWorkspaceState` already closes a connection and
reconnects against a shared workspace, so it is the natural place to assert the console
stayed quiet across that sequence. It should gain the `RemoteEndpoint` log assertion,
but it is not the deterministic pin, since nothing in it guarantees a request is still
in flight at close time.

## Alternatives considered

**`Launcher.Builder.setExceptionHandler`.** Also suppresses the four traces, and is a
smaller diff. Rejected: it only covers the request/response path, so a
`publishDiagnostics` push to a dead client still throws into the caller's thread, and
the throwable arriving there is a `CompletionException` wrapping the `JsonRpcException`,
which `indicatesStreamClosed` does not unwrap. That would mean hand-rolled unwrapping to
get a narrower fix.

**A multi-listener registry on `Workspace`.** The honest shape if several editors ever
attach at once, since a single slot already means last-connection-wins. Rejected as out
of scope: `graphitron:dev` serves one developer's editor, the single slot is not wrong
today, and widening the workspace's contract is a larger design change than this item
should carry. Worth filing separately if multi-editor attachment becomes real.

**Leave it and let the console be noisy.** Rejected on the grounds in the problem
statement: the cost is not the noise itself but that it trains a developer to ignore
`SEVERE` in the one console where a real dev-loop failure would appear.

## Retired vocabulary

None. No symbol or mechanism is removed; `GraphitronLanguageServer.exit` keeps its
signature and loses only a comment that is no longer true.

## Reviewer findings

### Round 1: Spec -> Ready, revisions requested (2026-08-21, session_01GggHMPBDXW1uqbku9ASeej)

**Question 1 fails on one checkable claim: the notification write path does not
throw.** In lsp4j 0.24.0, `RemoteEndpoint.notify` wraps its `out.consume(...)` in a
catch of `Exception`, consults `JsonRpcException.indicatesStreamClosed` itself, logs
"Failed to send notification message." at INFO on a stream-closed verdict (WARNING
otherwise), and returns. Nothing propagates to the caller. Three places in the plan
rest on the opposite claim:

1. The "Stream-closed write" definition: "The write side never asks." The *response*
   path never asks; the notification path already does.
2. Deliverable 2's motivation: a stale `publishDiagnosticsForRecalculate` does not
   throw "straight into whichever thread fired the recalculation". The Maven thread
   never sees the failure. What actually happens between disconnect and reconnect is
   one INFO log record per failed publish, carrying the throwable, so under a default
   `java.util.logging` console handler it is still a console stack trace, just not an
   exception crossing threads.
3. The `setExceptionHandler` rejection in Alternatives: "a `publishDiagnostics` push
   to a dead client still throws into the caller's thread, and the throwable arriving
   there is a `CompletionException` wrapping the `JsonRpcException`". No throwable
   arrives in the caller's thread, so that unwrap argument is moot for this path.
   (The narrower claim that `indicatesStreamClosed` does not unwrap
   `CompletionException` is itself true; verified in the bytecode.)

The design conclusions look like they survive re-grounding: if the notify INFO record
does print a stack trace in the dev console, wrapping both directions is still what
delivers "sees nothing in the console", and the compare-and-clear is still right as
teardown hygiene. But the corrected story is the author's to write: restate the
notification-path cost in terms of what actually happens (log noise, wasted drains,
dead per-connection state held live), re-derive the two-deliverable coupling and the
`setExceptionHandler` rejection from that, and confirm the observable, since
"What fixed means" is defined in console-visibility terms.

**Non-blocking.** The read-side consult of `indicatesStreamClosed` lives in
`StreamMessageProducer`, not `ConcurrentMessageProcessor` (which hosts the loop that
runs it). One-word fix while revising. Everything else checked out against the tree
and the 0.24.0 bytecode; the verification narrative is in this round's commit message.
