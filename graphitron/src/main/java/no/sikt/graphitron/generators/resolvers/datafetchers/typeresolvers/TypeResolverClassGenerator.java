package no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;
import no.sikt.graphitron.generators.abstractions.AbstractSchemaClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.ClassWiringContainer;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codeinterface.wiring.WiringContainer.asClassWiring;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;

/**
 * Class generator for wrapping the entity resolver.
 */
public class TypeResolverClassGenerator extends AbstractSchemaClassGenerator<TypeResolverTarget> {
    public static final String SAVE_DIRECTORY_NAME = "typeresolvers", FILE_NAME_SUFFIX = "TypeResolver";
    protected final List<ClassWiringContainer> typeWiringContainer = new ArrayList<>();

    public TypeResolverClassGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    public TypeSpec generate(TypeResolverTarget target) {
        var typeName = (target != null ? target.getName() : FEDERATION_ENTITY_UNION.getName()).replace("_", "");
        var resolverGenerator = new TypeResolverMethodGenerator(target, processedSchema);
        var spec = getSpec(typeName, List.of(resolverGenerator, new TypeNameMethodGenerator(target, processedSchema))).build();
        var className = getGeneratedClassName(typeName + getFileNameSuffix());
        addTypeResolvers(resolverGenerator.getTypeResolverWiring(), className);
        return spec;
    }

    @Override
    public String getDefaultSaveDirectoryName() {
        return DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + SAVE_DIRECTORY_NAME;
    }

    @Override
    public String getFileNameSuffix() {
        return FILE_NAME_SUFFIX;
    }

    public List<TypeSpec> generateAll() {
        var interfaces = processedSchema.getInterfaces().values().stream();
        var unions = processedSchema
                .getUnions()
                .values()
                .stream()
                .filter(it -> processedSchema.hasEntitiesField() || !it.getName().equals(FEDERATION_ENTITY_UNION.getName()))
                .toList();
        // Federation is not available, so neither stream actually finds the union except in our tests. If it becomes available, remove the special cases for entity.
        if (processedSchema.hasEntitiesField() && unions.stream().noneMatch(it -> it.getName().equals(FEDERATION_ENTITY_UNION.getName()))) {
            var entity = processedSchema.getUnion(FEDERATION_ENTITY_UNION.getName()); // Adds a null entry for the special case.
            return Stream
                    .concat(interfaces, Stream.concat(unions.stream(), Stream.of(entity)))
                    .map(this::generate)
                    .toList();
        }
        return Stream
                .concat(interfaces, unions.stream())
                .map(this::generate)
                .toList();
    }

    public List<ClassWiringContainer> getGeneratedTypeResolvers() {
        return typeWiringContainer;
    }

    protected void addTypeResolvers(List<WiringContainer> containers, ClassName className) {
        typeWiringContainer.addAll(asClassWiring(containers, className));
    }
}
