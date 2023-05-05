package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.helpers.InputCondition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLAlias;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import no.fellesstudentsystem.graphql.mapping.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Generator that creates the default data fetching methods.
 */
public class FetchDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    private static final String PAGE_SIZE_NAME = "pageSize";
    private final String idParamName = uncapitalize(getLocalObject().getName()) + "Ider";
    private final boolean isRoot = getLocalObject().isRoot();

    public FetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    public FetchDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema,
            Map<String, Class<?>> enumOverrides,
            Map<String, Method> conditionOverrides
    ) {
        super(localObject, processedSchema, enumOverrides, conditionOverrides);
    }

    /**
     * @param target A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#NODE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var refObject = ReferenceHelpers.findReferencedObjectDefinition(target, processedSchema);
        var localObject = getLocalObject();

        var context = new FetchContext(processedSchema, target, localObject, conditionOverrides);
        var selectRowCode = generateSelectRow(context);
        var hasKeyReference = context.hasKeyReference();

        var actualRefTable = refObject.getTable().getName();
        var code = CodeBlock
                .builder()
                .add(declareAliasesAndSetInitialCode(context, actualRefTable))
                .add(selectRowCode)
                .add(".as($S)\n", target.getName())
                .unindent()
                .unindent()
                .add(")\n")
                .add(".from(")
                .add(hasKeyReference || isRoot ? actualRefTable : localObject.getTable().getName())
                .add(")\n")
                .add(createSelectJoins(context.getJoinList()))
                .add(formatWhereContents(target, hasKeyReference, actualRefTable))
                .add(createSelectConditions(context.getConditionList()))
                .add(setPaginationAndFetch(target, actualRefTable));

        return getSpecBuilder(target, refObject.getGraphClassName())
                .addCode(code.build())
                .build();
    }

    private CodeBlock declareAliasesAndSetInitialCode(FetchContext context, String actualRefTable) {
        var code = CodeBlock
                .builder()
                .add(createSelectAliases(context.getJoinList(), context.getAliasList()))
                .add("return ctx\n")
                .indent()
                .indent()
                .add(".select(\n")
                .indent()
                .indent();
        if (!isRoot) {
            var localTableObject = getLocalObject().getTable();
            var localTable = localTableObject.getName();
            code
                    .add(context.hasKeyReference() ? actualRefTable + localTableObject.asGetIdCall() : localTable + ".getId()")
                    .add(",\n");
        }
        return code.build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, ClassName refClassName) {
        var spec = getDefaultSpecBuilder(
                referenceField.getName() + "For" + getLocalObject().getName(),
                getReturnType(referenceField, refClassName)
        );
        if (!isRoot) {
            spec.addParameter(ParameterizedTypeName.get(SET.className, STRING.className), idParamName);
        }

        if (referenceField.hasNonReservedInputFields()) {
            var allInputs = processedSchema.getInputTypes();
            referenceField
                    .getNonReservedInputFields()
                    .forEach(i -> spec.addParameter(i.getFieldType().getWrappedTypeClass(allInputs), i.getName()));
        }

        if (referenceField.hasForwardPagination()) {
            spec.addParameter(INTEGER.className, PAGE_SIZE_NAME);
            spec.addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        spec.addParameter(SELECTION_SETS.className, SELECTION_NAME);

        return spec;
    }

    @NotNull
    private ParameterizedTypeName getReturnType(ObjectField referenceField, ClassName refClassName) {
        if (isRoot) {
            return ParameterizedTypeName.get(LIST.className, refClassName);
        } else {
            return ParameterizedTypeName.get(
                    MAP.className,
                    STRING.className,
                    referenceField.getFieldType().isIterableWrapped() || referenceField.hasForwardPagination()
                            ? ParameterizedTypeName.get(LIST.className, refClassName)
                            : refClassName
            );
        }
    }

    private CodeBlock formatWhereContents(ObjectField referenceField, boolean hasKeyReference, String actualRefTable) {
        var code = CodeBlock.builder().add(".where(");

        if (!isRoot) {
            var localTableObject = getLocalObject().getTable();
            code
                    .add(
                            hasKeyReference
                                    ? actualRefTable + localTableObject.asHasIdsCall("$N")
                                    : localTableObject.getName() + ".hasIds($N)",
                            idParamName
                    )
                    .add(")\n");
        }
        if (referenceField.hasNonReservedInputFields()) {
            code.add(createWhere(actualRefTable, referenceField, !isRoot));
        } else if (isRoot) {
            return CodeBlock.builder().build();
        }
        return code.build();
    }

    private CodeBlock createWhere(String actualRefTable, ObjectField referenceField, boolean hasWhere) {
        var inputConditions = getInputConditions(referenceField.getNonReservedInputFields());
        var flatInputs = inputConditions.getIndependentConditions();
        var codeBlockBuilder = CodeBlock.builder();

        for (InputCondition inputCondition : flatInputs) {
            InputField field = inputCondition.getInput();
            var name = inputCondition.getNameWithPath();
            var checks = inputCondition.getChecksAsSequence();
            var checksNotEmpty = !checks.isEmpty();
            if (!referenceField.hasOverridingCondition() && !field.hasOverridingCondition()) {
                var fieldType = field.getFieldType();
                var fieldTypeName = fieldType.getName();
                codeBlockBuilder
                        .add(hasWhere ? ".and(" : "")
                        .add(checksNotEmpty ? checks + " ? " : "")
                        .add(actualRefTable + getJoinedFieldSource(field, actualRefTable) + "." + field.getUpperCaseName())
                        .add(toEnumConverter(fieldTypeName))
                        .add(fieldType.isIterableWrapped() ? ".in($N)" : ".eq($N)", name)
                        .add(checksNotEmpty ? " : noCondition()" : "")
                        .add(")\n");
            }

            if (field.hasCondition()) {
                var conditionInputs = List.of(actualRefTable, inputCondition.getCheckedNameWithPath());
                codeBlockBuilder.add(wrapCondition(field.applyCondition(conditionInputs, conditionOverrides), hasWhere));
            }
            if (!codeBlockBuilder.isEmpty()) {
                hasWhere = true;
            }
        }

        for (Map.Entry<InputField, List<InputCondition>> conditionTuple : inputConditions.getConditionTuples().entrySet()) {
            codeBlockBuilder.add(createTupleCondition(actualRefTable, hasWhere, conditionTuple.getKey(), conditionTuple.getValue()));
            hasWhere = true;
        }

        if (referenceField.hasCondition()) {
            var inputs = Stream.concat(
                    Stream.of(actualRefTable),
                    flatInputs.stream().map(InputCondition::getCheckedNameWithPath)
            ).collect(Collectors.toList());
            codeBlockBuilder.add(wrapCondition(referenceField.applyCondition(inputs, conditionOverrides), hasWhere));
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock createTupleCondition(String actualRefTable, boolean hasWhere, InputField argumentInputField, List<InputCondition> conditions) {
        var codeBlockBuilder = CodeBlock.builder();
        var argumentName = argumentInputField.getName();

        codeBlockBuilder
                .add(hasWhere ? ".and(" : "")
                .add("$N != null && $N.size() > 0 ?\n", argumentName, argumentName)
                .indent().indent()
                .add("row(\n")
                .indent().indent();

        for (int i = 0; i < conditions.size(); i++) {
            InputField field = conditions.get(i).getInput();
            var fieldType = field.getFieldType();
            var fieldTypeName = fieldType.getName();

            codeBlockBuilder
                    .add(actualRefTable + getJoinedFieldSource(field, actualRefTable) + "." + field.getUpperCaseName())
                    .add(toEnumConverter(fieldTypeName))
                    .add(i < conditions.size()-1 ? ",\n" : "");
        }
        codeBlockBuilder
                .unindent().unindent()
                .add("\n)")
                .add(".in($N.stream().map(input -> row(\n", argumentName)
                .indent().indent()
                .add(conditions.stream()
                        .map(it -> "input" + it.getNameWithPath().replaceFirst(argumentName, ""))
                        .collect(Collectors.joining(",\n")))
                .unindent().unindent()
                .add(")\n")
                .add(").collect($T.toList()))",  COLLECTORS.className)
                .add(" :\n")
                .add("noCondition()")
                .unindent().unindent()
                .add(")\n");

        return codeBlockBuilder.build();
    }

    private String wrapCondition(String condition, boolean hasWhere) {
        if (!condition.isEmpty()) {
            return (hasWhere ? ".and(" : "") + condition + ")\n";
        }
        return condition;
    }

    private String getJoinedFieldSource(InputField field, String refTableName) {
        if (field.hasImplicitJoin()) {
            return getAppliedImplicitJoin(field.getImplicitJoin(), refTableName);
        }
        return "";
    }

    private CodeBlock setPaginationAndFetch(ObjectField referenceField, String actualRefTable) {
        var refObject = ReferenceHelpers.findReferencedObjectDefinition(referenceField, processedSchema);
        var refTable = refObject.getTable().getName();
        var code = CodeBlock.builder();
        if (isRoot || referenceField.hasForwardPagination()) {
            code.add(".orderBy($N.getIdFields())\n", actualRefTable);
        }

        if (referenceField.hasForwardPagination()) {
            code.add(".seek($N.getIdValues($N))\n", refTable, GraphQLReservedName.PAGINATION_AFTER.getName());
            code.add(".limit($N + 1)\n", PAGE_SIZE_NAME);
        }

        if (isRoot) {
            code.addStatement(".fetch(0, $T.class)", refObject.getGraphClassName());
        } else {
            code
                    .add(".")
                    .add(
                            referenceField.getFieldType().isIterableWrapped() || referenceField.hasForwardPagination()
                                    ? "fetchGroups"
                                    : "fetchMap"
                    )
                    .addStatement("($T::value1, $T::value2)", RECORD2.className, RECORD2.className);
        }
        return code.unindent().unindent().build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getReferredFieldsFromObjectNames(processedSchema.getNamesWithTableOrConnections())
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(it -> !processedSchema.isInterface(it.getTypeName()))
                .map(this::generate)
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        var fieldStream = getLocalObject().getFields().stream();
        return isRoot
                ? fieldStream.allMatch(ObjectField::isGenerated)
                : fieldStream.allMatch(f -> !f.isResolver() || f.isGenerated());
    }
}
