package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import org.jooq.DSLContext;
import org.jooq.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;

/**
 * The build's errors for the claim defects: each {@code CLAIMS_CONFLICT} and
 * {@code ROUTINE_LOOKUP_DEFERRED} in {@code graphitron_entry_defect}, wherever it is written, with
 * the directives that wrote the claims and path steps under it, aggregated into the one message a
 * build report names them in. The defects and their claims are the facts; which directive wrote a
 * claim is read off the application at the claim's written position, and the message is this
 * reader's own.
 */
public final class ClaimConflicts {

    private ClaimConflicts() {}

    /** The conflict's code. */
    public static final String CONFLICT = "CLAIMS_CONFLICT";

    /** The routine-plus-lookup deferral's code. */
    public static final String DEFERRED = "ROUTINE_LOOKUP_DEFERRED";

    /** Every relation this component's statements name. */
    public static final Set<Table<?>> READS = Set.of(GRAPHITRON_ENTRY_DEFECT,
        GRAPHITRON_ENTRY_DEFECT_CLAIM, GRAPHITRON_ENTRY_DEFECT_PATH_STEP, GRAPHITRON_CLAIM,
        GRAPHITRON_DIRECTIVE_APPLICATION, GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
        GRAPHQL_AST_TYPE_DECLARATION_ENTRY);

    /** The detection pass's typed product. */
    public record Detection(List<ValidationError> violations) {

        public Detection {
            violations = List.copyOf(violations);
        }

        /** The empty detection, for callers running capture without the detection pass. */
        public static Detection empty() {
            return new Detection(List.of());
        }
    }

    /** A defect's key, the grouping the directive reads are collected under. */
    private record Site(String sourceName, int line, int column, String code) {}

    /** The claim defects over {@code graphName}'s partition, type grain first, each in name order. */
    public static Detection detect(DSLContext dsl, String graphName) {
        var directives = directives(dsl, graphName);
        var e = GRAPHITRON_ENTRY_DEFECT;
        var out = new ArrayList<ValidationError>();

        var t = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        dsl.select(t.NAME, e.CODE, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN)
            .from(e)
            .join(t).on(t.GRAPH_NAME.eq(e.GRAPH_NAME), t.SOURCE_NAME.eq(e.SOURCE_NAME),
                t.SOURCE_LINE.eq(e.SOURCE_LINE), t.SOURCE_COLUMN.eq(e.SOURCE_COLUMN))
            .where(e.GRAPH_NAME.eq(graphName), e.CODE.in(CONFLICT, DEFERRED))
            .orderBy(t.NAME)
            .forEach(r -> out.add(ValidationError.forType(r.value1(),
                rejectionOf(r.value2(), named(directives, r.value3(), r.value4(), r.value5(), r.value2())),
                new SourceLocation(r.value4(), r.value5(), r.value3()))));

        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        dsl.select(f.TYPE_NAME, f.NAME, e.CODE, e.SOURCE_NAME, e.SOURCE_LINE, e.SOURCE_COLUMN)
            .from(e)
            .join(f).on(f.GRAPH_NAME.eq(e.GRAPH_NAME), f.SOURCE_NAME.eq(e.SOURCE_NAME),
                f.SOURCE_LINE.eq(e.SOURCE_LINE), f.SOURCE_COLUMN.eq(e.SOURCE_COLUMN))
            .where(e.GRAPH_NAME.eq(graphName), e.CODE.in(CONFLICT, DEFERRED))
            .orderBy(f.TYPE_NAME, f.NAME)
            .forEach(r -> out.add(ValidationError.forField(r.value1() + "." + r.value2(),
                rejectionOf(r.value3(), named(directives, r.value4(), r.value5(), r.value6(), r.value3())),
                new SourceLocation(r.value5(), r.value6(), r.value4()))));
        return new Detection(out);
    }

    /**
     * The directives under each of the graph's claim defects: the one each claim was written by, read
     * off the application at the claim's position, and {@code routine} for each path step.
     */
    private static Map<Site, Set<String>> directives(DSLContext dsl, String graphName) {
        var out = new HashMap<Site, Set<String>>();
        var ec = GRAPHITRON_ENTRY_DEFECT_CLAIM;
        var c = GRAPHITRON_CLAIM;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        dsl.selectDistinct(ec.SOURCE_NAME, ec.SOURCE_LINE, ec.SOURCE_COLUMN, ec.CODE, da.DIRECTIVE_NAME)
            .from(ec)
            .join(c).on(c.GRAPH_NAME.eq(ec.GRAPH_NAME), c.COORDINATE.eq(ec.COORDINATE),
                c.KIND.eq(ec.KIND), c.CLAIMER.eq(ec.CLAIMER))
            .join(da).on(da.GRAPH_NAME.eq(c.GRAPH_NAME), da.SOURCE_NAME.eq(c.SOURCE_NAME),
                da.SOURCE_LINE.eq(c.SOURCE_LINE), da.SOURCE_COLUMN.eq(c.SOURCE_COLUMN))
            .where(ec.GRAPH_NAME.eq(graphName))
            .forEach(r -> out.computeIfAbsent(new Site(r.value1(), r.value2(), r.value3(), r.value4()),
                k -> new TreeSet<>()).add(r.value5()));
        var ps = GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
        dsl.selectDistinct(ps.SOURCE_NAME, ps.SOURCE_LINE, ps.SOURCE_COLUMN, ps.CODE)
            .from(ps)
            .where(ps.GRAPH_NAME.eq(graphName))
            .forEach(r -> out.computeIfAbsent(new Site(r.value1(), r.value2(), r.value3(), r.value4()),
                k -> new TreeSet<>()).add(AuthoredClaim.ROUTINE.directive()));
        return out;
    }

    /**
     * The directives a defect names, in {@link AuthoredClaim}'s declaration order, a directive
     * outside it after them in name order.
     */
    private static List<String> named(Map<Site, Set<String>> directives, String sourceName,
                                      int line, int column, String code) {
        var order = new HashMap<String, Integer>();
        for (var claim : AuthoredClaim.values()) {
            order.put(claim.directive(), claim.ordinal());
        }
        return directives.getOrDefault(new Site(sourceName, line, column, code), Set.of()).stream()
            .sorted(Comparator.comparing((String n) -> order.getOrDefault(n, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    /** The rejection a report carries for one claim defect, naming its directives. */
    private static Rejection rejectionOf(String code, List<String> directives) {
        if (DEFERRED.equals(code)) {
            return Rejection.deferred("@" + AuthoredClaim.ROUTINE.directive() + " with @"
                + AuthoredClaim.LOOKUP_KEY.directive()
                + " on a root field classifies but does not emit yet");
        }
        String named = directives.stream().map(n -> "@" + n).collect(Collectors.joining(", "));
        return Rejection.directiveConflict(directives, named + " are mutually exclusive");
    }
}
