package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the vocabulary binds, and where a directive's shape comes from.
 *
 * <p>The overlay is a hand-written table and the shape is the graph's own capture, so this pairs a
 * caller-supplied overlay with a fixture whose SDL declares the coordinates it names. Whether the
 * overlay graphitron ships agrees with the SDL graphitron ships is {@link DriftDetectionTest}'s
 * question; this one is about the two halves fitting together at all.
 *
 * <p>The fixture declares directives of an author's own rather than graphitron's, which is the point:
 * the shape comes from whatever the graph captured, so a directive nobody at Sikt wrote resolves the
 * same way {@code @table} does.
 */
class LspVocabularyTest {

    private static final String FIXTURE_SDL = """
        type Query { placeholder: Int }

        directive @demo(name: String!, ref: DemoRef) on OBJECT

        "Marker for the soon-to-be-removed @retired form."
        directive @retired on OBJECT

        input DemoRef {
            className: String
            nested: DemoNested
        }

        input DemoNested {
            deep: String
        }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.of(tmp, FIXTURE_SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void behaviorIsBoundAtEveryCoordinateTheOverlayNames() {
        var overlay = Map.<SchemaCoordinate, Behavior>of(
            new SchemaCoordinate.DirectiveArg("demo", "name"),
                new Behavior.CatalogTableBinding(),
            new SchemaCoordinate.InputField("DemoRef", "className"),
                new Behavior.ClassNameBinding()
        );

        var vocab = LspVocabulary.load(overlay, store.handle());

        assertThat(vocab.behaviorAt(new SchemaCoordinate.DirectiveArg("demo", "name")))
            .containsInstanceOf(Behavior.CatalogTableBinding.class);
        assertThat(vocab.behaviorAt(new SchemaCoordinate.InputField("DemoRef", "className")))
            .containsInstanceOf(Behavior.ClassNameBinding.class);
    }

    /**
     * An overlay entry the graph does not back is inert rather than fatal. The vocabulary used to
     * refuse to be built at all in this case, which was tenable while it parsed a file shipped in the
     * jar; a graph that has not been captured yet would now take the whole editor down with it.
     * Whether the shipped overlay and the shipped SDL agree is asserted where that is the subject.
     */
    @Test
    void anOverlayCoordinateTheGraphDoesNotDeclareBindsNothingAndBreaksNothing() {
        var fictional = new SchemaCoordinate.DirectiveArg("notADirective", "nope");
        var overlay = Map.<SchemaCoordinate, Behavior>of(fictional, new Behavior.CatalogTableBinding());

        var vocab = LspVocabulary.load(overlay, store.handle());

        assertThat(vocab.overlay()).containsKey(fictional);
        assertThat(vocab.surface().declaresDirective("notADirective")).isFalse();
    }

    /** A cursor inside a directive the graph never captured keys no coordinate. */
    @Test
    void aVocabularyWithNoSurfaceResolvesNothing() {
        var empty = LspVocabulary.empty();
        String source = """
            type Foo @demo(name: "x") { id: ID }
            """;

        assertThat(leavesForDirective(empty, source, "@demo")).isEmpty();
    }

    /**
     * List-fanout pin: a directive arg whose AST value is a
     * {@code list_value} fans out to one {@link LspVocabulary.Leaf} per
     * scalar element. The leaf's {@code valueNode} is always the scalar
     * element node, never the enclosing list; consumers downstream
     * ({@code Diagnostics.validateCatalogColumn},
     * {@code Hovers.richerHover}) treat the leaf value as a single
     * scalar, so the contract has to hold at emit time.
     */
    @Test
    void leafCoordinates_listValueFansOutOneLeafPerElement() {
        String source = """
            type Foo implements Node @table(name: "film") @node(keyColumns: ["a", "b"]) {
                id: ID
            }
            """;

        var leaves = leavesForDirective(BundledVocabulary.get(), source, "@node");

        var nodeArgLeaves = leaves.stream()
            .filter(l -> l.coord() instanceof SchemaCoordinate.DirectiveArg da
                && da.directive().equals("node")
                && da.arg().equals("keyColumns"))
            .toList();
        assertThat(nodeArgLeaves).hasSize(2);
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        assertThat(nodeArgLeaves)
            .extracting(l -> Nodes.unquote(Nodes.text(l.valueNode(), bytes)))
            .containsExactly("a", "b");
        // The leaves carry the scalar element node, not the enclosing list_value.
        assertThat(nodeArgLeaves)
            .allMatch(l -> !"list_value".equals(l.valueNode().getType()));
    }

    /** The descent follows the captured input-object tree however deep an author nests a literal. */
    @Test
    void leafCoordinatesDescendTheCapturedInputObjectTree() {
        String source = """
            type Foo @demo(ref: {nested: {deep: "x"}}) { id: ID }
            """;

        var leaves = leavesForDirective(LspVocabulary.load(store.handle()), source, "@demo");

        assertThat(leaves).extracting(LspVocabulary.Leaf::coord)
            .contains(new SchemaCoordinate.InputField("DemoNested", "deep"));
    }

    private static java.util.List<LspVocabulary.Leaf> leavesForDirective(
        LspVocabulary vocabulary, String source, String directiveTokenStart
    ) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var tree = parser.parse(source).orElseThrow();
        int idx = source.indexOf(directiveTokenStart);
        if (idx < 0) throw new AssertionError("missing " + directiveTokenStart);
        var pos = pointAt(source, idx + directiveTokenStart.length() - 1);
        var directive = Directives.findContaining(tree.getRootNode(), pos)
            .orElseThrow(() -> new AssertionError("no directive at " + pos));
        return vocabulary.leafCoordinates(directive, source.getBytes(StandardCharsets.UTF_8));
    }

    private static Point pointAt(String source, int offset) {
        int line = 0, col = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') { line++; col = 0; } else col++;
        }
        return new Point(line, col);
    }
}
