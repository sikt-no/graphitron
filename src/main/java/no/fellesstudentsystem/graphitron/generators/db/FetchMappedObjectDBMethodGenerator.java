package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PAGE_SIZE_NAME;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asQueryMethodName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates the default data fetching methods
 */
public class FetchMappedObjectDBMethodGenerator extends FetchDBMethodGenerator {

    public FetchMappedObjectDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    /**
     * @param target A {@link ObjectField} for which a method should be generated for.
     *                       This must reference an object with the
     *                       "{@link GenerationDirective#TABLE table}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        var localObject = getLocalObject();
        var context = new FetchContext(processedSchema, target, localObject);
        // Note that this must happen before alias declaration.
        var selectCode = generateSelectRow(context);
        var where = formatWhereContents(context);

        var code = CodeBlock
                .builder()
                .add(declareAliasesAndSetInitialCode(context))
                .add(selectCode)
                .add(".as($S)\n", target.getName())
                .unindent()
                .unindent()
                .add(")\n")
                .add(".from($L)\n", context.renderQuerySource(getLocalTable()))
                .add(createSelectJoins(context))
                .add(where)
                .add(createSelectConditions(context))
                .add(setPaginationAndFetch(target, context.getReferenceTable().getMappingName()));

        return getSpecBuilder(target, context.getReferenceObject().getGraphClassName())
                .addCode(code.build())
                .build();
    }

    private CodeBlock declareAliasesAndSetInitialCode(FetchContext context) {
        var code = CodeBlock
                .builder()
                .add(createSelectAliases(context.getJoinSet()))
                .add("return $N\n", Dependency.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(\n")
                .indent()
                .indent();

        var ref = context.getReferenceObjectField();
        if (!isRoot || ref.hasLookupKey()) {
            var key = ref.hasLookupKey() ? ref.getLookupKey().getUpperCaseName() : "getId()";
            code.add("$L.$L,\n", context.renderQuerySource(getLocalTable()), key);
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
                    .getNonReservedArguments()
                    .forEach(i -> spec.addParameter(inputIterableWrap(i), i.getName()));
        }

        if (referenceField.hasForwardPagination()) {
            spec.addParameter(INTEGER.className, PAGE_SIZE_NAME);
            spec.addParameter(STRING.className, GraphQLReservedName.PAGINATION_AFTER.getName());
        }
        return spec.addParameter(SELECTION_SET.className, SELECTION_NAME);
    }

    @NotNull
    private TypeName getReturnType(ObjectField referenceField, TypeName refClassName) {
        if (isRoot && !referenceField.hasLookupKey()) {
            return wrapList(refClassName);
        } else {
            var key = referenceField.hasLookupKey() ? referenceField.getLookupKey().getTypeClass() : STRING.className;
            return wrapMap(key, wrapListIf(refClassName, referenceField.isIterableWrapped() && !referenceField.hasLookupKey() || referenceField.hasForwardPagination()));
        }
    }

    private CodeBlock setPaginationAndFetch(ObjectField referenceField, String actualRefTable) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        var refTable = refObject.getTable().getMappingName();
        var code = CodeBlock.builder();
        if (!referenceField.hasLookupKey() && isRoot || referenceField.hasForwardPagination()) {
            code.add(".orderBy($N.getIdFields())\n", actualRefTable);
        }

        if (referenceField.hasForwardPagination()) {
            code.add(".seek($N.getIdValues($N))\n", refTable, GraphQLReservedName.PAGINATION_AFTER.getName());
            code.add(".limit($N + 1)\n", PAGE_SIZE_NAME);
        }

        if (isRoot && !referenceField.hasLookupKey()) {
            code.addStatement(".fetch(0, $T.class)", refObject.getGraphClassName());
        } else {
            code
                    .add(".")
                    .add(
                            referenceField.isIterableWrapped() && !referenceField.hasLookupKey() || referenceField.hasForwardPagination()
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