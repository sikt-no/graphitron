package no.sikt.graphitron.model.grammar;

/**
 * Splits a written reference to a Java constant on its last period, which is the whole of the
 * grammar the SDL admits wherever an author names one: a class FQN, then the field on it. A
 * {@code @scalarType(scalar:)} value is the shipped case.
 *
 * <p>Unlike {@link QualifiedNameGrammar}, this split has a malformed arm, and the difference is
 * about what the two grammars are for. A qualifier that partitions oddly still names something the
 * catalog either has or has not, so the non-match is the answer. A constant reference with no
 * usable period named no class at all, which is a distinct thing to tell an author from "that class
 * is not on your classpath", so the shape verdict is a value rather than a downstream miss.
 *
 * <p>It lives beside its sibling for the same reason that one gives: two modules read this split,
 * the generator resolving the constant against a classloader and the language server judging the
 * shape without one, and a private copy on either side would be a second opinion about a spelling
 * neither owns.
 */
public final class ConstantReferenceGrammar {

    private ConstantReferenceGrammar() {
    }

    /**
     * Outcome of splitting a constant reference. {@link Malformed} carries the value as written;
     * {@link Parsed} carries the class FQN and the field name.
     */
    public sealed interface Reference {
        record Parsed(String classFqn, String fieldName) implements Reference {}
        record Malformed(String value) implements Reference {}
    }

    /**
     * Splits {@code value} at its last period. A value with no period, a leading period, or a
     * trailing period is {@link Reference.Malformed}: each of those wrote something that names no
     * class.
     */
    public static Reference split(String value) {
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) {
            return new Reference.Malformed(value);
        }
        return new Reference.Parsed(value.substring(0, dot), value.substring(dot + 1));
    }
}
