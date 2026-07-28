package no.sikt.graphitron.command;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of the condition command relation: everything needed to emit and call one coordinate's
 * WHERE surface. Keyed {@code (coordinate, table)}: a polymorphic root expands to one row per
 * participant table, so a participant row differs from its siblings in {@link #table} and the
 * key needs no second column. A coordinate with an empty live filter set has no row, and every
 * consumer composes the neutral condition from that absence.
 *
 * <p>{@link #glue} is total, one per row: the emitted method that extracts this coordinate's
 * argument values into named locals and composes its predicates into one {@code org.jooq.Condition},
 * {@code Condition <method>(<JooqTable> table, Map<String, Object> args)}. Callers supply
 * {@code env.getArguments()} or {@code <sf>.getArguments()}; both surfaces expose the same coerced
 * map, so the old per-site argument-source fork is one call-site expression.
 *
 * <p>Reserved local names in a glue body are the two parameters and the fold seed
 * ({@code table}, {@code args}, {@code condition}); the producer's binding and lift locals must
 * be collision-free against them and each other, and the compact constructor is that rule's
 * enforcer, per row and per fragment.
 */
public record ConditionCommand(
    FieldCoordinates coordinate,
    TableRef table,
    List<Predicate> predicates,
    UnitMethodRef glue,
    List<OuterLift> lifts,
    List<FacetFragment> facets
) {

    /** Local names every glue body already binds; producer-named locals must avoid them. */
    private static final List<String> RESERVED_LOCALS = List.of("table", "args", "condition");

    public ConditionCommand {
        if (coordinate == null) {
            throw new IllegalArgumentException("a condition row is keyed by its field coordinate");
        }
        if (table == null) {
            throw new IllegalArgumentException("a condition row is keyed by its resolved table");
        }
        if (predicates == null || predicates.isEmpty()) {
            throw new IllegalArgumentException(
                "a condition row carries at least one predicate; a coordinate with no live filters has no row");
        }
        predicates = List.copyOf(predicates);
        if (glue == null) {
            throw new IllegalArgumentException("glue is total: every condition row names its glue method");
        }
        lifts = lifts == null ? List.of() : List.copyOf(lifts);
        facets = facets == null ? List.of() : List.copyOf(facets);
        requireDistinctLocals(glue.methodName(), predicates, lifts);
        for (var fragment : facets) {
            requireDistinctLocals(fragment.method().methodName(), fragment.predicates(), fragment.lifts());
        }
        requireFragmentsPartitionGeneratedTerms(predicates, facets);
    }

    /**
     * The sibling-name-collision dissolution's enforcer: one local, one value. A local name binds exactly one
     * argument value within its method (two predicates consuming the same argument value share
     * the local; the renderer declares it once), and no local shadows a reserved name or a lift.
     * Making this a constructor failure is what turns "producer-named locals are collision-free"
     * from prose into an invariant.
     */
    private static void requireDistinctLocals(String method, List<Predicate> predicates, List<OuterLift> lifts) {
        var boundTo = new java.util.HashMap<String, Object>();
        for (var reserved : RESERVED_LOCALS) {
            boundTo.put(reserved, RESERVED_LOCALS);
        }
        for (var lift : lifts) {
            requireOneBinding(method, boundTo, lift.localName(), lift);
        }
        for (var binding : bindingsOf(predicates)) {
            requireOneBinding(method, boundTo, binding.localName(), binding.param());
        }
    }

    private static void requireOneBinding(String method, java.util.Map<String, Object> boundTo,
            String localName, Object value) {
        var existing = boundTo.putIfAbsent(localName, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException(
                "glue method '" + method + "' binds local '" + localName
                + "' to two different values; producer-named locals must be collision-free");
        }
    }

    /**
     * Facet fragments are masked variants of the row, nothing more: the base fragment's and the
     * per-facet fragments' generated terms together partition the row's generated terms. Making
     * the partition a constructor failure keeps "fragments re-render the same predicate list"
     * mechanical instead of asserted only by fixture.
     */
    private static void requireFragmentsPartitionGeneratedTerms(List<Predicate> predicates, List<FacetFragment> facets) {
        if (facets.isEmpty()) {
            return;
        }
        var rowTerms = generatedTermsOf(predicates);
        var fragmentTerms = new ArrayList<ColumnTerm>();
        for (var fragment : facets) {
            fragmentTerms.addAll(generatedTermsOf(fragment.predicates()));
        }
        if (fragmentTerms.size() != rowTerms.size()
            || !fragmentTerms.containsAll(rowTerms) || !rowTerms.containsAll(fragmentTerms)) {
            throw new IllegalArgumentException(
                "facet fragments must partition the row's generated terms (base keeps everything"
                + " no facet owns, each per-facet fragment keeps exactly its own term); got "
                + fragmentTerms.size() + " fragment terms over " + rowTerms.size() + " row terms");
        }
    }

    private static List<ColumnTerm> generatedTermsOf(List<Predicate> predicates) {
        var terms = new ArrayList<ColumnTerm>();
        for (var predicate : predicates) {
            if (predicate instanceof Predicate.Generated generated) {
                terms.addAll(generated.terms());
            }
        }
        return terms;
    }

    private static List<ArgBinding> bindingsOf(List<Predicate> predicates) {
        var bindings = new ArrayList<ArgBinding>();
        for (var predicate : predicates) {
            switch (predicate) {
                case Predicate.Generated generated -> generated.terms().forEach(t -> bindings.add(t.binding()));
                case Predicate.Authored authored -> bindings.addAll(authored.bindings());
            }
        }
        return bindings;
    }

    /** Every binding the row's own predicates consume, in declaration order (terms, then call order). */
    public List<ArgBinding> bindings() {
        return bindingsOf(predicates);
    }
}
