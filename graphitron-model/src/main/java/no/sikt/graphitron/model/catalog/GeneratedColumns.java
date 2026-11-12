package no.sikt.graphitron.model.catalog;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Which columns of a relation the database computes: the columns declared
 * {@code GENERATED ALWAYS AS}, which no writer may name.
 *
 * <p>Asked of the database, on {@link GraphPartition}'s terms, because the generated model cannot
 * say: the code generator models H2's computed columns as ordinary fields, so no property of a
 * generated table tells them apart. A writer that copies every field of its target, as
 * {@code OccurrenceProducers.refresh} does, asks here which to leave out.
 *
 * <p>Read once per JVM and held. The answer is a property of the DDL, not of any store's rows, and
 * every store a JVM opens was created from the one DDL on its classpath: the store keeps its file
 * under a segment carrying its DDL hash and stamps the hash into {@code store_stamp}, so a store
 * from another DDL is never opened beside it. One query, then, and none per caller or per pass.
 * The answer is the table's own fields, so the folding between H2's catalog spelling and the
 * generated model's happens here and in no caller; a table with no generated column answers the
 * empty set.
 */
public final class GeneratedColumns {

    private static volatile Map<String, Set<String>> byTable;

    private GeneratedColumns() {}

    /** The fields of {@code table} the database generates. */
    public static Set<Field<?>> of(DSLContext dsl, Table<?> table) {
        var generated = all(dsl).getOrDefault(table.getName().toUpperCase(Locale.ROOT), Set.of());
        return java.util.Arrays.stream(table.fields())
            .filter(field -> generated.contains(field.getName().toUpperCase(Locale.ROOT)))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Set<String>> all(DSLContext dsl) {
        var known = byTable;
        if (known != null) {
            return known;
        }
        var read = new HashMap<String, Set<String>>();
        for (var row : dsl.select(field(name("TABLE_NAME"), String.class),
                    field(name("COLUMN_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("IS_GENERATED"), String.class).eq("ALWAYS"))
                .fetch()) {
            read.computeIfAbsent(row.value1(), ignored -> new HashSet<>()).add(row.value2());
        }
        var held = new HashMap<String, Set<String>>();
        read.forEach((relation, columns) -> held.put(relation, Set.copyOf(columns)));
        byTable = Map.copyOf(held);
        return byTable;
    }
}
