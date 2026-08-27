package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.derive.ViewReferences.Enclosure;
import no.sikt.graphitron.model.derive.ViewReferences.Reference;
import org.jooq.DSLContext;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * How many times the fact store's rules are actually evaluated during one read of everything a
 * reader reads, and how that total moves when a candidate set of relations is materialized instead.
 *
 * <p>The quantity a naming metric reports is how many times a rule is <em>named</em>. That models
 * one of the three mechanisms the register's own reasons cite and scores the other two as one: a
 * rule named once inside a correlated subquery runs once per driving row, and one named inside a
 * recursive term runs once per iteration. {@link ViewReferences} reads those positions off the
 * stored definitions; this weights them, so a relation whose single naming costs nine hundred
 * evaluations stops ranking level with one whose single naming costs one.
 *
 * <p><strong>Cardinality is read once, off the store as it stands, and reused for every candidate
 * cut set.</strong> That is not a shortcut: what a relation holds does not depend on whether it is
 * stored or evaluated, materialization changing what a read costs and not what it answers. It is
 * also what makes a counterfactual safe to score. Counting rows of a relation with its registration
 * removed would mean evaluating the rule the registration exists to stop evaluating, and the
 * register holds relations whose reasons record that doing so does not terminate, so a metric that
 * counted per candidate set would hang on exactly the registrations it most needs to price.
 *
 * <p>A test-scope instrument, run when a cut set is in question rather than on a cadence. It boots
 * nothing of its own: hand it a store that a capture has filled, because an empty one makes every
 * cardinality zero and every weight one, which is the naming metric again wearing this one's name.
 */
public final class ReEvaluationMetric {

    /**
     * What one re-evaluating position costs, as a multiplier on the rule underneath it.
     *
     * <p>Two implementations, and the reason there are two is the acceptance test. Weighting by
     * cardinality is the instrument; weighting everything at one turns it back into a pure count of
     * rule instantiations, which is derivable by hand from a view body and so is the only reading
     * an assertion can be written against without a second implementation to agree with.
     */
    @FunctionalInterface
    public interface Weighting {

        /** How many times the enclosed rule runs, per run of what encloses it. */
        BigInteger multiplierFor(Enclosure enclosure, Cardinalities cardinalities);

        /** Every position counts once, which reduces the metric to a count of instantiations. */
        static Weighting uniform() {
            return (enclosure, cardinalities) -> BigInteger.ONE;
        }

        /**
         * Every position counts as the rows of the largest relation driving it.
         *
         * <p>The largest rather than the product: a join's output is not its inputs multiplied
         * except where nothing constrains it, and every driving side in this schema is a key join.
         * The largest input is the honest reading of a bound nobody has measured, and it is a
         * bound: a driving side is at least as many rows as its largest relation.
         *
         * <p>A position whose driving side the walk could not name counts as one and is reported as
         * unknown rather than silently taken as free. That direction is deliberate. Counting an
         * unnamed driver as some default would put a number the walk did not derive into the score,
         * where counting it as one leaves the score a floor and the count of unknowns says how much
         * floor there is.
         */
        static Weighting byCardinality() {
            return (enclosure, cardinalities) -> enclosure.drivers().stream()
                .map(cardinalities::rowsIn)
                .max(BigInteger::compareTo)
                .filter(rows -> rows.signum() > 0)
                .orElse(BigInteger.ONE);
        }
    }

    /** Row counts, taken from the store on demand and once per relation. */
    public static final class Cardinalities {

        private final DSLContext dsl;
        private final Map<String, BigInteger> counted = new HashMap<>();

        private Cardinalities(DSLContext dsl) {
            this.dsl = dsl;
        }

        /** How many rows the named relation holds, counted once and remembered. */
        public BigInteger rowsIn(String relation) {
            return counted.computeIfAbsent(relation, r -> BigInteger.valueOf(
                dsl.fetchCount(table(name(r.toUpperCase(Locale.ROOT))))));
        }
    }

    /**
     * What one scoring run found: the weighted total, its split across the readers that produced
     * it, and the count of positions whose driving side the walk could not name.
     *
     * @param instantiations weighted rule evaluations summed over every root reader
     * @param byRootReader the same total, per reader, so a distribution is visible and not just a
     *        sum: the register's own shape is one family carrying almost all of it
     * @param unknownDrivers positions counted at one for want of a named driving side, which is the
     *        amount by which this score is a floor. Counted once per rule rather than once per
     *        expansion of it, the expansions of a rule sharing its parse
     */
    public record Score(BigInteger instantiations, Map<String, BigInteger> byRootReader,
                        int unknownDrivers) {

        public Score {
            byRootReader = Map.copyOf(byRootReader);
        }
    }

