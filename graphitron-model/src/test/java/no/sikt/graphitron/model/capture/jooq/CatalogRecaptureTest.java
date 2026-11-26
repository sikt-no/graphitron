package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_REACH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A catalog read again after its keys changed under their names, as a migration rewritten before it
 * was deployed changes them: a foreign key pointed at another table with another number of columns,
 * a unique key given another column, and a primary key and a unique key trading their kinds. The
 * second reading lands on the store the first one filled, and the store then holds what a store
 * reading only the second would.
 *
 * <p>The catalog is compiled from source into one directory and rewritten in place between the two
 * readings, so both are the same classpath entry and so the same catalog source, as in a dev loop. A
 * scenario keeps its catalog between steps, so no document can state a catalog that changed between
 * two readings of one store, and the failure lives only in the second reading of a store the first one
 * filled. So this reads twice into the thread's shared store under a graph and a catalog source of its
 * own, and compares with a fresh reading of the changed catalog in the same store, under a second
 * graph and a second source, its rows told apart by source.
 */
class CatalogRecaptureTest {

    private static final String PACKAGE = "no.sikt.graphitron.model.capture.jooq.recapture";

    private static final String GRAPH = "CatalogRecaptureTest";

    /** The graph the fresh reading of the changed catalog lands under, beside the recaptured one. */
    private static final String FRESH_GRAPH = "CatalogRecaptureTest-fresh";

    private static final List<Table<?>> COMPARED = List.of(SQL_CONSTRAINT, SQL_CONSTRAINT_COLUMN,
        SQL_PRIMARY_KEY, SQL_REFERENTIAL_CONSTRAINT, SQL_REFERENTIAL_CONSTRAINT_COLUMN,
        SQL_REFERENTIAL_CONSTRAINT_REACH);

    @Test
    @DisplayName("a catalog whose keys changed under their names is read again into the store the first reading filled")
    void keysChangedUnderTheirNamesAreReadAgain(@TempDir Path tmp) {
        Path entry = tmp.resolve("catalog");
        Path freshEntry = tmp.resolve("fresh-catalog");
        var recaptured = new java.util.LinkedHashMap<Table<?>, List<String>>();
        var fresh = new java.util.LinkedHashMap<Table<?>, List<String>>();
        SeededStore.withSeededStore(GRAPH, dsl -> {
            compile(entry, first(), tmp.resolve("sources-1"));
            capture(dsl, GRAPH, entry, LocalDateTime.of(2026, 11, 25, 10, 0));
            assertThat(rows(dsl, entry).get(SQL_REFERENTIAL_CONSTRAINT))
                .as("the first reading's foreign key references the four-column element")
                .anyMatch(row -> row.contains("melding_element_fk") && row.contains("utsendingsbunt_element"));

            compile(entry, second(), tmp.resolve("sources-2"));
            capture(dsl, GRAPH, entry, LocalDateTime.of(2026, 11, 25, 11, 0));
            recaptured.putAll(rows(dsl, entry));

            SeededStore.seedGraph(dsl, FRESH_GRAPH);
            compile(freshEntry, second(), tmp.resolve("sources-3"));
            capture(dsl, FRESH_GRAPH, freshEntry, LocalDateTime.of(2026, 11, 25, 12, 0));
            fresh.putAll(rows(dsl, freshEntry));
        });
        for (var table : COMPARED) {
            assertThat(recaptured.get(table)).as("%s after reading the changed catalog again", table.getName())
                .containsExactlyElementsOf(fresh.get(table));
        }
        assertThat(recaptured.get(SQL_REFERENTIAL_CONSTRAINT))
            .as("the foreign key now references the three-column element")
            .anyMatch(row -> row.contains("melding_element_fk") && row.contains("utsending_element|"))
            .noneMatch(row -> row.contains("utsendingsbunt_element"));
        assertThat(recaptured.get(SQL_PRIMARY_KEY)).as("the primary key is the one that was unique")
            .anyMatch(row -> row.contains("uq_x"));
    }

