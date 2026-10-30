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
 * A statement the store has prepared once is not prepared again: H2 keeps it in the session's
 * statement cache and hands the same {@link Command} back for the same text. Preparing is the
 * parser and the optimizer, and over a capture and the reads after it that is most of what the
 * store spends, so a regression to re-preparing fails the build here instead of slowing it.
 *
 * <p>The signal is {@code SessionLocal.prepareLocal} answering with the same instance, which H2
 * does for a command that was closed after its last use, as a JDBC statement is. It answers
 * with a fresh one when the cache is too small to have kept the statement, which is H2's default
 * of eight slots against the six hundred or so distinct statements a capture runs, and when
 * something emptied the cache in between, which a rollback does. Each case covers one of the two
 * sessions the store owns, the writer's and a reader's, and each runs on an in-memory store and on
 * a file-backed one opened a second time, the shape a warm build meets.
 */
class StoreStatementReuseTest {

    private static final ReadBudget UNBOUNDED = new ReadBudget.Unbounded();

    /** More distinct statements than H2's default cache holds, fewer than the store's. */
    private static final int OTHER_STATEMENTS = 200;

    private static final String SUBJECT = "SELECT COUNT(*) FROM store_graph WHERE graph_name = 'subject'";

    @Test
    void theWritersStatementsSurviveOtherStatements() {
        try (var store = FactStores.inMemory()) {
            assertWriterKeeps(store.connection());
        }
    }

    @Test
    void theWritersStatementsSurviveOtherStatementsInAReopenedFile(@TempDir Path tmp) {
        FactStores.fileBacked(tmp).close();
        try (var store = FactStores.fileBacked(tmp)) {
            assertWriterKeeps(store.connection());
        }
    }

    @Test
    void aReaderPreparesNothingForASecondIdenticalRead() {
        try (var store = FactStores.inMemory()) {
            assertReaderKeeps(() -> store.reader(UNBOUNDED));
        }
    }

    @Test
    void aReaderPreparesNothingForASecondIdenticalReadInAReopenedFile(@TempDir Path tmp) {
        FactStores.fileBacked(tmp).close();
        try (var store = FactStores.fileBacked(tmp)) {
            assertReaderKeeps(() -> store.reader(UNBOUNDED));
        }
    }

    private static void assertWriterKeeps(Connection writer) {
        SessionLocal session = session(writer);
        Command first = session.prepareLocal(SUBJECT);
        first.close();
        prepareOtherStatements(session);
        assertThat(session.prepareLocal(SUBJECT))
            .as("the writer's session still holds the statement after %d others", OTHER_STATEMENTS)
            .isSameAs(first);
    }

    private static void assertReaderKeeps(Supplier<StoreReader> readers) {
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
            assertThat(second)
                .as("the reader ended its first read without emptying its statement cache")
                .isSameAs(first);
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
