package no.sikt.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

abstract public class DataFetcherMethodGenerator<T extends ObjectField> extends AbstractMethodGenerator<T> {
    protected final List<WiringContainer> dataFetcherWiring = new ArrayList<>();

    public DataFetcherMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        super(localObject, processedSchema);
    }

    @Override
    public MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType) {
        return super
                .getDefaultSpecBuilder(methodName, returnType)
                .addModifiers(Modifier.STATIC);
    }

    public List<WiringContainer> getDataFetcherWiring() {
        return dataFetcherWiring;
    }
}
