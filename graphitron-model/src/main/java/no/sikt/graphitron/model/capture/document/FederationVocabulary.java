package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_ARGUMENT_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_DEFINITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_DEFINITION_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_FEDERATION;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The writer of the graph's composed vocabulary: the federation definitions its link brings in under
 * the names the graph sees, the directives it declares with their arguments, and the federation
 * types, as the set of {@code graphitron_type} the link adds.
 *
 * <p>The last step of the graphitron-ast anchor, after the link's imports, since what a definition
 * is called depends on them and the imports on what the mint applied. Every graph gets its directives,
 * a graph with no link its written ones alone. Marked and swept per graph on the reading's instant.
 * The federation types are elements like any other, which {@link EmittedAnchor}'s sweep takes when
 * the next reading starts and this step writes again; a name an author or the mint holds stays
 * theirs.
 */
final class FederationVocabulary {

    private FederationVocabulary() {}

    static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        definitions(dsl, graph, touchedAt);
        directives(dsl, graph, touchedAt);
        arguments(dsl, graph, touchedAt);
        types(dsl, graph, touchedAt);
    }

    private static void definitions(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_FEDERATION_DEFINITION_RULE;
        var t = GRAPHITRON_FEDERATION_DEFINITION;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.NAME, t.LOCAL_NAME, t.KIND, t.SOURCE_NAME, t.VERSION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.NAME, r.LOCAL_NAME, r.KIND, r.SOURCE_NAME, r.VERSION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.LOCAL_NAME, excluded(t.LOCAL_NAME))
            .set(t.KIND, excluded(t.KIND))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.VERSION, excluded(t.VERSION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    private static void directives(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_DIRECTIVE_RULE;
        var t = GRAPHITRON_DIRECTIVE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ORIGIN, t.REPEATABLE, t.DESCRIPTION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.DIRECTIVE_NAME, r.ORIGIN, r.REPEATABLE, r.DESCRIPTION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.REPEATABLE, excluded(t.REPEATABLE))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        // A directive the graph stopped declaring takes its arguments by cascade.
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    private static void arguments(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_DIRECTIVE_ARGUMENT_RULE;
        var t = GRAPHITRON_DIRECTIVE_ARGUMENT;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.DIRECTIVE_NAME, t.ARGUMENT_NAME, t.ORDINAL, t.TYPE_SDL,
                t.NAMED_TYPE, t.NON_NULL, t.IS_LIST, t.ITEM_NON_NULL, t.DEFAULT_VALUE_SDL,
                t.DESCRIPTION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.DIRECTIVE_NAME, r.ARGUMENT_NAME, r.ORDINAL, r.TYPE_SDL,
                    r.NAMED_TYPE, r.NON_NULL, r.IS_LIST, r.ITEM_NON_NULL, r.DEFAULT_VALUE_SDL,
                    r.DESCRIPTION, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.TYPE_SDL, excluded(t.TYPE_SDL))
            .set(t.NAMED_TYPE, excluded(t.NAMED_TYPE))
            .set(t.NON_NULL, excluded(t.NON_NULL))
            .set(t.IS_LIST, excluded(t.IS_LIST))
            .set(t.ITEM_NON_NULL, excluded(t.ITEM_NON_NULL))
            .set(t.DEFAULT_VALUE_SDL, excluded(t.DEFAULT_VALUE_SDL))
            .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).and(t.TOUCHED_AT.ne(touchedAt)).execute();
    }

    private static void types(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var f = GRAPHITRON_TYPE_FEDERATION;
        var e = GRAPHITRON_ELEMENT;
        var t = GRAPHITRON_TYPE;
        dsl.insertInto(e)
            .columns(e.GRAPH_NAME, e.COORDINATE, e.ELEMENT_KIND, e.TOUCHED_AT)
            .select(dsl.select(f.GRAPH_NAME, f.COORDINATE, inline("NAMED_TYPE"), val(touchedAt, e.TOUCHED_AT))
                .from(f)
                .where(f.GRAPH_NAME.eq(graph))
                .and(notExists(selectOne().from(e)
                    .where(e.GRAPH_NAME.eq(f.GRAPH_NAME)).and(e.COORDINATE.eq(f.COORDINATE)))))
            .execute();
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.COORDINATE, t.KIND, t.DESCRIPTION)
            .select(dsl.select(f.GRAPH_NAME, f.TYPE_NAME, f.COORDINATE, f.KIND, f.DESCRIPTION)
                .from(f)
                .where(f.GRAPH_NAME.eq(graph))
                .and(notExists(selectOne().from(t)
                    .where(t.GRAPH_NAME.eq(f.GRAPH_NAME)).and(t.TYPE_NAME.eq(f.TYPE_NAME)))))
            .execute();
    }
}
