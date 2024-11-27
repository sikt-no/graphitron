package no.sikt.graphitron.maven;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

public class QueryTypeFieldsWrappingMockGenerator extends AbstractClassGenerator<GenerationTarget> {

    public QueryTypeFieldsWrappingMockGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public List<TypeSpec> generateTypeSpecs() {
        return List.of(generate(null));
    }

    /*
      Generates a class with a method that returns a string representation of the query type fields.
     */
    @Override
    public TypeSpec generate(GenerationTarget target) {
        return TypeSpec.classBuilder("QueryDBQueries")
                .addMethod(
                        MethodSpec.methodBuilder("get")
                                .returns(String.class)
                                .addStatement("return \"$L\"",
                                        processedSchema.getQueryType().getFields()
                                                .stream().map(AbstractField::getName)
                                                .collect(Collectors.joining(", ", "[", "]")))
                                .build())
                .build();
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return "";
    }

    @Override
    public String getFileNameSuffix() {
        return "";
    }
}
