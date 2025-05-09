package no.sikt.graphitron.generators.resolvers.kickstart.update;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.METHOD_CONTEXT_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#MUTATION} directive set.
 */
public class MutationTypeResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private static final String VARIABLE_ROWS = "rowsUpdated";

    public MutationTypeResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    /**
     * @return List of variable names for the declared and fully set records.
     */
    @Override
    protected CodeBlock transformInputs(List<? extends InputField> specInputs, boolean hasRecords) {
        if (!parser.hasJOOQRecords()) {
            throw new UnsupportedOperationException("Must have at least one table reference when generating resolvers with queries. Mutation '" + localField.getName() + "' has no tables attached.");
        }

        return super.transformInputs(specInputs, parser.hasRecords());
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        var objectToCall = asQueryClass(target.getName());

        var updateClass = getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + UpdateDBClassGenerator.SAVE_DIRECTORY_NAME, objectToCall);
        return declare(
                !localField.hasServiceReference() ? VARIABLE_ROWS : asResultName(target.getUnprocessedFieldOverrideInput()),
                CodeBlock.of("$T.$L($L, $L)",
                        updateClass,
                        target.getName(), // Method name is expected to be the field's name.
                        asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME),
                        parser.getInputParamString()
                )
        );
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    @Override
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        var returnValue = !processedSchema.isObject(target)
                ? getIDMappingCode(mapperContext, localField, processedSchema, parser)
                : CodeBlock.of(getResolverResultName(target, processedSchema));
        return CodeBlock
                .builder()
                .add(makeResponses(mapperContext, localField, processedSchema, parser))
                .add(returnCompletedFuture(returnValue))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver()) {
            if (localField.hasMutationType()) {
                return List.of(generate(localField));
            } else if (!localField.hasServiceReference()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
