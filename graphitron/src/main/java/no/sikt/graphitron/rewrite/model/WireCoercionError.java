package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the <em>wire-coercion</em>
 * failures a scalar/enum SDL leaf hits when its consumer-declared Java type does not match what
 * graphql-java actually delivers on the wire. The generator would otherwise emit a raw
 * {@code (DeclaredType) wireValue} cast that compiles cleanly and {@code ClassCastException}s (or,
 * for enums, {@code IllegalArgumentException}s) on the first real request.
 *
 * <p>graphql-java delivers {@code ID} and enum values as {@code String}, {@code Int} as
 * {@code Integer}, {@code Float} as {@code Double}, input-objects as {@code Map<String, Object>}.
 * So a declared cast target of a jOOQ record, a numeric PK type, a domain class, or a
 * width-mismatched numeric is a guaranteed runtime crash invisible at build time. The classifier
 * confirms the coercion output is assignable to the declared type before emitting {@code Direct};
 * a mismatch surfaces here instead.
 *
 * <p>Two arms on two distinct axes (per the spec's D3/D4):
 * <ul>
 *   <li>{@link Assignability} — the coercion <em>class</em> (String / Integer / Double / Map) does
 *       not equal the declared Java type. Sites A-D of the audit.</li>
 *   <li>{@link EnumConstantDivergence} — the declared type <em>is</em> the enum and assignment
 *       succeeds, but an SDL enum value name diverges from the Java constant names, so
 *       {@code Enum.valueOf((String) ...)} throws. A constant-name-set membership check, not a
 *       class-assignability check; a sibling arm rather than a second axis folded into
 *       {@link Assignability}. Site E.</li>
 * </ul>
 *
 * <p>Sibling to {@link ServiceMethodCallError} / {@link ReflectionError} / {@link UpdateRowsError}
 * / {@link DeleteRowsError} / {@link ErrorChannelWalkerError}: each typed arm carries the
 * structural data its diagnostic message needs and a stable {@link #lspCode()} under the
 * {@code graphitron.wire-coercion.} namespace so the LSP {@code Diagnostic} projector reads the
 * wire code without a separate dispatch table (see {@code Diagnostics.lspCodeOf}). Adding a permit
 * here lands with a {@code RejectionSeverityCoverageTest.sampleFor} branch and a
 * {@code typed-rejection.adoc} paragraph (both drift-guarded).
 */
public sealed interface WireCoercionError extends Rejection.AuthorError permits
    WireCoercionError.Assignability,
    WireCoercionError.EnumConstantDivergence
{
    /** LSP wire code under the {@code graphitron.wire-coercion.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        // The orchestrator's renderer prepends caller-specific prose via diagnostic projection.
        return this;
    }

    /**
     * The graphql-java coercion output for {@code sdlLeafType} does not match the declared Java
     * type at the call site, so the generated {@code (declaredType) wireValue} cast would throw at
     * runtime. Carries the SDL leaf type name (the scalar / list-of-scalar shape as written), the
     * fully-qualified coercion-output class graphql-java actually delivers
     * ({@code java.lang.String} for {@code ID} / enum, {@code java.lang.Integer} for {@code Int},
     * ...), the fully-qualified declared Java type the cast targets, and a {@code site} string
     * naming where the leaf lives (for the message and LSP related-information).
     */
    record Assignability(
        String sdlLeafType,
        String coercionOutputType,
        String declaredType,
        String site
    ) implements WireCoercionError {
        @Override public String message() {
            return site + ": GraphQL '" + sdlLeafType + "' is delivered by graphql-java as '"
                + coercionOutputType + "', which cannot be cast to the declared Java type '"
                + declaredType + "' — the generated cast would compile but throw ClassCastException"
                + " on the first request. Declare the Java type as '" + coercionOutputType
                + "' (or a supertype), or route the value through a converting scalar / @nodeId decode";
        }
        @Override public String lspCode() { return "graphitron.wire-coercion.assignability"; }
    }

    /**
     * The declared Java type is the enum and assignment succeeds, but one or more SDL enum value
     * names have no matching Java constant, so {@code Enum.valueOf((String) wireValue)} throws
     * {@code IllegalArgumentException} at runtime for those values. Carries the fully-qualified
     * Java enum class name, the divergent SDL value names, the full set of Java constant names (the
     * candidate space), and a {@code site} string. Populated from the {@code EnumMappingResolver}
     * parity result so the value-name comparison is single-homed with the column/arg enum path.
     */
    record EnumConstantDivergence(
        String enumClassName,
        List<String> divergentSdlValues,
        List<String> javaConstants,
        String site
    ) implements WireCoercionError {
        public EnumConstantDivergence {
            divergentSdlValues = List.copyOf(divergentSdlValues);
            javaConstants = List.copyOf(javaConstants);
        }
        @Override public String message() {
            return site + ": GraphQL enum value(s) " + quoteJoin(divergentSdlValues)
                + " have no matching constant on Java enum '" + enumClassName
                + "' (constants: " + quoteJoin(javaConstants) + ") — Enum.valueOf would throw"
                + " IllegalArgumentException at runtime. Rename the SDL value(s) to match the Java"
                + " constant names, or bind the divergent name via @field(name:)";
        }
        @Override public String lspCode() { return "graphitron.wire-coercion.enum-constant-divergence"; }

        private static String quoteJoin(List<String> names) {
            return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
        }
    }
}
