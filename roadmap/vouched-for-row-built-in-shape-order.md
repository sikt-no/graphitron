---
id: R1208
title: "The voucher arm builds a VouchedFor row in its shape's component order, checked by name at generate time"
status: Backlog
bucket: tech-debt
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-11-24
last-updated: 2026-11-24
---

# The voucher arm builds a VouchedFor row in its shape's component order, checked by name at generate time

## Goal

A generated accepting dispatch can no longer put one vouched-for value into another field's slot of
the connection key. Today the voucher arm in `EntityFetcherDispatchClassGenerator.emitVoucherArm` builds
`new ConnectionKey.VouchedFor(vouched0, vouched1, ...)` in the order `EntityKeyQuery` reads the
vouched-for fields (by field name). The record's components come from `ConnectionKeyShape.vouchedFor()`,
which `AcceptVouchedForQuery` builds, also by field name. The two agree only because both happen to sort
by name. If two components of the same Java type were swapped, the code would still compile, and only an
execution test whose policy tests the pair would notice. The arm should build its arguments in the
shape's component order and check each by name at generate time, so a mismatch fails the build rather
than mounting the wrong values.

The principles-architect raised this at R1199's handover for review, as a follow-up predating that item
(the one-value key had the same reliance). R1199's Done reviewer filed it so it outlives that spec.
