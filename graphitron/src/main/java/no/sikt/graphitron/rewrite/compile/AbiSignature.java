package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The <em>signature-surface</em> (ABI) hash of a generated compilation unit. This is
 * the discriminator the recompile-set algorithm ({@link RecompileSet}) reads to decide whether a
 * changed unit propagates to its reverse-dependents: a body-only edit leaves the ABI hash still, an
 * edit to the public surface moves it.
 *
 * <p>The surface captured is exactly what a <em>dependent</em> unit recompiles against: type kind,
 * modifiers, type variables (with bounds), supertype, implemented interfaces, type-level annotations,
 * and, per member, its signature (field type; method type variables and return/parameter/thrown types)
 * with its annotations, plus the
 * <em>value</em> of every {@code static final} constant (because javac inlines those into callers, so
 * a value change is an ABI change even though no signature moves). What it deliberately drops is the
 * body: method code, static/instance initializer blocks, and javadoc. That drop is what makes a
 * body-only edit non-propagating (the spec's pruning clause); keeping the surface a superset of
 * javac's true cross-unit dependencies is what keeps the incremental compile sound (the completeness
 * clause).
 *
 * <p>Two deliberate over-approximations, both correctness-safe (they can only <em>widen</em> the
 * surface, never drop a real dependency) and both pruning-harmless on deterministic generated output:
 * members are canonicalised order-independently (a pure reorder does not move the hash), and every
 * {@code static final} initializer is folded in, not only provably compile-time-constant ones (a
 * runtime-constant value edit over-propagates, but the generator does not emit such edits in
 * isolation). Non-private visibility is <em>not</em> filtered: a private-member change over-propagates
 * rather than risk dropping a surface a same-package dependent can see.
 *
 * <p>This hash is <strong>derived state, not a parallel type system</strong>: the surface it encodes
 * is the same type facts the classified model already carries (supertypes, signatures, field types).
 * Per "model metadata over parallel type systems", once the data model surfaces those facts it is a
 * candidate to derive from them directly instead of re-reading the rendered {@link TypeSpec}, the same
 * re-source-from-facts move as the {@code CompileDependencyGraph} sourcing seam.
 */
public final class AbiSignature {

    private AbiSignature() {}

    /** Hex SHA-256 of {@link #of(TypeSpec)}: the stable ABI fingerprint of {@code type}. */
    public static String hash(TypeSpec type) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(of(type).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The canonical, deterministic rendering of {@code type}'s signature surface (bodies excluded).
     * Package-visible companion to {@link #hash} so tests can assert on the human-readable surface,
     * not only its digest.
     */
    static String of(TypeSpec type) {
        StringBuilder sb = new StringBuilder();
        appendType(sb, type);
        return sb.toString();
    }

    private static void appendType(StringBuilder sb, TypeSpec type) {
        sb.append(type.kind().name())
            .append(' ').append(type.name())
            .append(" typevars=").append(typeVariables(type.typeVariables()))
            .append(" mods=").append(modifiers(type.modifiers()))
            .append(" ann=").append(annotations(type.annotations()))
            .append(" extends=").append(type.superclass() == null ? "" : type.superclass().toString())
            .append(" implements=").append(typeNames(type.superinterfaces()))
            .append('\n');

        // Enum constants are ordinal-significant (values()/ordinal() are ABI), so their order is kept.
        for (Map.Entry<String, TypeSpec> constant : type.enumConstants().entrySet()) {
            sb.append("  const ").append(constant.getKey()).append('\n');
        }

        List<String> fields = new ArrayList<>();
        for (FieldSpec field : type.fieldSpecs()) {
            fields.add(fieldSignature(field));
        }
        appendSorted(sb, "  field ", fields);

        List<String> methods = new ArrayList<>();
        for (MethodSpec method : type.methodSpecs()) {
            methods.add(methodSignature(method));
        }
        appendSorted(sb, "  method ", methods);

        // Nested types recurse; ordered by their own signature so a reorder does not move the hash.
        List<String> nested = new ArrayList<>();
        for (TypeSpec inner : type.typeSpecs()) {
            nested.add(of(inner));
        }
        nested.sort(Comparator.naturalOrder());
        for (String signature : nested) {
            for (String line : signature.split("\n", -1)) {
                if (!line.isEmpty()) {
                    sb.append("  nested ").append(line).append('\n');
                }
            }
        }
    }

    private static String fieldSignature(FieldSpec field) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifiers(field.modifiers()))
            .append(' ').append(field.type().toString())
            .append(' ').append(field.name())
            .append(annotations(field.annotations()));
        // static final constants are inlined by javac into callers, so their VALUE is part of the ABI.
        if (field.modifiers().contains(Modifier.STATIC)
            && field.modifiers().contains(Modifier.FINAL)
            && field.initializer() != null
            && !field.initializer().isEmpty()) {
            sb.append(" = ").append(field.initializer().toString());
        }
        return sb.toString();
    }

    private static String methodSignature(MethodSpec method) {
        StringBuilder sb = new StringBuilder();
        sb.append(modifiers(method.modifiers())).append(' ');
        if (!method.typeVariables().isEmpty()) {
            sb.append(typeVariables(method.typeVariables())).append(' ');
        }
        if (method.isConstructor()) {
            sb.append("<init>");
        } else {
            sb.append(method.returnType() == null ? "void" : method.returnType().toString())
                .append(' ').append(method.name());
        }
        sb.append('(');
        List<String> params = new ArrayList<>();
        for (ParameterSpec parameter : method.parameters()) {
            params.add(parameter.type().toString());
        }
        sb.append(String.join(",", params));
        if (method.varargs()) {
            sb.append("...");
        }
        sb.append(')');
        sb.append(" throws=").append(typeNames(method.exceptions()));
        sb.append(annotations(method.annotations()));
        // Method body (method.code()) is deliberately excluded: body-only edits must not move the hash.
        return sb.toString();
    }

    private static void appendSorted(StringBuilder sb, String prefix, List<String> signatures) {
        signatures.sort(Comparator.naturalOrder());
        for (String signature : signatures) {
            sb.append(prefix).append(signature).append('\n');
        }
    }

    /**
     * Type variables in declaration order (they are positional: {@code Foo<A, B>} binds by index, so a
     * reorder is itself an ABI change), each with its bounds — a bound change alone (no signature
     * mentioning the variable moves) must still move the hash.
     */
    private static String typeVariables(List<TypeVariableName> typeVariables) {
        List<String> rendered = new ArrayList<>();
        for (TypeVariableName tv : typeVariables) {
            rendered.add(tv.name() + ":" + typeNames(tv.bounds()));
        }
        return rendered.toString();
    }

    private static String modifiers(Iterable<Modifier> modifiers) {
        List<String> names = new ArrayList<>();
        for (Modifier modifier : modifiers) {
            names.add(modifier.name());
        }
        names.sort(Comparator.naturalOrder());
        return names.toString();
    }

    private static String annotations(List<AnnotationSpec> annotations) {
        if (annotations.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>();
        for (AnnotationSpec annotation : annotations) {
            rendered.add(annotation.toString());
        }
        rendered.sort(Comparator.naturalOrder());
        return rendered.toString();
    }

    private static String typeNames(List<TypeName> types) {
        List<String> rendered = new ArrayList<>();
        for (TypeName type : types) {
            rendered.add(type.toString());
        }
        rendered.sort(Comparator.naturalOrder());
        return rendered.toString();
    }
}
