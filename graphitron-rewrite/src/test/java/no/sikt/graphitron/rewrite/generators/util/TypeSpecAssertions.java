package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Structural assertions over generated {@link TypeSpec}s. Exists to replace the
 * {@code assertThat(method.code().toString()).contains(...)} pattern banned by CLAUDE.md —
 * callers ask typed questions ("does {@code $fields} project this field?", "what kind of data
 * fetcher is wired for this field?") instead of grepping raw rendered bodies.
 *
 * <p>Body-scan fragility is confined to this file. If the emitter's output shape changes, one
 * place needs updating instead of every call site.
 */
public final class TypeSpecAssertions {

    private TypeSpecAssertions() {}

    /** Categories of {@code .dataFetcher(name, …)} second argument emitted by {@code wiring()}. */
    public enum DataFetcherKind {
        /** {@code new ColumnFetcher<>(…)} — scalar column projection. */
        COLUMN_FETCHER,
        /** {@code ClassName::methodName} — delegates to a generated fetcher method. */
        METHOD_REFERENCE,
        /** {@code env -> { … }} — inline lambda (e.g. single-cardinality multiset unwrap or backing-object cast). */
        LAMBDA,
        /** {@code PropertyDataFetcher.fetching(…)} — graphql-java reflective accessor for untyped POJO parents. */
        PROPERTY_FETCHER
    }

    /**
     * True when {@code type}'s {@code $fields} method contains a switch arm for {@code fieldName}.
     * The switch arm is emitted as {@code case "fieldName" -> …} (JavaPoet renders with the quoted
     * field name); this helper searches for that literal.
     */
    public static boolean hasFieldsArm(TypeSpec type, String fieldName) {
        return methodBody(type, "$fields")
            .map(body -> body.contains("case \"" + fieldName + "\""))
            .orElse(false);
    }

    /**
     * Returns the data-fetcher kind wired for {@code fieldName} in {@code type}'s {@code wiring()}
     * method, or empty when no {@code .dataFetcher("fieldName", …)} call is present. Throws when
     * the shape of the second argument is unrecognised — a safety net that surfaces emitter
     * changes at test time rather than silently returning a misclassification.
     */
    public static Optional<DataFetcherKind> wiringFor(TypeSpec type, String fieldName) {
        String body = methodBody(type, "wiring").orElse("");
        Pattern p = Pattern.compile(
            "\\.dataFetcher\\(\\s*\"" + Pattern.quote(fieldName) + "\"\\s*,\\s*(.*?)\\s*\\)(?=\\s*\\n|\\s*;|\\s*\\.)",
            Pattern.DOTALL);
        var m = p.matcher(body);
        if (!m.find()) return Optional.empty();
        String second = m.group(1).trim();
        // Dispatch on the leading shape of the second argument.
        if (second.startsWith("new ") && second.contains("ColumnFetcher")) {
            return Optional.of(DataFetcherKind.COLUMN_FETCHER);
        }
        if (second.startsWith("env ->") || second.startsWith("env->")) {
            return Optional.of(DataFetcherKind.LAMBDA);
        }
        if (second.contains("::")) {
            return Optional.of(DataFetcherKind.METHOD_REFERENCE);
        }
        if (second.contains("PropertyDataFetcher")) {
            return Optional.of(DataFetcherKind.PROPERTY_FETCHER);
        }
        throw new AssertionError(
            "Unrecognised dataFetcher shape for '" + fieldName + "': " + second
            + " — extend TypeSpecAssertions.DataFetcherKind and this classifier.");
    }

    /** Convenience: true when {@code wiring()} emits no {@code .dataFetcher(…)} at all. */
    public static boolean hasNoDataFetchers(TypeSpec type) {
        return !methodBody(type, "wiring").orElse("").contains(".dataFetcher(");
    }

    private static Optional<String> methodBody(TypeSpec type, String methodName) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .map(MethodSpec::code)
            .map(Object::toString);
    }
}
