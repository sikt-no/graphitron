package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 3 — unit coverage of the signature-surface (ABI) hash. The load-bearing property is the
 * split the recompile-set algorithm rides on: a body-only edit must leave the hash still, while any
 * edit to the surface a dependent compiles against (a signature, a supertype, an implemented
 * interface, a field type, or a {@code static final} constant's value) must move it.
 */
@UnitTier
class AbiSignatureTest {

    private static final ClassName STRING = ClassName.get("java.lang", "String");

    /** A class with one constant, one field, and one method whose body we can vary independently. */
    private static TypeSpec.Builder base() {
        return TypeSpec.classBuilder("Widget")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.INT, "LIMIT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", 1).build())
            .addField(STRING, "name", Modifier.PRIVATE)
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addParameter(TypeName.INT, "id")
                .addStatement("return $S", "a").build());
    }

    @Test
    void bodyOnlyEditDoesNotMoveTheHash() {
        TypeSpec original = base().build();
        TypeSpec bodyEdited = TypeSpec.classBuilder("Widget")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.INT, "LIMIT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", 1).build())
            .addField(STRING, "name", Modifier.PRIVATE)
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addParameter(TypeName.INT, "id")
                // same signature, different body
                .addStatement("return $S", "a totally different body").build())
            .build();

        assertThat(AbiSignature.hash(bodyEdited)).isEqualTo(AbiSignature.hash(original));
    }

    @Test
    void constantValueEditMovesTheHash() {
        TypeSpec original = base().build();
        TypeSpec constantEdited = TypeSpec.classBuilder("Widget")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.INT, "LIMIT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", 2).build()) // 1 -> 2: javac inlines this into callers
            .addField(STRING, "name", Modifier.PRIVATE)
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addParameter(TypeName.INT, "id")
                .addStatement("return $S", "a").build())
            .build();

        assertThat(AbiSignature.hash(constantEdited)).isNotEqualTo(AbiSignature.hash(original));
    }

    @Test
    void methodSignatureEditMovesTheHash() {
        TypeSpec original = base().build();
        TypeSpec signatureEdited = base()
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addParameter(TypeName.LONG, "id") // int -> long
                .addStatement("return $S", "a").build())
            .build();

        assertThat(AbiSignature.hash(signatureEdited)).isNotEqualTo(AbiSignature.hash(original));
    }

    @Test
    void supertypeEditMovesTheHash() {
        TypeSpec original = base().build();
        TypeSpec superEdited = base()
            .superclass(ClassName.get("java.util", "AbstractList"))
            .build();

        assertThat(AbiSignature.hash(superEdited)).isNotEqualTo(AbiSignature.hash(original));
    }

    @Test
    void implementedInterfaceEditMovesTheHash() {
        TypeSpec original = base().build();
        TypeSpec interfaceEdited = base()
            .addSuperinterface(ClassName.get("java.io", "Serializable"))
            .build();

        assertThat(AbiSignature.hash(interfaceEdited)).isNotEqualTo(AbiSignature.hash(original));
    }

    @Test
    void fieldTypeEditMovesTheHash() {
        TypeSpec original = base().build();
        TypeSpec fieldEdited = TypeSpec.classBuilder("Widget")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldSpec.builder(TypeName.INT, "LIMIT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", 1).build())
            .addField(ClassName.get("java.lang", "CharSequence"), "name", Modifier.PRIVATE) // String -> CharSequence
            .addMethod(MethodSpec.methodBuilder("describe")
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addParameter(TypeName.INT, "id")
                .addStatement("return $S", "a").build())
            .build();

        assertThat(AbiSignature.hash(fieldEdited)).isNotEqualTo(AbiSignature.hash(original));
    }

    @Test
    void typeVariableBoundEditMovesTheHash() {
        // A bound change alone moves no member signature (the variable's name stays "T" everywhere), so
        // the type-variable declaration itself must be part of the surface, on the type and per method.
        TypeSpec unbounded = base()
            .addTypeVariable(TypeVariableName.get("T"))
            .build();
        TypeSpec bounded = base()
            .addTypeVariable(TypeVariableName.get("T", ClassName.get("java.lang", "Number")))
            .build();
        assertThat(AbiSignature.hash(bounded)).isNotEqualTo(AbiSignature.hash(unbounded));

        TypeSpec genericMethod = base()
            .addMethod(MethodSpec.methodBuilder("lift")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeVariableName.get("R"))
                .returns(TypeVariableName.get("R"))
                .addStatement("return null").build())
            .build();
        TypeSpec genericMethodBounded = base()
            .addMethod(MethodSpec.methodBuilder("lift")
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariable(TypeVariableName.get("R", ClassName.get("java.lang", "Number")))
                .returns(TypeVariableName.get("R"))
                .addStatement("return null").build())
            .build();
        assertThat(AbiSignature.hash(genericMethodBounded)).isNotEqualTo(AbiSignature.hash(genericMethod));
    }

    @Test
    void memberReorderDoesNotMoveTheHash() {
        // Members are canonicalised order-independently, so a pure reorder is not an ABI change.
        TypeSpec ab = TypeSpec.classBuilder("Widget")
            .addMethod(MethodSpec.methodBuilder("a").addModifiers(Modifier.PUBLIC).build())
            .addMethod(MethodSpec.methodBuilder("b").addModifiers(Modifier.PUBLIC).build())
            .build();
        TypeSpec ba = TypeSpec.classBuilder("Widget")
            .addMethod(MethodSpec.methodBuilder("b").addModifiers(Modifier.PUBLIC).build())
            .addMethod(MethodSpec.methodBuilder("a").addModifiers(Modifier.PUBLIC).build())
            .build();

        assertThat(AbiSignature.hash(ba)).isEqualTo(AbiSignature.hash(ab));
    }
}
