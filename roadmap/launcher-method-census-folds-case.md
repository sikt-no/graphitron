---
id: R889
title: "The launcher-method census folds case, rejecting a valid deprecated-alias field pair"
status: Backlog
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# The launcher-method census folds case, rejecting a valid deprecated-alias field pair

A consumer schema that declares two sibling fields whose names differ only in the case of a letter after the first is rejected at generation, with a located error saying the two coordinates mint one launcher method. They do not. The two emitted methods are `rowsUndervisningStartterminIPeriode` and `rowsUndervisningStartterminIperiode`, which are two distinct and perfectly legal Java methods; nothing would collide at emission. They are rejected only because the census that guards launcher-method uniqueness lowercases its whole key before comparing.

A *launcher method* is the generated method that owns one root or batched-child coordinate's whole query composition, hosted on the coordinate's fetchers class. Its name is minted by a naming formula in `GeneratedUnits` (`rows<Field>`, `load<Field>`, `lookup<Field>`) and a *census* is the uniqueness check over those minted names, which exists because the formula is not injective: it upper-camels the field name, so the two fields `fooBar` and `FooBar` genuinely mint one method, `rowsFooBar`. The census lives at two sites that must agree, or validation and generation drift apart: `LauncherRelation`'s compact constructor (the relation's construction-time integrity check, whose failure is a hard throw) and `GraphitronSchemaValidator.validateLauncherMethodNames` reading `LauncherCommands.methodCollisions` (the authored-schema mirror, which is what an author sees, located at the colliding declarations).

The folding overshoots because `GeneratedUnits.upperCamel` changes the **first** letter and nothing else. Comparing the emitted method name exactly, still scoped per owning class, catches every genuine collision the formula can produce: two fields that mint one name mint the *identical* string, and identical strings compare equal case-sensitively too. What the fold adds beyond that is only the rejection of names that differ somewhere after the first letter, which is exactly the false positive.

The fold appears to have been inherited rather than reasoned. Its javadoc cites "the projection producer's address-census precedent", `ProjectionRelation`, which folds a fully-qualified class name. For class addresses the fold is justified: a generated class name becomes a file name, and a case-insensitive filesystem (macOS, Windows) would genuinely collide. Method names are never files, so that rationale does not carry over. `RoutineWriteRelationTest.coordinatesDifferingOnlyInCaseMintDistinctMethodsAndAreBothAdmitted` already pins the case-sensitive reading for the sibling relation whose formula *is* injective, and states in prose the belief this item corrects: that the non-injective formula is what forces the other relations to fold.

The schema being rejected is a deprecated-alias pair, a typo'd field kept alive beside its corrected spelling with a `@deprecated` reason carrying a published removal date. Fixing it consumer-side would mean retiring a field ahead of a date promised to API clients, to work around a check that is rejecting a valid schema, and keep-the-typo-as-a-deprecated-alias is a pattern any consumer may reach for, so the rejection will recur.

## Plan

1. `LauncherRelation`'s compact constructor: key the census on `owner().fqcn() + "#" + methodName()` with no fold. Rewrite the message so it no longer says "case-folded" and no longer implies case is what collided.
2. `LauncherCommands.methodCollisions`: same change to the grouping key, so the validator mirror agrees with the relation. `MethodCollision.foldedKey` is then misnamed and should become the exact key.
3. `GraphitronSchemaValidator.validateLauncherMethodNames`: update the rejection text, which currently says "(case-folded)".
4. Correct the two javadoc blocks that state the fold and its borrowed rationale (the class comment on `LauncherRelation`, the method comment on `methodCollisions`), and the sentence in `RoutineWriteRelationTest`'s javadoc asserting that the sibling relations need the fold.
5. Tests, both halves at both sites: a pair differing after the first letter (`undervisningStartterminIPeriode` / `undervisningStartterminIperiode`) admits and generates two methods; a pair differing in the first letter (`fooBar` / `FooBar`) still fails, at the relation with the hard throw and at the validator with the located rejection. Neither half is currently covered by a test.

## Verification that nothing downstream reads the name case-insensitively

Checked before writing the plan, because a second unstated reason for the fold would mean a narrower fix:

- **DataLoader names.** Derived from the GraphQL execution path, not from method names: `String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`, inline at single-tenant registration sites and through `TenantConnections.loaderName` / `tenantLoaderName` for multi-tenant ones (`TenantDslEmitter.loaderNameDeclaration`). Case-sensitive, and unrelated to the minted method name.
- **Registries.** No map, set, or store key in the in-scope modules folds a method name. The complete case-folding population in `graphitron`'s main sources is: the two launcher census sites at issue, the two projection address sites (class names, justified), SQL identifier folding in the catalog and the fact store (Postgres identifiers, unrelated), enum-name rendering, alias prefixes in `PathFragments`, and Levenshtein suggestion matching in `Rejection` / `BuildContext`.
- **Per-method emitted artifacts.** None. Launcher methods are added to their owner's `TypeSpec` by name (`MethodSpec.methodBuilder(row.unit().methodName())`); emitted files are per class, and class addresses keep their own folded census.

