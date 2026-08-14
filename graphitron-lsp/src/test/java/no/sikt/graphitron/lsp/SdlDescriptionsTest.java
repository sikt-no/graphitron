package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.SdlDescriptions;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The description read, per coordinate arm. Hover exercises three of the four arms end to end; this
 * pins all four against a capture, including the input-type arm no cursor position produces today
 * (the vocabulary's coordinate walk keys a cursor as a directive argument or an input field, never as
 * the input type itself), and the graph scoping every arm shares.
 */
class SdlDescriptionsTest {

    /**
     * A directive, an argument, an input type and an input field, each with a docstring of its own, so
     * every arm has a positive case that could only have come from its own relation.
     */
    private static final String SDL = """
        "Restricts access to callers who hold the named role."
        directive @auth(
            "The required role name."
            role: String!
        ) on FIELD_DEFINITION

        "How to narrow a listing."
        input Filter {
            "Match titles containing this."
            title: String
        }

        type Query { placeholder: Int }
        """;

    /** A graph that declares none of the above, but captured the same bundled directives. */
    private static final String OTHER_SDL = "type Query { placeholder: Int }\n";

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.of(tmp, SDL);
        store.andGraph(tmp, "elsewhere", OTHER_SDL, List.of());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void everyCoordinateArmReadsItsOwnRelation() {
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.Directive("auth")))
            .hasValueSatisfying(d -> assertThat(d).contains("hold the named role"));
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.DirectiveArg("auth", "role")))
            .hasValueSatisfying(d -> assertThat(d).contains("required role name"));
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.InputType("Filter")))
            .hasValueSatisfying(d -> assertThat(d).contains("narrow a listing"));
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.InputField("Filter", "title")))
            .hasValueSatisfying(d -> assertThat(d).contains("titles containing"));
    }

    /**
     * Graphitron's own bundled definitions come back from the same relations as the author's, because
     * capture reads {@code directives.graphqls} like any other schema file.
     */
    @Test
    void aBundledDirectiveIsReadTheSameWay() {
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.Directive("table")))
            .isPresent();
        assertThat(SdlDescriptions.of(store.handle(),
            new SchemaCoordinate.InputField("ExternalCodeReference", "className")))
            .isPresent();
    }

    /** No row, and no description on a row that has none, are the same absence to a reader. */
    @Test
    void aCoordinateNothingDescribesIsEmpty() {
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.Directive("ghost")))
            .isEmpty();
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.DirectiveArg("auth", "ghost")))
            .isEmpty();
        // Declared, and deliberately undocumented: the row exists and its description column is null.
        assertThat(SdlDescriptions.of(store.handle(), new SchemaCoordinate.InputType("Query")))
            .isEmpty();
    }

    @Test
    void anotherGraphsDeclarationsAreInvisible() {
        var other = store.handleFor("elsewhere");

        assertThat(SdlDescriptions.of(other, new SchemaCoordinate.Directive("auth"))).isEmpty();
        assertThat(SdlDescriptions.of(other, new SchemaCoordinate.InputType("Filter"))).isEmpty();
        // The bundled definitions are that graph's own too, so scoping costs it nothing it read.
        assertThat(SdlDescriptions.of(other, new SchemaCoordinate.Directive("table"))).isPresent();
    }
}
