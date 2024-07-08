package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.codebuilding.MappingCodeBlocks;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asResultName;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceUpdateResolverMethodGenerator extends UpdateResolverMethodGenerator {
    private boolean serviceReturnEndsWithRecord;

    public ServiceUpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        if (!target.hasServiceReference()) {
            return empty();
        }

        var dependency = createServiceDependency(target);
        serviceReturnEndsWithRecord = dependency.getService().inferIsReturnTypeRecord();
        return declare(asResultName(target.getName()), generateServiceCall(dependency.getService().getMethodName(), uncapitalize(dependency.getName())));
    }

    @NotNull
    private CodeBlock generateServiceCall(String methodName, String serviceObjectName) {
        return CodeBlock.of("$N.$L($L)", serviceObjectName, methodName, parser.getInputParamString());
    }

    /**
     * @return Code that both fetches record data and creates the appropriate response objects.
     */
    protected CodeBlock generateSchemaOutputs(ObjectField target) {
        if (processedSchema.isExceptionOrExceptionUnion(target.getTypeName())) {
            return empty();
        }

        var mapperContext = MapperContext.createResolverContext(target, false, processedSchema);
        return CodeBlock
                .builder()
                .add(MappingCodeBlocks.generateSchemaOutputs(mapperContext, serviceReturnEndsWithRecord, processedSchema))
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
