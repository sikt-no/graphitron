package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.public_.enums.MpaaRating;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film;
import org.jooq.Condition;
import org.jooq.impl.DSL;

/**
 * Condition methods whose parameter shapes the store judges before a condition is emitted: one Java
 * enum, which a list of the enum's values cannot fill, and a member of a parameterised class, which
 * the generated condition cannot declare a local of yet. Never called.
 */
public final class ConditionParameterShapeFixtures {

    private ConditionParameterShapeFixtures() {}

    /** A class whose member is declared under its type argument. */
    public static final class Holder<T> {
        /** The member, spelled {@code Holder<T>.Inner} wherever the owner is parameterised. */
        public final class Inner {}
    }

    /** Films of one rating. */
    public static Condition ratedAs(Film film, MpaaRating rating) {
        return rating == null ? DSL.noCondition() : film.RATING.eq(rating);
    }

    /** A parameter typed as a parameterised class's member. */
    public static Condition byHeldMember(Film film, Holder<String>.Inner held) {
        return DSL.noCondition();
    }
}
