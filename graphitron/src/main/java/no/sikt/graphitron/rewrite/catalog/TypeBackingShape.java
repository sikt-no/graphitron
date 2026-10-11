package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the walk bound a {@link no.sikt.graphitron.rewrite.model.GraphitronType} to, in the shape
 * the backing-class transcription writes into the store's walk shadow, projected by
 * {@link #byTypeName}. The language server read it through a snapshot until every surface asking it
 * moved to the store; only the backing differential reads it now, comparing the walk's answer with
 * the store's.
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
     * which the store answers from its reading of the classfiles (the {@code code_read_slot}
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
     * for the record: {@link WithTable} names the table, {@link Standalone} has none.
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
     * {@code @node} types, and table-backed input objects, named by the jOOQ table.
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

    /**
     * Each type of the walk's schema projected into its shape. The dispatch is exhaustive on the
     * {@code GraphitronType} sealed permits, so a new variant is a compile error here.
     */
    static Map<String, TypeBackingShape> byTypeName(GraphitronSchema schema) {
        var out = new LinkedHashMap<String, TypeBackingShape>();
        for (var entry : schema.types().entrySet()) {
            out.put(entry.getKey(), projectType(entry.getValue()));
        }
        return Map.copyOf(out);
    }

    private static TypeBackingShape projectType(GraphitronType type) {
        return switch (type) {
            case GraphitronType.JavaRecordType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.JavaRecordInputType t -> new TypeBackingShape.RecordBacking(t.fqClassName());
            case GraphitronType.PojoResultType.Backed t -> new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.PojoInputType t -> t.fqClassName() == null
                ? new TypeBackingShape.NoBacking.UnbackedResult()
                : new TypeBackingShape.PojoBacking(t.fqClassName());
            case GraphitronType.JooqRecordType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqRecordInputType t -> new TypeBackingShape.JooqRecordBacking.Standalone(t.fqClassName());
            case GraphitronType.JooqTableRecordType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.JooqTableRecordInputType t -> jooqRecordWithTable(t.fqClassName(), t.table());
            case GraphitronType.TableType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.NodeType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.TableInterfaceType t -> new TypeBackingShape.TableBacking(tableNameOf(t.table()));
            case GraphitronType.RootType ignored -> new TypeBackingShape.NoBacking.Root();
            case GraphitronType.InterfaceType ignored -> new TypeBackingShape.NoBacking.UnclassifiedInterface();
            case GraphitronType.UnionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ErrorType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EnumType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ScalarType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.ConnectionType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.EdgeType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.PageInfoType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetsType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.FacetValueType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.NestingType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
            case GraphitronType.UnclassifiedType ignored -> new TypeBackingShape.NoBacking.UnbackedResult();
        };
    }

    private static String tableNameOf(TableRef ref) {
        return ref == null ? null : ref.tableName();
    }

    private static TypeBackingShape jooqRecordWithTable(String fqClassName, TableRef table) {
        String tableName = tableNameOf(table);
        return tableName == null
            ? new TypeBackingShape.JooqRecordBacking.Standalone(fqClassName)
            : new TypeBackingShape.JooqRecordBacking.WithTable(fqClassName, tableName);
    }
}
