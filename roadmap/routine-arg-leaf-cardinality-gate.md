---
id: R627
title: "@routine binds a list-shaped argument leaf to a scalar IN parameter without rejection"
status: Backlog
bucket: bug
priority: 3
theme: routine
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# @routine binds a list-shaped argument leaf to a scalar IN parameter without rejection

`WireCoercionResolver.checkScalar` passes through when the SDL leaf and the declared Java type disagree on list-ness, deliberately: its javadoc states that it leaves "that cardinality concern to the shape checks". On the `@service` path those shape checks exist downstream. A `@routine` IN parameter has none, so a list-shaped argument bound to a scalar parameter is accepted at classify time and emits a cast that fails at request time: `@routine(argMapping: "pId: ids")` against `ids: [Int!]` emits `env.<Integer>getArgument("ids")`, a `ClassCastException` on an `ArrayList`. This is the cardinality sibling of the input-object footgun the argMapping seam unification closes (an input-object leaf bound to a scalar parameter, which that item rejects by name), and it reaches the same generated cast through the same uncovered gap. It is reachable today through the flat spelling and becomes easier to author once nested paths land, since a nested input field is as likely to be list-shaped as a field argument. The fix is one more condition on the routine-side leaf check that the seam item introduces: reject a list-shaped leaf bound to a non-list IN parameter, naming both shapes. Kept out of the seam item so that item does not grow a second rejection class beyond the one its consumer report names; picking it up afterwards is a small edit at a rejection site that will already exist.
