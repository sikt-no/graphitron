package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Diagnostic;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What diagnosing one document costs the store, counted rather than reasoned about. One statement, for
 * every document, whatever it contains and however large it is.
 *
 * <p>This is an enforcer, not a benchmark: no timing, no fixture scale, nothing that could fail for
 * being slow. It exists because the shape it pins is invisible from any behavioural assertion. Every
 * diagnostics test passed while the surface cost a statement per value an author wrote, since resolving
 * forty names in forty round trips reports exactly what resolving them in one does. The defect that
 * shape had was not a measured latency but a count that tracked the file: a ten-field type cost
 * thirty-one statements and a forty-field one would have cost a hundred and twenty-one, published on
 * every capture, for facts whose relations share no key and could always have been asked together.
 *
 * <p>The flat-growth cases are the ones that matter most here and the reason this test is not simply
 * "assert 1". A surface can be one statement per grain and still fan out per site; what
 * {@link #theCountDoesNotTrackTheDocumentsSize} pins is that ten sites and forty sites cost the same,
 * which is the property a future reader adding a check will break by resolving a value where they
 * found it. The natural move on adding a fact to a diagnostic is another query, and it is the move this
 * test refuses.
 */
class DiagnosticsStatementCountTest {

    private static final String SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    /**
     * A graph with a table-bound type, a type a producer's return backs, a {@code @node} declaration
     * and a reference between two bound tables: every census a document's values are resolved against
     * has something in it, so a count here is the cost of answering rather than of finding nothing.
     */
    private static final String GRAPH_SDL = """
        type Query {
            films: [Film]
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
        }

        type FilmCard { title: String }

        type Film @table(name: "film") @node {
            title: String
            filmId: ID @nodeId(typeName: "Film")
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") { name: String }
        """.formatted(SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, GRAPH_SDL, StoreFixture.backingClasses());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aTableNameCostsOneStatement() {
        assertThat(statementsFor("""
            type Film @table(name: "film") {
                title: String
            }
            """)).isEqualTo(1);
    }

    @Test
    void aColumnNameCostsOneStatement() {
        // The most expensive value an author could write, and the one that made the old count grow:
        // resolving it walked the site's own scope, then the parent's binding, then that table's
        // columns, each a round trip whose subject was decided by the one before it.
        assertThat(statementsFor("""
            type Film @table(name: "film") {
                title: String @field(name: "title")
            }
            """)).isEqualTo(1);
    }

    @Test
    void aColumnNameOnAClassBackedTypeCostsOneStatement() {
        // Deeper than the case above: no binding answers, so the scope is the type's backing class,
        // which is two populations and a grounding rule between them, and the class turns out to be a
        // record whose slots are what a name here resolves against.
        assertThat(statementsFor("""
            type FilmCard {
                title: String @field(name: "title")
            }
            """)).isEqualTo(1);
    }

    @Test
    void aDocumentSpanningEveryCensusCostsOneStatement() {
        var out = diagnose(GRAPH_SDL);
        assertThat(out).isEmpty();
        assertThat(statementsFor(GRAPH_SDL)).isEqualTo(1);
    }

    @Test
    void everyValueBeingWrongStillCostsOneStatement() {
        // The count is a property of the questions, not of the answers: a document where nothing
        // resolves must not pay for a second look at anything.
        var out = diagnose(WRONG_SDL);
        assertThat(out).hasSizeGreaterThanOrEqualTo(4);
        assertThat(statementsFor(WRONG_SDL)).isEqualTo(1);
    }

    private static final String WRONG_SDL = """
        type Film @table(name: "nosuchtable") {
            title: String @field(name: "nosuchcolumn")
            filmId: ID @nodeId(typeName: "NotANode")
            other: String @service(service: {className: "com.example.Absent", method: "nope"})
        }
        """;

    @Test
    void theCountDoesNotTrackTheDocumentsSize() {
        int ten = statementsFor(fields(10));
        int forty = statementsFor(fields(40));
        assertThat(ten).isEqualTo(1);
        assertThat(forty).isEqualTo(ten);
    }

    @Test
    void aDocumentWithNothingToResolveCostsNoStatement() {
        // Nothing to ask is not one statement returning nothing. A schema whose directives name no
        // value a census could resolve is the honest reading of a file carrying only @deprecated.
        assertThat(statementsFor("""
            type Film {
                title: String @deprecated(reason: "gone")
            }
            """)).isZero();
    }

    @Test
    void aSessionWithNoStoreCostsNoStatementAndSaysNothingAboutAnyValue() {
        // What a consumer sees before their first build. Every value arm defers, and the two checks
        // that need no census still speak: a scalar reference with no dot in it cannot resolve
        // whatever the classpath holds.
        var counted = new AtomicInteger();
        var out = Diagnostics.compute(
            LspVocabulary.load(), "file:///x.graphqls", file(WRONG_SDL),
            LspSchemaSnapshot.unavailable(), ValidationReport.empty(), Optional.empty());
        assertThat(out).isEmpty();
        assertThat(counted.get()).isZero();
    }

    // ===== Helpers =====

    /** A type with {@code count} column-bound fields, which is the shape the old count grew with. */
    private static String fields(int count) {
        var sb = new StringBuilder("type Film @table(name: \"film\") {\n");
        for (int i = 0; i < count; i++) {
            sb.append("    f").append(i).append(": String @field(name: \"title\")\n");
        }
        return sb.append("}\n").toString();
    }

    private static int statementsFor(String source) {
        var counted = new AtomicInteger();
        assertThat(diagnose(counting(counted), source)).isNotNull();
        return counted.get();
    }

    private static List<Diagnostic> diagnose(String source) {
        return diagnose(store.handle(), source);
    }

    private static List<Diagnostic> diagnose(StoreHandle handle, String source) {
        return Diagnostics.compute(
            LspVocabulary.load(), "file:///x.graphqls", file(source),
            LspSchemaSnapshot.unavailable(), ValidationReport.empty(), Optional.of(handle));
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

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
