package no.sikt.graphitron.maven;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.generators.abstractions.AbstractClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

public class QueryTypeFieldsWrappingMockGenerator extends AbstractClassGenerator {
    private final ProcessedSchema schema;

    public QueryTypeFieldsWrappingMockGenerator(ProcessedSchema processedSchema) {
        schema = processedSchema;
    }

    @Override
    public List<TypeSpec> generateAll() {
        return List.of(generate());
    }

    /*
      Generates a class with a method that returns a string representation of the query type fields.
     */
    public TypeSpec generate() {
        return TypeSpec.classBuilder("QueryDBQueries")
                .addMethod(
                        MethodSpec.methodBuilder("get")
                                .returns(String.class)
                                .addStatement("return $S",
                                        schema.getQueryType().getFields()
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
