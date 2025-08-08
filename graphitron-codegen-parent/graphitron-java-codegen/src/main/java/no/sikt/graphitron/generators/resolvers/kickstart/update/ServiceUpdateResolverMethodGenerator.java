package no.sikt.graphitron.generators.resolvers.kickstart.update;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.codebuilding.MappingCodeBlocks;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnCompletedFuture;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceUpdateResolverMethodGenerator extends UpdateResolverMethodGenerator {
    public ServiceUpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    @Override
    protected CodeBlock getMethodCall(ObjectField target, InputParser parser, boolean isMutatingMethod) {
        return CodeBlock.declare(target.getName(), "$N.$L($L)", uncapitalize(createServiceDependency(target).getName()), target.getService().getMethodName(), parser.getInputParamString());
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return CodeBlock.empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        return CodeBlock
                .builder()
                .add(MappingCodeBlocks.generateSchemaOutputs(mapperContext, processedSchema))
                .add(returnCompletedFuture(getResolverResultName(target, processedSchema)))
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (localField.isGeneratedWithResolver()) {
            if (localField.hasServiceReference()) {
                return List.of(generate(localField));
            } else if (!localField.hasMutationType()) {
                throw new IllegalStateException("Mutation '" + localField.getName() + "' is set to generate, but has neither a service nor mutation type set.");
            }
        }
        return List.of();
    }
}
