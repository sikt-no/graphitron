package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.test.FactStores;
import org.h2.command.Command;
import org.h2.engine.SessionLocal;
import org.h2.jdbc.JdbcConnection;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import static no.sikt.graphitron.model.test.StoreAnswers.answered;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@link StoreUse} buys: a store opened {@link StoreUse#LONG_LIVED} keeps the statements it
 * has prepared, and one opened {@link StoreUse#ONE_CAPTURE} keeps only H2's default few. Preparing
 * is the parser and the optimizer, and over repeated captures and the reads after them it is most of
 * what the store spends, so a regression to re-preparing fails the build here instead of slowing it,
 * and a default that quietly grew would put the memory on every caller.
 *
 * <p>The signal is {@code SessionLocal.prepareLocal} answering with the same instance, which H2
 * does for a command that was closed after its last use, as a JDBC statement is. It answers with a
 * fresh one when the cache is too small to have kept the statement, and when something emptied the
 * cache in between, which a rollback does. The long-lived cases cover both sessions the store owns,
 * the writer's and a reader's, on an in-memory store and on a file-backed one opened a second time,
 * the shape a warm build meets. The cache size is a database-level setting, so the last case pins
 * who decides it: the first handle to open the database.
 */
class StoreStatementReuseTest {

    private static final ReadBudget UNBOUNDED = new ReadBudget.Unbounded();

    /** More distinct statements than H2's default cache holds, fewer than the long-lived one's. */
    private static final int OTHER_STATEMENTS = 200;

    private static final String SUBJECT = "SELECT COUNT(*) FROM store_graph WHERE graph_name = 'subject'";

    @Test
    void aLongLivedWritersStatementsSurviveOtherStatements() {
        try (var store = FactStores.inMemory(StoreUse.LONG_LIVED)) {
            assertThat(writerKeeps(store.connection()))
                .as("the writer's session still holds the statement after %d others", OTHER_STATEMENTS)
                .isTrue();
        }
    }

    @Test
    void aLongLivedWritersStatementsSurviveOtherStatementsInAReopenedFile(@TempDir Path tmp) {
        FactStores.fileBacked(tmp, StoreUse.LONG_LIVED).close();
        try (var store = FactStores.fileBacked(tmp, StoreUse.LONG_LIVED)) {
            assertThat(writerKeeps(store.connection())).isTrue();
        }
    }

    @Test
    void aLongLivedReaderPreparesNothingForASecondIdenticalRead() {
        try (var store = FactStores.inMemory(StoreUse.LONG_LIVED)) {
            assertThat(readerKeeps(() -> store.reader(UNBOUNDED)))
                .as("the reader ended its first read without emptying its statement cache")
                .isTrue();
        }
    }

    @Test
    void aLongLivedReaderPreparesNothingForASecondIdenticalReadInAReopenedFile(@TempDir Path tmp) {
        FactStores.fileBacked(tmp, StoreUse.LONG_LIVED).close();
        try (var store = FactStores.fileBacked(tmp, StoreUse.LONG_LIVED)) {
            assertThat(readerKeeps(() -> store.reader(UNBOUNDED))).isTrue();
        }
    }

    @Test
    void aStoreForOneCaptureKeepsOnlyH2sDefault() {
        try (var store = FactStores.inMemory()) {
            assertThat(writerKeeps(store.connection()))
                .as("the default must stay small: every session that fills a big cache costs memory "
                    + "that a caller capturing once never gets back")
                .isFalse();
        }
    }

    @Test
    void theFirstHandleToOpenADatabaseDecidesItsCacheForEveryHandleAfter(@TempDir Path tmp) {
        try (var first = FactStores.fileBacked(tmp, StoreUse.LONG_LIVED);
             var second = FactStores.fileBacked(tmp, StoreUse.ONE_CAPTURE)) {
            assertThat(writerKeeps(second.connection()))
                .as("a later handle asking for the small cache joins a database that was opened "
                    + "for the big one, and H2 ignores its setting rather than refusing it")
                .isTrue();
            assertThat(first.connection()).isNotSameAs(second.connection());
        }
    }

    private static boolean writerKeeps(Connection writer) {
        SessionLocal session = session(writer);
        Command first = session.prepareLocal(SUBJECT);
        first.close();
        prepareOtherStatements(session);
        return session.prepareLocal(SUBJECT) == first;
    }

    private static boolean readerKeeps(Supplier<StoreReader> readers) {
        try (var reader = readers.get()) {
            Command first = answered(reader.read(dsl -> {
                SessionLocal session = session(dsl);
                Command command = session.prepareLocal(SUBJECT);
                command.close();
                prepareOtherStatements(session);
                return command;
            }));
            Command second = answered(reader.read(dsl -> {
                Command command = session(dsl).prepareLocal(SUBJECT);
                command.close();
                return command;
            }));
            return second == first;
        }
    }

    private static void prepareOtherStatements(SessionLocal session) {
        for (int i = 0; i < OTHER_STATEMENTS; i++) {
            session.prepareLocal("SELECT COUNT(*) FROM store_graph WHERE graph_name = 'other-" + i + "'").close();
        }
    }

    private static SessionLocal session(DSLContext dsl) {
        return dsl.connectionResult(StoreStatementReuseTest::session);
    }

    private static SessionLocal session(Connection connection) {
        try {
            return (SessionLocal) connection.unwrap(JdbcConnection.class).getSession();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
