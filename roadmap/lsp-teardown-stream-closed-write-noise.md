---
id: R794
title: "LSP connection teardown logs SEVERE stack traces for stream-closed writes"
status: Backlog
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

Whether this is the same item or a split is a Spec question. They share a cause
(teardown leaves per-connection state reachable) and the first fix makes the second
harmless, but only the second one makes it correct.

## Sketch of a fix

lsp4j takes a message-consumer wrapper, `Launcher.Builder.wrapMessages`, and applies
it to both the outgoing and incoming consumer, so one wrapper per launcher covers
every write. Have that wrapper swallow a `consume` failure exactly when
`JsonRpcException.indicatesStreamClosed` says the stream is gone, log it at debug, and
rethrow anything else. That is lsp4j's own predicate for this condition rather than a
hand-rolled message match, and it puts the judgement at the one seam every outbound
message passes through: the response path, the `publishDiagnostics` push, and the
synchronous error responses all get it at once. Both launcher sites want it.

Coverage: `DevServerTest` already drives a real socket against a real launcher, so the
regression pin is a request left in flight across a client disconnect, asserting no
`SEVERE` record reaches the handler.
