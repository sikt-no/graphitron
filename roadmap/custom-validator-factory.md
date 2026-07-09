---
id: R192
title: "Mojo-configured custom Bean Validation factory"
status: Backlog
bucket: architecture
priority: 6
theme: mutation-write
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Mojo-configured custom Bean Validation factory

Originally drafted as part of R45 (`tenant-routing-and-execution-input.md`) and inherited by R190 (`single-tenant-execution-input-factory.md`); carved out because the validator-override mechanism is independent of those items' surface narrowing. The generated `GraphitronContext` impl's `getValidator(env)` returns `Validation.buildDefaultValidatorFactory().getValidator()`; consumers who need a custom `Validator` (custom `ConstraintValidator` implementations, alternative providers, CDI integration) have no seam today and would have to reach for the legacy `GraphitronContext` interface that R190 seals. The proposed shape is a new Mojo element naming a consumer-supplied factory class whose instance graphitron calls per request: this pushes against R45's "extension points that don't pay for the openness" critique one level down, since the override hook is itself a per-request consumer-implemented surface. The Spec author must justify whether the override is per-request (functional interface, `(DataFetchingEnvironment) -> Validator`) or per-build (configured class graphitron instantiates once and calls `.getValidator()` on), and what the migration story is for consumers currently overriding `GraphitronContext.getValidator(env)` in the legacy generator. Depends on R190 landing first so the sealed-context method set is the baseline this item widens.
