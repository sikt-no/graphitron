package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.mappings.JavaPoetClassName.EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.OVERRIDE;

/**
 * This class contains common information and operations shared by resolver method generators.
 * @param <T> The field type that this generator operates on.
 */
abstract public class KickstartResolverMethodGenerator<T extends ObjectField> extends ResolverMethodGenerator<T> {
    public KickstartResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, wrapFuture(returnType))
                .addAnnotation(OVERRIDE.className)
                .addException(EXCEPTION.className);
    }
}
