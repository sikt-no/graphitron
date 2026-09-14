package no.sikt.graphitron.rewrite.test.conditions;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import org.jooq.Condition;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * Condition-method fixtures for the {@code @condition} rail whose parameters are fed by an
 * {@code argMapping} key projection: the method takes the decoded key column of a {@code @nodeId},
 * not the wire id.
 *
 * <p>A field-level method is bound to the whole field, so it is called once per statement whatever
 * the client sent, and an omitted {@code @nodeId} on the projected path arrives here as
 * {@code null}. That is the contract these methods are written against, and what absence means is
 * the author's to decide: the one below reads it as "unconstrained". The contrasting rail is the
 * FK-target {@code @nodeId} whole-slot binding, where an absent value contributes no predicate and
 * the method is not called at all.
 */
public final class ProjectedKeyConditionFixtures {

    private ProjectedKeyConditionFixtures() {}

    /**
     * Narrows films to the named language, and constrains nothing when no language was named. The
     * projected parameter is {@code language_id} off a decoded {@code @nodeId}, reached through an
     * {@code argMapping} that descends into an input object, so a client omitting that input field
     * reaches this method with {@code null}.
     */
    public static Condition filmsOfOptionalLanguage(Table<?> table, Integer languageId) {
        if (languageId == null) {
            return DSL.noCondition();
        }
        return table.field(Film.FILM.LANGUAGE_ID).eq(languageId);
    }

    /**
     * The same binding against a parameter declared as a Java primitive, which the build refuses.
     * The projected read is boxed at every path shape, an omitted {@code @nodeId} projecting
     * {@code null}, and {@code int p = <boxed null>} compiles and then fails at the unboxing, so the
     * compiler is no backstop and the refusal is the generator's. Wired by a rejection fixture only;
     * nothing emits against it.
     */
    public static Condition filmsOfOptionalLanguagePrimitive(Table<?> table, int languageId) {
        return table.field(Film.FILM.LANGUAGE_ID).eq(languageId);
    }
}
