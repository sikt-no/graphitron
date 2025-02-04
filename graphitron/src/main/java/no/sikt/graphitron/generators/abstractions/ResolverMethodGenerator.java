package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.dependencies.ServiceDependency;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.mappings.JavaPoetClassName.EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.OVERRIDE;

/**
 * This class contains common information and operations shared by resolver method generators.
 * @param <T> The field type that this generator operates on.
 */
abstract public class ResolverMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    public ResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, wrapFuture(returnType))
                .addAnnotation(OVERRIDE.className)
                .addException(EXCEPTION.className);
    }

    protected TypeName getReturnTypeName(ObjectField referenceField) {
        if (!referenceField.hasForwardPagination()) { // TODO: This is actually wrong for cases where a single class is returned!
            return wrapListIf(
                    processedSchema.isObject(referenceField)
                            ? processedSchema.getObject(referenceField).getGraphClassName()
                            : processedSchema.getInterface(referenceField).getGraphClassName(),
                    referenceField.isIterableWrapped()
            ); // Throws nullpointer if return type is not a schema type.
        }
        return processedSchema.getConnectionObject(referenceField).getGraphClassName();
    }

    protected ServiceDependency createServiceDependency(GenerationField target) {
        var dependency = new ServiceDependency(new ServiceWrapper(target, processedSchema.getObject(target)));
        dependencySet.add(dependency);
        return dependency;
    }
}
