package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Actor;
import no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * R232 execution-tier fixtures: {@code @reference(path: [{condition: {...}}])} condition methods
 * used by the Sakila example schema to exercise the {@code @condition}-join SQL shape end-to-end.
 *
 * <p>Each method here expresses an existing FK relationship as a SQL predicate so the
 * condition-join emission path can be exercised against real data and its output compared with
 * the FK-equivalent navigation. Sakila's natural FKs are the source of truth; these methods are
 * intentionally non-novel so the execution-tier asserts the SQL shape itself (parent + condition
 * + parentInput JOIN; correlated subquery in the inline shape) rather than the predicate.
 */
public final class ReferencePathConditionFixtures {

    private ReferencePathConditionFixtures() {}

    /**
     * Inline-TableField fixture: condition expressing the natural {@code customer.address_id =
     * address.address_id} FK as a Java predicate. Used by {@code Customer.addressByCondition}
     * to exercise the inline emission shape (correlated subquery wrapped in
     * {@code DSL.multiset(...)} with the condition method providing the WHERE-side predicate).
     */
    public static Condition customerToAddress(Table<?> customerTable, Table<?> addressTable) {
        return customerTable.field("address_id", Integer.class)
            .eq(addressTable.field("address_id", Integer.class));
    }

    /**
     * SplitTableField fixture: condition expressing the {@code film -> film_actor -> actor}
     * junction as an EXISTS predicate. Used by {@code Film.actorsByCondition} to exercise the
     * split-rows emission shape ({@code FROM actor JOIN film ON cond(film, actor) JOIN
     * parentInput ON film.film_id = parentInput.film_id}). The EXISTS subquery is the only way
     * to express a junction-mediated relationship as a two-arg condition predicate.
     */
    public static Condition filmActorsViaCondition(Table<?> filmTable, Table<?> actorTable) {
        return DSL.exists(
            DSL.selectOne()
                .from(FilmActor.FILM_ACTOR)
                .where(FilmActor.FILM_ACTOR.FILM_ID
                    .eq(filmTable.field("film_id", Integer.class)))
                .and(FilmActor.FILM_ACTOR.ACTOR_ID
                    .eq(actorTable.field("actor_id", Integer.class)))
        );
    }

    /**
     * Bridging-hop fixture: the terminal {@code @condition} of an FK-then-condition path
     * ({@code [{key: "film_actor_film_id_fkey"}, {condition: filmActorJunctionToActor}]}), used by
     * {@code Film.actorsViaJunctionCondition}. The first hop is an FK to the {@code film_actor}
     * junction; this method is the second (terminal) hop joining the junction to {@code actor}, so
     * its parameters are the junction (source) and the leaf (target).
     *
     * <p>Unlike the {@code Table<?>}-typed fixtures above, the parameters are the concrete jOOQ
     * table types {@link FilmActor} and {@link Actor}. These are mutually incompatible, so the
     * argument order is type-checked at compile time: if the split-rows emitter reverses the two
     * aliases on this bridging hop (calling {@code filmActorJunctionToActor(actorAlias,
     * filmActorAlias)}), the generated resolver fails to compile in {@code compile-spec}. That is
     * the regression guard for the opptak {@code samordnaOrganisasjoner} incompatible-types defect,
     * which the {@code Table<?>}-typed first-hop fixtures could not catch.
     */
    public static Condition filmActorJunctionToActor(FilmActor filmActor, Actor actor) {
        return filmActor.ACTOR_ID.eq(actor.ACTOR_ID);
    }
}
