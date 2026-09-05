---
id: R921
title: "After the first walk, a changed .java file is found by mtime rather than by hashing every source"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-09-04
last-updated: 2026-09-04
---

# After the first walk, a changed .java file is found by mtime rather than by hashing every source

## Goal

A `.java` save in a `graphitron:dev` session costs work proportional to the file that was saved. The
`java_` family is the store's record of a consumer's own source declarations, the one the language
server answers goto-definition from; it is refreshed on its own cadence, every time the source
watcher fires. Today that refresh reads and content-hashes *every* source file under the module's
compile roots to find the one that moved. When this lands, the first walk of a session still hashes
everything, because that is the walk that reconciles a store written by some other process, and
every walk after it hashes only the files whose modification time moved, the way the classpath
census already treats `target/classes`.

> **Absorbed by R922, 2026-09-05.** This item's execution has moved into R922, which builds the
> currency-claim mechanism and takes this refresh as its first client, so this file is a Backlog
> tombstone: it stays as a redirect while R922 is in flight and deletes when R922 reaches Done. The
> goal above is what R922 delivers for this cadence. Two things changed on the way. The cost this item
> said Spec owed is now measured: 1,316 `.java` files and 6.0 MiB over `graphitron-sakila-example`'s
> source roots, 151 ms cold and 30 to 46 ms warm per debounced save, so the item was worth keeping
> rather than discarding. And the detector is a store-held claim rather than a modification time, which
> retires the "why mtime is enough here" argument below: the claim is deleted by the watcher that saw
> the file move, so nothing has to reason about timestamps. The finding under "Implementation sketch"
> that the session record belongs to the writer rather than to the walker survives, and is answered
> structurally there, a file whose store write failed being a file that holds no claim.

## What a save costs today

`DevMojo.refreshSourceFacts` runs `SourceWalker.walkFiles` over `ctx.compileSourceRoots()` and hands
the result to `JavaSourceFacts.refresh`. The two halves treat the same question differently.

`SourceWalker` holds a per-file cache keyed on modification time and re-parses only the files whose
mtime moved, so the parse, which is the expensive half, is already incremental and already trusts
mtime.

`JavaSourceFacts.refresh` then computes `ClasspathSources.hash(file)` for every file in the walk,
compares it against the `java_file.stamp` the store recorded, and skips the ones that match. Its
javadoc calls this deliberate: "The hash is recomputed for every walked file each refresh,
deliberately: a modification time is the heuristic `store_source`'s stamp exists to avoid, and
hashing source files is cheap beside the parse it is protecting." The second half of that sentence is
true of a *cold* walk, where every file is parsed anyway. It stops being true on the cadence this
item is about, where the parse was skipped and the hash is the only thing left, so the hash is not
cheap beside anything; it is the whole cost of the round.

The cost is one full read of the module's sources per debounced save. Unmeasured, and that
measurement is the first thing Spec owes: the nearest figure is the 55.2 ms this repo's 28.2 MB jar
set takes to hash, and the reactor's own 1,743 non-generated source files come to 19.1 MB, so the
order of magnitude is tens of milliseconds plus per-file overhead, on a keystroke-adjacent cadence.
If it measures small enough to be imperceptible, record the number and discard the item, exactly as
the classpath item was asked to.

## Why mtime is enough here, after the first walk

The rationale the current code cites is about a stamp that *survives the process*, and it is right
about that: `store_source`'s comment says the `(path, size, last-modified)` triple is "tolerable
while a wrong answer dies with the JVM and not tolerable once it survives a build", because CI
caches, image layers and reproducible-build normalisation all produce files whose modification time
is constant or arbitrary. Nothing here proposes persisting an mtime. The recorded stamp stays a
content hash, computed from the file the writer is rewriting, and a reader comparing text against it
keeps comparing content.

What changes is which files a *later walk in the same session* asks the question about. Within one
JVM the failure the triple admits, content that changed while size and mtime did not, is produced by
a restored cache or a normalising image layer, none of which happen underneath a running editor; an
editor writing a file moves its mtime, and the operations that replace a whole source tree under a
session (a branch switch, a `git checkout`) move it too, which is the safe direction and costs a
re-hash rather than a wrong answer. This is the same bargain `ClasspathCensus` makes for
`target/classes`, argued at length there, and the same one `SourceWalker` already makes one call
frame away for the parse.

The first walk of a session is the one that cannot use it, and for a reason worth stating rather
than assuming: the store it reconciles against may have been written by another process, days ago,
over a source tree that has since moved in ways no live session observed. That walk hashes
everything, which is the current behaviour and is where the "after the first walk" in this item's
title comes from.

## Implementation sketch (fill in at Spec)

The signal already exists one frame away: `SourceWalker` knows which files it re-parsed, because
that is what its own cache decided. The cheapest shape is to carry it, so `JavaSourceFacts.refresh`
hashes the files the walk actually re-read and skips the rest. On the first walk of a session the
cache is empty, every file counts as re-read, and the behaviour is today's.

**The session record belongs to the writer, not to the walker**, and this is the one place the
sketch should not be simplified. `JavaSourceFacts.refresh` swallows a `DataAccessException` and
returns, by design, because store trouble may cost warmth and never the dev loop. If "unchanged"
were decided by the walker's cache alone, a file whose rewrite the store refused would be skipped by
every later save in the session while its rows stayed missing, and the language server would answer
from nothing until a restart. So the writer keeps its own record of the files it has *committed* at
a given (size, mtime) and skips on that, which makes a failed write cost a re-hash on the next save
rather than a hole that lasts the session.

Two smaller things fall out and should be settled in the same pass. `prune` still needs the whole
walked set, which it gets from the walk regardless of what was hashed, so its scope argument is
unaffected. And the javadoc sentence quoted above has to be rewritten rather than deleted: it is the
right argument about the persisted stamp and the wrong one about the cadence, and the next reader
needs to find both halves stated.

## Provenance

Found in a sweep of the refresh machinery after the classpath census item landed, looking for the
same shape elsewhere: a per-round cost that scales with the workspace rather than with the edit.
This is that shape on the `.java` cadence. The sibling findings from the same sweep are the duplicate
jar hash and the whole-partition rewrite per round, filed separately.
