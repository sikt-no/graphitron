package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import no.sikt.graphitron.model.grammar.FieldSetGrammar;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import no.sikt.graphitron.model.sink.BindBatch;
import org.jooq.DSLContext;
import org.jooq.Rows;
import org.jooq.Table;


import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_MUTATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ISSUE_CLAIM_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_CLAIM_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ACCEPT_KEY_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ISSUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_OUTBOX_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.applied;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.bool;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.inside;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.integer;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.naming;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.string;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.token;
import static no.sikt.graphitron.model.capture.document.GraphitronAstEntries.wrote;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The field site's half of the decode: what a directive written on an output field meant.
 *
 * <p>Keyed by the application's own position, which is {@code graphql_ast_directive_application_entry}'s
 * key, so a row here is the decode of exactly one row there. Which field the directive was written
 * on, and in which file, is one join away rather than a column.
 *
 * <p>The site is the field definitions of objects and interfaces, and nothing else. An input
 * object's field is an input value to the parser, so a directive written on one is the input-value
 * site's, not this one's, even where the two sites share a directive name. That is a change from
 * how the walk this replaces routed them, and it follows from the key: an entry references the AST
 * row it decodes, and those rows are in different relations.
 *
 * <p>Seventeen directive names reach this site and five of them get a relation here.
 * {@code @service}, {@code @externalField} and {@code @pivot} get none,
 * nothing reading a decode of them: each application and its arguments stand in the
 * {@code graphql_ast_} relations. {@code @splitQuery}, {@code @tenantFanOut} and
 * {@code @globalData} declare no argument, and {@code @multitableReference} is rejected before anything reads its one argument, so a row for
 * any of the four would carry its key and nothing else, which is what the applied-directive row
 * already says. {@code @reference} and {@code @referenceFor} are the path family's, written for
 * every site at once by {@link GraphitronReferencePathEntries}, {@code @field} and
 * {@code @nodeId} by {@link GraphitronBindingEntries}, and {@code @defaultOrder} by
 * {@link GraphitronOrderEntries}, the decode it shares with {@code @order}.
 *
 * @see GraphitronAstEntries for what every site's decode holds in common
 */
final class GraphitronFieldEntries {

    private GraphitronFieldEntries() {}

    /**
     * Makes {@code source}'s field-site rows under {@code graph} be what {@code document}'s
     * applications now say.
     */
    static void write(DSLContext dsl, String graph, String source,
                      List<GraphQLAstEntries.Nested<Directive>> applications, LocalDateTime touchedAt) {
        conditions(dsl, graph, touchedAt, naming(applied(applications, "condition"), "condition"));
        connections(dsl, graph, touchedAt, applied(applications, "asConnection"));
        mutations(dsl, graph, touchedAt, token(applied(applications, "mutation"), "typeName"));

        var routineApplications = wrote(applied(applications, "routine"), "name");
        routines(dsl, graph, touchedAt, routineApplications);
        outboxes(dsl, graph, touchedAt, applied(applications, "outbox"));
        var issueApplications = applied(applications, "issue");
        issues(dsl, graph, touchedAt, issueApplications);
        issueClaims(dsl, graph, touchedAt, issueApplications);
        var acceptApplications = applied(applications, "accept");
        accepts(dsl, graph, touchedAt, acceptApplications);
        acceptClaims(dsl, graph, touchedAt, acceptApplications);
        acceptKeys(dsl, graph, touchedAt, acceptApplications);
        GraphitronAstEntries.sweep(dsl, graph, source, touchedAt, TABLES_TO_SWEEP);
    }

