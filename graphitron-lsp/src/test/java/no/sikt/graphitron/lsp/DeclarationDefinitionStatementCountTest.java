package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one goto-definition on a declaration name costs the store, counted rather than reasoned about.
 * One statement per jump, whatever the coordinate resolves to and however many candidates it had to be
 * resolved between.
 *
 * <p>This is an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for
 * being slow. It exists because the shape it pins is invisible from any behavioural assertion. Every
 * jump lands on the same line whether the provider asked one question or six, and six is what it asked
 * before the resolution and the position behind it became arms of one statement: the type's binding,
 * then the census row for the table it picked, then the columns of that table, then the parse's
 * declaration for the constant. Each round trip's subject was decided by the answer before it, which is
 * what made the chain look necessary; the relations share no key, so it never was.
 *
 * <p>Every jump runs under an unavailable snapshot, which is the session the count matters most in: a
 * workspace that has captured its schema and never run a build navigates on the store alone, and it is
 * the gate this number replaced.
 */
class DeclarationDefinitionStatementCountTest {

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    /**
     * One coordinate per shape the count must hold for: a type bound to a table, a column on it, a type
     * a producer's return backs, a member on that type, a field whose own {@code @service} names a
     * method the census holds, and one whose {@code @routine} names a generated call the catalog
     * census holds.
     */
    private static final String SDL = """
        type Query {
            films: [Film]
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            called: [Film] @routine(name: "films_for_actor")
        }

        type Film @table(name: "film") {
            title: String
            rating: String @service(service: {className: "%1$s", method: "makeFilmRecord"})
        }

        type FilmCard {
            title: String
        }
        """.formatted(FIXTURE_SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    private static final String FIXTURE_RECORD = "no.sikt.graphitron.lsp.fixtures.R157FilmRecord";
    private static final String ROUTINES_FQN = "no.sikt.graphitron.rewrite.test.jooq.Routines";

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL, StoreFixture.backingClasses());
        // A jump needs a positioned declaration, so every target below has a source on disk. Their
        // contents are beside the point here: what is counted is how many statements it takes to get
        // from a coordinate to one of them.
        store.withJavaSource(tmp, store.tableClassFqn("film"), """
            public class Film {
                public final Object TITLE = null;
            }
            """);
        store.withJavaSource(tmp, FIXTURE_RECORD, """
            public record R157FilmRecord(String title) {
            }
            """);
        store.withJavaSource(tmp, FIXTURE_SERVICE, """
            public class R157Service {
                public Object makeFilmRecord() { return null; }
            }
            """);
        store.withJavaSource(tmp, ROUTINES_FQN, """
            public class Routines {
                public static Object filmsForActor(Object a, Object b) { return null; }
            }
            """);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aTableBoundTypeNameCostsOneStatement() {
        var file = file("type Film @table(name: \"film\") { title: String }");
        assertThat(statementsForJumpAt(file, 0, "type Fi".length())).isEqualTo(1);
    }

    @Test
    void aColumnMatchedFieldCostsOneStatement() {
        // The longest chain the incumbent had: the binding, the table's census row, its columns, and
        // the parsed declaration of the generated constant, each keyed on what the last one answered.
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(statementsForJumpAt(file, 1, "    titl".length())).isEqualTo(1);
    }

    @Test
    void aClassBackedTypeNameCostsOneStatement() {
        var file = file("type FilmCard { title: String }");
        assertThat(statementsForJumpAt(file, 0, "type FilmCa".length())).isEqualTo(1);
    }

    @Test
    void aMemberOnAClassBackedTypeCostsOneStatement() {
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(statementsForJumpAt(file, 1, "    titl".length())).isEqualTo(1);
    }

    @Test
    void aProducerBackedFieldCostsOneStatement() {
        var file = file("""
            type Film @table(name: "film") {
                rating: String
            }
            """);
        assertThat(statementsForJumpAt(file, 1, "    ratin".length())).isEqualTo(1);
    }

    /**
     * The coordinate whose resolution used to arrive from outside the statement: the pair a
     * {@code @routine} field binds to is a subquery in the same select as everything else, so
     * naming it, positioning it and counting its parameters together still cost one.
     */
    @Test
    void aRoutineBackedFieldCostsOneStatement() {
        var file = file("""
            type Query {
                called: [Film]
            }
            """);
        assertThat(statementsForJumpAt(file, 1, "    calle".length())).isEqualTo(1);
    }

    @Test
    void aDeclarationTheStoreKnowsNothingAboutCostsOneStatement() {
        // Absence is an answer, and it is the same statement: nothing here may fall back to probing
        // relation by relation to find out that none of them holds a row.
        var file = file("""
            type Unknown {
                mystery: String
            }
            """);
        var counted = new AtomicInteger();
        assertThat(jump(counting(counted), file, 1, "    myster".length())).isEmpty();
        assertThat(counted.get()).isEqualTo(1);
    }

    // ===== Helpers =====

    /**
     * The count for a coordinate that jumps. The jump is asserted present first, so a case cannot pass
     * by resolving to nothing and paying one statement for the silence.
     */
    private static int statementsForJumpAt(FileSnapshot file, int line, int column) {
        var counted = new AtomicInteger();
        assertThat(jump(counting(counted), file, line, column)).isPresent();
        return counted.get();
    }

    /** The fixture's own store, seen through a handle that counts the statements it executes. */
    private static StoreHandle counting(AtomicInteger counted) {
        var configuration = store.handle().dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    counted.incrementAndGet();
                }
            }));
        return new StoreHandle(DSL.using(configuration), StoreFixture.GRAPH);
    }

    private static java.util.Optional<org.eclipse.lsp4j.Location> jump(
        StoreHandle handle, FileSnapshot file, int line, int column
    ) {
        return DeclarationDefinitions.compute(file, handle, new Point(line, column));
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
