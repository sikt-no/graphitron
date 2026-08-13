---
id: R652
title: "The stamp comment still asks the retired resolution question"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# The stamp comment still asks the retired resolution question

Two prose surfaces still frame stamping as a filesystem resolution question, which is the
question the sealed source carrier retired for schema sources. `store_source.stamp`'s column
comment says the stamp is "NULL where nothing resolves to a regular file to hash" and then
enumerates the cases, one of which is "a programmatic caller may hand a bare name"; that case is
now decided by the source's `named` arm at mint, not by a probe. Every enumerated outcome is
still correct, so nothing is false, but the unifying framing describes machinery that no longer
runs for this population. The predicate does survive for classpath entries, which is why
`ClasspathSources` says the same thing legitimately, so the fix is to split the comment's
reasoning by population rather than to reword it wholesale. Beside it,
`WarmStartRefreshTest.aSchemaFileStampMatchesUntilTheFileChanges` carries an assertion
description reading "capture stamps a schema file that resolves to a regular file", which is the
retired question applied to exactly the retired population.

Filed rather than fixed at the gate for one concrete reason: `GraphitronModelStore`'s
compatibility segment is a prefix of the DDL hash, so editing a `COMMENT ON COLUMN` string moves
every consumer's warm store to a new directory. That cost is worth paying beside a DDL change
that moves the hash anyway, and not worth paying for a comment on its own. Land this with the
next change to `graphitron-model.sql`.
