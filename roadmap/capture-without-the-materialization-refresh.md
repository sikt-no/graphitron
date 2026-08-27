---
id: R865
title: "A capture cannot be run without the materialization refresh, so a schema whose refresh never returns leaves no store to debug"
status: Backlog
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A capture cannot be run without the materialization refresh, so a schema whose refresh never returns leaves no store to debug

Every capture pays the materialization refresh, and no caller can decline it. `FactCapture.capture`
ends its one transaction with `Materializations.refresh`, then calls `Materializations.analyse`
straight after the commit. There is no parameter, no property and no goal that stops before either.

## Vocabulary

A **registration** is a row of `meta_materialize`: a derivation kept as a view under a `_live` name,
plus a table of the same shape under the canonical name that readers spell. The **refresh** is the
pass that empties and refills those tables, and today it runs inside the capture's own transaction,
after the captured rows are flushed.

## Why this is worse than a slow step

Because the refresh is inside the capture's transaction, a capture that is killed during the refresh
commits **nothing**. Not the refreshed tables, which is expected, but also not the SDL rows, not the
catalog rows, not the classpath census, and not the capture-cadence derivations. A run that spent an
hour transcribing a schema leaves a store file holding zero graphs and zero fields.

That is not hypothetical. The investigation in R856 surveyed fifteen store files a real consumer
schema had left on disk and found that every one of them at the current registration count held zero
graphs and zero fields: not one had ever been captured into. The only populated stores were from an
older DDL with fewer registrations.

So the tooling has a hole exactly where it is needed most. On the one schema where somebody most
wants to look at the captured facts, the captured facts are the thing that cannot be obtained, and
the reason is that they are hostage to a later step that does not return. Timing the refresh's
statements against a populated store, which is how R856 and R848 both do their work, requires a
populated store that nobody on that schema can produce.

## What is not being proposed

This is not a fix for the refresh being slow, and it is not the transaction-boundary change R856
tested and recorded as a dead end. That arm split the capture into one transaction per registration
to give the planner statistics mid-pass, and it came back with a ratio of 0.82 against it. This item
takes no position on how many transactions a full capture uses. It asks for the ability to not run
the refresh at all.

It is also not a mode anyone should generate from. A store captured this way holds current base
relations and stale or empty materialized targets, so any reader that spells a canonical target name
gets a wrong answer. Whatever surface this lands on has to make that plain, and has to keep the
default unchanged.

## What changes when this lands

Somebody debugging a capture can get a captured store out of a schema whose refresh does not return,
and can then time the refresh's statements against real rows instead of against a fixture. Somebody
measuring what capture itself costs can separate the transcription from the derivation, which is a
split no figure in the tree currently states. And the two open measurement items on the register,
R848 on the cut set as a whole and R856 on the consumer schema, gain the instrument they both
currently work around.

The default path does not change: a capture that is not asked to skip the refresh runs it exactly as
it does now, in the same transaction, with the same rows at the end.

