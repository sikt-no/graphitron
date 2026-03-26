package no.sikt.graphitron.generators.db;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.context.FetchContext;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.KeyWrapper.getKeyTableRecordTypeName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.wrapSet;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_SELECT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.SELECTION_SET;

/**
 * Generator that creates DB fetch methods for fields where a @service returns a table-backed type.
 * Extends {@link FetchMappedObjectDBMethodGenerator} with {@link #isRoot()} returning false so that
 * the generated method includes resolver key parameters and WHERE clause for batch fetching by PK.
 */
public class ServiceResultFetchDBMethodGenerator extends FetchMappedObjectDBMethodGenerator {

    public ServiceResultFetchDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    protected boolean isRoot() {
        return false;
    }

    @Override
    protected CodeBlock getHelperMethodCallForNestedField(ObjectField field, FetchContext context) {
        return null;
    }

    @Override
    protected CodeBlock getSelectBlockForRecord(ObjectField target, CodeBlock selectRowBlock, boolean isReferenceResolverField, InputParser parser) {
        return selectRowBlock;
    }

    /**
     * Override to exclude input parameters from the DB method signature. Input arguments are passed
     * to the service call, not to the DB query.
     */
    @Override
    protected MethodSpec.Builder getSpecBuilder(ObjectField referenceField, TypeName refTypeName, InputParser parser) {
        return getDefaultSpecBuilder(
                asQueryMethodName(referenceField.getName(), getLocalObject().getName()),
                getReturnType(referenceField, refTypeName)
        )
                .addParameter(wrapSet(getKeyTableRecordTypeName(referenceField, processedSchema)), resolverKeyParamName)
                .addParameter(SELECTION_SET.className, VAR_SELECT);
    }

    @Override
    public List<MethodSpec> generateAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(GenerationSourceField::hasImplicitSplitQuery)
                .filter(ObjectField::hasServiceReference)
                .filter(processedSchema::hasTableObject)
                .map(this::generate)
                .filter(it -> !it.code().isEmpty())
                .toList();
    }
}
