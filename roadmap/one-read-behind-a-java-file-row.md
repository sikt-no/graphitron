---
id: R925
title: "A java_file row's stamp and its declarations come from one read of the file"
status: Backlog
bucket: correctness
priority: 2
theme: dev-loop
depends-on: []
created: 2026-09-06
last-updated: 2026-09-06
---

# A java_file row's stamp and its declarations come from one read of the file

## Goal

A row in the `java_` family describes one read of one file, so the content hash it carries and the
declarations beside it can never come from two different versions of that file. The `java_` family
is the store's record of what a consumer's `.java` sources declare, refreshed on the source-save
cadence by the dev loop; a *stamp* is the content hash a row is retained against, and a row is
skipped whenever the file still hashes to it. Today the two halves of a row are read at two
different moments, so a file saved in the gap between them produces a row whose stamp says "current"
and whose declarations are one version behind, permanently: every later refresh hashes the file,
finds the stamp matching, and skips it.

## The gap, in the order it happens

`SourceWalker.walkFiles` parses the file and returns a `ParsedFile`. `JavaSourceFacts.refresh` then
calls `ClasspathSources.hash(file.file())`, which opens the file again. Between those two reads the
file can move, and on the save cadence that is not a rare interleaving: the walk is what a save
triggered, and an editor writing a second time (or a formatter, or a build regenerating sources) is
ordinary.

When it happens the row is written with declarations from version one and the hash of version two.
Nothing afterwards notices: the next refresh hashes version two, compares it against the stamp,
finds them equal, and retains a row describing declarations nobody wrote. The symptom is
goto-definition and hover answering from positions one edit behind, for one file, until the session
restarts.

`SourceWalker`'s own cache has the same shape one layer down. It records `mtimeOf(f)` *after*
parsing, so a save landing between the parse and that stat writes the new modification time beside
the old declarations, and the walker never re-parses the file either.

## Not what the observation changed

Worth stating because the two look alike and the item that introduced the observation should not be
blamed for this one. The observation only ever lets a pass *skip* a hash, and the file above is
skipped for a different reason: its stamp genuinely matches the bytes on disk. The behaviour is
identical before and after that item, and hashing every walked file (which is what the loop did
before) never caught it either.

What did change is that the defect is now easier to see, because the writer states in its own
javadoc that a stamp vouches for the rows beside it.

## Sketch

The fix is to make one read produce both halves, rather than to add a second check. The walker
already opens the file to parse it, so the shape to reach for is a `ParsedFile` that carries the
content identity of the bytes it parsed, computed from those bytes rather than from a later reopen.
Then `JavaSourceFacts` stamps what it was handed and never opens the file at all, `read_at` dates
exactly that read, and the walker's own cache keys on the same identity instead of on a modification
time it stats afterwards.

Spec should also decide what the walker does about a file it cannot hash, and whether the identity
belongs on `ParsedFile` or on a small record beside it, since the walker is also the LSP's.

## Provenance

Found in the adversarial review of the item that added the observation and `read_at`, while checking
whether the comparison could make an existing staleness permanent. It cannot, and this can, by a
route that predates it.
