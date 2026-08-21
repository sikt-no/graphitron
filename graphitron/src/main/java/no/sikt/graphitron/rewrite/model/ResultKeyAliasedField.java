package no.sikt.graphitron.rewrite.model;

/**
 * A {@link ChildField} whose {@code $project} projection is aliased by the runtime <em>result
 * key</em> rather than the schema field name, and whose fetcher reads that value back by the same
 * key. Implemented by the four families that mint an aliased SELECT term per result-key bucket:
 * {@link ChildField.TableField} (lookup-keyed or not), {@link ChildField.PivotField},
 * {@link ChildField.ComputedField}, and {@link ChildField.ColumnBackedReferenceField}.
 *
 * <p>Result-key aliasing (the reserved {@code __rk_} prefix,
 * {@code GeneratorUtils.RESERVED_RK_ALIAS_PREFIX}) lets two aliases of the same reference
 * ({@code a: ref { x } b: ref { y }}) mint two distinct SQL aliases instead of colliding on one
 * field-named alias; the read side re-derives the alias via
 * {@code env.getField().getResultKey()}. The write arms (in
 * the projection producer and renderer) and the
 * read bindings (in {@code no.sikt.graphitron.rewrite.generators.FetcherEmitter}) are two
 * hand-enumerated sets that must agree: a variant that projects under a result-key alias without
 * the matching env-dependent read (or vice versa) collides aliased duplicates.
 *
 * <p>This marker single-homes that membership so the agreement is enforced rather than reviewed:
 * both the write-side {@code default} arm and the read-side method-backed fall-through throw when a
 * {@code ResultKeyAliasedField} reaches them unhandled, so a new alias-projecting variant fails
 * loudly at build time on whichever side forgot it. The scalar
 * {@link ChildField.ColumnBackedField} arm is deliberately <em>not</em> a member: it adds raw
 * {@code table.COL} instances (alias-independent, deduped by jOOQ {@code Field} identity) and
 * reads back through typed column constants.
 *
 * <p>The runtime result key is not the whole alias basis. A single-table discriminated interface's
 * query folds every participant's {@code $project} into one {@code LinkedHashSet<Field<?>>}, and
 * an aliased jOOQ field compares equal on its alias alone, so two participants declaring a
 * same-named field over different join paths would mint one alias and silently drop the second
 * term. {@link #aliasOwner()} is the per-instance verdict that closes that: which name qualifies
 * the alias, decided once at capture off {@code (declaring type, field name)}. Homing the accessor
 * on this marker is what makes the namespace decision enforced rather than reviewed, on the same
 * discipline as the membership guards above: the marker is exactly the membership of the
 * alias-minting families, so a new family fails compilation until it declares its verdict.
 *
 * <p>Standalone (does not extend {@link GraphitronField}) so it applies as an orthogonal
 * capability without being restricted by the sealed hierarchy, mirroring
 * {@link SqlGeneratingField} / {@link MethodBackedField}. Consumers receive a {@link ChildField} and
 * pattern-match with {@code instanceof ResultKeyAliasedField}.
 *
 * <p>{@link ChildField.ColumnBackedReferenceField} is a member on every emittable instance: only
 * its {@code CallSiteCompaction.Direct} compaction projects and reads a scalar aliased subquery;
 * the {@code NodeIdEncodeKeys} compaction is rejected at validate time regardless of arity
 * ({@code GraphitronSchemaValidator.validateColumnBackedReferenceField}), so it never reaches
 * emission on a valid schema, and by the carrier's constructor invariant every {@code Direct}
 * instance is single-column.
 */
public interface ResultKeyAliasedField {

    /**
     * Which name owns this field's result-key alias namespace. Both halves of the alias read this
     * one stamped value: the projection renderer composes the emitted prefix from it, and the
     * fetcher's read composes the same prefix from the same value off the same field instance.
     */
    AliasOwner aliasOwner();
}