    private static void capture(DSLContext dsl, String graph, Path entry, LocalDateTime touchedAt) {
        try (var loader = new URLClassLoader(new java.net.URL[] {entry.toUri().toURL()},
                CatalogRecaptureTest.class.getClassLoader())) {
            JooqFactCapture.capture(dsl, graph, PACKAGE, loader, List.of(), null, touchedAt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Each compared relation's rows of the catalog read from {@code entry}, every column but the
     * instant, sorted, the entry's own path spelled as a placeholder so two readings of one catalog
     * under two sources compare equal.
     */
    private static Map<Table<?>, List<String>> rows(DSLContext dsl, Path entry) {
        String source = entry.toString();
        var out = new java.util.LinkedHashMap<Table<?>, List<String>>();
        for (var table : COMPARED) {
            var fields = Stream.of(table.fields()).filter(f -> !f.getName().equalsIgnoreCase("touched_at")).toList();
            out.put(table, dsl.select(fields).from(table).fetch().stream()
                .map(row -> String.join("|", Stream.of(row.intoArray()).map(String::valueOf).toList()) + "|")
                .filter(row -> row.contains(source))
                .map(row -> row.replace(source, "<catalog>"))
                .sorted().toList());
        }
        return out;
    }

    /** Compiles {@code sources} into {@code entry}, replacing what it held. */
    private static void compile(Path entry, Map<String, String> sources, Path sourceRoot) {
        try {
            compileChecked(entry, sources, sourceRoot);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void compileChecked(Path entry, Map<String, String> sources, Path sourceRoot)
            throws IOException {
        if (Files.exists(entry)) {
            try (var walk = Files.walk(entry)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(entry);
        var files = new ArrayList<String>();
        for (var source : sources.entrySet()) {
            var file = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        var args = new ArrayList<>(List.of("-d", entry.toString(), "-classpath", System.getProperty("java.class.path"),
            "-proc:none", "-nowarn"));
        args.addAll(files);
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
        assertThat(status).as("the hand-written catalog compiles").isZero();
    }

    /**
     * The first catalog: melding's melding_element_fk references utsendingsbunt_element's four-column
     * key; x has pk_x on p and uq_x on q.
     */
    private static Map<String, String> first() {
        return catalog(
            "melding_element_fk", "UtsendingsbuntElement", "UTSENDINGSBUNT_ELEMENT", List.of("A", "B", "C", "D"),
            "pk_x", List.of("P"), "uq_x", List.of("Q"));
    }

    /**
     * The second: melding_element_fk references utsending_element's three-column key instead; uq_x is
     * x's primary key on p and q, and pk_x a unique key on p.
     */
    private static Map<String, String> second() {
        return catalog(
            "melding_element_fk", "UtsendingElement", "UTSENDING_ELEMENT", List.of("A", "B", "C"),
            "uq_x", List.of("P", "Q"), "pk_x", List.of("P"));
    }

    private static Map<String, String> catalog(String fkName, String targetClass, String targetConstant,
            List<String> fkColumns, String primaryName, List<String> primaryColumns,
            String uniqueName, List<String> uniqueColumns) {
        String schema = PACKAGE + ".rc";
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put(PACKAGE + ".DefaultCatalog", """
            package %s;
            public class DefaultCatalog extends org.jooq.impl.CatalogImpl {
                public static final DefaultCatalog DEFAULT_CATALOG = new DefaultCatalog();
                private DefaultCatalog() { super(""); }
                @Override public final java.util.List<org.jooq.Schema> getSchemas() { return java.util.List.of(%s.Rc.RC); }
            }
            """.formatted(PACKAGE, schema));
        sources.put(schema + ".Rc", """
            package %s;
            public class Rc extends org.jooq.impl.SchemaImpl {
                public static final Rc RC = new Rc();
                private Rc() { super(org.jooq.impl.DSL.name("rc"), null); }
                @Override public org.jooq.Catalog getCatalog() { return %s.DefaultCatalog.DEFAULT_CATALOG; }
                @Override public final java.util.List<org.jooq.Table<?>> getTables() {
                    return java.util.List.of(Tables.UTSENDINGSBUNT_ELEMENT, Tables.UTSENDING_ELEMENT, Tables.MELDING, Tables.X);
                }
            }
            """.formatted(schema, PACKAGE));
        sources.put(schema + ".Tables", """
            package %s;
            public class Tables {
                public static final UtsendingsbuntElement UTSENDINGSBUNT_ELEMENT = UtsendingsbuntElement.UTSENDINGSBUNT_ELEMENT;
                public static final UtsendingElement UTSENDING_ELEMENT = UtsendingElement.UTSENDING_ELEMENT;
                public static final Melding MELDING = Melding.MELDING;
                public static final X X = no.sikt.graphitron.model.capture.jooq.recapture.rc.X.X;
            }
            """.formatted(schema));
        sources.put(schema + ".UtsendingsbuntElement", keyedTable(schema, "UtsendingsbuntElement",
            "UTSENDINGSBUNT_ELEMENT", "utsendingsbunt_element", List.of("A", "B", "C", "D"),
            "utsendingsbunt_element_pkey", List.of("A", "B", "C", "D"), null, List.of(), ""));
        sources.put(schema + ".UtsendingElement", keyedTable(schema, "UtsendingElement", "UTSENDING_ELEMENT",
            "utsending_element", List.of("A", "B", "C"), "utsending_element_pkey", List.of("A", "B", "C"), null,
            List.of(), ""));
        String references = """
                @Override public java.util.List<org.jooq.ForeignKey<org.jooq.Record, ?>> getReferences() {
                    return java.util.List.of(org.jooq.impl.Internal.createForeignKey(this, org.jooq.impl.DSL.name("%s"),
                        new org.jooq.TableField[] { %s }, %s.%s.getPrimaryKey(),
                        new org.jooq.TableField[] { %s }, true));
                }
            """.formatted(fkName, String.join(", ", fkColumns), targetClass, targetConstant,
            String.join(", ", fkColumns.stream().map(c -> targetClass + "." + targetConstant + "." + c).toList()));
        sources.put(schema + ".Melding", keyedTable(schema, "Melding", "MELDING", "melding",
            List.of("ID", "A", "B", "C", "D"), "melding_pkey", List.of("ID"), null, List.of(), references));
        sources.put(schema + ".X", keyedTable(schema, "X", "X", "x", List.of("P", "Q"), primaryName, primaryColumns,
            uniqueName, uniqueColumns, ""));
        return sources;
    }

    /** One table of the catalog: integer columns, a primary key, at most one unique key, and {@code extra} members. */
    private static String keyedTable(String schema, String className, String constant, String sqlName,
            List<String> columns, String primaryName, List<String> primaryColumns, String uniqueName,
            List<String> uniqueColumns, String extra) {
        var fields = new StringBuilder();
        for (var column : columns) {
            fields.append("    public final org.jooq.TableField<org.jooq.Record, Integer> ").append(column)
                .append(" = createField(org.jooq.impl.DSL.name(\"").append(column.toLowerCase())
                .append("\"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, \"\");\n");
        }
        String uniques = uniqueName == null ? "java.util.List.of()"
            : "java.util.List.of(org.jooq.impl.Internal.createUniqueKey(this, org.jooq.impl.DSL.name(\"" + uniqueName
                + "\"), new org.jooq.TableField[] { " + String.join(", ", uniqueColumns) + " }, true))";
        return """
            package %s;
            public class %s extends org.jooq.impl.TableImpl<org.jooq.Record> {
                public static final %s %s = new %s();
            %s
                private %s() { super(org.jooq.impl.DSL.name("%s"), Rc.RC); }
                @Override public org.jooq.UniqueKey<org.jooq.Record> getPrimaryKey() {
                    return org.jooq.impl.Internal.createUniqueKey(this, org.jooq.impl.DSL.name("%s"),
                        new org.jooq.TableField[] { %s }, true);
                }
                @Override public java.util.List<org.jooq.UniqueKey<org.jooq.Record>> getUniqueKeys() { return %s; }
            %s
            }
            """.formatted(schema, className, className, constant, className, fields, className, sqlName,
            primaryName, String.join(", ", primaryColumns), uniques, extra);
    }
}
