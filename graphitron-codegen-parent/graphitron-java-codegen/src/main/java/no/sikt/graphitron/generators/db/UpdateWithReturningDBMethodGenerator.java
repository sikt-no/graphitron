package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.useJdbcBatchingForDeletes;
import static no.sikt.graphitron.definitions.fields.containedtypes.MutationType.DELETE;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;

/**
 * Generator that creates the default data mutation methods.
 */
public class UpdateWithReturningDBMethodGenerator extends FetchDBMethodGenerator {

    public UpdateWithReturningDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema, true);
    }

    /**
     * @param target A {@link ObjectField} for which a mutation method should be generated for.
     *               This must reference a field located within the Mutation type and with the
     *               "{@link GenerationDirective#MUTATION mutationType}" directive set.
     * @return The complete javapoet {@link MethodSpec} based on the provided reference field.
     */
    @Override
    public MethodSpec generate(ObjectField target) {
        if (!target.hasMutationType()) {
            return MethodSpec.methodBuilder(target.getName()).build();
        }

        // Field containing the actual data returned after the mutation
        var dataTarget = processedSchema.inferDataTargetForMutation(target)
                .orElseThrow(() -> new RuntimeException(String.format(
                        "Cannot determine field to output data to for mutation %s.",
                        target.formatPath())));

        var returnType = processedSchema.isRecordType(dataTarget)
                ? processedSchema.getRecordType(dataTarget).getGraphClassName()
                : inferFieldTypeName(dataTarget, true);

        var targetTable = processedSchema.findInputTables(target).stream() // TODO: support inferring target table from data output
                .findFirst()
                .orElseThrow(() -> new RuntimeException(String.format("Cannot infer target table for mutation %s.", target.formatPath())));

        var parser = new InputParser(target, processedSchema, false);

        var contextForData = new FetchContext(
                processedSchema,
                new VirtualSourceField(target, dataTarget),
                getLocalObject(),
                true,
                true);

        var code = CodeBlock.builder()
                .add(createAliasDeclarations(contextForData.getAliasSet()))
                .add(getQuery(target.getMutationType(), targetTable.getName(), target))
                .add(".returningResult($L)\n", processedSchema.isScalar(dataTarget) ? generateForField(dataTarget, contextForData) : generateSelectRow(contextForData))
                .add(setFetch(dataTarget));

        return getDefaultSpecBuilder(asQueryMethodName(target.getName(), getLocalObject().getName()), wrapListIf(returnType, dataTarget.isIterableWrapped()))
                .addParameters(getMethodParametersWithOrderField(parser))
                .addParameter(SELECTION_SET.className, VAR_SELECT)
                .addCode(code.build())
                .build();
    }

    private CodeBlock getQuery(MutationType mutationType, String targetTable, ObjectField initialTarget) {
        return CodeBlock.builder()
                .add("return $N", VAR_CONTEXT)
                .addIf(mutationType.equals(DELETE), getDeletePartOfQuery(targetTable, initialTarget))
                .build();
    }

    private CodeBlock getDeletePartOfQuery(String targetTable, ObjectField target) {
        var mutationContext = new FetchContext(processedSchema, target, getLocalObject(), true, true);
        return CodeBlock.builder()
                .add(".deleteFrom($N)", targetTable)
                .add("\n$L", formatWhereContentsForDeleteMutation(mutationContext, target))
                .build();
    }

    @Override
    protected TypeName iterableWrapType(GenerationField field) {
        return wrapListIf(inferFieldTypeName(field, false), field.isIterableWrapped());
    }

    protected CodeBlock formatWhereContentsForDeleteMutation(FetchContext context, ObjectField target) {
        return formatJooqConditions(new ArrayList<>(getInputConditions(context, target)));
    }

    private CodeBlock setFetch(ObjectField referenceField) {
        var refObject = processedSchema.getObjectOrConnectionNode(referenceField);
        var resType = refObject == null ? referenceField.getTypeClass() : refObject.getGraphClassName();
        return CodeBlock.statementOf(".fetch$1L($2N -> $2N.into($3T.class))",
                referenceField.isIterableWrapped() ? "" : "One", VAR_ITERATOR, resType);
    }

    @Override
    public List<MethodSpec> generateAll() {
        var localObject = getLocalObject();
        if (localObject.isExplicitlyNotGenerated()) {
            return List.of();
        }
        return localObject
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> it.isDeleteMutation() && !useJdbcBatchingForDeletes()) // Will be expanded to include all mutation types
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .collect(Collectors.toList());
    }
}