    private final DSLContext dsl;
    private final Weighting weighting;
    private final Cardinalities cardinalities;
    private final Map<String, String> sourceViewOfTarget = new TreeMap<>();
    private final Map<String, String> kinds;
    private final Map<String, List<Reference>> parsed = new HashMap<>();
    private final List<String> rootReaders;

    private ReEvaluationMetric(DSLContext dsl, Weighting weighting) {
        this.dsl = dsl;
        this.weighting = weighting;
        this.cardinalities = new Cardinalities(dsl);
        this.kinds = relationKinds(dsl);
        Materializations.registrations(dsl)
            .forEach(r -> sourceViewOfTarget.put(r.targetTableName(), r.sourceViewName()));
        this.rootReaders = rootReaders();
    }

    /** An instrument over one store, its cardinalities and parses shared across every scoring. */
    public static ReEvaluationMetric over(DSLContext dsl, Weighting weighting) {
        return new ReEvaluationMetric(dsl, weighting);
    }

    /** The readers a real read enters the derivation through: views no other view names. */
    public List<String> rootReadersInStore() {
        return rootReaders;
    }

    /**
     * The weighted rule evaluations one read of every root reader costs, with the named
     * registrations standing as tables and every other registration expanded back into its rule.
     *
     * @param cutSet relations to treat as materialized, named as a reader spells them. Not
     *        restricted to relations the register holds: the question this instrument exists for is
     *        what a <em>different</em> cut set would cost, so any view in the store is a candidate
     */
    public Score score(Set<String> cutSet) {
        Map<String, BigInteger> byRootReader = new TreeMap<>();
        Map<String, BigInteger> memo = new HashMap<>();
        int[] unknown = {0};
        BigInteger total = BigInteger.ZERO;
        for (String reader : rootReaders) {
            BigInteger cost = instantiations(reader, cutSet, memo, unknown);
            byRootReader.put(reader, cost);
            total = total.add(cost);
        }
        return new Score(total, byRootReader, unknown[0]);
    }

    /**
     * The weighted evaluations one read of a single named view costs, which is {@link #score} over
     * one reader rather than all of them. What a case with a hand-derived answer asserts against.
     */
    public BigInteger instantiationsOf(String view, Set<String> cutSet) {
        return instantiations(view, cutSet, new HashMap<>(), new int[] {0});
    }

    /** The score with every registration in place, which is the store as a build leaves it. */
    public Score scoreAsRegistered() {
        return score(everyRegisteredRelation());
    }

    /**
     * What refilling the cut set costs, once per capture: one evaluation of each materialized
     * relation's rule, and everything that rule's own reads evaluate underneath it.
     *
     * <p>Charged separately from {@link #score} because it is the other half of what a
     * registration is, and the half no naming metric has ever counted. A registration buys its
     * readers a table and bills every capture for a refresh, so a set of them is worth having only
     * where the first exceeds the second. Keeping the two apart is also what leaves {@link #score}
     * with the monotonicity worth asserting, more materialization never making a read cost more.
     */
    public BigInteger refreshCost(Set<String> cutSet) {
        Map<String, BigInteger> memo = new HashMap<>();
        int[] unknown = {0};
        BigInteger total = BigInteger.ZERO;
        for (String relation : cutSet) {
            String rule = sourceViewOfTarget.getOrDefault(relation, relation);
            total = total.add(BigInteger.ONE.add(instantiations(rule, cutSet, memo, unknown)));
        }
        return total;
    }

    /**
     * What one capture and one read of everything cost together under a cut set: the reads plus
     * the refills that made them cheap. The number a cut set is chosen on.
     */
    public BigInteger totalCost(Set<String> cutSet) {
        return score(cutSet).instantiations().add(refreshCost(cutSet));
    }

    /**
     * What each registration is worth on its own against the register as it stands: the rise in
     * total evaluations when that one registration is removed and every other stays.
     *
     * <p>The leave-one-out reading, which understates any registration with a near-substitute in
     * the register, two relations covering the same subtree each looking worth little while the
     * pair is worth a great deal. It is reported because it is what a per-relation measurement can
     * see, and named as marginal so it is not read as the registration's value. A registration
     * whose refresh costs more than it saves scores negative here, which is a finding rather than
     * an error.
     */
    public Map<String, BigInteger> marginalByRelation(Set<String> cutSet) {
        BigInteger baseline = totalCost(cutSet);
        Map<String, BigInteger> marginal = new TreeMap<>();
        for (String relation : cutSet) {
            Set<String> without = new TreeSet<>(cutSet);
            without.remove(relation);
            marginal.put(relation, totalCost(without).subtract(baseline));
        }
        return marginal;
    }

