package no.sikt.graphitron.rewrite;

import graphql.language.StringValue;
import graphql.schema.GraphQLDirectiveContainer;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.DataElement;
import no.sikt.graphitron.rewrite.model.FieldWrapper;

import java.util.Optional;

/**
 * Shared callables for the {@code @field(name:)} root-value sigils.
 *
 * <p>Two sigil literals are admitted: {@code $source} (R159), binding the upstream Java
 * value at the carrier-payload data field site to the SDL field; and {@code $errors}
 * (R178), binding the SDL field to {@code env.getLocalContext()} for errors-shaped fields
 * on payload-returning mutation types. The helper owns the canonical rejection messages
 * (unknown sigil, not-defined-here, type mismatch); the classifier, the LSP completions
 * arm, and the LSP diagnostics arm all route through these methods so a message tweak in
 * one place updates all three consumer surfaces.
 *
 * <p>The helper is sibling to {@link BuildContext#argString} rather than a replacement.
 * Existing call sites that read raw {@code @field(name:)} as a free-form string stay
 * structurally identical to today; only sites that opt into sigil awareness call into
 * {@link #parseArgFieldNameRef}.
 */
public final class FieldSourceSigil {

    /** R159 sigil literal. Authors write this exact value in {@code @field(name:)} to bind the SDL field to {@code env.getSource()}. */
    public static final String UPSTREAM_ROOT_LITERAL = "$source";

    /**
     * R178 sigil literal. Authors write this exact value in {@code @field(name:)} on an
     * errors-shaped field of a payload-returning mutation type to force the
     * {@code env.getLocalContext()} transport for that field's DataFetcher, bypassing the
     * default-name accessor-then-localContext fallback that fires for an unannotated
     * {@code errors}-named field.
     */
    public static final String LOCAL_CONTEXT_LITERAL = "$errors";

    private FieldSourceSigil() {}

    /**
     * Parsed form of a {@code @field(name:)} argument value. Three arms:
     * {@link BareName} carries the literal column / accessor name (current single-segment
     * shape); {@link UpstreamRoot} represents the {@code $source} sigil (R159);
     * {@link LocalContext} represents the {@code $errors} sigil (R178).
     */
    public sealed interface FieldNameRef
            permits FieldNameRef.BareName, FieldNameRef.UpstreamRoot, FieldNameRef.LocalContext {
        record BareName(String value) implements FieldNameRef {}
        record UpstreamRoot() implements FieldNameRef {}
        record LocalContext() implements FieldNameRef {}
    }

    /**
     * Outcome of {@link #parseArgFieldNameRef}. Three arms:
     *
     * <ul>
     *   <li>{@link Absent} — the directive or {@code name} argument is unset; callers fall
     *       through to whatever the directive-absent path is.</li>
     *   <li>{@link Ok} — the argument resolves to a {@link FieldNameRef}.</li>
     *   <li>{@link UnknownSigil} — the argument starts with {@code $} but is not the one
     *       admitted sigil literal; carries the raw text for the canonical rejection
     *       message.</li>
     * </ul>
     */
    public sealed interface ParseResult permits ParseResult.Absent, ParseResult.Ok, ParseResult.UnknownSigil {
        record Absent() implements ParseResult {}
        record Ok(FieldNameRef ref) implements ParseResult {}
        record UnknownSigil(String raw) implements ParseResult {}
    }

    /**
     * Reads {@code container.getAppliedDirective(directive).getArgument(arg)} and lifts the
     * raw string into a {@link ParseResult}. Sibling to {@link BuildContext#argString};
     * the two-helper split is load-bearing for the rule "sites that never accepted sigils
     * stay structurally identical to today" — a future refactor that rewrites
     * {@code argString} to delegate through this helper is incorrect by construction.
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

    /**
     * Pure raw-string parser exposed for unit-tier rule-table pinning. {@code null} and
     * blank-after-strip inputs map to {@link ParseResult.Absent}; sigil prefixes ({@code $}-
     * leading) map to {@link ParseResult.Ok} or {@link ParseResult.UnknownSigil} per the
     * admitted-literal table; everything else maps to {@link ParseResult.Ok} carrying a
     * {@link FieldNameRef.BareName}. Sibling of {@link #parseArgFieldNameRef}: that helper
     * extracts the raw value from a {@link GraphQLDirectiveContainer} and delegates here,
     * so the directive-container reads and the literal-parsing logic stay separately
     * testable.
     */
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
     * The set of sites where the sigil is admitted today. R159 has exactly one admitted
     * site ({@link CarrierDataField}); every other site classifies as {@link Other}.
     * Sealed so future broadening adds a new arm rather than reshaping call sites.
     */
    public sealed interface SiteContext permits SiteContext.CarrierDataField, SiteContext.Other {
        /** Carrier-payload data field on a {@code @service}-backed mutation (the R158 admit). */
        record CarrierDataField() implements SiteContext {}
        /** Every other site: record-backed, table-backed, POJO, root, etc. */
        record Other() implements SiteContext {}
    }

