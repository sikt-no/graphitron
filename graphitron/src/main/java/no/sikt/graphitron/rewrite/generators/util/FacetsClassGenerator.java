package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.command.GlobalCommand;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

/**
 * Generates the {@code Facets} class, emitted once per code-generation run where the schema has a
 * faceted connection: a record per facets type, one component per facet, and a record per facet
 * value type, its {@code value} and {@code count}. What a connection hands its facets field is an
 * instance of the first, and each facet's buckets instances of the second; every field of both
 * types is registered to read the component of its name, so neither is read off a map by
 * graphql-java's default fetcher.
 *
 * <p>A facet value's {@code value} is an {@code Object}: a facet value type is named by its GraphQL
 * value type and shared by facets over columns of different Java types, so the class cannot know the
 * value's class; the column's own type decodes it and the scalar or enum coerces it.
 *
 * <p>Renders the facets command row the plan produces off the classified facets types; a
 * store-reading emitter takes it over with the generated connection classes.
 */
public final class FacetsClassGenerator {

    public static final String CLASS_NAME = "Facets";

    private static final ClassName LIST = ClassName.get(List.class);

    /** Names a record component may not take: Java keywords and {@code Object}'s own methods. */
    private static final Set<String> RESERVED = Set.of(
        "hashCode", "toString", "equals", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    private FacetsClassGenerator() {}

    /** The record component, and so accessor, a field of a facets or facet value type reads. */
    public static String memberName(String fieldName) {
        return SourceVersion.isKeyword(fieldName) || RESERVED.contains(fieldName) ? fieldName + "_" : fieldName;
    }

    public static List<TypeSpec> generate(GlobalCommand.Facets command, String outputPackage) {
        var facets = ClassName.get(outputPackage + ".util", CLASS_NAME);
        var outer = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        for (String valueType : command.facetValueTypes()) {
            outer.addType(record(valueType, List.of(
                ParameterSpec.builder(ClassName.get(Object.class), "value").build(),
                ParameterSpec.builder(TypeName.INT, "count").build())));
        }
        for (var facetsType : command.facetsTypes()) {
            outer.addType(record(facetsType.name(), facetsType.facets().stream()
                .map(facet -> ParameterSpec.builder(
                    ParameterizedTypeName.get(LIST, facets.nestedClass(facet.facetValueType())),
                    memberName(facet.fieldName())).build())
                .toList()));
        }
        return List.of(outer.build());
    }

    private static TypeSpec record(String name, List<ParameterSpec> components) {
        var constructor = MethodSpec.constructorBuilder();
        components.forEach(constructor::addParameter);
        return TypeSpec.recordBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .recordConstructor(constructor.build())
            .build();
    }
}
