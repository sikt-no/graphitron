package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Structural assertions over generated {@link TypeSpec}s and
 * {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter} output.
 * Exists to replace the {@code assertThat(method.code().toString()).contains(...)} pattern
 * banned by CLAUDE.md — callers ask typed questions ("does {@code $fields} project this field?",
 * "what kind of data fetcher is wired for this field?") instead of grepping raw rendered bodies.
 *
 * <p>Body-scan fragility is confined to this file. If the emitter's output shape changes, one
 * place needs updating instead of every call site.
 */
public final class TypeSpecAssertions {

    private TypeSpecAssertions() {}

    /** Categories of {@code .dataFetcher(…)} second argument emitted into a
     *  {@code GraphQLCodeRegistry.Builder} by the fetcher-registration emitter. */
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
     * Returns the data-fetcher kind wired for ({@code typeName}, {@code fieldName}) in the
     * {@code registerFetchers} body, or empty when no
     * {@code .dataFetcher(FieldCoordinates.coordinates("typeName", "fieldName"), …)} call is
     * present. Throws when the shape of the value expression is unrecognised — a safety net that
     * surfaces emitter changes at test time rather than silently returning a misclassification.
     *
     * @param body     the {@code registerFetchers} body, e.g. from
     *                 {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter#emit(no.sikt.graphitron.rewrite.GraphitronSchema)}
     *                 (call {@code .toString()} on the returned {@link CodeBlock})
     */
    public static Optional<DataFetcherKind> wiringFor(String body, String typeName, String fieldName) {
        Pattern p = Pattern.compile(
            "\\.dataFetcher\\(\\s*(?:[\\w.]+\\.)?FieldCoordinates\\.coordinates\\(\\s*\""
                + Pattern.quote(typeName) + "\"\\s*,\\s*\""
                + Pattern.quote(fieldName) + "\"\\s*\\)\\s*,\\s*(.*?)\\s*\\)(?=\\s*\\n|\\s*;|\\s*\\.)",
            Pattern.DOTALL);
        var m = p.matcher(body);
        if (!m.find()) return Optional.empty();
        String second = m.group(1).trim();
        if (second.startsWith("new ") && second.contains("ColumnFetcher")) {
            return Optional.of(DataFetcherKind.COLUMN_FETCHER);
        }
        if (second.contains("PropertyDataFetcher")) {
            return Optional.of(DataFetcherKind.PROPERTY_FETCHER);
        }
        if (second.startsWith("(") && second.contains("env) ->")) {
            // typed lambda: `(DataFetchingEnvironment env) -> …`
            return Optional.of(DataFetcherKind.LAMBDA);
        }
        if (second.startsWith("env ->") || second.startsWith("env->")) {
            return Optional.of(DataFetcherKind.LAMBDA);
        }
        if (second.contains("::")) {
            return Optional.of(DataFetcherKind.METHOD_REFERENCE);
        }
        throw new AssertionError(
            "Unrecognised dataFetcher shape for '" + typeName + "." + fieldName + "': " + second
            + " — extend TypeSpecAssertions.DataFetcherKind and this classifier.");
    }

    /** Convenience overload: resolves the body from {@code bodies.get(typeName)} (empty if absent). */
    public static Optional<DataFetcherKind> wiringFor(java.util.Map<String, CodeBlock> bodies,
            String typeName, String fieldName) {
        var block = bodies.get(typeName);
        return wiringFor(block == null ? "" : block.toString(), typeName, fieldName);
    }

    /**
     * True when {@code type}'s {@code $fields} method unconditionally appends a jOOQ column to
     * the projection via the {@code if (!fields.contains(table.COL)) fields.add(table.COL)}
     * idiom used for BatchKey-column projection (plan-single-cardinality-split-query §2 +
     * plan-splittablefield-nestingfield nested recursion). Caller supplies the jOOQ field's
     * Java name (e.g. {@code "FILM_ID"}).
     */
    public static boolean appendsRequiredColumn(TypeSpec type, String columnJavaName) {
        String body = methodBody(type, "$fields").orElse("");
        return body.contains("fields.add(table." + columnJavaName + ")");
    }

    private static Optional<String> methodBody(TypeSpec type, String methodName) {
        return type.methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .map(MethodSpec::code)
            .map(Object::toString);
    }
}
