package no.sikt.graphitron.rewrite.model;

/**
 * A {@link ChildField} whose {@code $fields} projection is aliased by the runtime <em>result
 * key</em> rather than the schema field name, and whose fetcher reads that value back by the same
 * key. Implemented by the four families that mint an aliased SELECT term per result-key bucket:
 * {@link ChildField.TableField}, {@link ChildField.LookupTableField},
 * {@link ChildField.ComputedField}, and {@link ChildField.ColumnReferenceField}.
 *
 * <p>Result-key aliasing (through the reserved {@code __rk_} prefix,
 * {@code GeneratorUtils.RESERVED_RK_ALIAS_PREFIX}) is what lets two aliases of the same reference
 * ({@code a: ref { x } b: ref { y }}) mint two distinct SQL aliases instead of colliding on one
 * field-named alias; the read side re-derives the alias via
 * {@code env.getField().getResultKey()}. The write arms (in
 * {@code no.sikt.graphitron.rewrite.generators.TypeClassGenerator} and the inline emitters) and the
 * read bindings (in {@code no.sikt.graphitron.rewrite.generators.FetcherEmitter}) are two
 * hand-enumerated sets that must agree: a variant that projects under a result-key alias but forgets
 * the matching env-dependent read (or vice versa) reincarnates the aliased-duplicate defect.
 *
 * <p>This marker single-homes that membership so the agreement is enforced rather than reviewed:
 * both the write-side {@code default} arm and the read-side method-backed fall-through throw when a
 * {@code ResultKeyAliasedField} reaches them unhandled, so a new alias-projecting variant is a loud
 * build-time failure on whichever side it forgot. The marker carries no method: the alias basis is
 * entirely runtime-keyed (the result key), with no per-variant model value to expose. The scalar
 * {@link ChildField.ColumnField} / {@link ChildField.CompositeColumnField} arms are deliberately
 * <em>not</em> members: they add raw {@code table.COL} instances (alias-independent, deduped by jOOQ
 * {@code Field} identity) and read back through typed column constants.
 *
 * <p>Intentionally standalone (does not extend {@link GraphitronField}) so it applies as an
 * orthogonal capability without being restricted by the sealed hierarchy, mirroring
 * {@link SqlGeneratingField} / {@link MethodBackedField}. Consumers receive a {@link ChildField} and
 * pattern-match with {@code instanceof ResultKeyAliasedField}.
 *
 * <p>{@link ChildField.ColumnReferenceField} is a member on every emittable instance: only its
 * {@code CallSiteCompaction.Direct} compaction projects and reads a scalar aliased subquery, and the
 * {@code NodeIdEncodeKeys} compaction is rejected at validate time
 * ({@code GraphitronSchemaValidator.validateColumnReferenceField}, a deferred rejection), so a
 * {@code NodeIdEncodeKeys} column-reference never reaches emission on a valid schema.
 */
public interface ResultKeyAliasedField {
}
