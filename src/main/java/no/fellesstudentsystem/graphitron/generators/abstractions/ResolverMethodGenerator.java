package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.dependencies.ContextDependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.declareContextVariable;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapFuture;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * This class contains common information and operations shared by resolver method generators.
 * @param <T> The field type that this generator operates on.
 */
abstract public class ResolverMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    public ResolverMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
        dependencySet.add(ContextDependency.getInstance());
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return MethodSpec
                .methodBuilder(methodName)
                .addAnnotation(OVERRIDE.className)
                .addModifiers(Modifier.PUBLIC)
                .addException(EXCEPTION.className)
                .returns(wrapFuture(returnType))
                .addStatement(declareContextVariable());
    }
}
