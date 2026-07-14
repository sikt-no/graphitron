package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Falsifiability unit for {@link EmittedMethodClosure}: the level-1 closure oracle is only worth
 * its green if a genuinely dangling callee turns it red and prose look-alikes do not. Hand-built
 * {@link TypeSpec} pairs pin the scanner's contract from both sides — a resolvable static call,
 * an import-resolved cross-package call, a same-package call with no import, a nested-type
 * callee, a method reference — and the negative space: a callee name the target does not declare
 * lands in {@code unresolved()}, while constructors, javadoc prose, string literals, enum-constant
 * reads, and non-generated qualifiers produce no edge at all.
 */
@UnitTier
class EmittedMethodClosureTest {

    private static final String PKG = "gen.pkg";

    @Test
    void resolvedStaticCall_isAnEdgeAndNotAViolation() {
        var walk = walk(
            unit("Caller", caller -> caller.addMethod(body("go",
                "$T.serve()", ClassName.get(PKG, "Target")))),
            unit("Target", target -> target.addMethod(staticMethod("serve"))));

        assertThat(walk.edges()).hasSize(1);
        assertThat(walk.hasEdge(PKG + ".Caller", PKG + ".Target", "serve")).isTrue();
        assertThat(walk.unresolved()).isEmpty();
    }

    @Test
    void calleeTheTargetDoesNotDeclare_isAViolation() {
        var walk = walk(
            unit("Caller", caller -> caller.addMethod(body("go",
                "$T.missing()", ClassName.get(PKG, "Target")))),
            unit("Target", target -> target.addMethod(staticMethod("serve"))));

        assertThat(walk.unresolved()).hasSize(1);
        assertThat(walk.unresolved().getFirst().methodName()).isEqualTo("missing");
    }

    @Test
    void crossPackageImportAndSamePackageBareName_bothResolve() {
        var other = ClassName.get("gen.other", "Far");
        var walk = walk(Map.of(
            PKG + ".Caller", unitSpec("Caller", caller -> caller.addMethod(body("go",
                "$T.far(); Near.near()", other))),
            PKG + ".Near", unitSpec("Near", near -> near.addMethod(staticMethod("near"))),
            "gen.other.Far", unitSpec("Far", far -> far.addMethod(staticMethod("far")))));

        assertThat(walk.hasEdge(PKG + ".Caller", "gen.other.Far", "far")).isTrue();
        assertThat(walk.hasEdge(PKG + ".Caller", PKG + ".Near", "near")).isTrue();
        assertThat(walk.unresolved()).isEmpty();
    }

    @Test
    void nestedTypeCallee_resolvesThroughTheTypePath_andEnumConstantReadDoesNot() {
        var target = TypeSpec.classBuilder("Target")
            .addModifiers(Modifier.PUBLIC)
            .addType(TypeSpec.classBuilder("Inner")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addMethod(staticMethod("inner"))
                .build())
            .addType(TypeSpec.enumBuilder("Kind")
                .addModifiers(Modifier.PUBLIC)
                .addEnumConstant("A")
                .addMethod(MethodSpec.methodBuilder("label")
                    .addStatement("return $S", "x").returns(String.class).build())
                .build())
            .build();
        var walk = walk(Map.of(
            PKG + ".Caller", unitSpec("Caller", caller -> caller.addMethod(body("go",
                // Inner.inner() resolves through the nested path; Kind.A.label() is an
                // enum-constant read (A is no nested type), so no static callee is recorded.
                "$T.Inner.inner(); $T.Kind.A.label()",
                ClassName.get(PKG, "Target"), ClassName.get(PKG, "Target")))),
            PKG + ".Target", target));

        assertThat(walk.hasEdge(PKG + ".Caller", PKG + ".Target.Inner", "inner")).isTrue();
        assertThat(walk.edges()).hasSize(1);
        assertThat(walk.unresolved()).isEmpty();
    }

    @Test
    void methodReferenceResolves_constructorAndConstructorRefDoNot() {
        var walk = walk(
            unit("Caller", caller -> caller.addMethod(body("go",
                "java.util.stream.Stream.of(1).map($T::serve); new $T()",
                ClassName.get(PKG, "Target"), ClassName.get(PKG, "Target")))),
            unit("Target", target -> target.addMethod(staticMethod("serve"))));

        assertThat(walk.hasEdge(PKG + ".Caller", PKG + ".Target", "serve")).isTrue();
        assertThat(walk.edges()).hasSize(1);
        assertThat(walk.unresolved()).isEmpty();
    }

    @Test
    void proseInJavadocAndStringLiterals_producesNoEdges() {
        var walk = walk(
            unit("Caller", caller -> caller.addMethod(MethodSpec.methodBuilder("go")
                .addJavadoc("Delegates to {@code Target.phantom()} eventually.\n")
                .addStatement("String s = $S", "call Target.phantom() here")
                .build())),
            unit("Target", target -> target.addMethod(staticMethod("serve"))));

        assertThat(walk.edges()).isEmpty();
        assertThat(walk.unresolved()).isEmpty();
    }

    @Test
    void nonGeneratedQualifiers_areIgnored() {
        var walk = walk(
            unit("Caller", caller -> caller.addMethod(body("go",
                "java.util.List.of(); $T.emptyList()", ClassName.get("java.util", "Collections")))),
            unit("Target", target -> target.addMethod(staticMethod("serve"))));

        assertThat(walk.edges()).isEmpty();
        assertThat(walk.unresolved()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------

    @SafeVarargs
    private static EmittedMethodClosure walk(Map.Entry<String, TypeSpec>... units) {
        Map<String, TypeSpec> byFqcn = new LinkedHashMap<>();
        for (var unit : units) {
            byFqcn.put(unit.getKey(), unit.getValue());
        }
        return EmittedMethodClosure.walk(byFqcn);
    }

    private static EmittedMethodClosure walk(Map<String, TypeSpec> units) {
        return EmittedMethodClosure.walk(units);
    }

    private static Map.Entry<String, TypeSpec> unit(String simpleName,
                                                    java.util.function.UnaryOperator<TypeSpec.Builder> body) {
        return Map.entry(PKG + "." + simpleName, unitSpec(simpleName, body));
    }

    private static TypeSpec unitSpec(String simpleName,
                                     java.util.function.UnaryOperator<TypeSpec.Builder> body) {
        return body.apply(TypeSpec.classBuilder(simpleName).addModifiers(Modifier.PUBLIC)).build();
    }

    private static MethodSpec staticMethod(String name) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
    }

    private static MethodSpec body(String name, String statement, Object... args) {
        return MethodSpec.methodBuilder(name).addStatement(statement, args).build();
    }
}
