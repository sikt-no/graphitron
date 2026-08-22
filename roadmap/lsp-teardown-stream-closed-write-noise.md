---
id: R794
title: "LSP connection teardown logs SEVERE stack traces for stream-closed writes"
status: Spec
bucket: architecture
priority: 3
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-22
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
`JsonRpcException.indicatesStreamClosed`, and already consults it in two of the three
places it could. `StreamMessageProducer` consults it to end its read loop quietly.
`RemoteEndpoint.notify` consults it to pick a log level for a failed *notification*,
INFO rather than WARNING. The response path is the one that never asks, and that is
where the `SEVERE` traces come from.

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
`publishDiagnosticsForRecalculate`, and any mutation in that window runs it.

What that run does today is almost nothing, and the plan has to be honest about the
almost. The connection's drain executor is shut down in `DevServer.serve`'s `finally`
before the socket closes, and `publishDiagnosticsForRecalculate` catches the
`RejectedExecutionException` a submit into it raises, resets its pending flag and logs
at debug. No drain walks, no store read runs, no publish is attempted, so no JUL record
appears. The code on both sides of that absorption says why it is there: the listener
slot is not cleared on teardown, so a build swap can still reach a dead connection's
service. The one cost that survives is exactly that reachability: live shared state
holding a dead connection's service, documented by a comment on each side instead of
removed by code. That is hygiene rather than a crash or noise, and Deliverable 2 is
scoped on those terms.

Both defects stay in scope as two ordered deliverables, but they are two distinct costs
with no overlap. Deliverable 1 silences the console, including the notification
records, since a wrapper that swallows the failure inside `consume` means `notify`
never catches anything to log; it does not touch the stale reference. Deliverable 2
retires the stale reference and the prose that leans on it, and would not, on its own,
quiet a single trace. Shipping only the first would leave live state still pointing at
dead connections; shipping only the second would leave the console still noisy.

## What "fixed" means

A developer detaching an editor from `graphitron:dev` and reattaching sees nothing in
the console. A write that fails for any reason other than the peer being gone is still
loud, and no other behaviour changes: every message that can be delivered still is.

That observable is worth grounding, because it is the whole target and it depends on how
lsp4j's records reach the console. lsp4j logs through `java.util.logging`, and the tree
has no `jul-to-slf4j` bridge, no `SLF4JBridgeHandler` install and no
`logging.properties` anywhere, so those records go to JUL's default console handler,
which is enabled at INFO and whose `SimpleFormatter` appends the stack trace of any
record carrying a throwable. That is why the response path's `SEVERE` records print as
traces, and it is also why the notification path's INFO records do: same handler, same
formatter, quieter label. Both were confirmed by emitting a record of each level with a
throwable under a default JUL setup rather than reasoned about from the levels alone.
The practical consequence for this item is that "quiet the console" cannot be satisfied
by attending only to `SEVERE`.

## Deliverable 1: the write side asks the question lsp4j already answers

The seam is `Launcher.Builder.wrapMessages`. It is applied to the outgoing
`StreamMessageConsumer` (inside `createRemoteEndpoint`) and to the incoming consumer
(inside `create`), so one wrapper per launcher covers every message in both
directions. Verified against the 0.24.0 bytecode rather than assumed, because a
wrapper applied to only one of the two would quiet the `SEVERE` traces and leave the
`publishDiagnostics` push logging a trace of its own at INFO.

The wrapper catches a `consume` failure, consults
`JsonRpcException.indicatesStreamClosed`, and on a true verdict logs at debug and
returns; anything else rethrows unchanged. lsp4j's own predicate is the whole point:
it already enumerates the conditions that mean "the peer is gone" (`Socket closed`,
`Connection reset`, `Broken pipe`, `Stream closed`, `Pipe closed`,
`ClosedChannelException`, `InterruptedIOException`) and recurses through
`JsonRpcException` causes itself, and lsp4j already trusts it in `StreamMessageProducer`
and in `RemoteEndpoint.notify`. Matching on messages by hand here would be a second,
worse copy of a predicate that ships in the dependency. Note what this makes the change:
not teaching lsp4j a new judgement, but applying the one it already makes in two places
out of three to the third.

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

