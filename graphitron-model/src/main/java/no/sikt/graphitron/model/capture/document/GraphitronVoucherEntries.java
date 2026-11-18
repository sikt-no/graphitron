package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.FieldSetGrammar;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_VOUCHED_FOR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ISSUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ISSUE_VOUCHED_FOR_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The type site's decode of the two voucher markers, {@code @issue} and {@code @accept}: their scope,
 * lifetime, key and the field sets they parse, one relation per argument shape.
 *
 * <p>Keyed by the application's own position, as every site's decode is. Its own class beside
 * {@link GraphitronTypeEntries} because the five relations are one family read together by the
 * voucher stages, and their field-set parse is shared by both markers.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronVoucherEntries {

    private GraphitronVoucherEntries() {}

    /**
     * Makes {@code source}'s voucher-marker rows under {@code graph} be what {@code document}'s
     * type-site applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        var issueApplications = applied(applications, "issue");
        issues(dsl, graph, touchedAt, issueApplications);
        issueVouchesFor(dsl, graph, touchedAt, issueApplications);
        var acceptApplications = applied(applications, "accept");
        accepts(dsl, graph, touchedAt, acceptApplications);
        acceptVouchesFor(dsl, graph, touchedAt, acceptApplications);
        acceptKeys(dsl, graph, touchedAt, acceptApplications);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /** What the sweep deletes from, and the only thing that reads it. */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_ISSUE_ENTRY,
        GRAPHITRON_AST_ISSUE_VOUCHED_FOR_ENTRY,
        GRAPHITRON_AST_ACCEPT_ENTRY,
        GRAPHITRON_AST_ACCEPT_VOUCHED_FOR_ENTRY,
        GRAPHITRON_AST_ACCEPT_KEY_ENTRY);

    private static void issues(DSLContext dsl, String graph, LocalDateTime touchedAt,
                               List<Directive> applications) {
        var t = GRAPHITRON_AST_ISSUE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "scope"), t.SCOPE),
            application -> val(string(application, "ttl"), t.TTL),
            application -> val(string(application, "vouchesFor"), t.VOUCHES_FOR)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCOPE, t.TTL, t.VOUCHES_FOR)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCOPE, excluded(t.SCOPE))
                .set(t.TTL, excluded(t.TTL))
                .set(t.VOUCHES_FOR, excluded(t.VOUCHES_FOR)));
    }

    /**
     * One selection of a voucher marker's field-set argument, its vouchesFor or an {@code @accept}'s key,
     * at its index in the parse, with its segments.
     */
    private record Claim(Directive application, int position, List<String> segments) {}

    /** Each selection of each application's vouchesFor field set, at its index in the parse. */
    private static List<Claim> claims(List<Directive> applications) {
        return selections(applications, "vouchesFor");
    }

    /** Each selection of each application's {@code argument} field set, at its index in the parse. */
    private static List<Claim> selections(List<Directive> applications, String argument) {
        var claims = new java.util.ArrayList<Claim>();
        for (Directive application : applications) {
            int position = 0;
            String fieldSet = string(application, argument);
            if (fieldSet == null) {
                continue;
            }
            for (List<String> segments : FieldSetGrammar.paths(fieldSet)) {
                claims.add(new Claim(application, position++, segments));
            }
        }
        return claims;
    }

    private static void issueVouchesFor(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_ISSUE_VOUCHED_FOR_ENTRY;
        var rows = claims(applications).stream().collect(Rows.toRowList(
            claim -> val(graph, t.GRAPH_NAME),
            claim -> GraphQLAstEntries.sourceName(claim.application()),
            claim -> GraphQLAstEntries.sourceLine(claim.application()),
            claim -> GraphQLAstEntries.sourceColumn(claim.application()),
            claim -> val(claim.position(), t.POSITION),
            claim -> val(touchedAt, t.TOUCHED_AT),
            claim -> val(claim.segments().getFirst(), t.FIELD_NAME),
            claim -> val(claim.segments().size() > 1, t.NESTED)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.TOUCHED_AT, t.FIELD_NAME, t.NESTED)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.FIELD_NAME, excluded(t.FIELD_NAME))
                .set(t.NESTED, excluded(t.NESTED)));
    }

    private static void accepts(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                List<Directive> applications) {
        var t = GRAPHITRON_AST_ACCEPT_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "scope"), t.SCOPE),
            application -> val(string(application, "key"), t.KEY_FIELDS),
            application -> val(string(application, "vouchesFor"), t.VOUCHES_FOR)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCOPE, t.KEY_FIELDS, t.VOUCHES_FOR)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCOPE, excluded(t.SCOPE))
                .set(t.KEY_FIELDS, excluded(t.KEY_FIELDS))
                .set(t.VOUCHES_FOR, excluded(t.VOUCHES_FOR)));
    }

    private static void acceptVouchesFor(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                     List<Directive> applications) {
        var t = GRAPHITRON_AST_ACCEPT_VOUCHED_FOR_ENTRY;
        var rows = claims(applications).stream().collect(Rows.toRowList(
            claim -> val(graph, t.GRAPH_NAME),
            claim -> GraphQLAstEntries.sourceName(claim.application()),
            claim -> GraphQLAstEntries.sourceLine(claim.application()),
            claim -> GraphQLAstEntries.sourceColumn(claim.application()),
            claim -> val(claim.position(), t.POSITION),
            claim -> val(touchedAt, t.TOUCHED_AT),
            claim -> val(claim.segments().getFirst(), t.FIELD_NAME),
            claim -> val(claim.segments().size() > 1, t.NESTED)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.TOUCHED_AT, t.FIELD_NAME, t.NESTED)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.FIELD_NAME, excluded(t.FIELD_NAME))
                .set(t.NESTED, excluded(t.NESTED)));
    }

    private static void acceptKeys(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_ACCEPT_KEY_ENTRY;
        var rows = selections(applications, "key").stream().collect(Rows.toRowList(
            key -> val(graph, t.GRAPH_NAME),
            key -> GraphQLAstEntries.sourceName(key.application()),
            key -> GraphQLAstEntries.sourceLine(key.application()),
            key -> GraphQLAstEntries.sourceColumn(key.application()),
            key -> val(key.position(), t.POSITION),
            key -> val(touchedAt, t.TOUCHED_AT),
            key -> val(key.segments().getFirst(), t.FIELD_NAME),
            key -> val(key.segments().size() > 1, t.NESTED)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.POSITION, t.TOUCHED_AT, t.FIELD_NAME, t.NESTED)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.FIELD_NAME, excluded(t.FIELD_NAME))
                .set(t.NESTED, excluded(t.NESTED)));
    }
}
