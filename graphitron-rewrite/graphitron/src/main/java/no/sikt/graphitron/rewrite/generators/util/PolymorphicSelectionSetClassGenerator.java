package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code PolymorphicSelectionSet} utility class, emitted once per code-generation run.
 *
 * <p>Carries a single static factory, {@code restrictTo(source, concreteTypeName)}, that returns a
 * delegating view of a {@code DataFetchingFieldSelectionSet}. The view's
 * {@code getFieldsGroupedByResultKey()} retains only entries whose
 * {@code SelectedField.getObjectTypeNames()} contains {@code concreteTypeName}; all other methods
 * delegate to the source unchanged.
 *
 * <p>The Stage-2 per-typename SELECT in the multi-table polymorphic dispatcher
 * ({@code MultiTablePolymorphicEmitter.buildPerTypenameSelect}) feeds the wrapped view into the
 * emitted {@code <Type>.$fields(...)} call so each per-typename SELECT projects only columns
 * actually selected for that variant. The wrapper closes the over-selection hole described in
 * roadmap R108.
 *
 * <p>Design note: this is a deliberate, localised wire-boundary adapter (a delegating proxy over a
 * graphql-java interface), justified because the {@code $fields} contract reads
 * {@code SelectedField.getSelectionSet()} during nested-projection recursion. A bare
 * {@code Map<String, List<SelectedField>>} argument would not survive that contract; returning
 * {@code DataFetchingFieldSelectionSet} is the minimum-disruption shape that keeps the diff
 * localised to one emitter call site. The wrapper sits at the same wire-boundary tier as
 * {@code ConnectionHelper.encodeCursor} / {@code decodeCursor}: <em>wire-format encoding is a
 * boundary concern, never a model concern</em>. The shape is not a template for further proxies
 * over graphql-java types.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public class PolymorphicSelectionSetClassGenerator {

    public static final String CLASS_NAME = "PolymorphicSelectionSet";

    private static final ClassName SELECTION_SET  = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName LIST           = ClassName.get(List.class);
    private static final ClassName ARRAY_LIST     = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP            = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    public static List<TypeSpec> generate() {
        var listOfSelectedField = ParameterizedTypeName.get(LIST, SELECTED_FIELD);
        var mapStringList       = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfSelectedField);

        var sourceField   = FieldSpec.builder(SELECTION_SET, "source", Modifier.PRIVATE, Modifier.FINAL).build();
        var typeNameField = FieldSpec.builder(String.class,  "concreteTypeName", Modifier.PRIVATE, Modifier.FINAL).build();

        var nestedConstructor = MethodSpec.constructorBuilder()
            .addParameter(SELECTION_SET, "source")
            .addParameter(String.class, "concreteTypeName")
            .addStatement("this.source = source")
            .addStatement("this.concreteTypeName = concreteTypeName")
            .build();

        // The one materially-overridden method. Walks the source's grouped map, filters each
        // entry's SelectedFields by getObjectTypeNames().contains(concreteTypeName), drops keys
        // whose filtered list is empty.
        var getFieldsGrouped = MethodSpec.methodBuilder("getFieldsGroupedByResultKey")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(mapStringList)
            .addStatement("$T result = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .addCode("for ($T<String, $T> entry : source.getFieldsGroupedByResultKey().entrySet()) {\n",
                ClassName.get("java.util", "Map", "Entry"), listOfSelectedField)
            .addStatement("    $T matched = new $T<>(entry.getValue().size())", listOfSelectedField, ARRAY_LIST)
            .addCode("    for ($T sf : entry.getValue()) {\n", SELECTED_FIELD)
            .addCode("        if (sf.getObjectTypeNames().contains(concreteTypeName)) {\n")
            .addStatement("            matched.add(sf)")
            .addCode("        }\n")
            .addCode("    }\n")
            .addCode("    if (!matched.isEmpty()) {\n")
            .addStatement("        result.put(entry.getKey(), matched)")
            .addCode("    }\n")
            .addCode("}\n")
            .addStatement("return result")
            .build();

        // Delegations. Every other method on DataFetchingFieldSelectionSet defers to source so
        // graphql-java's contract (especially the nested-projection recursion that walks
        // SelectedField.getSelectionSet()) keeps working without a parallel implementation.
        var stringVarArg = ArrayTypeName.of(String.class);

        var contains = MethodSpec.methodBuilder("contains")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(String.class, "fieldGlobPattern")
            .addStatement("return source.contains(fieldGlobPattern)")
            .build();

        var containsAnyOf = MethodSpec.methodBuilder("containsAnyOf")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(String.class, "fieldGlobPattern")
            .addParameter(stringVarArg, "fieldGlobPatterns").varargs(true)
            .addStatement("return source.containsAnyOf(fieldGlobPattern, fieldGlobPatterns)")
            .build();

        var containsAllOf = MethodSpec.methodBuilder("containsAllOf")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(String.class, "fieldGlobPattern")
            .addParameter(stringVarArg, "fieldGlobPatterns").varargs(true)
            .addStatement("return source.containsAllOf(fieldGlobPattern, fieldGlobPatterns)")
            .build();

        var getFields = MethodSpec.methodBuilder("getFields")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfSelectedField)
            .addStatement("return source.getFields()")
            .build();

        var getImmediateFields = MethodSpec.methodBuilder("getImmediateFields")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfSelectedField)
            .addStatement("return source.getImmediateFields()")
            .build();

        var getFieldsGlob = MethodSpec.methodBuilder("getFields")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(listOfSelectedField)
            .addParameter(String.class, "fieldGlobPattern")
            .addParameter(stringVarArg, "fieldGlobPatterns").varargs(true)
            .addStatement("return source.getFields(fieldGlobPattern, fieldGlobPatterns)")
            .build();

        var getFieldsGroupedGlob = MethodSpec.methodBuilder("getFieldsGroupedByResultKey")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(mapStringList)
            .addParameter(String.class, "fieldGlobPattern")
            .addParameter(stringVarArg, "fieldGlobPatterns").varargs(true)
            .addStatement("return source.getFieldsGroupedByResultKey(fieldGlobPattern, fieldGlobPatterns)")
            .build();

        var filtered = TypeSpec.classBuilder("Filtered")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(SELECTION_SET)
            .addField(sourceField)
            .addField(typeNameField)
            .addMethod(nestedConstructor)
            .addMethod(getFieldsGrouped)
            .addMethod(contains)
            .addMethod(containsAnyOf)
            .addMethod(containsAllOf)
            .addMethod(getFields)
            .addMethod(getImmediateFields)
            .addMethod(getFieldsGlob)
            .addMethod(getFieldsGroupedGlob)
            .build();

        var privateCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        var restrictTo = MethodSpec.methodBuilder("restrictTo")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(SELECTION_SET)
            .addParameter(SELECTION_SET, "source")
            .addParameter(String.class, "concreteTypeName")
            .addJavadoc("Returns a view of {@code source} whose\n"
                + "{@link $T#getFieldsGroupedByResultKey()} retains only entries\n"
                + "whose {@link $T#getObjectTypeNames()} contains\n"
                + "{@code concreteTypeName}. All other methods delegate to\n"
                + "{@code source} unchanged.\n",
                SELECTION_SET, SELECTED_FIELD)
            .addStatement("return new Filtered(source, concreteTypeName)")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateCtor)
            .addMethod(restrictTo)
            .addType(filtered)
            .build();

        return List.of(spec);
    }
}
