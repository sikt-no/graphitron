package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import org.jooq.Condition;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectOne;

/**
 * The authored-claim conflict rule, projected from the store: the family's violations are the
 * rows of the {@code intent_authored_claim_conflict} view, and this class derives the located
 * {@link ValidationError} values from them, byte-identical in message and location to what the
 * classification walk's dissolved detector sites used to tombstone. The reduction itself (the
 * distinct-claims grouping and the routine-plus-lookup carve-out) lives in the view's SQL; what
 * remains here is the population this consumer asks about, and the mint of the {@link Rejection}
 * arms the report carries, from the view's closed {@code verdict} vocabulary and the violated
 * coordinate's own claim rows.
 *
 * <p>The message is minted here rather than read off the view because its naming order is
 * {@link AuthoredClaim}'s declaration order, which is not a captured fact of any graph and so is
 * no view's to express. {@link #rejectionOf} is that mint's one home, shared with
 * {@link AuthoredClaimRejectionRows}, the capture-cadence writer that stores the same rejection
 * for the diagnostics surface to read as a plain column.
 *
 * <p>{@code @splitQuery} is a delivery-axis directive that never claims: it has no claim-view
 * arm, so a routine-with-splitQuery co-occurrence never reaches the view's reduction.
 *
 * <p>The field grain returns its verdicts as typed {@link FieldVerdict} values rather than bare
 * errors: the verdict carries the coordinate's {@link FieldClaim}s enriched with their decoded
 * slot facts, so the projection's {@code Conflicted} arm consumes the {@link FieldVerdict.Conflict}
 * type instead of re-testing the reduction predicate, and {@link Detection#violations()} derives
 * the error stream from the same verdicts at one site. The {@link FieldVerdict} arm is chosen by
 * the view's own verdict column, never by re-testing the claim set.
 *
 * <p>Locations are the view's: a field violation carries the field's own declared position, a
 * type violation the type's base declaration site at merge ordinal 0, which is what the walk's
 * {@code locationOf} read off the AST for the same coordinates. Each claim additionally carries
 * the claiming application's own position from the claim-view row.
 *
 * <p>The view itself is total over the authored claims. This class is the <em>build-error</em>
 * consumer of it, so the population it asks about is the classification domain: only a coordinate
 * the generator intends to classify can fail a build, and a contradiction outside that domain
 * costs no emitted source. The join is {@code intent_type_domain} on the coordinate's owning type
 * at both grains, a field's population being its type's. The editor's diagnostic arm asks a
 * different question of the same rows and joins nothing, which is why the filter lives here rather
 * than in the view.
 */
public final class AuthoredClaimConflicts {

    private AuthoredClaimConflicts() {}

    /**
     * The detection pass's typed product: the type-grain violations, and the field-grain
     * reduction outcomes with their claims. {@link #violations()} is the error stream every
     * caller reads; the projection overlay additionally reads {@link #fieldConflicts()}.
     */
    public record Detection(List<ValidationError> typeViolations, List<FieldVerdict> fieldVerdicts) {

        public Detection {
            typeViolations = List.copyOf(typeViolations);
            fieldVerdicts = List.copyOf(fieldVerdicts);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of(), List.of());
        }

        /**
         * Every violation the detection minted, type grain first, each grain in coordinate
         * order; the field-grain errors derive from the verdicts here, at the one site, so the
         * two products cannot disagree.
         */
        public List<ValidationError> violations() {
            var out = new ArrayList<>(typeViolations);
            for (var v : fieldVerdicts) {
                out.add(ValidationError.forField(v.coordinate(), v.rejection(), v.location()));
            }
            return List.copyOf(out);
        }

