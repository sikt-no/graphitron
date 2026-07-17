package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code SelectionOccurrences} utility class, emitted once per code-generation run.
 *
 * <p>One GraphQL result key can carry several {@code SelectedField} occurrences in a
 * {@code DataFetchingFieldSelectionSet.getFieldsGroupedByResultKey()} bucket, because the map
 * flattens the whole subtree: in a Relay connection, {@code edges { node { x } }} and
 * {@code nodes { x }} collapse into one {@code x} entry. The generated {@code <Type>.$fields}
 * projection loop must treat such a bucket as one field whose sub-selection is the <em>union</em>
 * of every occurrence's sub-selection, or the sides diverge (a sub-field requested under only one
 * path is missing from the SELECT and the reader fails with a jOOQ "not contained in row type"
 * error). This class carries the three schema-independent statics that loop leans on:
 *
 * <ul>
 *   <li>{@code mergeByResultKey(occurrences)} — the union: merges each occurrence's
 *       {@code getSelectionSet().getFieldsGroupedByResultKey()} into one insertion-ordered map,
 *       concatenating bucket lists per key.</li>
 *   <li>{@code canonical(resultKey, occurrences)} — the universal name guard: the switch dispatches
 *       on one field name per bucket, so occurrences that disagree on {@code getName()} (two
 *       distinct fields aliased to one result key across sibling selection sets) are
 *       unrepresentable for <em>every</em> arm; fail loud instead of silently running one field's
 *       arm over another field's sub-selection. Returns the first occurrence as the canonical one
 *       once the guard passes.</li>
 *   <li>{@code requireConsistentArguments(resultKey, occurrences)} — the arm-scoped argument guard:
 *       emitted only into switch arms whose body reads runtime arguments off the
 *       {@code SelectedField}, where serving the first occurrence's arguments for every path would
 *       be silent wrong data.</li>
 * </ul>
 *
 * <p>The statics are schema-independent graphql-java manipulation (identical bytecode for every
 * schema), so they live here as a shared runtime scaffold rather than as per-class private helpers
 * copied into every generated type class. Sibling of {@code PolymorphicSelectionSet}, which stays
 * a pure delegating view; this class owns the occurrence-list semantics.
 *
 * <p>Both guards throw the generated {@code GraphitronClientException}
 * ({@link no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator}):
 * the divergence is a client-side query mistake (conflicting selections merged onto one result
 * key), and {@code ErrorRouter.surfaceClientErrorOrRedact} surfaces that marker's message to the
 * client where a plain runtime exception would be redacted to a correlation id. Same disposition
 * as {@code ConnectionHelper}'s malformed-cursor guard.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public final class SelectionOccurrencesClassGenerator {

    public static final String CLASS_NAME = "SelectionOccurrences";

    private static final ClassName SELECTED_FIELD  = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName LIST            = ClassName.get(List.class);
    private static final ClassName ARRAY_LIST      = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP             = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName OBJECTS         = ClassName.get("java.util", "Objects");

    private SelectionOccurrencesClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        // Client-error marker: the divergence guards throw it so the no-channel disposition
        // surfaces the real message instead of redacting to a correlation id.
        var clientException = ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var listOfSelectedField = ParameterizedTypeName.get(LIST, SELECTED_FIELD);
        var mapStringList       = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfSelectedField);
        var entryType           = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"), ClassName.get(String.class), listOfSelectedField);

        var mergeByResultKey = MethodSpec.methodBuilder("mergeByResultKey")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(listOfSelectedField, "occurrences")
            .addJavadoc("Merges each occurrence's sub-selection\n"
                + "({@link $T#getSelectionSet()}{@code .getFieldsGroupedByResultKey()}) into one\n"
                + "result-key-grouped map: bucket lists are concatenated per key, insertion-ordered.\n"
                + "A single occurrence (the common case) short-circuits to its own grouped map.\n",
                SELECTED_FIELD)
            .beginControlFlow("if (occurrences.size() == 1)")
            .addStatement("return occurrences.get(0).getSelectionSet().getFieldsGroupedByResultKey()")
            .endControlFlow()
            .addStatement("$T merged = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .beginControlFlow("for ($T occurrence : occurrences)", SELECTED_FIELD)
            .beginControlFlow(
                "for ($T entry : occurrence.getSelectionSet().getFieldsGroupedByResultKey().entrySet())",
                entryType)
            .addStatement("merged.computeIfAbsent(entry.getKey(), key -> new $T<>()).addAll(entry.getValue())",
                ARRAY_LIST)
            .endControlFlow()
            .endControlFlow()
            .addStatement("return merged")
            .build();

        var canonical = MethodSpec.methodBuilder("canonical")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(SELECTED_FIELD)
            .addParameter(String.class, "resultKey")
            .addParameter(listOfSelectedField, "occurrences")
            .addJavadoc("Returns the bucket's canonical occurrence (the first one) after checking\n"
                + "that every occurrence selects the same underlying field. Occurrences that\n"
                + "disagree on {@link $T#getName()} are two distinct fields aliased to one result\n"
                + "key across sibling selection paths (e.g. {@code edges.node} vs {@code nodes} in\n"
                + "a connection); the projection switch dispatches on a single name per bucket, so\n"
                + "that shape is unrepresentable and fails loud instead of silently dropping one\n"
                + "side's selection.\n",
                SELECTED_FIELD)
            .addStatement("$T first = occurrences.get(0)", SELECTED_FIELD)
            .beginControlFlow("if (occurrences.size() == 1)")
            .addStatement("return first")
            .endControlFlow()
            .beginControlFlow("for ($T occurrence : occurrences)", SELECTED_FIELD)
            .beginControlFlow("if (!occurrence.getName().equals(first.getName()))")
            .addStatement("throw new $T($S + resultKey + $S + first.getName() + $S + occurrence.getName()\n+ $S)",
                clientException,
                "Result key '",
                "' selects two different fields ('",
                "' and '",
                "') across selection paths merged into one projection (e.g. edges.node vs nodes"
                    + " in a connection); alias the two selections to distinct result keys")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return first")
            .build();

        var requireConsistentArguments = MethodSpec.methodBuilder("requireConsistentArguments")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(String.class, "resultKey")
            .addParameter(listOfSelectedField, "occurrences")
            .addJavadoc("Checks that every occurrence in the bucket carries the same runtime\n"
                + "arguments. Called only from switch arms whose emitted body reads arguments off\n"
                + "the canonical {@link $T}: there, serving the first occurrence's arguments for\n"
                + "every selection path would be silent wrong data, so divergence fails loud.\n"
                + "Selection paths merged into one bucket sit in sibling selection sets that GraphQL\n"
                + "field-merging validation never compares, so divergent arguments are legal at the\n"
                + "GraphQL layer and must be rejected here.\n",
                SELECTED_FIELD)
            .beginControlFlow("if (occurrences.size() == 1)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T first = occurrences.get(0)", SELECTED_FIELD)
            .beginControlFlow("for ($T occurrence : occurrences)", SELECTED_FIELD)
            .beginControlFlow("if (!$T.equals(occurrence.getArguments(), first.getArguments()))", OBJECTS)
            .addStatement("throw new $T($S + first.getName() + $S + resultKey + $S + first.getArguments()\n+ $S + occurrence.getArguments() + $S)",
                clientException,
                "Field '",
                "' (result key '",
                "') is selected with conflicting arguments across selection paths merged into one"
                    + " projection (e.g. edges.node vs nodes in a connection): ",
                " vs ",
                "; alias the two selections to distinct result keys")
            .endControlFlow()
            .endControlFlow()
            .build();

        var privateCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(privateCtor)
            .addMethod(mergeByResultKey)
            .addMethod(canonical)
            .addMethod(requireConsistentArguments)
            .build();

        return List.of(spec);
    }
}
