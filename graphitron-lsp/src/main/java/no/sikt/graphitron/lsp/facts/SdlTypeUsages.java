package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;

/**
 * Every SDL site that uses a type name: the population behind find-references, and the reverse of
 * the lookup {@link SdlDeclarations} makes. That one asks where a type is declared and answers with
 * one site; this asks who uses it and answers with as many as the schema has.
 *
 * <p>Three relations carry the uses, and one row of the answer means one site in one of them: a
 * field or an argument whose {@code named_type} is this type, or a polymorphic membership naming
 * it, which is one relation covering both an {@code implements} clause and a union member. They are
 * read as one statement rather than three, because one request is one statement and an interactive
 * read pays for every round trip it makes; the arms union because they are the same grain, a
 * position in a schema file, differing only in which relation recorded it.
 *
 * <p>What a result points at differs by arm, and the difference is the relations' rather than this
 * reader's. {@code graphql_poly_member} positions the type token
 * itself, so a result lands on the name the author would rename. {@code graphql_field} and
 * {@code graphql_argument} position the member's own declaration, so a result lands at the start of
 * {@code films: [Film!]!} rather than on the {@code Film} inside it. Both are the site, at the
 * granularity the capture recorded, which is the granularity this surface promises.
 *
 * <p>Every position here is the last capture's. A usage typed since then is not in these relations
 * at all, and one in a buffer edited since then reports the line it was captured at. That is the
 * whole answer's cadence rather than this reader's caveat: the row set and the positions come from
 * the same capture, so they cannot disagree with each other, only with a buffer.
 */
public final class SdlTypeUsages {

    private SdlTypeUsages() {}

    /**
     * The sites using {@code typeName}, in file-then-position order so the same schema answers the
     * same way every time. Empty when nothing uses the type, which is an answer rather than a
     * decline: there is no "known reference whose site went unrecorded" case on this side, the way
     * goto-definition has one, because a use that is not in these relations is a use that does not
     * exist as far as the capture saw.
     *
     * @param includeDeclaration whether the type's own declaration sites join the list, which is
     *                           the editor's call and arrives on the request as
     *                           {@code ReferenceContext.isIncludeDeclaration}. Every declaration
     *                           site joins, base and extensions alike, because each is a place the
     *                           name is written.
     */
    public static List<Location> of(StoreHandle store, String typeName, boolean includeDeclaration) {
        if (typeName == null || typeName.isEmpty()) return List.of();
        String graph = store.graphName();

        var uses = store.dsl()
            .select(GRAPHQL_FIELD.SOURCE_NAME, GRAPHQL_FIELD.SOURCE_LINE, GRAPHQL_FIELD.SOURCE_COLUMN)
            .from(GRAPHQL_FIELD)
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(graph))
            .and(GRAPHQL_FIELD.NAMED_TYPE.eq(typeName))
            .unionAll(store.dsl()
                .select(GRAPHQL_ARGUMENT.SOURCE_NAME, GRAPHQL_ARGUMENT.SOURCE_LINE,
                    GRAPHQL_ARGUMENT.SOURCE_COLUMN)
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graph))
                .and(GRAPHQL_ARGUMENT.NAMED_TYPE.eq(typeName)))
            .unionAll(store.dsl()
                .select(GRAPHQL_POLY_MEMBER.SOURCE_NAME, GRAPHQL_POLY_MEMBER.SOURCE_LINE,
                    GRAPHQL_POLY_MEMBER.SOURCE_COLUMN)
                .from(GRAPHQL_POLY_MEMBER)
                .where(GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(graph))
                // Whichever end of the membership the document did not declare is the end that
                // names this type, and that is the token a rename would touch. One arm covers both
                // because the store holds one relation; the two names swap roles by kind, so both
                // are tested rather than one.
                .and(GRAPHQL_POLY_MEMBER.CONTAINER_NAME.eq(typeName)
                    .and(GRAPHQL_POLY_MEMBER.CONTAINER_KIND.eq("INTERFACE"))
                    .or(GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME.eq(typeName)
                        .and(GRAPHQL_POLY_MEMBER.CONTAINER_KIND.eq("UNION")))))
            .fetch();

        var sites = new ArrayList<Location>(uses.size());
        for (var use : uses) {
            SdlDeclarations.location(use.value1(), use.value2(), use.value3()).ifPresent(sites::add);
        }
        if (includeDeclaration) {
            sites.addAll(declarationSites(store, typeName));
        }
        sites.sort(SdlTypeUsages::byFileThenPosition);
        return List.copyOf(sites);
    }

    /**
     * Every site declaring the type, which on an extended type is the base declaration and each
     * extension. {@link SdlDeclarations#typeLocation} picks one of these because a jump must land
     * somewhere; a reference list has no such constraint and wants all of them.
     */
    private static List<Location> declarationSites(StoreHandle store, String typeName) {
        var rows = store.dsl()
            .select(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME,
                GRAPHQL_TYPE_DECLARATION.SOURCE_LINE, GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN)
            .from(GRAPHQL_TYPE_DECLARATION)
            .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(typeName))
            .fetch();
        var sites = new ArrayList<Location>(rows.size());
        for (var row : rows) {
            SdlDeclarations.location(row.value1(), row.value2(), row.value3()).ifPresent(sites::add);
        }
        return sites;
    }

    /**
     * File, then line, then column. The order is not the editor's business (clients group and sort
     * results themselves) but it is the test's and the reader's: an answer that arrives in a
     * different order each run is one nobody can assert against or eyeball twice.
     */
    private static int byFileThenPosition(Location left, Location right) {
        int byFile = left.getUri().compareTo(right.getUri());
        if (byFile != 0) return byFile;
        int byLine = Integer.compare(
            left.getRange().getStart().getLine(), right.getRange().getStart().getLine());
        if (byLine != 0) return byLine;
        return Integer.compare(
            left.getRange().getStart().getCharacter(), right.getRange().getStart().getCharacter());
    }
}
