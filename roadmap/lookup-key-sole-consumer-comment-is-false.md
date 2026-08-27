---
id: R866
title: "graphitron_field_lookup_key claims a sole consumer and three relations read it"
status: Backlog
bucket: cleanup
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# graphitron_field_lookup_key claims a sole consumer and three relations read it

`graphitron_field_lookup_key` is the capture table for `@lookupKey` written on an input field,
which is the retired site for that directive. Its own relation comment says "the sole consumer is
the located migration rejection". Three relations read it: the rejection the comment names, the
`lookup_bearing` recursive term inside `intent_authored_field_claim`, and a third reader that takes
it as its own precedence arm. A relation comment in this store is load-bearing prose, and a
contributor who trusts this one will conclude that changing the table's population can only move a
rejection message, when it also moves what the authored-claim view answers and therefore what the
column scope relation resolves.

The fix is a comment correction: state the readers, or state the rule that makes an inventory
unnecessary the way `intent_field_navigated_type`'s comment now does after it had its own count
wrong twice. Naming the readers is the smaller change and the weaker one, because a count is what
went stale here in the first place; a rule ("every reader of the retired lookup-key site is a
reader of this table") does not rot when a fourth arrives. Whichever form it takes, the work is
one comment and no DDL.

Filed at R850's Done gate rather than folded into it. R850's spec raised this as a non-blocking
round-1 finding and its author response deferred the correction to whichever of two homes came
true: candidate B's implementation if B shipped, since B would have added a fourth reader, and a
Backlog item if it did not. B did not ship, so this is the promised home, filed so the obligation
outlives the deleted spec file.

