package no.sikt.graphitron.rewrite.schema;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;

import java.util.List;

/**
 * The assembly stage's outcome as a value: either the executable schema graphql-java built from a
 * registry, or the errors it raised instead.
 *
 * <p>Assembling is the SDL toolchain's own validation pass, and it is the only place the
 * specification's structural rules get checked at all: that every named type resolves, that an
 * object satisfies the interfaces it claims, that a directive sits somewhere its definition
 * permits, that the schema has a query root. Those verdicts are facts about the consumer's schema
 * worth as much as the declarations they judge, so assembly runs on every pass whether or not the
 * assembled schema is going to be used for anything, and its outcome is data rather than control
 * flow. A caller that needs the schema reads {@link Assembled}; a caller that only needs the
 * verdict reads either arm and writes it down.
 *
 * <p>Sealed on the two outcomes rather than returning a nullable schema, so a caller cannot reach
 * for the schema without having said what it does when there is none.
 */
public sealed interface SchemaAssembly {

    /** The registry assembled; {@code schema} is the executable schema it produced. */
    record Assembled(GraphQLSchema schema) implements SchemaAssembly {}

    /**
     * The registry did not assemble.
     *
     * @param errors the refusals, transcribed, in graphql-java's own emit order
     * @param cause  the problem as graphql-java raised it, so a caller that fails the build throws
     *               what it always threw rather than a reconstruction
     */
    record Rejected(List<SchemaError> errors, SchemaProblem cause) implements SchemaAssembly {}

    /**
     * Assembles {@code registry}, returning the outcome instead of throwing on refusal.
     *
     * <p>The wiring is the echoing stand-in: this schema is assembled to be read, never executed,
     * so every field resolves through graphql-java's echoing fetcher and every non-spec scalar
     * gets a fake coercer. Without the fake scalars a custom scalar would fail assembly for want
     * of an implementation, which would report a wiring gap as though it were an author's schema
     * error.
     */
    static SchemaAssembly of(TypeDefinitionRegistry registry) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, definition) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        try {
            return new Assembled(new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring));
        } catch (SchemaProblem e) {
            return new Rejected(SchemaError.allOf(SchemaError.Stage.ASSEMBLY, e.getErrors()), e);
        }
    }

    /**
     * The assembled schema, or a thrown {@link SchemaProblem} where the registry did not assemble.
     * For callers whose contract is to fail on an unassemblable schema and who have nothing to
     * record about why.
     */
    default GraphQLSchema orThrow() {
        return switch (this) {
            case Assembled a -> a.schema();
            case Rejected r -> throw r.cause();
        };
    }

    /** The refusals, empty on {@link Assembled}; the arm-agnostic read for a caller that records them. */
    default List<SchemaError> errors() {
        return switch (this) {
            case Assembled ignored -> List.of();
            case Rejected r -> r.errors();
        };
    }
}
