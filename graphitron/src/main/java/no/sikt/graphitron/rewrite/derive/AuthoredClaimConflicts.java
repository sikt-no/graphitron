package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;

/**
 * The authored-claim conflict rule, projected from the store: the family's violations are the
 * rows of the {@code intent_authored_claim_conflict} view, and this class derives the located
 * {@link ValidationError} values from them, byte-identical in message and location to what the
 * classification walk's dissolved detector sites used to tombstone. The reduction itself (the
 * distinct-claims grouping, the routine-plus-lookup carve-out, the ordered claim render, the
 * domain gate as a join against the {@code walk_claim_domain} reach rows) lives in the view's
 * SQL; what remains here is the decode of its closed {@code verdict} / {@code directives}
 * vocabulary into the {@link Rejection} arms the report carries.
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
 * <p>Minting is gated on the reified {@link ClaimDomain} rows the view joins; the gate's
 * rationale and removal criterion live on the {@code walk_claim_domain} family's relation
 * comments, and the caller who wants the gate populated writes it through
 * {@link ClaimDomainRows} before reading.
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
     * Projects both grains' violations from the view over {@code graphName}'s partition. Empty
     * for every conflict-free graph, and for any graph whose reach rows were never written.
     */
    public static Detection detect(DSLContext dsl, String graphName) {
        return new Detection(typeGrain(dsl, graphName), fieldGrain(dsl, graphName));
    }

    private record FieldCoordinate(String typeName, String fieldName) {}

    /** One claim-view row at a field coordinate, before slot-fact enrichment. */
    private record ClaimRow(AuthoredClaim classifier, String trigger, boolean decoded, SourceLocation location) {}

    private static List<FieldVerdict> fieldGrain(DSLContext dsl, String graphName) {
        var v = INTENT_AUTHORED_CLAIM_CONFLICT;
        var verdicts = new ArrayList<FieldVerdict>();
        dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNotNull())
            .orderBy(v.TYPE_NAME, v.FIELD_NAME)
            .forEach(row -> {
                var coordinate = new FieldCoordinate(row.getTypeName(), row.getFieldName());
                var enriched = claimsAt(dsl, graphName, coordinate).stream()
                    .map(claim -> enrich(dsl, graphName, coordinate, claim))
                    .toList();
                var rejection = rejectionOf(row.getVerdict(), row.getDirectives());
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
        return dsl.selectFrom(v)
            .where(v.GRAPH_NAME.eq(graphName), v.FIELD_NAME.isNull())
            .orderBy(v.TYPE_NAME)
            .fetch(row -> ValidationError.forType(row.getTypeName(),
                rejectionOf(row.getVerdict(), row.getDirectives()),
                location(row.getSourceName(), row.getSourceLine(), row.getSourceColumn())));
    }

    /**
     * Decodes the view's closed verdict vocabulary into the {@link Rejection} arm the report
     * carries. The {@code directives} render arrives in {@link AuthoredClaim} declaration order
     * (the view's {@code LISTAGG} restates it), so the conflict names its directives in the
     * fixed order the class javadoc pins; the deferral's message is composed from the enum
     * constants the carve-out recognises, never from the render.
     */
    private static Rejection rejectionOf(String verdict, String directives) {
        if ("DEFERRED".equals(verdict)) {
            return Rejection.deferred("@" + AuthoredClaim.ROUTINE.directive() + " with @"
                + AuthoredClaim.LOOKUP_KEY.directive()
                + " on a root field classifies but does not emit yet");
        }
        var names = List.of(directives.split(","));
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
