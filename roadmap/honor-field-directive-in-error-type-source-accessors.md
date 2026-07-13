---
id: R202
title: "Honor @field(name:) in @error type extra-field accessor matching against handler source class"
status: Backlog
bucket: bug
priority: 5
theme: error-channel
depends-on: []
created: 2026-05-20
last-updated: 2026-07-13
---

# Honor @field(name:) in @error type extra-field accessor matching against handler source class

`FieldBuilder.checkErrorTypeSourceAccessors` (`FieldBuilder.java:3174-3209`; called at `:2949`) verifies that each `@error` object type's extra fields (everything except `path` / `message`) can be populated from the handler's source class (the exception class for a `GENERIC` handler, the `DataAccessException`-shaped source for a `DATABASE` handler, etc.). For each `(sdlField, sourceClass)` pair it calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), expectedReturn, …)` (`:3193-3198`) with the raw SDL name as the accessor base. When the exception's accessor diverges from the SDL field name, e.g. an exception exposing `getErrorCode()` mapped to an SDL field named `code`, or a Norwegian-named accessor under an English SDL, the resolver returns `Rejected` and the type fails classification with no author escape hatch. `@field(name:)` on the SDL extra field is the natural override: this is structurally the R191 case (free-form Java class as the logical "parent", SDL field bound by accessor name, divergent names need a directive remap). The fix is to read `DIR_FIELD` on each extra `sdlField` in the per-field loop at `:3191-3198` and pass the directive value (when present) as the second arg to `ClassAccessorResolver.resolve` instead of `sdlField.getName()`. The directive's docstring already covers this site under the "underlying-binding target" reading; the docstring update tracked separately should mention `@error` extra fields as one of the free-form-Java-class accessor-axis examples. No emitter change needed: `ClassAccessorResolver.Resolved.methodName()` is the actual reflected method name, so the runtime invocation path is unaffected. R461 (accessor-resolution unification behind `ClassAccessorResolver.enumerate`, In Review as of 2026-07-13) touched `ClassAccessorResolver.resolve` (signature unchanged; it gained the bridge/synthetic/`Object`-declaration filter) but does not read `@field(name:)`; the remap this item wants happens upstream of the candidate enumeration.
