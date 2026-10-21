# A forked LSP test JVM died with SIGSEGV in G1's root-region scan

Seen once, 2026-10-21 06:52 CEST, in a `graphitron-lsp` module run
(`mvn test -pl graphitron-lsp -am`, worktree `session-c`, on trunk `2fcc64fa8` plus the windowed
inlay walk). Surefire reported the fork as crashed with exit code 134 while
`NodeTypeCompletionsTest` ran, after 369 of the module's 646 tests had passed. The immediate rerun
of the same command, and the full reactor build after it, were green.

## The report's header

```
#  SIGSEGV (0xb) at pc=0x0000000000000000, pid=396029, tid=396108
#
# JRE version: OpenJDK Runtime Environment (nix) (25.0.4.1+1) (build 25.0.4.1+1)
# Java VM: OpenJDK 64-Bit Server VM (nix) (25.0.4.1+1, mixed mode, sharing, tiered, compressed oops, compressed class ptrs, g1 gc, linux-amd64)
# Problematic frame:
# V  [libjvm.so+0x9212d6]  G1CMRootRegionScanTask::work(unsigned int)+0x126
```

Command line `-Xmx6g --enable-native-access=ALL-UNNAMED`, 43 s into the fork, on a 16-core host
with 62 GB.

## The crashing thread

```
Current thread (0x000070ead8000d30):  WorkerThread "G1 Conc#1"      [id=396108, stack(0x000070eab9df4000,0x000070eab9ef4000) (1024K)]

Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
V  [libjvm.so+0x9212d6]  G1CMRootRegionScanTask::work(unsigned int)+0x126
V  [libjvm.so+0x12de0d5]  WorkerThread::run()+0x75
V  [libjvm.so+0x11fe13c]  Thread::call_run()+0xac
V  [libjvm.so+0xef1186]  thread_native_entry(Thread*)+0xd6
C  [libc.so.6+0x9ce73]  start_thread+0x2e3

siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x0000000000000000

RAX=0xffff000000000088, RBX=0xffff00070337a048
```

Two further threads also faulted while the report was written. No Java frame is on the crashing
stack: the fault is in the garbage collector's concurrent marking, scanning a heap region's roots,
and `RAX` and `RBX` hold addresses that are not canonical, the high bits of a heap address set
where none should be.

## What it points at

A collector thread faulting on a pointer that is not one is what a corrupted object reference
looks like, and the LSP is the module that loads native code: tree-sitter and its GraphQL grammar,
extracted to `/tmp` and called through the foreign-function API, with captured nodes routed into
`Arena.ofAuto()` (`Directives.findAll`). So native-memory corruption is the first suspect if it
recurs. A JVM fault is the other reading, and one occurrence cannot tell them apart.

If it recurs: keep the `hs_err_pid*.log` the fork writes into `graphitron-lsp/`, note which test
class was running and how many had passed, and run the module with `-XX:+UseSerialGC` to see whether
the fault follows the collector or the code.

It did not reproduce in ten further runs the same day; see
`2026-10-21-lsp-test-jvm-sigsegv-not-reproduced.md`.
