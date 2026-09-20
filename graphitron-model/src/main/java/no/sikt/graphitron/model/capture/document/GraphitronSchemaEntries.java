package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import graphql.language.Node;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_LINK_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_LINK_IMPORT_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.elementsOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.stringOf;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.writtenIn;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The schema site's half of the decode: what a directive written on the schema block meant.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_schema_directive_entry}'s
 * key, so a row here is the decode of exactly one row there. Which schema definition or extension
 * the directive was written on, and in which file, is one join away rather than a column.
 *
 * <p>The smallest site, and the only one whose subject is the document as a whole: a schema block
 * declares nothing that a coordinate could name, so what is written here is about the corpus and not
 * about an element of it. One directive reaches the site with anything to decode, federation's
 * {@code @link}, and it gets two relations because its import list is a list.
 *
 * <p>{@code @link} also carries the one argument shape no other site meets. Federation lets an
 * import be written as a bare string or as an object binding it to a local name, and both spellings
 * mean the same thing with the alias absent in the first. {@link GraphitronAstEntries} already spells
 * each half, {@link GraphitronAstEntries#writtenIn} for the strings and
 * {@link GraphitronAstEntries#elementsOf} for the objects, so this writer reads both and puts them back
 * in one order by the position each carries rather than asking the facade for a third reading.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronSchemaEntries {

    private GraphitronSchemaEntries() {}

    /**
     * Makes {@code source}'s schema-site rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        var links = applied(applications, "link");
        links(dsl, graph, touchedAt, links);
        linkImports(dsl, graph, touchedAt, links);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix, on the terms {@link GraphitronAstEntries#sweep} states: a relation this writer gained
     * and did not list would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP =
        List.of(GRAPHITRON_AST_LINK_IMPORT_ENTRY, GRAPHITRON_AST_LINK_ENTRY);

    /**
     * A row for every application and not only for those that wrote a URL, because the import list
     * is the child relation and its elements need the parent to hang off.
     */
    private static void links(DSLContext dsl, String graph, LocalDateTime touchedAt,
                              List<Directive> applications) {
        var t = GRAPHITRON_AST_LINK_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "url"), t.URL)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.URL)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.URL, excluded(t.URL)));
    }

    private static void linkImports(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_LINK_IMPORT_ENTRY;
        var rows = importsOf(applications).stream().collect(Rows.toRowList(
            entry -> val(graph, t.GRAPH_NAME),
            entry -> GraphQLAstEntries.sourceName(entry.node()),
            entry -> GraphQLAstEntries.sourceLine(entry.node()),
            entry -> GraphQLAstEntries.sourceColumn(entry.node()),
            entry -> val(touchedAt, t.TOUCHED_AT),
            entry -> val(entry.name(), t.NAME),
            entry -> val(entry.alias(), t.ALIAS)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.NAME, t.ALIAS)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.NAME, excluded(t.NAME))
                .set(t.ALIAS, excluded(t.ALIAS)));
    }

    /** One import, whichever of the two spellings the author reached for. */
    private record Import(Directive application, Node<?> node, String name, String alias) {}

    /**
     * Every import of every application, in the order each was written.
     *
     * <p>The two spellings arrive as two lists because the facade reads one shape per call, and each
     * row names the element it decodes, so the two lists need nothing to put them back together: an
     * element is one node whichever spelling it took, and the order they were written in is the
     * list's own rather than something either reading has to carry.
     */
    private static List<Import> importsOf(List<Directive> applications) {
        var imports = new ArrayList<Import>();
        for (var written : writtenIn(applications, "import")) {
            imports.add(new Import(written.application(), written.node(), written.value(), null));
        }
        for (var element : elementsOf(applications, "import")) {
            String name = stringOf(inside(element.node(), "name"));
            if (name == null) {
                continue;
            }
            imports.add(new Import(element.application(), element.node(), name,
                stringOf(inside(element.node(), "as"))));
        }
        return imports;
    }
}
