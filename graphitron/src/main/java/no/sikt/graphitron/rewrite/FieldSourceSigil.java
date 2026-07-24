package no.sikt.graphitron.rewrite;

import graphql.language.StringValue;
import graphql.schema.GraphQLDirectiveContainer;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.Optional;

/**
 * Shared callables for the {@code @field(name:)} root-value sigils: {@code $source}
 * (binds the upstream Java value at the payload data field site) and {@code $errors}
 * (binds the field to {@code env.getLocalContext()} on payload-returning mutation types).
 * Owns the canonical rejection messages; the classifier and both LSP arms (completions,
 * diagnostics) route through these methods, so a message tweak lands on all three surfaces.
 *
 * <p>Sibling to {@link BuildContext#argString}, not a replacement: sites that read
 * {@code @field(name:)} as a free-form string stay on {@code argString}; only sites that
 * opt into sigil awareness call {@link #parseArgFieldNameRef}.
 */
public final class FieldSourceSigil {

    /** Sigil literal. Authors write this exact value in {@code @field(name:)} to bind the SDL field to {@code env.getSource()}. */
    public static final String UPSTREAM_ROOT_LITERAL = "$source";

    /**
     * Sigil literal. On an errors-shaped field of a payload-returning mutation type,
     * forces the {@code env.getLocalContext()} transport for that field's DataFetcher,
     * bypassing the accessor-then-localContext fallback that fires for an unannotated
     * {@code errors}-named field.
     */
    public static final String LOCAL_CONTEXT_LITERAL = "$errors";

    private FieldSourceSigil() {}

    /** Parsed form of a {@code @field(name:)} argument value: a bare column / accessor name, or one of the two sigils. */
    public sealed interface FieldNameRef
            permits FieldNameRef.BareName, FieldNameRef.UpstreamRoot, FieldNameRef.LocalContext {
        record BareName(String value) implements FieldNameRef {}
        record UpstreamRoot() implements FieldNameRef {}
        record LocalContext() implements FieldNameRef {}
    }

    /**
     * Outcome of {@link #parseArgFieldNameRef}. {@link Absent}: the directive or
     * {@code name} argument is unset; callers fall through to the directive-absent path.
     * {@link Ok}: resolves to a {@link FieldNameRef}. {@link UnknownSigil}:
     * {@code $}-prefixed but not an admitted literal; carries the raw text for
     * {@link #unknownSigilMessage}.
     */
    public sealed interface ParseResult permits ParseResult.Absent, ParseResult.Ok, ParseResult.UnknownSigil {
        record Absent() implements ParseResult {}
        record Ok(FieldNameRef ref) implements ParseResult {}
        record UnknownSigil(String raw) implements ParseResult {}
    }

    /**
     * Lifts the raw {@code @field(name:)} argument string into a {@link ParseResult}.
     * The split from {@link BuildContext#argString} is load-bearing: sites that never
     * accepted sigils must keep reading through {@code argString}, so a refactor that
     * routes {@code argString} through this helper is incorrect by construction.
     */
    public static ParseResult parseArgFieldNameRef(
            GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return new ParseResult.Absent();
        var argument = dir.getArgument(arg);
        if (argument == null) return new ParseResult.Absent();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return parseRawValue(sv.getValue());
        if (value instanceof String s) return parseRawValue(s);
        return new ParseResult.Absent();
    }

    /** Pure raw-string parser, package-visible so the unit tier can pin the rule table directly. */
    static ParseResult parseRawValue(String raw) {
        if (raw == null) return new ParseResult.Absent();
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return new ParseResult.Absent();
        if (trimmed.startsWith("$")) {
            if (UPSTREAM_ROOT_LITERAL.equals(trimmed)) {
                return new ParseResult.Ok(new FieldNameRef.UpstreamRoot());
            }
            if (LOCAL_CONTEXT_LITERAL.equals(trimmed)) {
                return new ParseResult.Ok(new FieldNameRef.LocalContext());
            }
            return new ParseResult.UnknownSigil(trimmed);
        }
        return new ParseResult.Ok(new FieldNameRef.BareName(trimmed));
    }

