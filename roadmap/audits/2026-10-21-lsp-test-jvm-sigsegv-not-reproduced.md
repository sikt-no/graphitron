# The LSP test JVM's SIGSEGV in G1 did not reproduce

The crash is described in `2026-10-21-lsp-test-jvm-sigsegv-in-g1.md`: one `graphitron-lsp` module run
on 2026-10-21 lost its forked test JVM to a SIGSEGV in G1's concurrent root-region scan. This is the
attempt to make it happen again, the same day, and what the crash report says about where it came
from.

## What was run

The module's whole suite, 646 tests, by the command the crashing run used, on the code it ran:
trunk `52b8f6965`, whose `graphitron-lsp` sources are the crashing run's, the commits between being
roadmap-only. Each run was one `mvn -o test -pl graphitron-lsp` against the session's own local
repository, with `-Xmx6g` for the forked JVM, other sessions' builds running on the same 16-core host
throughout.

| Runs | JVM flags | Wall time each | Load average | Result |
|---|---|---|---|---|
| 8 | as the crashing run | 105 to 323 s | 17 to 55 | all green, 5,168 test executions, no crash |
| 2 | `-XX:+UnlockDiagnosticVMOptions -XX:+VerifyBeforeGC -XX:+VerifyAfterGC -XX:+VerifyDuringGC` | about 2,400 s | 14 to 35 | all green, no heap verification failure, no crash |

Counting the crashing run, its immediate rerun and the full reactor build that followed, the
module's suite ran thirteen times that day and crashed once.

The verification flags are what the second row is for. G1 checks every reference in the heap before,
during and after each collection with them on, so a corrupted object reference, the reading the
crash report's registers suggest, would have stopped a run with a verification failure at the
first collection after the corruption, whether or not anything then crashed on it. Neither run did.
The flags reach the fork through `JDK_JAVA_OPTIONS`; `-DargLine` does not override the module's own
`@{argLine}`, and the fork's environment is where to confirm they arrived.

## What the crash report adds

The suite runs its test classes in parallel: the report lists JUnit's `ForkJoinPool-1` with 23
workers, about ten of them running Java when the collector faulted, and one of the two further
threads that faulted while the report was written is a test worker, `ForkJoinPool-1-worker-7`, not a
collector thread.

The last VM operations before the crash are a dense run of `HandshakeAllThreads (CloseScopedMemory)`,
ten in the final 80 ms, then a young evacuation pause from 43.259 to 43.325 s; the fault is in the
concurrent marking that follows such a pause. That handshake is what closing a shared foreign-memory
arena costs, and jtreesitter opens and closes shared arenas in `Tree`, `Parser` and `QueryCursor`,
so native memory was being released across threads up to the moment of the crash. That is ordinary
use of the library rather than evidence of a fault in it, and the nearest activity to the crash
there is.

## What it leaves

Two readings remain and one occurrence separates neither: a fault in the JVM on the path where
shared arenas close while G1 marks concurrently, or a use of native memory after its arena closed,
in jtreesitter or in how the LSP holds its nodes, which the heap verification would not see because
the memory is not the Java heap's.

If it recurs, keep the `hs_err_pid*.log` the fork writes into `graphitron-lsp/`, note the test class
running, the count passed and whether other sessions' builds were running, and run the suite under
`-XX:+UseSerialGC` passed through `JDK_JAVA_OPTIONS`: a crash that follows the collector away from
G1 points at the native side.
