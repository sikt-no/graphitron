---
id: R807
title: "The diagnostic view spells Java class names that nothing binds to the classes"
status: Backlog
bucket: tech-debt
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# The diagnostic view spells Java class names that nothing binds to the classes

**The `Rejection.*` half of this item has dissolved into R803's implementation, exactly as
the sequencing note below predicted.** R803's message fork resolved onto the post-capture
arm: the claim-conflict arm's message is minted in Java into
`intent_authored_claim_rejection`, and the variant and kind minted with it, from
`RejectionFacts.classSpelling` and `RejectionKind.of` over the actual rejection value. Both
SQL literals are gone from the DDL, the spelling is now bound to the class through the one
Java site the residue writer already used, and a rename of a leaf carries into both
relations by construction. The gate this item's fallback proposed (every dotted
`Rejection.*` literal in the DDL resolves to a loadable class) has nothing left to guard,
and adding it would be an enforcer for an empty population.

What survives is the loose end at the bottom of this item: the syntax-error arm's
`'InvalidSyntaxException'` literal. That one carries no rename risk, being a third-party
name, so what is left here is a consistency question and not a correctness one. The rest of
this body is kept as the record of how the defect was found and why the test suite did not
catch it, which is still worth reading; treat the "What to do" section as answered.

The original statement follows.

The `diagnostic` view's claim-conflict arm writes two of our own class names as SQL string
literals, `'Rejection.Deferred'` and `'Rejection.InvalidSchema.DirectiveConflict'`, and
nothing connects either to the class it names. Rename the record and the SQL keeps the old
spelling. This is not a rendering problem and does not belong to R803's rule about a
collection serialized into a scalar; it is the "every invariant has an enforcer" axiom, and
it is the same shape as the eight-branch `CASE` ladder R803 removes for restating
`AuthoredClaim`'s declaration order.

## Why the test suite does not catch it

This is the part worth writing down, because the tests look like they cover it and do not.

The *residue* arm derives the same spelling rather than writing it:
`RejectionFacts.classSpelling` is
`cls.getCanonicalName().substring(cls.getPackageName().length() + 1)` over the rejection's
actual class. So the store holds two independent sources for one name: capture computes it,
the view's pilot arm hardcodes it.

`DiagnosticFactsTest` asserts the literal against both, in the same file:

* one assertion reads `REJECTION_VALIDATION_ERROR` and pins the *computed* spelling to the
  literal,
* another reads `DIAGNOSTIC` and pins the *hardcoded* spelling to the same literal.

Rename `Rejection.InvalidSchema.DirectiveConflict` in Java and follow what happens. The
computed assertion fails, so the rename is noticed. The natural repair is to update that
test's literal, since it is the one that broke. The view's SQL literal is untouched and its
assertion never failed, so nothing points at it. The build goes green with one test file
asserting two different spellings for one rejection family, and the two arms of a single
view disagreeing about what to call it. `RejectionResidueDrainageTest` holds a third copy of
the roster with the same property.

So the defect is not "an unpinned literal". It is a pin that anchors the wrong end: it holds
the SQL and the test together while letting both drift away from the class.

## What to do

Bind the spelling to the class. The obvious shape is for the pilot arm's variant to come
from `classSpelling` like the residue arm's does, which means it stops being a SQL literal
at all and follows the message wherever R803's fork puts it (if that arm mints post-capture
from Java, the variant mints with it and this item largely dissolves into R803's
implementation). If the pilot arm stays in SQL, the fallback is a gate asserting that every
dotted `Rejection.*` literal in the DDL resolves to a loadable class under the rejection
model's package, which is weaker but mechanical.

Sequence this behind R803 rather than beside it: which repair is right depends on the fork,
and doing it first would mean doing it twice.

## Scope: which literals are in and which are not

A scan of the DDL's statement regions (excluding `COMMENT ON` prose) finds ten dotted
string literals, in two families that need opposite treatment:

**In scope, our own vocabulary:** `Rejection.Deferred` and
`Rejection.InvalidSchema.DirectiveConflict` in the `diagnostic` view. We rename these
classes; the SQL cannot follow.

**Out of scope, and the item should say why rather than leave it ambiguous:**
`intent_delivery_container` is a `VALUES` table of `java.util.List`, `java.util.Set`,
`java.util.Collection`, `java.util.Optional`, `java.util.Map`,
`java.util.concurrent.CompletableFuture`, `org.jooq.Result` and `org.jooq.Record`, each
with its element index and whether it multiplies. Those are third-party names we do not
control and will never rename, and the relation is a lookup table of container semantics,
which makes the names data rather than a restatement of our own vocabulary. A gate that
caught these would be wrong.

## A loose end worth a look while in here

The syntax-error arm hardcodes `'InvalidSyntaxException'` where the sibling schema-error arm
projects `s.error_class` from its table. `graphql_syntax_error` has no `error_class` column,
which is presumably why. Two arms of one view answering the same question two ways is worth
either a column on the table or a sentence in the comment saying why the asymmetry is right.
Third-party name, so it does not carry the rename risk above; this is consistency, not
correctness.
