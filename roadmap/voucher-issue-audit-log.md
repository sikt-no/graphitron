---
id: R1194
title: "An issuing subgraph logs each voucher it signs, as the accepting one logs each redemption"
status: Backlog
bucket: feature
priority: 5
theme: runtime-connection
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# An issuing subgraph logs each voucher it signs, as the accepting one logs each redemption

## Goal

An issuing subgraph logs each scope of vouchers it signs, one line per operation and scope with the vouch id, scope and expiry, on the same `no.sikt.graphitron.voucher.audit` logger the accepting subgraph's redemptions and rejections use, so the two halves of a handover can be traced against each other by vouch id. R1119's audit-logging requirement covers issuing and redemption; slice 8d.2 built only the accepting side. Never the token or its values.
