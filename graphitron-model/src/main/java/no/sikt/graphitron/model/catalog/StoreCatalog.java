package no.sikt.graphitron.model.catalog;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The one answer to "what relations exist": the booted store's own metadata plus the schema
 * self-description rows, read back as records. Every consumer that needs the relation census
 * (the generated schema reference, the docs drift guard) reads this class rather than querying
 * a second way, and the family assignment comes from the store's {@code meta_relation_family}
 * census view, never re-derived here, so two mechanisms of different fidelity can never answer
 * the question differently.
 *
 * <p>The records carry data only: names, comment text, keys, constraint clauses, all verbatim
 * from the engine. No rendering vocabulary (page names, markup, file layout) appears here; that
 * is the renderer's own business, and keeping it out is what lets more than one consumer share
 * the reader.
 *
 * <p>Ordering is fixed so consumers are deterministic without sorting again: families by their
 * authored ordinal, relations by name, columns in declaration order, key and constraint columns
 * in constraint order.
 */
public record StoreCatalog(List<Family> families, List<Exemption> exemptions,
                           List<Relation> relations) {

    /** One {@code meta_family} row: a relation-name prefix and its authored charter. */
    public record Family(String prefix, String title, int ordinal, String definition) {}

    /**
     * One {@code meta_prefixless_relation} row. An empty {@code page} is the authored statement
     * that the relation belongs on no family's page; where it renders instead is the consumer's
     * decision, not data this reader carries.
     */
    public record Exemption(String relationName, Optional<String> page, String reason) {}

    /** One relation of the store's schema, with its census verdict and physical shape. */
    public record Relation(String relationName, boolean view, String comment,
                           Optional<String> familyPrefix, boolean exempted, List<Column> columns,
                           List<String> primaryKey, List<ForeignKey> foreignKeys,
                           List<Check> checks) {}

    /** One column: engine type and nullability verbatim, comment text as authored. */
    public record Column(String columnName, String type, boolean nullable, String comment) {}

    /** One FOREIGN KEY: child columns, the referenced relation, its columns, in key order. */
    public record ForeignKey(List<String> columns, String referencedRelation,
                             List<String> referencedColumns) {}

    /** One CHECK constraint's clause as the engine renders it; NOT NULL never appears here. */
    public record Check(String clause) {}

    /**
     * Reads the whole catalog from a booted store in one pass. Deliberately plain-name jOOQ
     * rather than the generated table constants: the hand-written half of this module never
     * references its own generated half (the boot and codegen-driver classes for compile-order
     * reasons, this class so the javadoc reference gate resolves it from source alone), and the
     * spellings are pinned to the live schema by the gates and the reader's own tests.
     */
    public static StoreCatalog read(DSLContext dsl) {
        var families = dsl.select(field(name("PREFIX"), String.class),
                field(name("TITLE"), String.class),
                field(name("ORDINAL"), Integer.class),
                field(name("DEFINITION"), String.class))
            .from(table(name("META_FAMILY")))
            .orderBy(field(name("ORDINAL")))
            .fetch(r -> new Family(r.value1(), r.value2(), r.value3(), r.value4()));

        var exemptions = dsl.select(field(name("RELATION_NAME"), String.class),
                field(name("PAGE"), String.class),
                field(name("REASON"), String.class))
            .from(table(name("META_PREFIXLESS_RELATION")))
            .orderBy(field(name("RELATION_NAME")))
            .fetch(r -> new Exemption(r.value1(), Optional.ofNullable(r.value2()), r.value3()));

        var comments = relationComments(dsl);
        var columns = columnsByRelation(dsl);
        var primaryKeys = keyColumnsByRelation(dsl, "PRIMARY KEY");
        var foreignKeys = foreignKeysByRelation(dsl);
        var checks = checksByRelation(dsl);

        var relations = dsl.select(field(name("RELATION_NAME"), String.class),
                field(name("RELATION_TYPE"), String.class),
                field(name("PREFIX"), String.class),
                field(name("EXEMPTED"), Boolean.class))
            .from(table(name("META_RELATION_FAMILY")))
            .orderBy(field(name("RELATION_NAME")))
            .fetch(r -> new Relation(r.value1(), "VIEW".equals(r.value2()), comments.get(r.value1()),
                Optional.ofNullable(r.value3()), Boolean.TRUE.equals(r.value4()),
                columns.getOrDefault(r.value1(), List.of()),
                primaryKeys.getOrDefault(r.value1(), List.of()),
                foreignKeysByName(foreignKeys, r.value1()),
                checks.getOrDefault(r.value1(), List.of())));

        return new StoreCatalog(families, exemptions, relations);
    }

    private static Map<String, String> relationComments(DSLContext dsl) {
        var comments = new LinkedHashMap<String, String>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .forEach(r -> comments.put(lower(r.value1()), r.value2()));
        return comments;
    }

    private static Map<String, List<Column>> columnsByRelation(DSLContext dsl) {
        var columns = new LinkedHashMap<String, List<Column>>();
        dsl.select(field(name("TABLE_NAME"), String.class),
                field(name("COLUMN_NAME"), String.class),
                field(name("DATA_TYPE"), String.class),
                field(name("IS_NULLABLE"), String.class),
                field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("TABLE_NAME")), field(name("ORDINAL_POSITION")))
            .forEach(r -> columns.computeIfAbsent(lower(r.value1()), k -> new ArrayList<>())
                .add(new Column(lower(r.value2()), r.value3(), "YES".equals(r.value4()), r.value5())));
        return columns;
    }

    private static Map<String, List<String>> keyColumnsByRelation(DSLContext dsl, String type) {
        var keys = new LinkedHashMap<String, List<String>>();
        dsl.select(field(name("TC", "TABLE_NAME"), String.class),
                field(name("KCU", "COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("TC"))
            .join(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("KCU"))
            .on(field(name("TC", "CONSTRAINT_NAME"), String.class)
                .eq(field(name("KCU", "CONSTRAINT_NAME"), String.class)))
            .where(field(name("TC", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TC", "CONSTRAINT_TYPE"), String.class).eq(type))
            .orderBy(field(name("TC", "TABLE_NAME")), field(name("KCU", "ORDINAL_POSITION")))
            .forEach(r -> keys.computeIfAbsent(lower(r.value1()), k -> new ArrayList<>())
                .add(lower(r.value2())));
        return keys;
    }

    /** Child relation name to its FOREIGN KEYs, resolved through the referenced unique key. */
    private static Map<String, List<ForeignKey>> foreignKeysByRelation(DSLContext dsl) {
        record Side(String relation, List<String> columns) {}
        var constraintSides = new LinkedHashMap<String, Side>();
        dsl.select(field(name("KCU", "CONSTRAINT_NAME"), String.class),
                field(name("KCU", "TABLE_NAME"), String.class),
                field(name("KCU", "COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "KEY_COLUMN_USAGE")).as("KCU"))
            .where(field(name("KCU", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("KCU", "CONSTRAINT_NAME")), field(name("KCU", "ORDINAL_POSITION")))
            .forEach(r -> constraintSides
                .computeIfAbsent(r.value1(), k -> new Side(lower(r.value2()), new ArrayList<>()))
                .columns().add(lower(r.value3())));

        var foreignKeys = new LinkedHashMap<String, List<ForeignKey>>();
        dsl.select(field(name("RC", "CONSTRAINT_NAME"), String.class),
                field(name("RC", "UNIQUE_CONSTRAINT_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "REFERENTIAL_CONSTRAINTS")).as("RC"))
            .where(field(name("RC", "CONSTRAINT_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("RC", "CONSTRAINT_NAME")))
            .forEach(r -> {
                Side child = constraintSides.get(r.value1());
                Side parent = constraintSides.get(r.value2());
                foreignKeys.computeIfAbsent(child.relation(), k -> new ArrayList<>())
                    .add(new ForeignKey(List.copyOf(child.columns()), parent.relation(),
                        List.copyOf(parent.columns())));
            });
        return foreignKeys;
    }

    private static Map<String, List<Check>> checksByRelation(DSLContext dsl) {
        var checks = new LinkedHashMap<String, List<Check>>();
        dsl.select(field(name("TC", "TABLE_NAME"), String.class),
                field(name("CC", "CHECK_CLAUSE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("TC"))
            .join(table(name("INFORMATION_SCHEMA", "CHECK_CONSTRAINTS")).as("CC"))
            .on(field(name("TC", "CONSTRAINT_NAME"), String.class)
                .eq(field(name("CC", "CONSTRAINT_NAME"), String.class)))
            .where(field(name("TC", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TC", "CONSTRAINT_TYPE"), String.class).eq("CHECK"))
            .orderBy(field(name("TC", "TABLE_NAME")), field(name("TC", "CONSTRAINT_NAME")))
            .forEach(r -> checks.computeIfAbsent(lower(r.value1()), k -> new ArrayList<>())
                .add(new Check(r.value2())));
        return checks;
    }

    private static List<ForeignKey> foreignKeysByName(Map<String, List<ForeignKey>> keys,
                                                      String relation) {
        return keys.getOrDefault(relation, List.of());
    }

    private static String lower(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }
}