    /**
     * True only at the R159-admitted site (carrier-payload data field today). Consumers
     * (classifier admit, LSP completion, LSP diagnostic) ask one question and get one
     * answer; broadening admit in a future item flips a single return value here.
     */
    public static boolean sourceSigilDefinedAt(SiteContext ctx) {
        return ctx instanceof SiteContext.CarrierDataField;
    }

    /** Canonical message for {@code @field(name: "$X")} where {@code $X} is not an admitted sigil literal. */
    public static String unknownSigilMessage(String raw) {
        return "Unknown sigil '" + raw + "' on @field(name:); allowed: "
            + UPSTREAM_ROOT_LITERAL + ", " + LOCAL_CONTEXT_LITERAL;
    }

    /**
     * Canonical message for {@code @field(name: "$source")} at a site that does not admit
     * the sigil. The LSP overlays this at AST-validation time; the build's classifier
     * surfaces the same message via the existing {@code UnclassifiedField} route only at
     * the carrier-data-field site (other sites surface today's generic unknown-accessor /
     * unknown-column rejection unchanged).
     */
    public static String sourceSigilNotDefinedHereMessage() {
        return "'" + UPSTREAM_ROOT_LITERAL + "' is not defined at this site; "
            + "it is only valid on the data field of a carrier-payload type returned by a "
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
     * Type-matching predicate at the admitted carrier-data-field site. Compares the
     * producer's reflected return {@link TypeName} against the SDL data element's
     * expected backing class:
     *
     * <ul>
     *   <li>{@link DataElement.Table}: expected element class is the table's record class.
     *       List-wrapping is preserved on both sides; {@link FieldWrapper.List} requires
     *       the producer to return {@code List<RecordClass>} or {@code Result<RecordClass>},
     *       {@link FieldWrapper.Single} requires the producer to return {@code RecordClass}
     *       directly.</li>
     *   <li>{@link DataElement.Record}: expected element class is the
     *       {@link DataElement.Record#fqClassName()}; the same list-wrapping rule applies.
     *       Exact equality today; future items may relax to assignability when a forcing
     *       function appears.</li>
     *   <li>{@link DataElement.Id}: out of scope; {@code $source} on ID-element carriers
     *       is not admitted. Returns a rejection.</li>
     * </ul>
     *
     * <p>Returns {@link Optional#empty()} on match; on mismatch, returns the canonical
     * message produced by {@link #typeMismatchMessage}.
     */
    public static Optional<String> sourceSigilTypeMatches(
            TypeName producerReturnType, String producerClassName, String producerMethodName,
            DataElement element) {
        TypeName expectedElementClass = expectedElementClass(element);
        if (expectedElementClass == null) {
            return Optional.of(typeMismatchMessage(producerClassName, producerMethodName,
                producerReturnType.toString(), "<no backing class for element kind "
                + element.getClass().getSimpleName() + ">"));
        }
        boolean sdlIsList = element.wrapper().isList();
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

    private static TypeName expectedElementClass(DataElement element) {
        return switch (element) {
            case DataElement.Table t -> t.table().recordClass();
            case DataElement.Record r -> ClassName.bestGuess(r.fqClassName());
            case DataElement.Id ignored -> null;
        };
    }

    /**
     * Returns the element {@link TypeName} of a producer return type, given whether the
     * SDL field is list-shaped. List-shaped: accepts {@code List<X>} or {@code Result<X>}
     * (their type-argument is the element). Single: accepts a bare {@link ClassName} only.
     * Returns null on shape mismatch (single SDL but list producer, list SDL but scalar
     * producer, list SDL but list of a parametric type we cannot peel into a single
     * element).
     */
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
