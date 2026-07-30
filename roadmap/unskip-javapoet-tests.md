---
id: R560
title: "Un-skip graphitron-javapoet test suite"
status: Backlog
bucket: cleanup
priority: 6
theme: testing
depends-on: []
created: 2026-07-30
last-updated: 2026-07-30
---

# Un-skip graphitron-javapoet test suite

`graphitron-javapoet`'s surefire configuration carries `<skipTests>true</skipTests>`, so the module's 23 test files never run in any build, local or CI. The element is present in the pom as far back as visible history and carries no comment explaining it, so the reason (if there ever was one) is lost. Measured 2026-07-30 by temporarily removing that one element and running `mvn -pl graphitron-javapoet test`: 400 tests run, 400 pass, 1 skipped, about 6 seconds for the whole module. This is the javapoet fork's own regression suite for the code emitter every generated file passes through, sitting unrun for the price of one XML element.

Surfaced while specifying R25, whose baseline coverage run found the module producing no JaCoCo exec data. That looked like a wiring bug and was not: zero coverage was a correct measurement of zero executed code. R25 deliberately leaves this alone, since un-skipping changes what the build runs rather than what it measures.

The deliverable is deleting the element. The plan body should confirm the full reactor stays green with the suite enabled (including under CI's `-T 1C`), check whether the ~6 seconds is worth a `slow`-style opt-out (almost certainly not), and decide whether the module's tests need tier annotations, given that `TierAnnotationEnforcementTest` covers `graphitron` and `graphitron-sakila-example` but not this module.