    /**
     * The set of sites where the sigil is admitted. Sealed so a broadened admit adds a
     * new arm rather than reshaping call sites.
     */
    public sealed interface SiteContext permits SiteContext.PayloadDataField, SiteContext.Other {
        /** Carrier-payload data field on a {@code @service}-backed mutation (the @service-carrier admit). */
        record PayloadDataField() implements SiteContext {}
        /** Every other site: record-backed, table-backed, POJO, root, etc. */
        record Other() implements SiteContext {}
    }

    /**
     * True only at the admitted site. The classifier admit, LSP completion, and LSP
     * diagnostic all route through this single predicate.
     */
    public static boolean sourceSigilDefinedAt(SiteContext ctx) {
        return ctx instanceof SiteContext.PayloadDataField;
    }

    /** Canonical message for {@code @field(name: "$X")} where {@code $X} is not an admitted sigil literal. */
    public static String unknownSigilMessage(String raw) {
        return "Unknown sigil '" + raw + "' on @field(name:); allowed: "
            + UPSTREAM_ROOT_LITERAL + ", " + LOCAL_CONTEXT_LITERAL;
    }

    /**
     * Canonical message for {@code @field(name: "$source")} at a site that does not admit
     * the sigil. The LSP overlays this at AST-validation time; the build's classifier
     * surfaces the same message via the {@code UnclassifiedField} route only at the
     * payload-data-field site (other sites keep the generic unknown-accessor /
     * unknown-column rejection).
     */
    public static String sourceSigilNotDefinedHereMessage() {
        return "'" + UPSTREAM_ROOT_LITERAL + "' is not defined at this site; "
            + "it is only valid on the data field of a payload type returned by a "
            + "@service-backed mutation.";
    }

    /**
     * Canonical message for {@code @field(name: "$source")} at the admitted carrier site
     * when the producer's reflected return type does not match the SDL element's backing
     * class.
     */
    public static String typeMismatchMessage(
            String producerClassName, String producerMethodName,
            String sourceReturnType, String expectedSdlElementType) {
        return "'" + UPSTREAM_ROOT_LITERAL + "' on @field(name:) binds the upstream Java "
            + "value to the SDL field, but the producer '" + producerClassName + "."
            + producerMethodName + "' returns '" + sourceReturnType
            + "' which does not match the SDL element's expected backing type '"
            + expectedSdlElementType + "'.";
    }

    /**
     * Type-matching predicate at the admitted site: compares the producer's reflected
     * return {@link TypeName} against the expected SDL element backing class. When
     * {@code sdlIsList}, the producer must return {@code List<expected>} or
     * {@code Result<expected>}; otherwise the bare class. Returns {@link Optional#empty()}
     * on match, else the {@link #typeMismatchMessage} text.
     */
    public static Optional<String> sourceSigilTypeMatches(
            TypeName producerReturnType, String producerClassName, String producerMethodName,
            TypeName expectedElementClass, boolean sdlIsList) {
        TypeName actualElement = unwrapListLike(producerReturnType, sdlIsList);
        if (actualElement == null) {
            return Optional.of(typeMismatchMessage(producerClassName, producerMethodName,
                producerReturnType.toString(),
                (sdlIsList ? "List<" : "") + expectedElementClass + (sdlIsList ? ">" : "")));
        }
        if (!actualElement.equals(expectedElementClass)) {
            return Optional.of(typeMismatchMessage(producerClassName, producerMethodName,
                producerReturnType.toString(),
                (sdlIsList ? "List<" : "") + expectedElementClass + (sdlIsList ? ">" : "")));
        }
        return Optional.empty();
    }

    /** Element {@link TypeName} of a producer return type, or null on shape mismatch. */
    private static TypeName unwrapListLike(TypeName producerReturnType, boolean sdlIsList) {
        if (sdlIsList) {
            if (producerReturnType instanceof ParameterizedTypeName parameterized
                    && parameterized.typeArguments().size() == 1
                    && (parameterized.rawType().equals(ClassName.get("java.util", "List"))
                        || parameterized.rawType().equals(ClassName.get("org.jooq", "Result")))) {
                return parameterized.typeArguments().get(0);
            }
            return null;
        }
        if (producerReturnType instanceof ClassName) {
            return producerReturnType;
        }
        return null;
    }
}
