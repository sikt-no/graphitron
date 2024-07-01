package no.fellesstudentsystem.graphitron.generators.resolvers.update;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks;
import no.fellesstudentsystem.graphitron.generators.context.MapperContext;
import no.fellesstudentsystem.graphitron.generators.dependencies.ServiceDependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.asResultName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ServiceCodeBlocks.getResolverResultName;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates the resolvers for update queries with the {@link GenerationDirective#SERVICE} directive set.
 */
public class ServiceUpdateResolverMethodGenerator extends UpdateResolverMethodGenerator {

    public ServiceUpdateResolverMethodGenerator(ObjectField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    protected CodeBlock generateUpdateMethodCall(ObjectField target) {
        var service = context.getService();
        var dependency = new ServiceDependency(service.getServiceClassName());
        dependencySet.add(dependency);

        var objectToCall = uncapitalize(dependency.getName());
        var serviceResultName = asResultName(target.getName());
        var serviceMethod = service.getMethod();
        var methodName = uncapitalize(serviceMethod != null ? serviceMethod.getName() : target.getName());
        return declare(serviceResultName, generateServiceCall(methodName, objectToCall));
    }

    @NotNull
    private CodeBlock generateServiceCall(String methodName, String serviceObjectName) {
        return CodeBlock.of("$N.$L($L)", serviceObjectName, methodName, context.getServiceInputString());
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
                .add(ServiceCodeBlocks.generateSchemaOutputs(mapperContext, context.getService(), processedSchema))
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
