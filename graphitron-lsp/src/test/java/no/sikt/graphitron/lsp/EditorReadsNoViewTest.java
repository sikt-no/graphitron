package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The editor's declaration hovers and inlay hints read stored relations and no view. A view is
 * evaluated whole on every read where H2 cannot push the asked coordinate into it, which is what
 * made a field hover cost seconds on a large graph; a key lookup in a table does not grow with the
 * graph. The rule is checked on the statements each surface issues, against every view the store
 * declares, so a new line reading a view fails here rather than on a large schema in someone's
 * editor.
 */
class EditorReadsNoViewTest {

    private static final String SDL = """
        interface Node { id: ID! }

        type Query { films: [Film] }

        type Film @table(name: "film") {
            title: String
            titel: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            details: FilmDetails
        }

        type FilmDetails { title: String }

        type Language implements Node @table(name: "language") @node { id: ID! name: String }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.held().ofCatalog(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void noDeclarationHoverStatementNamesAView() {
        var views = store.handle().dsl()
            .fetch("SELECT table_name FROM information_schema.views WHERE table_schema = 'PUBLIC'")
            .getValues(0, String.class);
        assertThat(views).as("the store declares views, so the check can fail").isNotEmpty();

        var statements = new ArrayList<String>();
        var handle = recording(statements);
        hover(handle, "type Query { films: [Film] }\n", 0, "type Query { fil".length());
        hover(handle, "type Film @table(name: \"film\") {\n    title: String\n}\n", 1, "    titl".length());
        hover(handle, "type Film @table(name: \"film\") {\n    titel: String\n}\n", 1, "    tite".length());
        hover(handle, "type Film @table(name: \"film\") {\n    language: Language\n}\n", 1, "    langu".length());
        hover(handle, "type FilmDetails {\n    title: String\n}\n", 1, "    titl".length());
        hover(handle, "type Film @table(name: \"film\") {\n    title: String\n}\n", 0, "type Fi".length());
        hover(handle, "type Language implements Node @table(name: \"language\") @node {\n    id: ID!\n}\n", 0,
            "type Langu".length());
        hover(handle, "type FilmDetails {\n    title: String\n}\n", 0, "type FilmDe".length());
        assertThat(statements).hasSize(8);

        assertThat(viewsNamed(views, statements)).as("views a declaration hover read").isEmpty();
    }

    @Test
    void noInlayHintStatementNamesAView() {
        var views = store.handle().dsl()
            .fetch("SELECT table_name FROM information_schema.views WHERE table_schema = 'PUBLIC'")
            .getValues(0, String.class);
        var statements = new ArrayList<String>();
        var source = """
            type Film @table(name: "film") {
                title: String @field
                titel: String
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
                details: FilmDetails
            }
            type FilmDetails { title: String }
            type Language implements Node @table(name: "language") @node { id: ID! name: String }
            """;
        var file = WorkspaceFileTestSupport.snapshot(source);
        var end = file.tree().getRootNode().getEndPoint();
        var hints = InlayHints.compute(new InlayHintConfig(true, true, true, true), file,
            Optional.of(recording(statements)),
            new Range(new Position(0, 0), new Position(end.row(), end.column())));
        assertThat(hints).as("the region renders, so the statement was asked").isNotEmpty();
        assertThat(statements).hasSize(1);
        assertThat(viewsNamed(views, statements)).as("views an inlay request read").isEmpty();
    }

    @Test
    void theCheckSeesAViewWhereOneIsRead() {
        // The control: go-to-definition still resolves through intent_bound_table, so the same
        // check run over its statement has to find it, or the one above passes by matching nothing.
        var views = store.handle().dsl()
            .fetch("SELECT table_name FROM information_schema.views WHERE table_schema = 'PUBLIC'")
            .getValues(0, String.class);
        var statements = new ArrayList<String>();
        DeclarationDefinitions.compute(
            WorkspaceFileTestSupport.snapshot("type Film @table(name: \"film\") {\n    title: String\n}\n"),
            recording(statements), new Point(0, "type Fi".length()));
        assertThat(statements).isNotEmpty();
        assertThat(viewsNamed(views, statements)).isNotEmpty();
    }

    private static List<String> viewsNamed(List<String> views, List<String> statements) {
        List<String> named = new ArrayList<>();
        for (String view : views) {
            var pattern = Pattern.compile("\\b" + Pattern.quote(view) + "\\b", Pattern.CASE_INSENSITIVE);
            for (String sql : statements) {
                if (pattern.matcher(sql).find()) named.add(view);
            }
        }
        return named;
    }

    private static void hover(StoreHandle handle, String source, int line, int column) {
        assertThat(DeclarationHovers.compute(WorkspaceFileTestSupport.snapshot(source),
            Optional.of(handle), new Point(line, column))).isPresent();
    }

    /** The fixture's store, seen through a handle that records the SQL of every statement it executes. */
    private static StoreHandle recording(List<String> statements) {
        var configuration = store.handle().dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    statements.add(ctx.sql());
                }
            }));
        return new StoreHandle(DSL.using(configuration), StoreFixture.GRAPH);
    }
}
