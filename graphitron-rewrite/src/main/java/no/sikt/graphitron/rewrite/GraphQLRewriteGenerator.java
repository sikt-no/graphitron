package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.GraphitronWiringClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.GraphitronValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;

/**
 * Entry point for the rewrite code-generation pipeline.
 *
 * <p>This pipeline is independent of the legacy {@link no.sikt.graphitron.generate.GraphQLGenerator}: it parses the GraphQL
 * schema with its own {@link GraphitronSchemaBuilder}, runs its own list of generators, and
 * writes output to the same configured output directory. Generators added here incrementally
 * replace their legacy counterparts as the rewrite pipeline matures.
 */
public class GraphQLRewriteGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLRewriteGenerator.class);

    public static void generate() {
        var registry = getTypeDefinitionRegistry(RewriteConfig.generatorSchemaFiles());
        var schema = GraphitronSchemaBuilder.build(registry);

        schema.warnings().forEach(w -> {
            var loc = w.location();
            if (loc != null) {
                LOGGER.warn("{}:{}:{}: warning: {}", loc.getSourceName(), loc.getLine(), loc.getColumn(), w.message());
            } else {
                LOGGER.warn("warning: {}", w.message());
            }
        });

        var jooqCatalog = new JooqCatalog(RewriteConfig.getGeneratedJooqPackage());
        var errors = new GraphitronSchemaValidator(jooqCatalog).validate(schema);
        if (!errors.isEmpty()) {
            errors.forEach(e -> {
                var loc = e.location();
                if (loc != null) {
                    LOGGER.error("{}:{}:{}: error: {}", loc.getSourceName(), loc.getLine(), loc.getColumn(), e.message());
                } else {
                    LOGGER.error("error: {}", e.message());
                }
            });
            throw new RuntimeException("Rewrite schema validation failed with " + errors.size() + " error(s)");
        }

        var fetcherClasses = TypeFetcherGenerator.generate(schema);
        var fetcherClassNames = fetcherClasses.stream().map(TypeSpec::name).toList();

        // Collect one wiring entry per distinct Connection type referenced by any field.
        // Multiple fields may return the same Connection type; TypeRuntimeWiring is per-type.
        var connectionTypeMap = new java.util.LinkedHashMap<String, String>();
        schema.fields().forEach((coords, field) -> {
            if (field instanceof SqlGeneratingField sgf
                    && sgf.returnType().wrapper() instanceof FieldWrapper.Connection conn) {
                String parentType = coords.getTypeName();
                String fieldName = coords.getFieldName();
                String connName = conn.connectionName() != null
                    ? conn.connectionName()
                    : parentType + capitalize(fieldName) + "Connection";
                connectionTypeMap.putIfAbsent(connName, connName.replace("Connection", "Edge"));
            }
        });
        var connectionWirings = connectionTypeMap.entrySet().stream()
            .map(e -> new GraphitronWiringClassGenerator.ConnectionWiring(e.getKey(), e.getValue()))
            .toList();

        write(GraphitronValuesClassGenerator.generate(),          "rewrite");
        write(ColumnFetcherClassGenerator.generate(),             "rewrite");
        write(NodeIdEncoderClassGenerator.generate(),             "rewrite");
        write(ConnectionResultClassGenerator.generate(),          "rewrite");
        write(ConnectionHelperClassGenerator.generate(),          "rewrite");
        write(OrderByResultClassGenerator.generate(),             "rewrite");
        write(TypeClassGenerator.generate(schema),                "rewrite.types");
        write(TypeConditionsGenerator.generate(schema),           "rewrite.types");
        write(fetcherClasses,                                      "rewrite.types");
        write(List.of(GraphitronWiringClassGenerator.generate(fetcherClassNames, connectionWirings)), "rewrite");
    }

    private static void write(List<TypeSpec> specs, String subPackage) {
        var packageName = RewriteConfig.outputPackage() + "." + subPackage;
        specs.forEach(spec -> {
            try {
                JavaFile.builder(packageName, spec).indent("    ").build()
                    .writeTo(new File(RewriteConfig.outputDirectory()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        LOGGER.info("Rewrite: generated sources to: {}", packageName);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
