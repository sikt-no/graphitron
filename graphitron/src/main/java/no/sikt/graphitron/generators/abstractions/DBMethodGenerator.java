package no.sikt.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.codebuilding.VariableNames;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapListIf;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DSL_CONTEXT;

/**
 * Generic select query generation functionality is contained within this class.
 * @param <T> Field type that this generator operates on.
 */
abstract public class DBMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {

    public DBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC)
                .addParameter(DSL_CONTEXT.className, VariableNames.CONTEXT_NAME);
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    @Override
    protected TypeName iterableWrapType(GenerationField field) {
        return wrapListIf(inferFieldTypeName(field, true), field.isIterableWrapped());
    }
}
