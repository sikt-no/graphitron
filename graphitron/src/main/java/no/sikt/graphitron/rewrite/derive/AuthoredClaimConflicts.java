package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;

/**
 * The fact store's first reader: the authored-claim conflict rule. One grouping query per grain
 * over the claim views ({@code intent_authored_type_claim}, {@code intent_authored_field_claim}),
 * every statement graph-scoped; a coordinate claimed for more than one classification kind mints
 * the located {@link ValidationError} the classification walk's dissolved detector sites used to
 * tombstone, byte-identical in message and location.
 *
 * <p>The reduction is typed over the {@link AuthoredClaim} vocabulary. More than one claim is a
 * {@link Rejection.InvalidSchema.DirectiveConflict} naming every claim in declaration order
 * (which reproduces the walk's per-position list orders), with one carve-out: exactly the
 * routine and lookup pair is the recognised-but-unsupported combination and mints the pinned
 * typed {@link Rejection.Deferred} instead. The old pairwise table's Composes row (routine with
 * splitQuery) needs no counterpart because {@code @splitQuery} never claims: it has no view arm,
 * so the combination never reaches the reduction.
 *
 * <p>Locations join from the store rather than the walked model: a field violation carries the
 * field's own declared position ({@code graphql_field}), a type violation the type's base
 * declaration site ({@code graphql_type_declaration} at merge ordinal 0), which is what the
 * walk's {@code locationOf} read off the AST for the same coordinates.
 *
 * <p>Minting is gated on {@link ClaimDomain} membership; the gate's rationale and removal
 * criterion live on that record.
 */
public final class AuthoredClaimConflicts {

    private AuthoredClaimConflicts() {}

    /**
     * Runs both grain detections over {@code graphName}'s partition and returns the violations,
     * type grain first, each grain in coordinate order. Empty for every conflict-free graph.
     */
    public static List<ValidationError> detect(DSLContext dsl, String graphName, ClaimDomain domain) {
        var violations = new ArrayList<ValidationError>();
        violations.addAll(typeGrain(dsl, graphName, domain));
        violations.addAll(fieldGrain(dsl, graphName, domain));
        return List.copyOf(violations);
    }

    private record FieldCoordinate(String typeName, String fieldName) {}

    private static List<ValidationError> fieldGrain(DSLContext dsl, String graphName, ClaimDomain domain) {
        var fc = INTENT_AUTHORED_FIELD_CLAIM;
        var gf = GRAPHQL_FIELD;
        var claims = new LinkedHashMap<FieldCoordinate, EnumSet<AuthoredClaim>>();
        var locations = new LinkedHashMap<FieldCoordinate, SourceLocation>();
        dsl.selectDistinct(fc.TYPE_NAME, fc.FIELD_NAME, fc.CLASSIFIER,
                gf.SOURCE_NAME, gf.SOURCE_LINE, gf.SOURCE_COLUMN)
            .from(fc)
            .join(gf).on(gf.GRAPH_NAME.eq(fc.GRAPH_NAME),
                gf.TYPE_NAME.eq(fc.TYPE_NAME),
                gf.FIELD_NAME.eq(fc.FIELD_NAME))
            .where(fc.GRAPH_NAME.eq(graphName))
            .orderBy(fc.TYPE_NAME, fc.FIELD_NAME, fc.CLASSIFIER)
            .forEach(row -> {
                var coordinate = new FieldCoordinate(row.value1(), row.value2());
                claims.computeIfAbsent(coordinate, c -> EnumSet.noneOf(AuthoredClaim.class))
                    .add(AuthoredClaim.fromClassifier(row.value3()));
                locations.putIfAbsent(coordinate, location(row.value4(), row.value5(), row.value6()));
            });

        var violations = new ArrayList<ValidationError>();
        claims.forEach((coordinate, present) -> {
            if (present.size() < 2 || !domain.containsField(coordinate.typeName(), coordinate.fieldName())) {
                return;
            }
            violations.add(ValidationError.forField(
                coordinate.typeName() + "." + coordinate.fieldName(),
                reduce(present),
                locations.get(coordinate)));
        });
        return violations;
    }

    private static List<ValidationError> typeGrain(DSLContext dsl, String graphName, ClaimDomain domain) {
        var tc = INTENT_AUTHORED_TYPE_CLAIM;
        var td = GRAPHQL_TYPE_DECLARATION;
        var claims = new LinkedHashMap<String, EnumSet<AuthoredClaim>>();
        var locations = new LinkedHashMap<String, SourceLocation>();
        dsl.selectDistinct(tc.TYPE_NAME, tc.CLASSIFIER,
                td.SOURCE_NAME, td.SOURCE_LINE, td.SOURCE_COLUMN)
            .from(tc)
            .leftJoin(td).on(td.GRAPH_NAME.eq(tc.GRAPH_NAME),
                td.TYPE_NAME.eq(tc.TYPE_NAME),
                td.MERGE_ORDINAL.eq(0))
            .where(tc.GRAPH_NAME.eq(graphName))
            .orderBy(tc.TYPE_NAME, tc.CLASSIFIER)
            .forEach(row -> {
                String typeName = row.value1();
                claims.computeIfAbsent(typeName, t -> EnumSet.noneOf(AuthoredClaim.class))
                    .add(AuthoredClaim.fromClassifier(row.value2()));
                locations.putIfAbsent(typeName, location(row.value3(), row.value4(), row.value5()));
            });

        var violations = new ArrayList<ValidationError>();
        claims.forEach((typeName, present) -> {
            if (present.size() < 2 || !domain.containsType(typeName)) {
                return;
            }
            violations.add(ValidationError.forType(typeName, reduce(present), locations.get(typeName)));
        });
        return violations;
    }

    /**
     * The typed reduction over a coordinate's claims (two or more by the caller's guard).
     * {@link EnumSet} iterates in declaration order, so the conflict names its directives in the
     * fixed order the class javadoc pins.
     */
    private static Rejection reduce(EnumSet<AuthoredClaim> present) {
        if (present.equals(EnumSet.of(AuthoredClaim.LOOKUP_KEY, AuthoredClaim.ROUTINE))) {
            return Rejection.deferred("@" + AuthoredClaim.ROUTINE.directive() + " with @"
                + AuthoredClaim.LOOKUP_KEY.directive()
                + " on a root field classifies but does not emit yet");
        }
        var names = present.stream().map(AuthoredClaim::directive).toList();
        String at = names.stream().map(n -> "@" + n).collect(Collectors.joining(", "));
        return Rejection.directiveConflict(names, at + " are mutually exclusive");
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
