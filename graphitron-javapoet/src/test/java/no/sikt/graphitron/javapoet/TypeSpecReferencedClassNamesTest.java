package no.sikt.graphitron.javapoet;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R455 workstream A — the reference walk must see every structured {@code $T}, including ones nested
 * inside {@code $L} blocks / anonymous classes / annotations and ones reachable only through
 * type-variable bounds. These were the blind spots that let {@link TypeSpec#referencedClassNames()}
 * silently under-report, falsifying the compile-graph completeness oracle's superset guarantee.
 */
public final class TypeSpecReferencedClassNamesTest {

    private static final ClassName TARGET = ClassName.get("com.example", "Target");
    private static final ClassName OTHER = ClassName.get("com.example", "Other");
    private static final ClassName BOUND = ClassName.get("com.example", "Bound");
    private static final ClassName MARKER = ClassName.get("com.example", "Marker");

    @Test
    public void seesTypeArgNestedInsideCodeBlockPassedAsLiteralArg() {
        // A $T two $L levels deep: addStatement wraps the outer block as $L, which carries a $L block
        // which carries the $T. Before the fix, argToLiteral stored each block opaque and the $T was lost.
        CodeBlock innermost = CodeBlock.of("$T.create()", TARGET);
        CodeBlock middle = CodeBlock.builder().add("wrap($L)", innermost).build();
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addMethod(MethodSpec.methodBuilder("m").addStatement("use($L)", middle).build())
            .build();

        assertThat(host.referencedClassNames()).contains(TARGET);
    }

    @Test
    public void seesTypeReferencesInsideAnonymousClassPassedAsLiteralArg() {
        TypeSpec anonymous = TypeSpec.anonymousClassBuilder("")
            .addSuperinterface(Runnable.class)
            .addField(TARGET, "field")
            .addMethod(MethodSpec.methodBuilder("run")
                .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
                .addStatement("$T.noop()", OTHER)
                .build())
            .build();
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addMethod(MethodSpec.methodBuilder("m")
                .addStatement("$T r = $L", Runnable.class, anonymous)
                .build())
            .build();

        assertThat(host.referencedClassNames()).contains(TARGET, OTHER);
    }

    @Test
    public void seesAnnotationTypePassedAsLiteralArg() {
        AnnotationSpec annotation = AnnotationSpec.builder(MARKER).build();
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addMethod(MethodSpec.methodBuilder("m").addStatement("register($L)", annotation).build())
            .build();

        assertThat(host.referencedClassNames()).contains(MARKER);
    }

    @Test
    public void seesTypeLevelTypeVariableBounds() {
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addTypeVariable(TypeVariableName.get("T", BOUND))
            .build();

        assertThat(host.referencedClassNames()).contains(BOUND);
    }

    @Test
    public void seesMethodLevelTypeVariableBounds() {
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addMethod(MethodSpec.methodBuilder("m")
                .addTypeVariable(TypeVariableName.get("T", BOUND))
                .build())
            .build();

        assertThat(host.referencedClassNames()).contains(BOUND);
    }

    @Test
    public void recursiveTypeVariableBoundTerminatesAndIsCollected() {
        // T extends Comparable<T>: walking the declared type variable descends its bounds, which
        // reference the type variable again. The identity-visited guard makes this terminate rather
        // than recurse forever; Comparable is collected.
        TypeVariableName t = TypeVariableName.get("T")
            .withBounds(ParameterizedTypeName.get(ClassName.get(Comparable.class), TypeVariableName.get("T")));
        TypeSpec host = TypeSpec.classBuilder("Host").addTypeVariable(t).build();

        assertThat(host.referencedClassNames()).contains(ClassName.get(Comparable.class));
    }

    @Test
    public void doesNotSeeClassNameBakedIntoARawString() {
        // The documented residual: a class name in a $L String / $S is a raw string, not a structured
        // reference, so it is deliberately invisible.
        TypeSpec host = TypeSpec.classBuilder("Host")
            .addMethod(MethodSpec.methodBuilder("m")
                .addStatement("String s = $S", TARGET.canonicalName())
                .addStatement("String l = $L", "\"" + OTHER.canonicalName() + "\"")
                .build())
            .build();

        assertThat(host.referencedClassNames()).doesNotContain(TARGET, OTHER);
    }
}