This one is hygiene, not crash prevention and not noise reduction, and is worth doing
on those terms rather than inflated ones. It stops a dead connection's service being
reachable from the shared workspace, and it lets two comments that currently document
a standing leak document a race guard instead.

`Workspace.setRecalculateListener` is a single-slot setter with a no-op default, so the
minimal correct fix is a compare-and-clear: teardown clears the slot only if the
listener still installed is the one this connection put there. Plain unconditional
clearing is wrong, because a reconnect can install its listener before the old
connection's teardown runs, and an unconditional clear would then silently stop
diagnostics for the live editor. Getting that ordering backwards would turn a cosmetic
bug into a functional one, which is why it is spelled out here rather than left to the
implementation.

The compare needs an identity to compare on, and a method reference is not one: every
evaluation of `this::publishDiagnosticsForRecalculate` yields a fresh object with
identity equality, so a teardown that passes a second evaluation compares unequal
against the instance `setClient` registered and clears nothing, silently. That arm
fails closed, so the item would ship looking done with the slot never cleared. The
mechanism: `GraphitronTextDocumentService` holds the listener `Runnable` in a field,
created once, registers that instance in `setClient`, and surrenders the same instance
at teardown for the compare-and-clear. (A registration token returned by the workspace
would also work; the field is the smaller change and keeps the workspace's surface at
one method plus its inverse.)

