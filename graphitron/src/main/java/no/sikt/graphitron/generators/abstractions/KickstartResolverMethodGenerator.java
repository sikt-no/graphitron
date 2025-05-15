package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapFuture;
import static no.sikt.graphitron.mappings.JavaPoetClassName.EXCEPTION;
import static no.sikt.graphitron.mappings.JavaPoetClassName.OVERRIDE;

/**
 * This class contains common information and operations shared by resolver method generators.
 */
abstract public class KickstartResolverMethodGenerator extends ResolverMethodGenerator {
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

    /**
     * @return Does this method generator generate all possible methods? False if any are set to not generate.
     */
    public abstract boolean generatesAll();
}
