package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.*;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.mappings.ReferenceHelpers;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;
import no.fellesstudentsystem.graphql.mapping.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {
    private static final String PAGE_SIZE_NAME = "pageSize";

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    public FetchMappedObjectDBMethodGenerator(
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
     *                       "{@link GenerationDirective#TABLE table}" directive set.
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
                .add(formatWhereContents(target, context.getCurrentJoinSequence(), hasKeyReference, actualRefTable))
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
            var localTableName = getLocalObject().getTable().getName();
            var referenceTableName = context.getReferenceTable().getName();
            var qualifiedId = TableReflection.getQualifiedId(referenceTableName, localTableName);

            code
                    .add(context.hasKeyReference() ? actualRefTable + String.format(".get%s()", qualifiedId) : localTableName + ".getId()")
                    .add(",\n");
        }
        return code.build();
    }

    @NotNull
    private MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName) {
        var spec = getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        );
        if (!isRoot) {
            spec.addParameter(getStringSetTypeName(), idParamName);
        }

        if (referenceField.hasNonReservedInputFields()) {
            referenceField
                    .getNonReservedInputFields()
                    .forEach(i -> spec.addParameter(inputIterableWrap(i), i.getName()));
        }

        if (referenceField.hasForwardPagination()) {
            spec.addParameter(INTEGER.className, PAGE_SIZE_NAME);
            spec.addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        spec.addParameter(SELECTION_SET.className, SELECTION_NAME);

        return spec;
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        if (isRoot) {
            return wrapList(refClassName);
        } else {
            return wrapStringMap(
                    wrapListIf(refClassName, referenceField.isIterableWrapped() || referenceField.hasForwardPagination())
            );
        }
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
                            referenceField.isIterableWrapped() || referenceField.hasForwardPagination()
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
                .filter(it -> !processedSchema.isInterface(it))
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