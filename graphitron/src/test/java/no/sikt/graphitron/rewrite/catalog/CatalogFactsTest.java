package no.sikt.graphitron.rewrite.catalog;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R362 — pins the {@link CatalogFacts} fact-capture behaviour where it lives: a real Sakila jOOQ
 * catalog reduced through {@link CatalogBuilder#buildCatalogFacts(JooqCatalog)} carries the SQL and
 * Java column names, SQL types, nullability, the PK, unique key(s), index(es), and outgoing /
 * incoming foreign keys with their column pairs for known tables.
 *
 * <p>Also pins the load-bearing invariant from {@link CatalogFacts}: the projection retains no live
 * jOOQ reflection handle, so it reads back the same values after the codegen loader is closed
 * (no {@code NoClassDefFoundError}).
 */
@PipelineTier
class CatalogFactsTest {

    private static CatalogFacts sakilaFacts() {
        return CatalogBuilder.buildCatalogFacts(new JooqCatalog(DEFAULT_JOOQ_PACKAGE));
    }

    private static CatalogFacts.Table resolved(CatalogFacts facts, String table) {
        var resolution = facts.resolve(table, Optional.empty());
        assertThat(resolution).isInstanceOf(CatalogFacts.TableResolution.Resolved.class);
        return ((CatalogFacts.TableResolution.Resolved) resolution).table();
    }

    @Test
    void film_carriesColumnSqlAndJavaNamesTypesAndNullability() {
        var film = resolved(sakilaFacts(), "film");

        assertThat(film.columns()).extracting(CatalogFacts.Column::sqlName)
            .contains("film_id", "title", "language_id");
        assertThat(film.columns()).extracting(CatalogFacts.Column::javaName)
            .contains("FILM_ID", "TITLE", "LANGUAGE_ID");

        var filmId = film.columns().stream()
            .filter(c -> c.sqlName().equals("film_id")).findFirst().orElseThrow();
        assertThat(filmId.nullable()).isFalse();
        assertThat(filmId.sqlType()).as("SQL data-type name is captured, not the Java class").isNotBlank();
    }

    @Test
    void film_carriesPrimaryKey() {
        var film = resolved(sakilaFacts(), "film");
        assertThat(film.primaryKey()).isPresent();
        assertThat(film.primaryKey().get().columns()).containsExactly("film_id");
    }

    @Test
    void storageBin_carriesUniqueKeyDistinctFromPrimaryKey() {
        // storage_bin (R266 fixture): PK bin_id, UNIQUE(code). The unique key surfaces with the PK
        // excluded from uniqueKeys.
        var bin = resolved(sakilaFacts(), "storage_bin");
        assertThat(bin.primaryKey()).isPresent();
        assertThat(bin.primaryKey().get().columns()).containsExactly("bin_id");
        assertThat(bin.uniqueKeys())
            .as("the non-PK UNIQUE(code) constraint surfaces, PK excluded")
            .anySatisfy(k -> assertThat(k.columns()).containsExactly("code"));
        assertThat(bin.uniqueKeys())
            .noneSatisfy(k -> assertThat(k.columns()).containsExactly("bin_id"));
    }

    @Test
    void actor_carriesIndexWithColumns() {
        // init.sql declares `CREATE INDEX idx_actor_last_name ON actor(last_name)` — the one explicit
        // non-constraint index in the fixture. Index capture rides jOOQ codegen's includeIndexes flag.
        var actor = resolved(sakilaFacts(), "actor");
        assertThat(actor.indexes())
            .anySatisfy(i -> {
                assertThat(i.name()).isEqualToIgnoringCase("idx_actor_last_name");
                assertThat(i.columns()).containsExactly("last_name");
            });
        assertThat(actor.indexes()).allSatisfy(i -> {
            assertThat(i.name()).isNotBlank();
            assertThat(i.columns()).isNotEmpty();
        });
    }

    @Test
    void film_carriesOutgoingForeignKeyToLanguageWithColumnPairs() {
        var film = resolved(sakilaFacts(), "film");
        assertThat(film.foreignKeys().outgoing())
            .anySatisfy(fk -> {
                assertThat(fk.targetTable()).endsWith(".language");
                assertThat(fk.constraintName()).isNotBlank();
                assertThat(fk.columns()).contains("language_id");
                assertThat(fk.targetColumns()).contains("language_id");
            });
    }

    @Test
    void language_carriesIncomingForeignKeyFromFilm() {
        var language = resolved(sakilaFacts(), "language");
        assertThat(language.foreignKeys().incoming())
            .anySatisfy(fk -> {
                assertThat(fk.sourceTable()).endsWith(".film");
                assertThat(fk.constraintName()).isNotBlank();
                assertThat(fk.targetColumns()).contains("language_id");
            });
    }

    @Test
    void tableIdIsSchemaQualifiedAndStable() {
        var facts = sakilaFacts();
        var film = resolved(facts, "film");
        assertThat(film.qualifiedName()).isEqualTo(film.schema() + ".film");
        assertThat(facts.tablesByQualifiedName()).containsKey(film.qualifiedName());
    }

    // ---- Load-bearing invariant: no retained live reflection handle ----

    @Test
    void factsHoldNoLiveJooqHandlesAnywhereInTheGraph() {
        // The strong guard: walk the whole frozen object graph and assert nothing is a live jOOQ
        // handle (org.jooq.*) or a Class. A regression that stuffs a Table<?> / ForeignKey<?,?> /
        // Field / Class into any nested record trips here, before it can NoClassDefFoundError in
        // an MCP request after the loader closes.
        assertNoLiveHandles(sakilaFacts(), "CatalogFacts");
    }

    @Test
    void factsReadableAfterCodegenLoaderClosed_returnSameValues() throws Exception {
        // Build the projection through a dedicated, closeable URLClassLoader (the shape DevMojo's
        // withCodegenScope uses), capture a table's facts, close the loader, then re-read: a frozen
        // value projection returns the identical values with no NoClassDefFoundError.
        var classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparator);
        var urls = new ArrayList<URL>(classpath.length);
        for (var entry : classpath) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        CatalogFacts facts;
        CatalogFacts.Table before;
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new),
                CatalogFactsTest.class.getClassLoader())) {
            var jooq = new JooqCatalog(DEFAULT_JOOQ_PACKAGE, loader);
            facts = CatalogBuilder.buildCatalogFacts(jooq);
            before = resolved(facts, "film");
        } // loader closed here

        var after = resolved(facts, "film");
        assertThat(after.qualifiedName()).isEqualTo(before.qualifiedName());
        assertThat(after.columns()).isEqualTo(before.columns());
        assertThat(after.primaryKey()).isEqualTo(before.primaryKey());
        assertThat(after.foreignKeys()).isEqualTo(before.foreignKeys());
        assertThat(after.indexes()).isEqualTo(before.indexes());
    }

    /** Recursively walks records / collections / optionals, failing on any jOOQ handle or {@link Class}. */
    private static void assertNoLiveHandles(Object value, String path) {
        if (value == null) return;
        if (value instanceof Class<?>) {
            throw new AssertionError("CatalogFacts retains a Class at " + path + ": " + value);
        }
        String typeName = value.getClass().getName();
        if (typeName.startsWith("org.jooq")) {
            throw new AssertionError("CatalogFacts retains a live jOOQ handle at " + path + ": " + typeName);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof Optional<?> opt) {
            opt.ifPresent(v -> assertNoLiveHandles(v, path + ".get()"));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                assertNoLiveHandles(k, path + ".key");
                assertNoLiveHandles(v, path + "[" + k + "]");
            });
            return;
        }
        if (value instanceof Collection<?> coll) {
            int i = 0;
            for (var v : coll) {
                assertNoLiveHandles(v, path + "[" + i++ + "]");
            }
            return;
        }
        RecordComponent[] components = value.getClass().getRecordComponents();
        if (components != null) {
            for (var rc : components) {
                try {
                    assertNoLiveHandles(rc.getAccessor().invoke(value), path + "." + rc.getName());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Could not read record component " + path + "." + rc.getName(), e);
                }
            }
            return;
        }
        throw new AssertionError("Unexpected non-value type in CatalogFacts at " + path + ": " + typeName);
    }
}
