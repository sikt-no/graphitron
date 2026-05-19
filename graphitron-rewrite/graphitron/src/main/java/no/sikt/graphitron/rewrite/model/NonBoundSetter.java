package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Method;

/**
 * One non-bound SDL field on the setter shape: the setter to invoke and the literal default
 * value the emitter prints at the call site. Carried inside the {@code SetterMethod} arm of
 * {@link ErrorsSlot} so each emit site has all the data it needs to print
 * {@code p.setX(<default>);} statements for non-bound SDL fields, symmetric with the
 * all-fields-ctor arm's {@link DefaultedSlot} list.
 *
 * <p>The mutable-bean emit walks the surrounding SDL field list in declaration order and prints
 * one {@code p.setX(...);} per binding: the bound errors slot's setter receives the runtime
 * errors list and every {@code NonBoundSetter} prints its {@code defaultLiteral} as the
 * argument. After all setters, the emit returns {@code p}.
 *
 * @param setter         the Java-bean setter for the SDL field (e.g. {@code setData})
 * @param defaultLiteral the language-default literal to print as the setter argument
 *                       ({@code "null"} for reference / {@code Optional} types; {@code "0"},
 *                       {@code "0L"}, {@code "false"}, etc. for primitives)
 */
public record NonBoundSetter(Method setter, String defaultLiteral) {
    public NonBoundSetter {
        if (setter == null) {
            throw new IllegalArgumentException("NonBoundSetter: setter must be non-null");
        }
        if (defaultLiteral == null) {
            throw new IllegalArgumentException(
                "NonBoundSetter: defaultLiteral must be non-null (setter="
                    + setter.getName() + ")");
        }
    }
}
