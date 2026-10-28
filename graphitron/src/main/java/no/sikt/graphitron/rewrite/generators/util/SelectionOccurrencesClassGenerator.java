package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code SelectionOccurrences} utility class, emitted once per code-generation run.
 *
 * <p>Every generated {@code <Type>.$project(grouped, table, env)} takes its selection as a map
 * from result key to the {@code SelectedField} occurrences under that key, and this class is the
 * one place such maps are built. A GraphQL response key names a field only within its own
 * selection set, so every adapter here groups <em>immediate</em> children
 * ({@code getImmediateFields()}) and never the whole subtree: a key reused under a different
 * parent is a different field, and must not reach this unit's switch. The adapters:
 * <ul>
 *   <li>{@code ownFields}: the fetched field's immediate children, the input of every launcher
 *       whose unit fields sit directly under the fetched field;</li>
 *   <li>{@code connectionNodes}: the node level of a Relay connection, every immediate
 *       {@code nodes} occurrence and every {@code node} under an immediate {@code edges}, merged.
 *       This is the one place two selection paths share a projection: both are served from one
 *       SQL row by one node reader;</li>
 *   <li>{@code mergeByResultKey}: the union of several occurrences' immediate children, the input
 *       a nested {@code $project} call receives for one result-key bucket.</li>
 * </ul>
 *
 * <p>A bucket therefore holds more than one occurrence only when several parent occurrences were
 * merged: the connection's {@code edges.node} and {@code nodes} paths, a child bucket of such a
 * merge, or two fragments' type-conditioned occurrences of one key. The projection loop treats
 * such a bucket as one field whose sub-selection is the union of every occurrence's, guarded by
 * {@code canonical} (fails loud when the occurrences name different fields) and
 * {@code requireConsistentArguments} (emitted only into switch arms that read runtime
 * arguments, where serving the first occurrence's arguments for every path would be silent wrong
 * data).
 *
 * <p>The two {@code restrictTo} statics scope a grouped map to one concrete type of a polymorphic
 * selection, after the descent to the node level: the per-typename select of a multi-table
 * interface restricts everything, and the single-table discriminated fold restricts selectively
 * so that every arm writing one shared alias sees one occurrence set (see
 * {@link no.sikt.graphitron.command.LaunchSource.DiscriminatedTable.SelectionRestriction}).
 * Both read {@code SelectedField.getName()}, never the client-minted result key.
 *
 * <p>The statics are identical bytecode for every schema, so they live here as a shared runtime
 * scaffold rather than as per-class private helpers copied into every generated type class.
 *
 * <p>Both guards throw the generated {@code GraphitronClientException}
 * ({@link no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator}):
 * the divergence is a client-side query mistake, and {@code ErrorRouter.surfaceClientErrorOrRedact}
 * surfaces that marker's message to the client where a plain runtime exception would be redacted
 * to a correlation id. Same disposition as {@code ConnectionHelper}'s malformed-cursor guard.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public final class SelectionOccurrencesClassGenerator {

    public static final String CLASS_NAME = "SelectionOccurrences";

    private static final ClassName SELECTED_FIELD  = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName SELECTION_SET   = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
    private static final ClassName SET             = ClassName.get("java.util", "Set");
    private static final ClassName LIST            = ClassName.get(List.class);
    private static final ClassName ARRAY_LIST      = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP             = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName OBJECTS         = ClassName.get("java.util", "Objects");

    private SelectionOccurrencesClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        var clientException = ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var listOfSelectedField = ParameterizedTypeName.get(LIST, SELECTED_FIELD);
        var mapStringList       = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfSelectedField);
        var setOfString         = ParameterizedTypeName.get(SET, ClassName.get(String.class));
        var entryType           = ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"), ClassName.get(String.class), listOfSelectedField);

        var group = MethodSpec.methodBuilder("groupByResultKey")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(mapStringList, "into")
            .addParameter(listOfSelectedField, "fields")
            .addJavadoc("Appends each field to its result key's bucket, insertion-ordered.\n")
            .beginControlFlow("for ($T field : fields)", SELECTED_FIELD)
            .addStatement("into.computeIfAbsent(field.getResultKey(), key -> new $T<>()).add(field)", ARRAY_LIST)
            .endControlFlow()
            .build();

        var ownFields = MethodSpec.methodBuilder("ownFields")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(SELECTION_SET, "selection")
            .addJavadoc("The selection's immediate fields grouped by result key, in query order: the\n"
                + "fields selected on the fetched field's own type at its own level, and nothing\n"
                + "deeper.\n")
            .addStatement("$T grouped = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .addStatement("groupByResultKey(grouped, selection.getImmediateFields())")
            .addStatement("return grouped")
            .build();

        var connectionNodes = MethodSpec.methodBuilder("connectionNodes")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(SELECTION_SET, "connectionSelection")
            .addJavadoc("The node level of a Relay connection's selection: every immediate\n"
                + "{@code nodes} occurrence and every {@code node} occurrence under an immediate\n"
                + "{@code edges}, merged as {@link #mergeByResultKey} merges. Matches on\n"
                + "{@link $T#getName()}, the Relay field names, so a client alias on {@code edges}\n"
                + "or {@code nodes} does not change the answer.\n",
                SELECTED_FIELD)
            .addStatement("$T nodeOccurrences = new $T<>()", listOfSelectedField, ARRAY_LIST)
            .beginControlFlow("for ($T field : connectionSelection.getImmediateFields())", SELECTED_FIELD)
            .beginControlFlow("if (field.getName().equals($S))", "nodes")
            .addStatement("nodeOccurrences.add(field)")
            .nextControlFlow("else if (field.getName().equals($S))", "edges")
            .beginControlFlow("for ($T edgeField : field.getSelectionSet().getImmediateFields())", SELECTED_FIELD)
            .beginControlFlow("if (edgeField.getName().equals($S))", "node")
            .addStatement("nodeOccurrences.add(edgeField)")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return mergeByResultKey(nodeOccurrences)")
            .build();

        var mergeByResultKey = MethodSpec.methodBuilder("mergeByResultKey")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(listOfSelectedField, "occurrences")
            .addJavadoc("Merges each occurrence's immediate children\n"
                + "({@link $T#getSelectionSet()}{@code .getImmediateFields()}) into one\n"
                + "result-key-grouped map: bucket lists are concatenated per key, insertion-ordered.\n",
                SELECTED_FIELD)
            .addStatement("$T merged = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .beginControlFlow("for ($T occurrence : occurrences)", SELECTED_FIELD)
            .addStatement("groupByResultKey(merged, occurrence.getSelectionSet().getImmediateFields())")
            .endControlFlow()
            .addStatement("return merged")
            .build();

        var restrictTo = MethodSpec.methodBuilder("restrictTo")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(mapStringList, "grouped")
            .addParameter(String.class, "concreteTypeName")
            .addJavadoc("Keeps only the occurrences whose {@link $T#getObjectTypeNames()}\n"
                + "contains {@code concreteTypeName}, dropping buckets left empty: the selection\n"
                + "one concrete type of a polymorphic field projects on its own.\n",
                SELECTED_FIELD)
            .addStatement("return restrictTo(grouped, concreteTypeName, $T.of())", SET)
            .build();

        var restrictToSelective = MethodSpec.methodBuilder("restrictTo")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringList)
            .addParameter(mapStringList, "grouped")
            .addParameter(String.class, "concreteTypeName")
            .addParameter(setOfString, "sharedAliasFieldNames")
            .addJavadoc("Restricts each bucket to {@code concreteTypeName} in two steps: it keeps\n"
                + "the occurrences whose {@link $T#getObjectTypeNames()} contain the type, and if\n"
                + "any kept occurrence names a field in {@code sharedAliasFieldNames}, it also\n"
                + "keeps every occurrence in the bucket that names such a field, whatever its type.\n"
                + "\n"
                + "<p>For a select list several concrete types' projections merge into: a field\n"
                + "every type writes under one shared alias must reach every type's projection\n"
                + "with the same occurrences, or the identical alias would carry different SQL per\n"
                + "type and only one term would survive. Two different shared-alias fields under\n"
                + "one key therefore meet in one bucket, where {@link #canonical} rejects them.\n"
                + "Field names, not result keys, so a client alias of a shared field stays shared.\n",
                SELECTED_FIELD)
            .addStatement("$T result = new $T<>()", mapStringList, LINKED_HASH_MAP)
            .beginControlFlow("for ($T entry : grouped.entrySet())", entryType)
            .addStatement("boolean keepsShared = false")
            .beginControlFlow("for ($T occurrence : entry.getValue())", SELECTED_FIELD)
            .beginControlFlow("if (occurrence.getObjectTypeNames().contains(concreteTypeName)\n"
                + "&& sharedAliasFieldNames.contains(occurrence.getName()))")
            .addStatement("keepsShared = true")
            .endControlFlow()
            .endControlFlow()
            .addStatement("$T kept = new $T<>(entry.getValue().size())", listOfSelectedField, ARRAY_LIST)
            .beginControlFlow("for ($T occurrence : entry.getValue())", SELECTED_FIELD)
            .beginControlFlow("if (occurrence.getObjectTypeNames().contains(concreteTypeName)\n"
                + "|| keepsShared && sharedAliasFieldNames.contains(occurrence.getName()))")
            .addStatement("kept.add(occurrence)")
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("if (!kept.isEmpty())")
            .addStatement("result.put(entry.getKey(), kept)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return result")
            .build();

        var canonical = MethodSpec.methodBuilder("canonical")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(SELECTED_FIELD)
            .addParameter(String.class, "resultKey")
            .addParameter(listOfSelectedField, "occurrences")
            .addJavadoc("Returns the bucket's canonical occurrence (the first one) after checking\n"
                + "that every occurrence selects the same underlying field. Occurrences that\n"
                + "disagree on {@link $T#getName()} are two distinct fields aliased to one result\n"
                + "key across parent occurrences merged into one projection: the {@code edges.node}\n"
                + "and {@code nodes} paths of one connection, or two fragments' fields that write one\n"
                + "shared alias in a discriminated fold. The projection switch dispatches on a single\n"
                + "name per bucket, so that shape is unrepresentable and fails loud instead of\n"
                + "silently dropping one side's selection. A key reused under different parents\n"
                + "never reaches one bucket, because every adapter groups immediate children only.\n",
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
                + "Parent occurrences merged into one bucket (a connection's {@code edges.node} and\n"
                + "{@code nodes} paths) sit in sibling selection sets that GraphQL\n"
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
            .addMethod(ownFields)
            .addMethod(connectionNodes)
            .addMethod(mergeByResultKey)
            .addMethod(restrictTo)
            .addMethod(restrictToSelective)
            .addMethod(canonical)
            .addMethod(requireConsistentArguments)
            .addMethod(group)
            .build();

        return List.of(spec);
    }
}
