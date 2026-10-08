package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.test.RunawayRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two rules the fixture enforces rather than leaves to its callers: a store a class shares is
 * unchanged when the class is done, and DDL reaches only a store the fixture owns.
 *
 * <p>The shared fixture is driven through its own open and close here, which are what its
 * {@code beforeAll} and {@code afterAll} call, so the check is pinned without running a class
 * around it.
 */
class StoreFixtureTest {

    @TempDir
    Path tmp;

    @Test
    void aSharedStoreNobodyWroteToClosesCleanly() {
        var shared = StoreFixture.held().sharedCatalog();
        shared.open(tmp);
        assertThat(GraphitronMcpServer.catalogTablesResult(shared.handle(), Map.of()).isError())
            .as("a read is what the shared store is for")
            .isNotEqualTo(Boolean.TRUE);

        assertThatCode(shared::close).doesNotThrowAnyException();
    }

    /** The view hides the mutators; a write through the handle's writable context is still caught. */
    @Test
    void aSharedStoreACaseInsertedIntoFailsItsClass() {
        var shared = StoreFixture.held().sharedCatalog();
        shared.open(tmp);
        shared.handle().dsl().execute("INSERT INTO sql_schema (source_name, table_schema)"
            + " SELECT source_name, 'written_by_a_case' FROM store_source LIMIT 1");

        assertThatThrownBy(shared::close)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("changed the rows")
            .hasMessageContaining("SQL_SCHEMA");
    }

    /** The rename a runaway relation performs is a schema change, and caught as one. */
    @Test
    void aSharedStoreACaseMadeRunawayFailsItsClass() {
        var shared = StoreFixture.held().sharedCatalog();
        shared.open(tmp);
        RunawayRelation.install(shared.handle().dsl(), "sql_table");

        assertThatThrownBy(shared::close)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("changed the tables")
            .hasMessageContaining("SQL_TABLE" + RunawayRelation.ORIGINAL_SUFFIX.toUpperCase(Locale.ROOT));
    }

    /** A borrowed store is the test thread's for every later case, so DDL on it is refused. */
    @Test
    void makeRunawayRefusesABorrowedFixture() {
        try (var fixture = StoreFixture.ofCatalog(tmp)) {
            assertThatThrownBy(() -> fixture.makeRunaway("sql_table"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StoreFixture.held()");
        }
    }
}
