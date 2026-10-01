package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import graphql.language.StringValue;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;
import no.sikt.graphitron.model.selection.GraphQLSelectionParseException;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_ast_argmapping_pair_entry}: every entry of every argMapping a
 * document writes, at the string it was written as.
 *
 * <p>Which strings are mappings is the vocabulary's answer, by name: an argument or an input field
 * it calls {@code argMapping}, found by {@link VocabularyWalk} at whatever depth. That reaches the
 * one {@code @routine} declares and the one every {@code ExternalCodeReference} carries, so
 * {@code @routine}, {@code @service}, {@code @externalField} and {@code @condition} are read the same
 * way because they are read by the same code.
 *
 * <p>The string is parsed by {@link ArgMappingSigil#entries}, which judges nothing about the site.
 * Whether a site admits a sigil, or an argMapping at all, is a rule about the application and is
 * not applied here: what was written is what the rows say. A string that does not parse writes no
 * rows; the transcription keeps it verbatim.
 */
final class GraphitronArgMappingEntries {

    private GraphitronArgMappingEntries() {}

    /** The vocabulary's name for a mapping, wherever it declares one. */
    private static final String MAPPING_NAME = "argMapping";

    /** One entry of one mapping, at the string it was written in. */
    private record Pair(StringValue mapping, int position, ArgMappingSigil.Entry entry) {}

    /**
     * Makes {@code source}'s rows under {@code graph} be the mappings {@code applications} write,
     * then sweeps what this reading did not touch.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications,
                      LocalDateTime touchedAt) {
        var pairs = new ArrayList<Pair>();
        for (var nested : applications) {
            VocabularyWalk.walk(nested.node(), (name, value, declared) -> {
                if (!name.equals(MAPPING_NAME) || !(value instanceof StringValue mapping)) {
                    return;
                }
                List<ArgMappingSigil.Entry> entries;
                try {
                    entries = ArgMappingSigil.entries(mapping.getValue());
                } catch (GraphQLSelectionParseException e) {
                    return;
                }
                for (int position = 0; position < entries.size(); position++) {
                    pairs.add(new Pair(mapping, position, entries.get(position)));
                }
            });
        }

        var t = GRAPHITRON_AST_ARGMAPPING_PAIR_ENTRY;
        var rows = pairs.stream().collect(Rows.toRowList(
            pair -> val(graph, t.GRAPH_NAME),
            pair -> GraphQLAstEntries.sourceName(pair.mapping()),
            pair -> GraphQLAstEntries.sourceLine(pair.mapping()),
            pair -> GraphQLAstEntries.sourceColumn(pair.mapping()),
            pair -> val(pair.position(), t.POSITION),
            pair -> val(touchedAt, t.TOUCHED_AT),
            pair -> val(pair.entry().parameter(), t.PARAM_NAME),
            pair -> val(pair.entry().boundTo(), t.BOUND_TO)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.TOUCHED_AT, t.PARAM_NAME, t.BOUND_TO)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.PARAM_NAME, excluded(t.PARAM_NAME))
                .set(t.BOUND_TO, excluded(t.BOUND_TO)));
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, List.of(t));
    }
}