    /**
     * What the sweep deletes from, and the only thing that reads it. Listed rather than found by
     * prefix: a relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        GRAPHITRON_AST_FIELD_CONDITION_ENTRY,
        GRAPHITRON_AST_CONNECTION_ENTRY,
        GRAPHITRON_AST_MUTATION_ENTRY,
        GRAPHITRON_AST_ROUTINE_ENTRY, GRAPHITRON_AST_OUTBOX_ENTRY,
        GRAPHITRON_AST_ISSUE_ENTRY,
        GRAPHITRON_AST_ISSUE_CLAIM_ENTRY,
        GRAPHITRON_AST_ACCEPT_ENTRY,
        GRAPHITRON_AST_ACCEPT_CLAIM_ENTRY,
        GRAPHITRON_AST_ACCEPT_KEY_ENTRY);

    private static void conditions(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                   List<Directive> applications) {
        var t = GRAPHITRON_AST_FIELD_CONDITION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(inside(application, "condition", "className"), t.CLASS_NAME),
            application -> val(inside(application, "condition", "method"), t.METHOD),
            application -> val(inside(application, "condition", "argMapping"), t.ARGMAPPING),
            application -> val(bool(application, "override"), t.OVERRIDE)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.CLASS_NAME, t.METHOD, t.ARGMAPPING, t.OVERRIDE)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.CLASS_NAME, excluded(t.CLASS_NAME))
                .set(t.METHOD, excluded(t.METHOD))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING))
                .set(t.OVERRIDE, excluded(t.OVERRIDE)));
    }

    private static void connections(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_CONNECTION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(integer(application, "defaultFirstValue"), t.DEFAULT_FIRST_VALUE),
            application -> val(string(application, "connectionName"), t.CONNECTION_NAME)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.DEFAULT_FIRST_VALUE, t.CONNECTION_NAME)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.DEFAULT_FIRST_VALUE, excluded(t.DEFAULT_FIRST_VALUE))
                .set(t.CONNECTION_NAME, excluded(t.CONNECTION_NAME)));
    }

    private static void mutations(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                  List<Directive> applications) {
        var t = GRAPHITRON_AST_MUTATION_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(token(application, "typeName"), t.OPERATION),
            application -> val(bool(application, "multiRow"), t.MULTI_ROW),
            application -> val(bool(application, "failOnMissing"), t.FAIL_ON_MISSING),
            application -> val(string(application, "table"), t.TABLE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "table")),
                t.TABLE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "table")),
                t.TABLE_REF_NAME_PART)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.OPERATION, t.MULTI_ROW, t.FAIL_ON_MISSING, t.TABLE_REF,
                    t.TABLE_REF_NAMESPACE_PART, t.TABLE_REF_NAME_PART)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.OPERATION, excluded(t.OPERATION))
                .set(t.MULTI_ROW, excluded(t.MULTI_ROW))
                .set(t.FAIL_ON_MISSING, excluded(t.FAIL_ON_MISSING))
                .set(t.TABLE_REF, excluded(t.TABLE_REF))
                .set(t.TABLE_REF_NAMESPACE_PART, excluded(t.TABLE_REF_NAMESPACE_PART))
                .set(t.TABLE_REF_NAME_PART, excluded(t.TABLE_REF_NAME_PART)));
    }

    private static void routines(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_ROUTINE_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "name"), t.ROUTINE_REF),
            application -> val(QualifiedNameGrammar.namespacePart(string(application, "name")),
                t.ROUTINE_REF_NAMESPACE_PART),
            application -> val(QualifiedNameGrammar.namePart(string(application, "name")),
                t.ROUTINE_REF_NAME_PART),
            application -> val(string(application, "argMapping"), t.ARGMAPPING)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.ROUTINE_REF, t.ROUTINE_REF_NAMESPACE_PART, t.ROUTINE_REF_NAME_PART,
                    t.ARGMAPPING)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.ROUTINE_REF, excluded(t.ROUTINE_REF))
                .set(t.ROUTINE_REF_NAMESPACE_PART, excluded(t.ROUTINE_REF_NAMESPACE_PART))
                .set(t.ROUTINE_REF_NAME_PART, excluded(t.ROUTINE_REF_NAME_PART))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

    private static void outboxes(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                 List<Directive> applications) {
        var t = GRAPHITRON_AST_OUTBOX_ENTRY;
        var rows = applications.stream().collect(Rows.toRowList(
            application -> val(graph, t.GRAPH_NAME),
            application -> GraphQLAstEntries.sourceName(application),
            application -> GraphQLAstEntries.sourceLine(application),
            application -> GraphQLAstEntries.sourceColumn(application),
            application -> val(touchedAt, t.TOUCHED_AT),
            application -> val(string(application, "event"), t.EVENT_REF),
            application -> val(string(application, "argMapping"), t.ARGMAPPING)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.EVENT_REF, t.ARGMAPPING)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.EVENT_REF, excluded(t.EVENT_REF))
                .set(t.ARGMAPPING, excluded(t.ARGMAPPING)));
    }

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
            application -> val(string(application, "claims"), t.CLAIMS)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCOPE, t.TTL, t.CLAIMS)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCOPE, excluded(t.SCOPE))
                .set(t.TTL, excluded(t.TTL))
                .set(t.CLAIMS, excluded(t.CLAIMS)));
    }

    /**
     * One selection of a voucher marker's field-set argument, its claims or an {@code @accept}'s key,
     * at its index in the parse, with its segments.
     */
    private record Claim(Directive application, int position, List<String> segments) {}

    /** Each selection of each application's claims field set, at its index in the parse. */
    private static List<Claim> claims(List<Directive> applications) {
        return selections(applications, "claims");
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

    private static void issueClaims(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                    List<Directive> applications) {
        var t = GRAPHITRON_AST_ISSUE_CLAIM_ENTRY;
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
            application -> val(string(application, "claims"), t.CLAIMS)));
        BindBatch.execute(dsl, rows, markers ->
            dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                    t.TOUCHED_AT, t.SCOPE, t.KEY_FIELDS, t.CLAIMS)
                .values(markers)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
                .set(t.SCOPE, excluded(t.SCOPE))
                .set(t.KEY_FIELDS, excluded(t.KEY_FIELDS))
                .set(t.CLAIMS, excluded(t.CLAIMS)));
    }

    private static void acceptClaims(DSLContext dsl, String graph, LocalDateTime touchedAt,
                                     List<Directive> applications) {
        var t = GRAPHITRON_AST_ACCEPT_CLAIM_ENTRY;
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