        /** The {@link FieldVerdict.Conflict} arms only: the coordinates the projection overlays. */
        public List<FieldVerdict.Conflict> fieldConflicts() {
            return fieldVerdicts.stream()
                .filter(FieldVerdict.Conflict.class::isInstance)
                .map(FieldVerdict.Conflict.class::cast)
                .toList();
        }
    }

    /**
     * The field-grain reduction outcome at one coordinate: two or more claims reduced to either
     * the mutual-exclusivity conflict or the recognised routine-plus-lookup deferral. The arm is
     * the discriminator downstream consumers switch on; only {@link Conflict} projects the
     * {@code Conflicted} classification.
     */
    public sealed interface FieldVerdict {

        String typeName();

        String fieldName();

        /** The coordinate's claims in {@link AuthoredClaim} declaration order. */
        List<FieldClaim> claims();

        /** The reduction's rejection, exactly what the minted {@link ValidationError} carries. */
        Rejection rejection();

        /** The field's own declared position, the violation's mint location. */
        SourceLocation location();

        default String coordinate() {
            return typeName() + "." + fieldName();
        }

        /** Mutually exclusive claims: the coordinate projects {@code Conflicted}. */
        record Conflict(String typeName, String fieldName, List<FieldClaim> claims,
                        Rejection rejection, SourceLocation location) implements FieldVerdict {
            public Conflict {
                claims = List.copyOf(claims);
            }
        }

        /** The recognised routine-plus-lookup pair: a capability-gap deferral, not a conflict. */
        record Deferred(String typeName, String fieldName, List<FieldClaim> claims,
                        Rejection rejection, SourceLocation location) implements FieldVerdict {
            public Deferred {
                claims = List.copyOf(claims);
            }
        }
    }

    /**
     * Projects both grains' violations from the view over {@code graphName}'s partition, narrowed
     * to the classification domain. Empty for every conflict-free graph, and for any graph whose
     * domain rows were never derived.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(typeGrain(dsl, graphName), fieldGrain(dsl, graphName));
    }

    /**
     * The build-error consumer's population: the violated coordinate's owning type is a member of
     * the classification domain. One predicate for both grains, a field coordinate being in the
     * domain exactly when its owning type is.
     */
    private static Condition inDomain(String graphName) {
        var d = INTENT_TYPE_DOMAIN;
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        return exists(selectOne().from(d)
            .where(d.GRAPH_NAME.eq(graphName), d.TYPE_NAME.eq(v.TYPE_NAME)));
    }

    /** A violated field-grain coordinate, the key both grains' claim reads are grouped by. */
    public record FieldCoordinate(String typeName, String fieldName) {}

    /** One claim-view row at a field coordinate, before slot-fact enrichment. */
    private record ClaimRow(AuthoredClaim classifier, String trigger, boolean decoded, SourceLocation location) {}

    private static List<FieldVerdict> fieldGrain(DSLContext dsl, String graphName) {
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var verdicts = new ArrayList<FieldVerdict>();
        dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNotNull(), inDomain(graphName))
            .orderBy(v.TYPE_NAME, v.FIELD_NAME)
            .forEach(row -> {
                var coordinate = new FieldCoordinate(row.getTypeName(), row.getFieldName());
                var claims = claimsAt(dsl, graphName, coordinate);
                var enriched = claims.stream()
                    .map(claim -> enrich(dsl, graphName, coordinate, claim))
                    .toList();
                var rejection = rejectionOf(row.getVerdict(),
                    claims.stream().map(ClaimRow::classifier).toList());
                var location = location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn());
                // The view's verdict column is the discriminator; the claim-set predicate is
                // not re-tested here.
                verdicts.add(rejection instanceof Rejection.Deferred
                    ? new FieldVerdict.Deferred(coordinate.typeName(), coordinate.fieldName(), enriched, rejection, location)
                    : new FieldVerdict.Conflict(coordinate.typeName(), coordinate.fieldName(), enriched, rejection, location));
            });
        return verdicts;
    }

    /** The violated coordinate's claim rows, in {@link AuthoredClaim} declaration order. */
    private static List<ClaimRow> claimsAt(DSLContext dsl, String graphName, FieldCoordinate coordinate) {
        var fc = INTENT_AUTHORED_FIELD_CLAIM;
        return dsl.selectDistinct(fc.CLASSIFIER, fc.TRIGGER, fc.DECODED,
                fc.SOURCE_NAME, fc.SOURCE_LINE, fc.SOURCE_COLUMN)
            .from(fc)
            .where(fc.GRAPH_NAME.eq(graphName),
                fc.TYPE_NAME.eq(coordinate.typeName()),
                fc.FIELD_NAME.eq(coordinate.fieldName()))
            .fetch(row -> new ClaimRow(AuthoredClaim.fromClassifier(row.value1()), row.value2(),
                Boolean.TRUE.equals(row.value3()), location(row.value4(), row.value5(), row.value6())))
            .stream()
            .sorted(Comparator.comparing(ClaimRow::classifier))
            .toList();
    }

    /**
     * Joins one claim's slot facts from its semantic relation, scoped to the conflicted
     * coordinate. A presence-arm row ({@code decoded} false) has no semantic row by the view's
     * anti-join construction, so its slots stay absent. The {@code TABLE} / {@code ERROR} arms
     * are unreachable at the field grain by the view masks; reaching one is vocabulary drift
     * between the two grains' views, a build bug no author input can provoke.
     */
    private static FieldClaim enrich(DSLContext dsl, String graphName, FieldCoordinate c, ClaimRow row) {
        return switch (row.classifier()) {
            case SERVICE -> {
                var s = GRAPHITRON_SERVICE;
                var r = row.decoded()
                    ? dsl.select(s.CLASS_NAME, s.METHOD).from(s)
                        .where(s.GRAPH_NAME.eq(graphName), s.TYPE_NAME.eq(c.typeName()), s.FIELD_NAME.eq(c.fieldName()))
                        .fetchOne()
                    : null;
                yield new FieldClaim.Service(
                    r == null ? null : r.value1(), r == null ? null : r.value2(),
                    row.trigger(), row.decoded(), row.location());
            }
            case EXTERNAL_FIELD -> {
                var e = GRAPHITRON_EXTERNAL_FIELD;
                var r = row.decoded()
                    ? dsl.select(e.CLASS_NAME, e.METHOD).from(e)
                        .where(e.GRAPH_NAME.eq(graphName), e.TYPE_NAME.eq(c.typeName()), e.FIELD_NAME.eq(c.fieldName()))
                        .fetchOne()
                    : null;
                yield new FieldClaim.ExternalField(
                    r == null ? null : r.value1(), r == null ? null : r.value2(),
                    row.trigger(), row.decoded(), row.location());
            }
            case NODE_ID -> {
                var n = GRAPHITRON_FIELD_NODE_ID;
                var r = row.decoded()
                    ? dsl.select(n.NODE_TYPE_REF).from(n)
                        .where(n.GRAPH_NAME.eq(graphName), n.TYPE_NAME.eq(c.typeName()), n.FIELD_NAME.eq(c.fieldName()))
                        .fetchOne()
                    : null;
                yield new FieldClaim.NodeId(
                    r == null ? null : r.value1(), row.trigger(), row.decoded(), row.location());
            }
            case LOOKUP_KEY -> new FieldClaim.LookupKey(row.trigger(), row.decoded(), row.location());
            case ROUTINE -> {
                // The whole chain is this one claim's slots, in application-ordinal order.
                var rt = GRAPHITRON_ROUTINE;
                var refs = row.decoded()
                    ? dsl.select(rt.ROUTINE_REF).from(rt)
                        .where(rt.GRAPH_NAME.eq(graphName), rt.TYPE_NAME.eq(c.typeName()), rt.FIELD_NAME.eq(c.fieldName()))
                        .orderBy(rt.ORDINAL)
                        .fetch(r -> r.value1())
                    : null;
                yield new FieldClaim.Routine(refs, row.trigger(), row.decoded(), row.location());
            }
            case MUTATION -> {
                var m = GRAPHITRON_MUTATION;
                var r = row.decoded()
                    ? dsl.select(m.OPERATION, m.TABLE_REF).from(m)
                        .where(m.GRAPH_NAME.eq(graphName), m.TYPE_NAME.eq(c.typeName()), m.FIELD_NAME.eq(c.fieldName()))
                        .fetchOne()
                    : null;
                yield new FieldClaim.Mutation(
                    r == null ? null : r.value1(), r == null ? null : r.value2(),
                    row.trigger(), row.decoded(), row.location());
            }
            case TABLE, ERROR -> throw new IllegalStateException(
                "the field-grain claim view produced type-grain classifier '" + row.classifier()
                + "' at " + c.typeName() + "." + c.fieldName()
                + "; the view masks and the vocabulary must move together");
        };
    }

    private static List<ValidationError> typeGrain(DSLContext dsl, String graphName) {
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var claims = typeClaims(dsl, graphName);
        return dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNull(), inDomain(graphName))
            .orderBy(v.TYPE_NAME)
            .fetch(row -> ValidationError.forType(row.getTypeName(),
                rejectionOf(row.getVerdict(),
                    claims.getOrDefault(row.getTypeName(), List.of())),
                location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn())));
    }

    /**
     * Mints the {@link Rejection} the report carries at one violated coordinate, from the view's
     * closed verdict vocabulary and the coordinate's own claims. The message's naming order is
     * {@link AuthoredClaim}'s declaration order, so it is stated here in terms of the enum that
     * owns that order rather than restated as a sort key anywhere else; the deferral's message is
     * composed from the two enum constants the carve-out recognises.
     *
     * <p>The one home for this family's message, shared with the writer that mints it into the
     * store ({@link AuthoredClaimRejectionRows}), so the build's error stream and the diagnostics
     * surface cannot word one violation two ways.
     */
    public static Rejection rejectionOf(String verdict, List<AuthoredClaim> claims) {
        if ("DEFERRED".equals(verdict)) {
            return Rejection.deferred("@" + AuthoredClaim.ROUTINE.directive() + " with @"
                + AuthoredClaim.LOOKUP_KEY.directive()
                + " on a root field classifies but does not emit yet");
        }
        if (claims.isEmpty()) {
            throw new IllegalStateException("the conflict view named a coordinate no claim view "
                + "claims; the view's grouping and its claim arms must move together");
        }
        var names = claims.stream().distinct().sorted().map(AuthoredClaim::directive).toList();
        String at = names.stream().map(n -> "@" + n).collect(Collectors.joining(", "));
        return Rejection.directiveConflict(names, at + " are mutually exclusive");
    }

    /**
     * Every violated type-grain coordinate's distinct claims for {@code graphName}, in
     * {@link AuthoredClaim} declaration order. One read over the conflict view joined to the
     * type-grain claim view, so only violated coordinates are read; ungated, the view being total
     * over the authored claims and each consumer applying its own population.
     */
    public static Map<String, List<AuthoredClaim>> typeClaims(DSLContext dsl, String graphName) {
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var tc = INTENT_AUTHORED_TYPE_CLAIM;
        var out = new LinkedHashMap<String, List<AuthoredClaim>>();
        dsl.selectDistinct(v.TYPE_NAME, tc.CLASSIFIER)
            .from(v)
            .join(tc).on(tc.GRAPH_NAME.eq(v.GRAPH_NAME), tc.TYPE_NAME.eq(v.TYPE_NAME))
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNull())
            .forEach(row -> out.computeIfAbsent(row.value1(), key -> new ArrayList<>())
                .add(AuthoredClaim.fromClassifier(row.value2())));
        out.replaceAll((key, claims) -> claims.stream().sorted().toList());
        return out;
    }

    /** The field-grain sibling of {@link #typeClaims}, keyed by the violated coordinate. */
    public static Map<FieldCoordinate, List<AuthoredClaim>> fieldClaims(
        DSLContext dsl, String graphName
    ) {
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var fc = INTENT_AUTHORED_FIELD_CLAIM;
        var out = new LinkedHashMap<FieldCoordinate, List<AuthoredClaim>>();
        dsl.selectDistinct(v.TYPE_NAME, v.FIELD_NAME, fc.CLASSIFIER)
            .from(v)
            .join(fc).on(fc.GRAPH_NAME.eq(v.GRAPH_NAME), fc.TYPE_NAME.eq(v.TYPE_NAME),
                fc.FIELD_NAME.eq(v.FIELD_NAME))
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNotNull())
            .forEach(row -> out
                .computeIfAbsent(new FieldCoordinate(row.value1(), row.value2()),
                    key -> new ArrayList<>())
                .add(AuthoredClaim.fromClassifier(row.value3())));
        out.replaceAll((key, claims) -> claims.stream().sorted().toList());
        return out;
    }

    /** The store's position columns as a graphql-java location; {@code null} when unpositioned. */
    private static SourceLocation location(String sourceName, Integer line, Integer column) {
        if (line == null || column == null) {
            return null;
        }
        return new SourceLocation(line, column, sourceName);
    }
}
