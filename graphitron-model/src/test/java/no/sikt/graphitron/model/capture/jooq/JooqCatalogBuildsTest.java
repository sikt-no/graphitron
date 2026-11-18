package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A catalog source is the entry the catalog's classes were read from, plus their package: two
 * builds of one jOOQ package are two sources, and one build read by two graphs is one.
 *
 * <p>The two builds are compiled here, from one template differing in the one table each declares,
 * into two directories that are two classpath entries. They are one package with one set of class
 * names, which is what a stale jar beside a fresh compile is, and what no fixture of a single
 * build can state.
 */
class JooqCatalogBuildsTest {

    private static final String PACKAGE = "no.sikt.graphitron.model.capture.jooq.builds";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);
    private static final LocalDateTime THIRD = SECOND.plusMinutes(1);

    @TempDir
    static Path builds;

    private static Path buildA;
    private static Path buildB;

    @BeforeAll
    static void compileTwoBuilds() throws IOException {
        buildA = compile("a", "book");
        buildB = compile("b", "pamphlet");
    }

    @Test
    @DisplayName("two graphs on two builds of one package each see their own tables, A read first")
    void twoBuildsAThenB() {
        twoBuilds(true);
    }

    @Test
    @DisplayName("two graphs on two builds of one package each see their own tables, B read first")
    void twoBuildsBThenA() {
        twoBuilds(false);
    }

    private static void twoBuilds(boolean aFirst) {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, "a");
            SeededStore.seedGraph(dsl, "b");
            if (aFirst) {
                capture(dsl, "a", buildA, ClasspathEntry.Origin.REACTOR, FIRST);
                capture(dsl, "b", buildB, ClasspathEntry.Origin.REACTOR, SECOND);
            } else {
                capture(dsl, "b", buildB, ClasspathEntry.Origin.REACTOR, FIRST);
                capture(dsl, "a", buildA, ClasspathEntry.Origin.REACTOR, SECOND);
            }
            assertThat(tablesOf(dsl, "a")).as("graph a's tables").containsExactly("book");
            assertThat(tablesOf(dsl, "b")).as("graph b's tables").containsExactly("pamphlet");

            // Read again, the build read first is re-read after the other: neither sweep reaches
            // the other build's rows.
            if (aFirst) {
                capture(dsl, "a", buildA, ClasspathEntry.Origin.REACTOR, THIRD);
            } else {
                capture(dsl, "b", buildB, ClasspathEntry.Origin.REACTOR, THIRD);
            }
            assertThat(tablesOf(dsl, "a")).as("graph a's tables, after a third reading").containsExactly("book");
            assertThat(tablesOf(dsl, "b")).as("graph b's tables, after a third reading").containsExactly("pamphlet");
            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")))
                .as("one source per build").isEqualTo(2);
            assertThat(dsl.selectDistinct(SQL_SCHEMA.SCHEMA_PACKAGE).from(SQL_SCHEMA)
                    .fetch(SQL_SCHEMA.SCHEMA_PACKAGE))
                .as("both of one package").containsExactly(PACKAGE + ".shelf");
        }
    }

    @Test
    @DisplayName("one build read by two graphs is one source both claim")
    void oneBuildSharedIsOneSource() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, "a");
            SeededStore.seedGraph(dsl, "other");
            capture(dsl, "a", buildA, ClasspathEntry.Origin.REACTOR, FIRST);
            capture(dsl, "other", buildA, ClasspathEntry.Origin.DECLARED, SECOND);

            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA")))
                .as("one source").isEqualTo(1);
            assertThat(claims(dsl, "other")).as("claimed by both")
                .isEqualTo(claims(dsl, "a")).hasSize(1);
            assertThat(tablesOf(dsl, "other")).containsExactly("book");
        }
    }

    @Test
    @DisplayName("a graph naming a package only an inherited entry holds claims no catalog")
    void aTransitiveCatalogIsNoCatalog() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, "a");
            capture(dsl, "a", buildA, ClasspathEntry.Origin.REACTOR, FIRST);
            assertThat(claims(dsl, "a")).as("declared, the catalog is claimed").hasSize(1);

            capture(dsl, "a", buildA, ClasspathEntry.Origin.TRANSITIVE, SECOND);

            assertThat(claims(dsl, "a")).as("inherited, it is not").isEmpty();
            assertThat(tablesOf(dsl, "a")).isEmpty();
        }
    }

    @Test
    @DisplayName("a run handed no classpath reads its catalog from the entry its loader finds it in")
    void noClasspathIsTheLoadersEntry() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            SeededStore.seedGraph(dsl, "a");
            JooqFactCapture.capture(dsl, "a", PACKAGE, loaderOf(buildA), List.of(), null, FIRST);

            assertThat(claims(dsl, "a")).as("the source names the entry, never the package alone")
                .containsExactly(buildA + "!" + PACKAGE + ".shelf");
            assertThat(tablesOf(dsl, "a")).containsExactly("book");
        }
    }

    private static void capture(DSLContext dsl, String graph, Path build, ClasspathEntry.Origin origin,
                                LocalDateTime touchedAt) {
        JooqFactCapture.capture(dsl, graph, PACKAGE, loaderOf(build),
            List.of(new ClasspathEntry(build, origin, null, null)), null, touchedAt);
    }

    private static List<String> tablesOf(DSLContext dsl, String graph) {
        return dsl.select(SQL_TABLE.TABLE_NAME).from(SQL_TABLE)
            .join(STORE_GRAPH_SOURCE).on(STORE_GRAPH_SOURCE.SOURCE_NAME.eq(SQL_TABLE.SOURCE_NAME))
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graph))
            .orderBy(SQL_TABLE.TABLE_NAME)
            .fetch(SQL_TABLE.TABLE_NAME);
    }

    private static List<String> claims(DSLContext dsl, String graph) {
        return dsl.select(STORE_GRAPH_SOURCE.SOURCE_NAME).from(STORE_GRAPH_SOURCE)
            .join(STORE_SOURCE).on(STORE_SOURCE.SOURCE_NAME.eq(STORE_GRAPH_SOURCE.SOURCE_NAME))
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graph), STORE_SOURCE.SOURCE_KIND.eq("JOOQ_SCHEMA"))
            .orderBy(STORE_GRAPH_SOURCE.SOURCE_NAME)
            .fetch(STORE_GRAPH_SOURCE.SOURCE_NAME);
    }

    /** A loader over one build, which the test's own classpath lends jOOQ to and nothing of the package. */
    private static ClassLoader loaderOf(Path build) {
        try {
            return new URLClassLoader(new URL[] {build.toUri().toURL()},
                JooqCatalogBuildsTest.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One build of the catalog, whose one schema declares the one table named. */
    private static Path compile(String name, String table) throws IOException {
        Path sources = builds.resolve(name + "-src");
        Path classes = Files.createDirectories(builds.resolve(name));
        String dir = PACKAGE.replace('.', '/');
        String schemaPackage = PACKAGE + ".shelf";
        String cls = Character.toUpperCase(table.charAt(0)) + table.substring(1);
        List<Path> files = new ArrayList<>();
        files.add(write(sources.resolve(dir + "/DefaultCatalog.java"), """
            package %1$s;
            public class DefaultCatalog extends org.jooq.impl.CatalogImpl {
                public static final DefaultCatalog DEFAULT_CATALOG = new DefaultCatalog();
                private DefaultCatalog() { super(""); }
                @Override public final java.util.List<org.jooq.Schema> getSchemas() {
                    return java.util.List.of(%2$s.Shelf.SHELF);
                }
            }
            """.formatted(PACKAGE, schemaPackage)));
        files.add(write(sources.resolve(dir + "/shelf/Shelf.java"), """
            package %1$s;
            public class Shelf extends org.jooq.impl.SchemaImpl {
                public static final Shelf SHELF = new Shelf();
                private Shelf() { super(org.jooq.impl.DSL.name("shelf"), null); }
                @Override public org.jooq.Catalog getCatalog() { return %2$s.DefaultCatalog.DEFAULT_CATALOG; }
                @Override public final java.util.List<org.jooq.Table<?>> getTables() {
                    return java.util.List.of(Tables.%3$s);
                }
            }
            """.formatted(schemaPackage, PACKAGE, table.toUpperCase())));
        files.add(write(sources.resolve(dir + "/shelf/Tables.java"), """
            package %1$s;
            public class Tables {
                public static final %2$s %3$s = %2$s.%3$s;
            }
            """.formatted(schemaPackage, cls, table.toUpperCase())));
        files.add(write(sources.resolve(dir + "/shelf/" + cls + ".java"), """
            package %1$s;
            public class %2$s extends org.jooq.impl.TableImpl<%2$sRecord> {
                public static final %2$s %3$s = new %2$s();
                public final org.jooq.TableField<%2$sRecord, Integer> id = createField(
                    org.jooq.impl.DSL.name("id"), org.jooq.impl.SQLDataType.INTEGER.nullable(false), this, "");
                private %2$s() { super(org.jooq.impl.DSL.name("%4$s"), Shelf.SHELF); }
                @Override public Class<%2$sRecord> getRecordType() { return %2$sRecord.class; }
            }
            """.formatted(schemaPackage, cls, table.toUpperCase(), table)));
        files.add(write(sources.resolve(dir + "/shelf/" + cls + "Record.java"), """
            package %1$s;
            public class %2$sRecord extends org.jooq.impl.TableRecordImpl<%2$sRecord> {
                public %2$sRecord() { super(%2$s.%3$s); }
                public Integer getId() { return (Integer) get(0); }
            }
            """.formatted(schemaPackage, cls, table.toUpperCase())));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var arguments = new ArrayList<String>(List.of("-d", classes.toString(),
            "-classpath", System.getProperty("java.class.path"), "-proc:none", "-nowarn"));
        files.forEach(file -> arguments.add(file.toString()));
        int status = compiler.run(null, null, null, arguments.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("build " + name + " did not compile");
        }
        return classes;
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
