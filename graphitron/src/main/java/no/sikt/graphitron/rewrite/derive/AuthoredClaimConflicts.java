package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
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
 * <p>The field grain returns its reduction as a typed {@link FieldVerdict} rather than a bare
 * error: the verdict carries the coordinate's {@link FieldClaim}s enriched with their decoded
 * slot facts, so the projection's {@code Conflicted} arm consumes the {@link FieldVerdict.Conflict}
 * type instead of re-testing the reduction predicate, and {@link Detection#violations()} derives
 * the error stream from the same verdicts at one site. The reduction evaluates once; the
 * {@link FieldVerdict} arm is chosen by the reduction's own output type
 * ({@link Rejection.Deferred} versus conflict), never by re-testing the claim set.
 *
 * <p>Locations join from the store rather than the walked model: a field violation carries the
 * field's own declared position ({@code graphql_field}), a type violation the type's base
 * declaration site ({@code graphql_type_declaration} at merge ordinal 0), which is what the
 * walk's {@code locationOf} read off the AST for the same coordinates. Each claim additionally
 * carries the claiming application's own position from the view row.
 *
 * <p>Minting is gated on {@link ClaimDomain} membership; the gate's rationale and removal
 * criterion live on that record.
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
     * Runs both grain detections over {@code graphName}'s partition. Empty for every
     * conflict-free graph.
     */
    public static Detection detect(DSLContext dsl, String graphName, ClaimDomain domain) {
        return new Detection(typeGrain(dsl, graphName, domain), fieldGrain(dsl, graphName, domain));
    }

    private record FieldCoordinate(String typeName, String fieldName) {}

    /** One claim-view row at a field coordinate, before slot-fact enrichment. */
    private record ClaimRow(AuthoredClaim classifier, String trigger, boolean decoded, SourceLocation location) {}

    private static List<FieldVerdict> fieldGrain(DSLContext dsl, String graphName, ClaimDomain domain) {
        var fc = INTENT_AUTHORED_FIELD_CLAIM;
        var gf = GRAPHQL_FIELD;
        var claims = new LinkedHashMap<FieldCoordinate, List<ClaimRow>>();
        var locations = new LinkedHashMap<FieldCoordinate, SourceLocation>();
        dsl.selectDistinct(fc.TYPE_NAME, fc.FIELD_NAME, fc.CLASSIFIER, fc.TRIGGER, fc.DECODED,
                fc.SOURCE_NAME, fc.SOURCE_LINE, fc.SOURCE_COLUMN,
                gf.SOURCE_NAME, gf.SOURCE_LINE, gf.SOURCE_COLUMN)
            .from(fc)
            .join(gf).on(gf.GRAPH_NAME.eq(fc.GRAPH_NAME),
                gf.TYPE_NAME.eq(fc.TYPE_NAME),
                gf.FIELD_NAME.eq(fc.FIELD_NAME))
            .where(fc.GRAPH_NAME.eq(graphName))
            .orderBy(fc.TYPE_NAME, fc.FIELD_NAME, fc.CLASSIFIER)
            .forEach(row -> {
                var coordinate = new FieldCoordinate(row.value1(), row.value2());
                claims.computeIfAbsent(coordinate, c -> new ArrayList<>())
                    .add(new ClaimRow(AuthoredClaim.fromClassifier(row.value3()), row.value4(),
                        Boolean.TRUE.equals(row.value5()), location(row.value6(), row.value7(), row.value8())));
                locations.putIfAbsent(coordinate, location(row.value9(), row.value10(), row.value11()));
            });

        var verdicts = new ArrayList<FieldVerdict>();
        claims.forEach((coordinate, rows) -> {
            var present = rows.stream().map(ClaimRow::classifier)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuthoredClaim.class)));
            if (present.size() < 2 || !domain.containsField(coordinate.typeName(), coordinate.fieldName())) {
                return;
            }
            var enriched = rows.stream()
                .sorted(Comparator.comparing(ClaimRow::classifier))
                .map(row -> enrich(dsl, graphName, coordinate, row))
                .toList();
            var rejection = reduce(present);
            var location = locations.get(coordinate);
            // The reduction's own output type is the discriminator; the claim-set predicate is
            // not re-tested here.
            verdicts.add(rejection instanceof Rejection.Deferred
                ? new FieldVerdict.Deferred(coordinate.typeName(), coordinate.fieldName(), enriched, rejection, location)
                : new FieldVerdict.Conflict(coordinate.typeName(), coordinate.fieldName(), enriched, rejection, location));
        });
        return verdicts;
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
                var rt = GRAPHITRON_ROUTINE;
                var r = row.decoded()
                    ? dsl.select(rt.ROUTINE_REF).from(rt)
                        .where(rt.GRAPH_NAME.eq(graphName), rt.TYPE_NAME.eq(c.typeName()), rt.FIELD_NAME.eq(c.fieldName()))
                        .orderBy(rt.ORDINAL)
                        .limit(1)
                        .fetchOne()
                    : null;
                yield new FieldClaim.Routine(
                    r == null ? null : r.value1(), row.trigger(), row.decoded(), row.location());
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
