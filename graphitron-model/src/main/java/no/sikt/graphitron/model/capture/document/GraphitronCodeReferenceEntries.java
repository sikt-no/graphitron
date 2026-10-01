package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import graphql.language.Node;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_ast_code_reference_entry}: every Java code reference a document
 * writes, at the node it was written as.
 *
 * <p>Which values are references is read off the vocabulary rather than listed by site, through
 * {@link VocabularyWalk}. A value the bundled definitions type {@code ExternalCodeReference} is
 * one, however deep: a directive argument
 * of that type, a field of an input object an argument carries, an element of a list of those. So
 * a site the vocabulary gains writes rows here without a writer of its own, which is the failure a
 * per-site list has, each site a decode nobody else shares.
 *
 * <p>{@code @sourceRow} is the one exception, spelling its reference as two arguments of its own
 * rather than as the input type, so it is named here and its row sits at the directive.
 *
 * <p>The reference only: a class and the method where it names one. Its {@code argMapping} is the
 * application's fact and not the reference's, and a default a site applies to an omitted method is
 * a resolution, so neither is written here.
 */
final class GraphitronCodeReferenceEntries {

    private GraphitronCodeReferenceEntries() {}

    /** The vocabulary's name for a reference, which is what the walk looks for. */
    private static final String REFERENCE_TYPE = "ExternalCodeReference";

    /** One reference as written, at the node that wrote it. */
    private record Written(Node<?> node, String className, String method) {}

    /**
     * Makes {@code source}'s rows under {@code graph} be the references {@code applications}
     * write, then sweeps what this reading did not touch.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications,
                      LocalDateTime touchedAt) {
        var written = new ArrayList<Written>();
        for (var nested : applications) {
            Directive application = nested.node();
            if (application.getName().equals("sourceRow")) {
                var className = string(application, "className");
                if (className != null) {
                    written.add(new Written(application, className, string(application, "method")));
                }
                continue;
            }
            VocabularyWalk.walk(application, (name, value, declared) -> {
                if (declared.getName().equals(REFERENCE_TYPE) && value instanceof ObjectValue object) {
                    var className = stringOf(inside(object, "className"));
                    if (className != null) {
                        written.add(new Written(object, className,
                            stringOf(inside(object, "method"))));
                    }
                }
            });
        }

        var t = GRAPHITRON_AST_CODE_REFERENCE_ENTRY;
        var rows = written.stream().collect(Rows.toRowList(
            reference -> val(graph, t.GRAPH_NAME),
            reference -> GraphQLAstEntries.sourceName(reference.node()),
            reference -> GraphQLAstEntries.sourceLine(reference.node()),
            reference -> GraphQLAstEntries.sourceColumn(reference.node()),
            reference -> val(touchedAt, t.TOUCHED_AT),
            reference -> val(reference.className(), t.CLASS_NAME),
            reference -> val(reference.method(), t.METHOD)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD)));
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, List.of(t));
    }

    private static String stringOf(Value<?> value) {
        return value instanceof StringValue written ? written.getValue() : null;
    }
}
