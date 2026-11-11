package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code PageInfo} class, emitted once per code-generation run: the page info every
 * connection hands its {@code pageInfo} field, one class shared by every connection. Its members are
 * named after {@link no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType#SPEC_FIELDS}, and each
 * of those the schema's {@code PageInfo} type declares is registered to read the member of its name, so the
 * type is wired like any other rather than read off a map by graphql-java's default fetcher.
 *
 * <p>A record, which the release-17 floor for generated sources allows. The start and end cursors
 * are null on an empty page.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public final class PageInfoClassGenerator {

    public static final String CLASS_NAME = "PageInfo";

    private PageInfoClassGenerator() {}

    public static List<TypeSpec> generate() {
        var string = ClassName.get(String.class);
        var constructor = MethodSpec.constructorBuilder()
            .addParameter(ParameterSpec.builder(TypeName.BOOLEAN, "hasNextPage").build())
            .addParameter(ParameterSpec.builder(TypeName.BOOLEAN, "hasPreviousPage").build())
            .addParameter(ParameterSpec.builder(string, "startCursor").build())
            .addParameter(ParameterSpec.builder(string, "endCursor").build())
            .build();
        return List.of(TypeSpec.recordBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .recordConstructor(constructor)
            .build());
    }
}