    /** Every relation the register materializes, named as a reader spells it. */
    public Set<String> everyRegisteredRelation() {
        return new TreeSet<>(sourceViewOfTarget.keySet());
    }

    /**
     * The weighted evaluations one read of a rule costs, not counting the one evaluation of the
     * rule itself: each reference contributes its own weight times what the rule beneath it costs,
     * plus that one evaluation, and contributes nothing at all when what it names is stored.
     */
    private BigInteger instantiations(String relation, Set<String> cutSet,
            Map<String, BigInteger> memo, int[] unknown) {
        BigInteger remembered = memo.get(relation);
        if (remembered != null) {
            return remembered;
        }
        memo.put(relation, BigInteger.ZERO);
        BigInteger total = BigInteger.ZERO;
        for (Reference reference : referencesIn(relation)) {
            String rule = ruleBehind(reference.relation(), cutSet);
            if (rule == null) {
                continue;
            }
            BigInteger weight = BigInteger.ONE;
            for (Enclosure enclosure : reference.enclosing()) {
                if (enclosure.drivers().isEmpty()) {
                    unknown[0]++;
                }
                weight = weight.multiply(weighting.multiplierFor(enclosure, cardinalities));
            }
            total = total.add(weight.multiply(
                BigInteger.ONE.add(instantiations(rule, cutSet, memo, unknown))));
        }
        memo.put(relation, total);
        return total;
    }

    /**
     * The rule a reference makes the reader evaluate, or null when it makes it read stored rows: a
     * base table, or any relation the cut set materializes.
     *
     * <p>A relation the register already holds is a table in the store and its rule lives in the
     * source view beside it, so dropping it from a cut set resolves to that view. The rule is where
     * it is whether or not the registration stands, which is what lets a counterfactual be scored
     * without touching the store.
     */
    private String ruleBehind(String relation, Set<String> cutSet) {
        if (cutSet.contains(relation)) {
            return null;
        }
        String sourceView = sourceViewOfTarget.get(relation);
        if (sourceView != null) {
            return sourceView;
        }
        return "VIEW".equals(kinds.get(relation)) ? relation : null;
    }

    /** The references in one view's stored definition, parsed once per view and remembered. */
    private List<Reference> referencesIn(String view) {
        return parsed.computeIfAbsent(view, v -> ViewReferences.readBy(dsl, v));
    }

    /**
     * The views no other view names, which is where a real read enters the derivation.
     *
     * <p>A registration's source view is not one, and is not allowed to make one of something
     * else either. It reads on a refresh's behalf rather than a reader's, so what it names is not
     * thereby read by anybody, and counting it here twice over would be wrong in two directions at
     * once: it would price every registered rule as though a reader evaluated it, and it would
     * demote whatever it names out of the reader set. The second is the dangerous one and it is
     * why this rule is stated rather than assumed. A relation named only by refresh sources is
     * where a read enters the derivation as surely as any other, and treating a source view's
     * naming as a reader's naming took a whole family of relations out of every score silently,
     * leaving the registrations under it looking worth exactly nothing.
     *
     * <p>What a refresh costs is not dropped, it is charged where it belongs, by
     * {@link #refreshCost} against the cut set that asks for it.
     */
    private List<String> rootReaders() {
        Set<String> sourceViews = new TreeSet<>(sourceViewOfTarget.values());
        List<String> views = kinds.entrySet().stream()
            .filter(entry -> "VIEW".equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        Set<String> named = new TreeSet<>();
        for (String view : views) {
            if (!sourceViews.contains(view)) {
                named.addAll(ViewReferences.relationsReadBy(dsl, view));
            }
        }
        List<String> readers = new ArrayList<>();
        for (String view : views) {
            if (!named.contains(view) && !sourceViews.contains(view)) {
                readers.add(view);
            }
        }
        return List.copyOf(readers);
    }

    /** Every relation in the store's schema, lowercased, mapped to the engine's kind for it. */
    private static Map<String, String> relationKinds(DSLContext dsl) {
        Map<String, String> kinds = new TreeMap<>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch()
            .forEach(row -> kinds.put(row.value1().toLowerCase(Locale.ROOT), row.value2()));
        return kinds;
    }
}
