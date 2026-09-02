package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.model.classpath.CompletionData;

/**
 * What the walk bound a {@link no.sikt.graphitron.rewrite.model.GraphitronType} to, in the shape
 * the backing-class transcription writes into the store's walk shadow. Produced by
 * {@link CatalogBuilder#projectTypesByName}, which the language server read through the snapshot
 * until every surface asking it moved to the store.
 *
 * <p>Five permits, one per distinguishable downstream behaviour. Each names what backs the type and
 * nothing more: what a backing then offers a member name is a fact about a class or a table, read
 * from the store by whoever asks, so no permit carries a projected member list.
 */
public sealed interface TypeBackingShape
    permits TypeBackingShape.RecordBacking,
            TypeBackingShape.PojoBacking,
            TypeBackingShape.JooqRecordBacking,
            TypeBackingShape.TableBacking,
            TypeBackingShape.NoBacking {

    /**
     * Type backed by a Java {@code record} class, named by its binary class name. What the class
     * offers a member name is not carried here: the record's components are a fact about the class,
     * which the store answers from the classpath census (the {@code intent_class_member_slot}
     * relation), so a consumer holding this name reads them there rather than off a list projected
     * per build.
     */
    record RecordBacking(String fqClassName) implements TypeBackingShape {}

    /**
     * Type backed by a plain Java class, named by its binary class name. Its members are the bean
     * accessors the store's member-slot relation derives from the census, on the same terms as
     * {@link RecordBacking}'s components; the bean rule itself lives in that relation and nowhere
     * else.
     */
    record PojoBacking(String fqClassName) implements TypeBackingShape {}

    /**
     * Type backed by a jOOQ {@code Record<?>} subclass. Sealed over whether
     * the classifier carries a {@link no.sikt.graphitron.model.jooq.TableRef}
     * for the record: {@link WithTable} routes column-set lookup through
     * {@link CompletionData#getTable}; {@link Standalone} declines (no
     * actionable column metadata available).
     */
    sealed interface JooqRecordBacking extends TypeBackingShape
        permits JooqRecordBacking.WithTable, JooqRecordBacking.Standalone {

        String fqClassName();

        /**
         * jOOQ record bound to a specific table (the classifier carried a
         * {@link no.sikt.graphitron.model.jooq.TableRef}). {@code tableName}
         * is the jOOQ table name for column lookup.
         */
        record WithTable(String fqClassName, String tableName) implements JooqRecordBacking {}

        /**
         * jOOQ record without a table binding, typically a custom
         * {@code Record<?>} subclass authored by the consumer outside the
         * jOOQ-generated table set. No column candidates available.
         */
        record Standalone(String fqClassName) implements JooqRecordBacking {}
    }

    /**
     * Type bound to a jOOQ table: {@code @table}-bearing objects and interfaces,
     * {@code @node} types, and table-backed input objects. Column lookup goes
     * through {@link CompletionData#getTable} keyed by {@code tableName}.
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
         * A result type with no backing-class projection: unions, errors,
         * enums, scalars, connection / edge / PageInfo wrappers, nesting types,
         * an unclassified type, or an input object whose backing class did not
         * resolve. None carry a component / accessor list a {@code @field(name:)}
         * site could resolve against, so the {@code @field} arm produces no
         * completions. A plain SDL object does not land here: it either binds
         * by reflection or classifies as an {@code UnclassifiedType}.
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