The clear belongs where the connection ends rather than in `exit()`. `exit()` is a
client-driven notification that a disconnecting editor may never send, so the `finally`
in `DevServer.serve` is the only place guaranteed to run. `GraphitronLanguageServer`
needs to expose the teardown; its `exit()` comment ("lsp4j drives process lifetime;
nothing to clean up") is true for stdio and false for the shared-workspace server, and
should stop saying so.

The clear narrows the disconnect window; it does not close it. A mutation can read the
slot before the clear and run the listener after the executor shutdown, so the
`RejectedExecutionException` absorption in `publishDiagnosticsForRecalculate` stays as
the guard for that residual race. It must not be deleted as dead code when the slot
starts being cleared: removing it would hand the mutator (the dev goal's watcher
thread among them) exactly the throw it exists to prevent. What changes is its story,
along with the `finally` comment in `DevServer.serve`: both currently state the
uncleared slot as their reason for existing, and after this item both describe the
residual race instead.

## Coverage

Three pins, deliberately split by what each can prove:

1. **The predicate boundary**, in `graphitron-lsp`, with no sockets: hand the wrapper a
   `MessageConsumer` that throws `JsonRpcException(SocketException("Socket closed"))`
   and assert the wrapper returns; hand it one that throws a `JsonRpcException` wrapping
   an unrelated `IOException` and assert it propagates. This is the pin that fails if
   someone later widens the catch.
2. **The end-to-end suppression**, in `graphitron-lsp` next to the factory it pins
   (the alternative home, `graphitron-maven-plugin` beside `DevServerTest`, would put
   the pin behind that module's native-access surefire configuration for no gain),
   deterministic rather than racy. A test-owned launcher pair over a real socket, with a local service method that blocks on a latch:
   the client sends the request, the test closes the socket, the test releases the latch,
   the handler completes, and lsp4j attempts the write into a socket that is already
   gone. Attach a `java.util.logging.Handler` to the
   `org.eclipse.lsp4j.jsonrpc.RemoteEndpoint` logger and assert no record arrives
   carrying a throwable, at any level. Asserting on `SEVERE` alone would be the wrong
   pin: a record's level is what labels it, not what makes it print a trace, so a
   `SEVERE`-only assertion would pass while the notification path still filled the
   console at INFO. Add a companion case that drives a notification write (a
   `publishDiagnostics` push after the socket is gone) rather than a response, since that
   is the path an exception-handler-shaped fix would have missed entirely.
   The latch is what makes this worth writing: "send a request and close fast" would pass
   vacuously whenever the response happened to win the race, and a test that can silently
   prove nothing is worse here than no test.
3. **The listener slot**, in `graphitron-lsp`, driven through the service's own
   register-and-clear path rather than with test-authored `Runnable`s, which would
   compare each instance against itself and pass whatever identity the production
   registration uses. Positive case: a service registered via `setClient` and then
   torn down leaves the slot cleared, observable as its listener no longer firing on
   the next mutation. Negative case: tearing down a service after a second service
   has taken the slot leaves the second registration in place and firing.

`DevServerTest.multipleClientsShareWorkspaceState` already closes a connection and
reconnects against a shared workspace, so it is the natural place to assert the console
stayed quiet across that sequence. It should gain the `RemoteEndpoint` log assertion,
but it is not the deterministic pin, since nothing in it guarantees a request is still
in flight at close time.

## Alternatives considered

**`Launcher.Builder.setExceptionHandler`.** Also suppresses the four `SEVERE` traces,
and is a smaller diff. Rejected because it is scoped to the request/response path: a
`publishDiagnostics` push to a dead client is a notification, handled by
`RemoteEndpoint.notify` before any exception handler is consulted, so it keeps logging
its own stack trace at INFO and "sees nothing in the console" is not met. There is a
second, smaller reason to prefer the consumer seam even for the path this does cover:
the throwable reaching an exception handler is a `CompletionException` wrapping the
`JsonRpcException`, and `indicatesStreamClosed` recurses only through `JsonRpcException`
causes, so this route needs hand-rolled unwrapping where `wrapMessages` sees the
exception raw.

**A multi-listener registry on `Workspace`.** The honest shape if several editors ever
attach at once, since a single slot already means last-connection-wins. Rejected as out
of scope: `graphitron:dev` serves one developer's editor, the single slot is not wrong
today, and widening the workspace's contract is a larger design change than this item
should carry. Worth filing separately if multi-editor attachment becomes real.

**Leave it and let the console be noisy.** Rejected on the grounds in the problem
statement: the cost is not the noise itself but that it trains a developer to ignore
`SEVERE` in the one console where a real dev-loop failure would appear.

## Retired vocabulary

No symbol or mechanism is removed, but one claim is: "the listener slot is not cleared
on teardown" stops being true. Three prose sites state it and must be rewritten when
the clear lands: the `exit()` comment in `GraphitronLanguageServer` ("lsp4j drives
process lifetime; nothing to clean up"), the `finally` comment in `DevServer.serve`,
and the javadoc on `GraphitronTextDocumentService.publishDiagnosticsForRecalculate`.
Grep for "listener slot" at the Done gate. The rewritten comments around the
`RejectedExecutionException` absorption describe the residual race the absorption
still guards; the absorption itself survives, per Deliverable 2.

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

**Addressed (author, round 1).** Both findings confirmed independently before revising:
`RemoteEndpoint.notify`'s catch of `Exception` around `out.consume` reads exactly as
described in the bytecode, and the read-side consult greps to `StreamMessageProducer`
alone. The notification-path cost is restated in terms of what actually happens (a
logged record per failed publish, a drain's walk and store read spent on an answer with
no recipient, a dead service reachable from shared state), the two-deliverable coupling
is re-derived as two distinct costs rather than a defect and its mitigation, and the
`setExceptionHandler` rejection now rests on the notification path lying outside its
scope, keeping the `CompletionException` point only as the secondary reason it is. The
observable in "What 'fixed' means" is grounded rather than asserted: the tree has no JUL
bridge or `logging.properties`, and a record carrying a throwable prints a stack trace
under the default handler at INFO as well as `SEVERE`, which was confirmed by emitting
one. Coverage pin 2 changed as a result, from a `SEVERE`-only assertion to any record
carrying a throwable, plus a notification-path case.

### Round 2: Spec -> Ready, revisions requested (2026-08-22, session_01KH4F9G6Ad8qwSCmJdQeRMr)

Round 1's finding is addressed, and everything Deliverable 1 rests on re-verified clean
against the tree and the 0.24.0 bytecode, including the load-bearing new claim that a
wrapper swallowing inside `consume` leaves `notify` nothing to log. Deliverable 1 is
ready as written. Two findings on Deliverable 2.

**Finding 1: two of Deliverable 2's three costs are already absorbed in the tree.**
`DevServer.serve`'s `finally` calls `drainExecutor.shutdownNow()` before it closes the
socket, and `GraphitronTextDocumentService.publishDiagnosticsForRecalculate` catches the
`RejectedExecutionException` that a submit to a shut-down executor throws, resets
`drainWanted`, and logs at debug. So in exactly the window the plan describes, between an
editor detaching and the next reconnect, a stale listener's `run()` reaches a dead
executor and returns. There is no drain walk, no store read, no publish, and therefore no
JUL record. Both the first cost ("every failed publish logs one record carrying the
throwable") and the second ("the drain ... did real work first, walking each queued file
and running a store read") are unreachable there. The third cost, a dead connection's
service reachable from live shared state, stands.

Two consequences for the plan body. First, the two-deliverable coupling now rests on that
third cost alone: "shipping only the first would leave the item quiet and still wrong" is
true only in the stale-reference sense. That is still a fair reason to do the clear, and
the plan already frames Deliverable 2 as hygiene, but it should be argued on the cost it
actually buys rather than on noise and wasted work the tree already absorbs.

Second, `Retired vocabulary` names one stale comment when there are three. Both
`DevServer.serve`'s `finally` comment ("the workspace outlives this connection with its
listener slot uncleared, so a build swap can still submit to this executor after the
shutdown") and `publishDiagnosticsForRecalculate`'s javadoc ("the window is real: ... its
listener slot is not cleared on teardown") state the uncleared slot as their own reason for
existing, and Deliverable 2 narrows that window to a residual race: a mutation that reads
the slot before the clear and runs the listener after the shutdown. Say explicitly that the
`RejectedExecutionException` absorption stays as the guard for that race rather than
becoming dead code, because "teardown clears the slot" reads as license to delete it, and
deleting it would hand a mutator (the dev goal's watcher thread among them) the throw that
javadoc exists to prevent.

**Finding 2: the compare in compare-and-clear needs an identity the plan has not given
it.** `setClient` registers `workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate)`.
A method reference yields a fresh object at every evaluation and lambdas inherit identity
equality, so a teardown that calls the clear with `this::publishDiagnosticsForRecalculate`
compares unequal against the instance the registration installed and clears nothing. The
plan spells out the opposite hazard, an unconditional clear clobbering a live reconnect, on
the grounds that getting the ordering backwards would turn a cosmetic bug into a functional
one. The same standard applies here, and more sharply: this arm fails closed and silently,
so the item would ship looking done with the slot never cleared. Name the mechanism the
compare uses, either capturing the registered `Runnable` in a field on the service and
handing that same instance back, or having registration return a token teardown surrenders.

Coverage pin 3 as written would not catch it. "Against a `Workspace` directly" with
test-authored `Runnable`s compares each instance against itself, which passes whatever the
production registration does. The pin needs to drive the service's own register-and-clear
path for at least the positive case.

**Non-blocking.** Coverage pin 2 does not name a module. Pins 1 and 3 say `graphitron-lsp`,
and pin 2 needs only lsp4j plus a loopback socket, so `graphitron-lsp` reads as intended,
but the factory's home module is worth stating since the alternative reading
(`graphitron-maven-plugin`, next to `DevServerTest`) would put the pin behind that module's
native-access surefire configuration for no gain.

**Addressed (author, round 2).** Both findings confirmed against the tree before
revising: `DevServer.serve`'s `finally` shuts the drain executor down before closing
the socket, `publishDiagnosticsForRecalculate` absorbs the rejected submit with a
debug log, and `setClient` registers a fresh method-reference object per evaluation.
The second-defect section now derives its cost from what the tree actually does (a
stale run reaches a dead executor and returns; only the reachability cost survives)
and the two-deliverable coupling rests on that cost alone. Deliverable 2 names the
compare's identity mechanism, the service holding the registered `Runnable` in a field
and surrendering the same instance at teardown, with the workspace-token alternative
noted and set aside; it also states that the `RejectedExecutionException` absorption
stays as the guard for the residual race (slot read before the clear, listener run
after the shutdown) rather than becoming dead code. Retired vocabulary now names all
three prose sites carrying the uncleared-slot claim and gives the Done gate its grep
query. Coverage pin 3 drives the service's own register-and-clear path in both
directions, and pin 2 names its module.
