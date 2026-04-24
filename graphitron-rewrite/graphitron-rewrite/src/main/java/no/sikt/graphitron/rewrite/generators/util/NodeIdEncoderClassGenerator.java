package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Generates the {@code NodeIdEncoder} utility class, emitted once per code-generation run
 * alongside other rewrite output.
 *
 * <p>The generated class produces opaque node IDs from a type discriminator and a list of
 * key-column values. Wire format matches the legacy {@code NodeIdStrategy} encoding so that
 * IDs round-trip between generators:
 * <pre>{@code
 * "typeId:v1,v2,..."  →  base64-url (no padding, UTF-8)
 * }</pre>
 * Commas inside values are escaped as {@code %2C}. If any value is {@code null} the method
 * returns {@code null} so the GraphQL field resolves to {@code null} rather than emitting a
 * malformed ID.
 *
 * <p>Generated as a source file rather than shipped as a library dependency so that consuming
 * projects have no runtime dependency on Graphitron itself.
 */
public class NodeIdEncoderClassGenerator {

    public static final String CLASS_NAME = "NodeIdEncoder";

    private static final ClassName BASE64       = ClassName.get(Base64.class);
    private static final ClassName CHARSETS     = ClassName.get(StandardCharsets.class);

    public static List<TypeSpec> generate() {
        var privateCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        var encode = MethodSpec.methodBuilder("encode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "typeId")
            .addParameter(Object[].class, "values")
            .varargs(true)
            .addStatement("$T sb = new StringBuilder(typeId).append(':')", StringBuilder.class)
            .beginControlFlow("for (int i = 0; i < values.length; i++)")
                .addStatement("Object v = values[i]")
                .addStatement("if (v == null) return null")
                .addStatement("if (i > 0) sb.append(',')")
                .addStatement("sb.append(v.toString().replace($S, $S))", ",", "%2C")
            .endControlFlow()
            .addStatement("return $T.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes($T.UTF_8))",
                BASE64, CHARSETS)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateCtor)
            .addMethod(encode)
            .build();

        return List.of(spec);
    }
}
