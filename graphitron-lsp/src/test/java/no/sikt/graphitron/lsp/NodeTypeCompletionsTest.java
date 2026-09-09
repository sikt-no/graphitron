package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.NodeTypeCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @nodeId(typeName: "...")} GraphQL-type-name
 * completion. The candidate set is two populations: {@code graphitron_node_entry}, every type whose
 * SDL carries {@code @node} regardless of whether the author filled in {@code typeId} or
 * {@code keyColumns}, and the polymorphic containers {@code intent_node_container_member} reports at
 * least one table-bound node-type member for, which a {@code @service} slot may name to take the id
 * of any implementation.
 *
 * <p>The container half is offered wherever the directive is written, including at a read-side
 * coordinate the polymorphic rule does not reach; the keyset answers what the value may name and the
 * store's {@code CONTAINER_NOT_AT_A_SLOT} verdict answers what happened where it was named, which is
 * the division the incumbent family already runs.
 */
class NodeTypeCompletionsTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    @TempDir
    static Path tmp;

    private static StoreFixture nodes;
    private static StoreFixture noNodes;
    private static StoreFixture containers;

    @BeforeAll
    static void capture() {
        // One @node with both arguments filled in and one with neither: both are candidates, which is
        // what the relation says by keying on the type alone.
        nodes = StoreFixture.of(tmp, """
            type Query { x: Int }
            type Film @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID }
            type Actor @node { id: ID }
            """);
        noNodes = StoreFixture.of(tmp, "unnoded", "type Query { x: Int }\n", List.of());
        // A union whose two members are node types over tables of their own, plus one whose members
        // are neither: the first is a legal typeName: value and the second is not, which is what the
        // two flags on the membership relation decide.
        // Captured with the fixture catalog, because the membership relation reads a member's
        // resolved table binding rather than the directive: a container is a legal typeName: value
        // when at least one member is a node type over a table the catalog holds.
        containers = StoreFixture.ofCatalog(tmp, "containers", """
            type Query { x: Int }
            type Customer @table(name: "customer") @node(keyColumns: ["customer_id"]) { id: ID }
            type Staff @table(name: "staff") @node(keyColumns: ["staff_id"]) { id: ID }
            union AddressOccupant = Customer | Staff
            type Plain { name: String }
            type Other { name: String }
            union Anything = Plain | Other
            """);
    }

    @AfterAll
    static void closeStores() {
        nodes.close();
        noNodes.close();
        containers.close();
    }

    @Test
    void typeNameCompletionReturnsNodeBearingTypes() {
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(nodes.handle(), source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("Film", "Actor");
    }

    @Test
    void cursorOutsideTypeNameArgReturnsEmpty() {
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "Film")): Int
            }
            """;
        // Cursor on the directive name token, not inside the arg value.
        int line = 1;
        int col = source.split("\n")[line].indexOf("@nodeId") + 1;
        Point cursor = new Point(line, col);

        var items = run(nodes.handle(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void noNodeDeclarationsReturnsEmptyList() {
        // A graph whose SDL declares no @node at all. The provider offers no candidates rather than
        // failing, which is also what a graph captured before the author wrote one looks like.
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(noNodes.handle(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void typeNameCompletionAlsoOffersContainersWithNodeMembers() {
        // The container is a candidate beside its own members, and the union with no node members is
        // not: a value the build would refuse is a value completion must not offer.
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(containers.handle(), source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("Customer", "Staff", "AddressOccupant");
    }

    @Test
    void aContainerCompletesAtAReadSideCoordinateToo() {
        // The keyset is keyed on the type name and not on the coordinate, so it says the same thing
        // at a filter argument as at a @service input. Whether the rule reaches this coordinate is
        // the store's own verdict's answer and arrives as a diagnostic, not as a withheld completion.
        String source = """
            type Query {
                occupants(occupantId: ID @nodeId(typeName: "")): [Customer!]!
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        assertThat(run(containers.handle(), source, cursor))
            .extracting(CompletionItem::getLabel)
            .contains("AddressOccupant");
    }

    private static List<CompletionItem> run(StoreHandle store, String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return NodeTypeCompletions.generate(VOCAB, store, context);
    }
}
