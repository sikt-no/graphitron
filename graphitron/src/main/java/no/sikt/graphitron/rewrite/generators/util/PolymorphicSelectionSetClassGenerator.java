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
 * Generates the {@code PolymorphicSelectionSet} utility class, emitted once per code-generation
 * run. Its static factory {@code restrictTo(source, concreteTypeName)} returns a delegating view
 * of a {@code DataFetchingFieldSelectionSet} whose {@code getFieldsGroupedByResultKey()} retains
 * only entries whose {@code SelectedField.getObjectTypeNames()} contains
 * {@code concreteTypeName}; the three-argument overload restricts only the entries whose field
 * name is in {@code perTypeFieldNames} and passes every other entry through whole.
 *
 * <p>Two consumers feed the wrapped view into the emitted {@code <Type>.$project(...)} call, for
 * the same reason at different granularities:
 * <ul>
 *   <li>the stage-2 per-typename SELECT in
 *       {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter} restricts
 *       everything (the two-argument form): each per-typename SELECT is its own statement, so it
 *       should project only what that variant selected and never a sibling participant's
 *       columns;</li>
 *   <li>the single-table discriminated fold in
 *       {@link no.sikt.graphitron.render.DiscriminatedTableFragments} restricts selectively (the
 *       three-argument form): its participants' projections merge into <em>one</em> select list,
 *       so a name every arm aliases identically has to keep every occurrence in every arm, while
 *       a name the participant type qualifies has to be scoped to that participant. The filter is
 *       what makes "a shared alias requires a shared occurrence set" hold by construction; see
 *       {@link no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.SelectionRestriction}.
 *       </li>
 * </ul>
 *
 * <p>The predicate reads {@code SelectedField.getName()}, never the map key: the key is a
 * client-minted result key, so keying on it would drop {@code x: interfaceField} out of the
 * exempt set for no reason the author could see.
 *
 * <p>Design note: a delegating proxy over a graphql-java interface is justified here because the
 * {@code $project} contract reads {@code SelectedField.getSelectionSet()} during nested-projection
 * recursion; a bare {@code Map<String, List<SelectedField>>} argument would not survive that
 * contract. The wrapper sits at the same wire-boundary tier as
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
    private static final ClassName SET            = ClassName.get("java.util", "Set");
    private static final ClassName ARRAY_LIST     = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP            = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    public static List<TypeSpec> generate() {
        var listOfSelectedField = ParameterizedTypeName.get(LIST, SELECTED_FIELD);
        var mapStringList       = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfSelectedField);

        var setOfString = ParameterizedTypeName.get(SET, ClassName.get(String.class));

        var sourceField   = FieldSpec.builder(SELECTION_SET, "source", Modifier.PRIVATE, Modifier.FINAL).build();
        var typeNameField = FieldSpec.builder(String.class,  "concreteTypeName", Modifier.PRIVATE, Modifier.FINAL).build();
        var perTypeField  = FieldSpec.builder(setOfString, "perTypeFieldNames", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Field names to restrict; {@code null} restricts every entry.\n")
            .build();

        var nestedConstructor = MethodSpec.constructorBuilder()
            .addParameter(SELECTION_SET, "source")
            .addParameter(String.class, "concreteTypeName")
            .addParameter(setOfString, "perTypeFieldNames")
            .addStatement("this.source = source")
            .addStatement("this.concreteTypeName = concreteTypeName")
            .addStatement("this.perTypeFieldNames = perTypeFieldNames")
            .build();

        // The one materially-overridden method.
        var getFieldsGrouped = MethodSpec.methodBuilder("getFieldsGroupedByResultKey")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(mapStringList)
            .addStatement("$T result = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .addCode("for ($T<String, $T> entry : source.getFieldsGroupedByResultKey().entrySet()) {\n",
                ClassName.get("java.util", "Map", "Entry"), listOfSelectedField)
            // Exempt entry: kept whole, so every arm of a merged select list sees the same
            // occurrences of a name they all alias identically. The name comes off the occurrence,
            // never off the map key, which is a client-minted result key.
            .addCode("    if (perTypeFieldNames != null && !entry.getValue().isEmpty()\n")
            .addCode("            && !perTypeFieldNames.contains(entry.getValue().get(0).getName())) {\n")
            .addStatement("        result.put(entry.getKey(), entry.getValue())")
            .addCode("        continue;\n")
            .addCode("    }\n")
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

        // Every other method defers to source so graphql-java's nested-projection recursion
        // (which walks SelectedField.getSelectionSet()) keeps working.
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
            .addField(perTypeField)
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
            .addStatement("return new Filtered(source, concreteTypeName, null)")
            .build();

        var restrictToSelective = MethodSpec.methodBuilder("restrictTo")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(SELECTION_SET)
            .addParameter(SELECTION_SET, "source")
            .addParameter(String.class, "concreteTypeName")
            .addParameter(setOfString, "perTypeFieldNames")
            .addJavadoc("Returns a view of {@code source} that restricts only the entries whose\n"
                + "{@link $T#getName()} is in {@code perTypeFieldNames}: those keep\n"
                + "only occurrences whose {@link $T#getObjectTypeNames()} contains\n"
                + "{@code concreteTypeName}, every other entry passes through whole.\n"
                + "\n"
                + "<p>For a select list several concrete types' projections merge into: a\n"
                + "field name every type aliases identically must keep the same occurrences\n"
                + "in every type's projection, or the identical alias would carry different\n"
                + "SQL per type and only one term would survive. Field names, not result\n"
                + "keys, so a client alias of a shared field stays exempt.\n"
                + "\n"
                + "<p>All other methods delegate to {@code source} unchanged.\n",
                SELECTED_FIELD, SELECTED_FIELD)
            .addStatement("return new Filtered(source, concreteTypeName, perTypeFieldNames)")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateCtor)
            .addMethod(restrictTo)
            .addMethod(restrictToSelective)
            .addType(filtered)
            .build();

        return List.of(spec);
    }
}
