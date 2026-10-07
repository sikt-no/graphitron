---
id: R993
title: "The pipeline-tier classpath census leaves code_method_parameter.element_class and delivery NULL although CodeCapture writes them"
status: Backlog
bucket: bug
theme: testing
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# The pipeline-tier classpath census leaves code_method_parameter.element_class and delivery NULL although CodeCapture writes them

## Goal

A store captured the way the pipeline-tier tests capture it (`CapturedStore.ofCatalog` with a classpath census) carries the same parameter and result peel on the parameter and method rows that real `CodeCapture` writes: `code_method_parameter.element_class` / `delivery` and `code_method.result_erased_class` / `result_element_class` are populated wherever `code_type_element` holds the same fact for the declared type. Today, by the report of the session that implemented the list-shaped `@nodeId` producer slot (which shipped reading `code_type_element` instead for this reason), those columns are NULL on every row under that capture, although `CodeCapture` writes them and `CodeCaptureTest` pins them equal to the per-type dictionary. So a reader that asks the parameter row, as that item's spec first planned to, passes the store tier and silently reads nothing at the pipeline tier. A plausible lead, unverified: `CodeCapture` derives the peel through a `containers` map that the census path may hand over empty. The first step is to confirm the gap with a pipeline-tier probe, then either make the census path write the columns or state why that path cannot, and in either case make the two tiers agree.
