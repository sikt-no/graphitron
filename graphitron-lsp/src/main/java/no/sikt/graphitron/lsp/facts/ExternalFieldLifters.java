package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.HashSet;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_EXTERNAL_FIELD_METHOD;

/**
 * Which of a class's methods an author may name in {@code @externalField(reference:)}, read from the
 * arm whose subject is that question.
 *
 * <p>A lifter is a method the generator calls for a jOOQ {@code Field} it cannot read off a table,
 * and what makes one is a conjunction: public, static, one parameter typed as a jOOQ table, a
 * {@code Field} return. The census states none of that. It carries no static flag at all, and its
 * return type is a name rather than a resolved type, so a surface reading the census can compare a
 * shape and no more. The arm applies the generator's own rule at capture, so this read is a lookup
 * where the shape check was a guess.
 *
 * <p>Keyed by descriptor and not by name. Two overloads of one name are two methods and the arm
 * admits them one at a time, so a class declaring {@code titleUpper(FilmTable)} beside
 * {@code titleUpper(String)} offers the first and not the second. Matching on the name would offer
 * both and put the editor back where it started.
 *
 * <p>An empty answer has two causes and the caller wants neither distinguished: a class declaring no
 * lifter, and a class the arm never read, which is every class outside the reactor. Both mean the
 * same thing to an author, no method here is known to bind, and a lifter genuinely cannot live in a
 * jar: it is called on the consumer's own generated tables and is written in the consumer's own code.
 */
public final class ExternalFieldLifters {

    private ExternalFieldLifters() {}

    /** The descriptors of {@code classFqn}'s admitted lifters, empty when it declares none. */
    public static Set<String> descriptorsOf(StoreHandle store, String classFqn) {
        return new HashSet<>(store.dsl()
            .selectDistinct(CODE_EXTERNAL_FIELD_METHOD.DESCRIPTOR)
            .from(CODE_EXTERNAL_FIELD_METHOD)
            .where(store.reads(CODE_EXTERNAL_FIELD_METHOD.SOURCE_NAME)
                .and(CODE_EXTERNAL_FIELD_METHOD.CLASS_NAME.eq(classFqn)))
            .fetch(CODE_EXTERNAL_FIELD_METHOD.DESCRIPTOR));
    }
}
