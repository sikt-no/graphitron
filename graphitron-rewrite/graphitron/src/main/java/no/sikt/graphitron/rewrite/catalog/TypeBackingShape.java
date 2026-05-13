package no.sikt.graphitron.rewrite.catalog;

import java.util.List;

/**
 * LSP-facing projection of a {@link no.sikt.graphitron.rewrite.model.GraphitronType}'s
 * backing shape: what the {@code @field(name:)} validator and completion arm
 * resolves the SDL author's component name against. Carried alongside
 * {@link DirectiveShape} on {@link LspSchemaSnapshot.Built}.
 *
 * <p>Five permits, one per distinguishable downstream behaviour:
 * <ul>
 *   <li>{@link RecordBacking} — backing class is a Java {@code record}; member
 *       list is the record's components.</li>
 *   <li>{@link PojoBacking} — backing class is a plain Java class; member list
 *       is the bean-accessor projection of its public method set.</li>
 *   <li>{@link JooqRecordBacking} — backing class is a jOOQ {@code Record}
 *       subclass; the column-set lookup goes through the existing
 *       {@link CompletionData#getTable} path keyed by table name.</li>
 *   <li>{@link TableBacking} — any GraphitronType carrying a jOOQ table binding
 *       (including {@code @table} interfaces). Same data path as
 *       {@link JooqRecordBacking}: keyed by table name.</li>
 *   <li>{@link NoBacking} — sealed sub-taxonomy over three observably-different
 *       diagnostic cases. A {@code @field} site under any of them produces no
 *       completions; the diagnostic arm picks its hint from the sub-permit.</li>
 * </ul>
 */
public sealed interface TypeBackingShape
    permits TypeBackingShape.RecordBacking,
            TypeBackingShape.PojoBacking,
            TypeBackingShape.JooqRecordBacking,
            TypeBackingShape.TableBacking,
            TypeBackingShape.NoBacking {

    /**
     * One member of a record- or POJO-backed type. {@code name} is the
     * component / accessor name the SDL author writes into
     * {@code @field(name:)}; {@code displayType} is the rendered Java type
     * (e.g. {@code "String"}, {@code "Integer"}, {@code "List<String>"}) used
     * in hover output.
     */
    record MemberSlot(String name, String displayType) {}

    /**
     * Type backed by a Java {@code record} class. {@code fqClassName} is the
     * binary class name; {@code components} is the record's component list in
     * declaration order.
     */
    record RecordBacking(String fqClassName, List<MemberSlot> components) implements TypeBackingShape {
        public RecordBacking {
            components = List.copyOf(components);
        }
    }

    /**
     * Type backed by a plain Java class. {@code accessors} is the bean-shape
     * projection of the public method set: each {@code get<X>} / {@code is<X>}
     * no-arg method yields a slot named {@code x} (first letter lowercased) of
     * the method's return type.
     */
    record PojoBacking(String fqClassName, List<MemberSlot> accessors) implements TypeBackingShape {
        public PojoBacking {
            accessors = List.copyOf(accessors);
        }
    }

    /**
     * Type backed by a jOOQ {@code Record<?>} subclass. Sealed over whether
     * the classifier carries a {@link no.sikt.graphitron.rewrite.model.TableRef}
     * for the record: {@link WithTable} routes column-set lookup through
     * {@link CompletionData#getTable}; {@link Standalone} declines (no
     * actionable column metadata available).
     */
    sealed interface JooqRecordBacking extends TypeBackingShape
        permits JooqRecordBacking.WithTable, JooqRecordBacking.Standalone {

        String fqClassName();

        /**
         * jOOQ record bound to a specific table — the classifier carried a
         * {@link no.sikt.graphitron.rewrite.model.TableRef}. {@code tableName}
         * is the jOOQ table name for column lookup.
         */
        record WithTable(String fqClassName, String tableName) implements JooqRecordBacking {}

        /**
         * jOOQ record without a table binding — typically a custom
         * {@code Record<?>} subclass authored by the consumer outside the
         * jOOQ-generated table set. No column candidates available.
         */
        record Standalone(String fqClassName) implements JooqRecordBacking {}
    }

    /**
     * Type bound to a jOOQ table — covers {@code @table}-bearing objects,
     * {@code @table}-bearing interfaces, {@code @node} types, and table-backed
     * input objects. {@code tableName} is the jOOQ table name.
     */
    record TableBacking(String tableName) implements TypeBackingShape {}

    /**
     * Sealed sub-taxonomy for types with no backing-class projection. Each
     * arm carries the same observational behaviour today (empty completions,
     * no diagnostic on {@code @field(name:)}), but the diagnostic arm reads
     * the permit identity to pick its hint when it surfaces the failure mode
     * to the author.
     */
    sealed interface NoBacking extends TypeBackingShape
        permits NoBacking.Root, NoBacking.UnbackedResult, NoBacking.UnclassifiedInterface {

        /**
         * A root operation type (Query, Mutation, Subscription). A
         * {@code @field(name:)} site under a root is a category error;
         * the diagnostic hint is "the directive applies on object fields, not
         * on operations".
         */
        record Root() implements NoBacking {}

        /**
         * A result type whose classifier produced no backing: union types, an
         * {@code @record}-declared type whose {@code className} was unset, or
         * a plain SDL object. The diagnostic hint is "add {@code @record} or
         * {@code @table}".
         */
        record UnbackedResult() implements NoBacking {}

        /**
         * An interface with no {@code @table} discriminator. The
         * {@code @field(name:)} arm cannot resolve until the author picks an
         * implementing type; the diagnostic stays silent (no actionable hint
         * available yet).
         */
        record UnclassifiedInterface() implements NoBacking {}
    }
}
