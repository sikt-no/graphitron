package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.MaterializeDependencies;
import no.sikt.graphitron.model.derive.Materializations;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Realises a candidate materialization cut set inside a live store: the register reduced to the
 * registrations the candidate keeps, every excluded relation reading its rule again, and the refresh
 * edges re-derived for the register that leaves. What a case needs to ask what a <em>set</em> of
 * registrations costs, where {@link UnregisteredRelation} answers the same question for one.
 *
 * <p>{@link UnregisteredRelation} alone cannot answer it, and the reason is a cadence rather than a
 * gap in its surface. Its swap installs after a capture has run, so the refresh pass has already
 * paid for every registration by the time the excluded ones stop being tables; a set built with the
 * swap alone therefore prices reads and nothing else. Recovering the other axis by summing the
 * excluded registrations' own refresh figures would be worse than incomplete: a retained
 * registration whose source view reads a target the candidate removed gets <em>dearer</em>, not
 * cheaper, so the sum is wrong in the direction that flatters the smaller set.
 *
 * <p>So the candidate is realised where the register states it, before the refresh, and in four
 * steps whose <b>order is the mechanism</b>:
 *
 * <ol>
 *   <li>empty {@code meta_materialize_dependency} wholesale;</li>
 *   <li>delete the excluded registrations' rows from {@code meta_materialize};</li>
 *   <li>{@link UnregisteredRelation#install} each excluded registration, so its canonical name
 *   resolves to the rule instead of to a table nothing will fill;</li>
 *   <li>{@link MaterializeDependencies#populate}, which rewrites the edges for the register that
 *   remains.</li>
 * </ol>
 *
 * <p><b>Step 1 is what the schema forces, and it clears rather than authors.</b>
 * {@code meta_materialize_dependency} carries a foreign key into {@code meta_materialize} on both of
 * its columns with no {@code ON DELETE} action, and H2 checks immediately, so step 2 throws on its
 * own for any excluded registration an edge names on either side, which in a twelve-stage register
 * is practically every candidate. The one statement the keys leave available is the unqualified
 * delete, which is where {@link MaterializeDependencies#populate}'s own transaction opens: step 1 is
 * that writer's first move run early, not a second writer's edit. Between it and step 4 the relation
 * is empty and a refresh order taken there would degrade to the register's own key order, which is
 * why this method does all four steps and refreshes nothing.
 *
 * <p><b>Step 4 is not optional and a caller may not author the edges instead.</b> The rows that bite
 * are a retained registration depending on an excluded one, and no hand edit has an answer for them:
 * keeping the row makes step 2 throw, and dropping it alone loses a constraint silently, because
 * after step 3 the retained view evaluates the excluded rule live and so reaches the deeper retained
 * targets it must still refresh after. Only the walk re-derives those transitive edges, which is
 * also why step 3 precedes step 4: before the swap the excluded canonical name is still a base table
 * and the walk ends at a base table, deriving no onward edge at all.
 *
 * <p>Takes {@link Materializations.Registration} rather than relation names, {@link
 * UnregisteredRelation}'s reason and one this class needs more: a candidate is a subset of a
 * register, and a name that no row holds is a candidate about a different store. {@link
 * #excluding(DSLContext, String...)} and {@link #keepingOnly(DSLContext, Set)} are the two ways a
 * case states one without spelling the register out.
 *
 * <p>One-way per store, and more thoroughly so than {@link UnregisteredRelation}: this rewrites the
 * register itself. Take a store from {@link FactStores}, realise one candidate in it, measure, and
 * close it; expect one store per candidate.
 */
public final class CandidateCutSet {

    private CandidateCutSet() {}

    /**
     * Reduces {@code dsl}'s register to {@code candidate} and leaves the store ready to refresh:
     * the four steps above, in that order, with nothing refreshed in between.
     *
     * <p>The caller refreshes afterwards, through {@link Materializations#refresh} or
     * {@link Materializations#refreshAll} with a {@link no.sikt.graphitron.model.derive.RefreshProgress}
     * of its own, and that is what prices the candidate's refresh axis directly rather than by
     * summing anything.
     *
     * @param dsl the store's own writer surface, this being DDL and registry writes rather than
     *     reads; measure through a reader minted afterwards, per {@link UnregisteredRelation}
     * @param candidate the registrations to keep, a subset of {@link Materializations#registrations}
     * @throws IllegalArgumentException if the candidate names a registration the register does not
     *     hold, which is a candidate written against a different store
     */
    public static void realise(DSLContext dsl, Set<Materializations.Registration> candidate) {
        List<Materializations.Registration> register = Materializations.registrations(dsl);
        var strangers = new TreeSet<String>();
        candidate.stream()
            .filter(registration -> !register.contains(registration))
            .forEach(registration -> strangers.add(registration.sourceViewName()));
        if (!strangers.isEmpty()) {
            throw new IllegalArgumentException("the candidate names " + strangers
                + ", which this store's register does not hold; a candidate is a subset of"
                + " Materializations.registrations(dsl), taken from the store it is realised in");
        }
        List<Materializations.Registration> excluded = register.stream()
            .filter(registration -> !candidate.contains(registration))
            .toList();

        dsl.deleteFrom(table(name("META_MATERIALIZE_DEPENDENCY"))).execute();
        for (Materializations.Registration registration : excluded) {
            dsl.deleteFrom(table(name("META_MATERIALIZE")))
                .where(field(name("SOURCE_VIEW_NAME"), String.class)
                    .eq(registration.sourceViewName()))
                .execute();
        }
        for (Materializations.Registration registration : excluded) {
            UnregisteredRelation.install(dsl, registration);
        }
        MaterializeDependencies.populate(dsl);
    }

    /**
     * The register minus the registrations whose source views {@code sourceViewNames} spells: the
     * leave-one-out shape, and the way a case states a candidate by what it drops.
     *
     * @throws IllegalArgumentException if a name is not a registered source view in this store
     */
    public static Set<Materializations.Registration> excluding(DSLContext dsl,
                                                               String... sourceViewNames) {
        var dropping = Set.of(sourceViewNames);
        List<Materializations.Registration> register = Materializations.registrations(dsl);
        var unknown = new TreeSet<>(dropping);
        register.forEach(registration -> unknown.remove(registration.sourceViewName()));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("no registration in this store has the source view "
                + unknown + ", so a candidate excluding it would exclude nothing");
        }
        return register.stream()
            .filter(registration -> !dropping.contains(registration.sourceViewName()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The registrations whose source views {@code sourceViewNames} spells, and no others: the shape
     * a designed candidate takes, where a handful of cuts are named and everything else goes.
     *
     * @throws IllegalArgumentException if a name is not a registered source view in this store
     */
    public static Set<Materializations.Registration> keepingOnly(DSLContext dsl,
                                                                 Set<String> sourceViewNames) {
        List<Materializations.Registration> register = Materializations.registrations(dsl);
        var unknown = new TreeSet<>(sourceViewNames);
        register.forEach(registration -> unknown.remove(registration.sourceViewName()));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("no registration in this store has the source view "
                + unknown + ", so a candidate keeping it would keep nothing");
        }
        return register.stream()
            .filter(registration -> sourceViewNames.contains(registration.sourceViewName()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
