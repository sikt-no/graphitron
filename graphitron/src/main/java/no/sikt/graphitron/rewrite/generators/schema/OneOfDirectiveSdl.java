package no.sikt.graphitron.rewrite.generators.schema;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;

/**
 * Codegen-side source of truth for reinstating the GraphQL {@code @oneOf} directive
 * <em>definition</em> in the federation SDL outputs.
 *
 * <p>When a consumer schema applies the built-in {@code @oneOf} directive on an input type,
 * graphitron's federation outputs print the <em>application</em> (<code>input Foo @oneOf</code>)
 * but never the <em>definition</em> (<code>directive @oneOf on INPUT_OBJECT</code>). The federation
 * printer graphitron drives,
 * {@link com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter#generateServiceSDLV2},
 * strips definitions of spec-defined directives (it filters
 * {@code graphql.schema.idl.DirectiveInfo.isGraphqlSpecifiedDirective} via its internal
 * {@code includeSchemaElement} predicate), and {@code @oneOf} is one of them. graphql-java serves
 * such a schema fine because it knows {@code @oneOf} intrinsically; Apollo's composer (older than
 * the {@code @oneOf} spec addition) rejects the subgraph SDL it reads with
 * {@code Unknown directive "@oneOf"}.
 *
 * <p>graphql-java's own {@code SchemaPrinter} (the non-federation arm) <em>does</em> print the
 * definition, so only the two federation seams need correcting: the on-disk
 * {@code schema.graphqls} ({@link SchemaSdlEmitter}) and the runtime {@code _Service.sdl} baked
 * into the generated {@code GraphitronSchema.build}. Both run
 * {@code generateServiceSDLV2} and both reinstate the definition by string-augmenting its output
 * against this one constant.
 *
 * <p>This class is the codegen-side half (the file arm calls it directly). The runtime arm cannot
 * link against the {@code graphitron} module, so its mirror is emitted into
 * {@code <outputPackage>.util.OneOfDirectiveSdl} by
 * {@link no.sikt.graphitron.rewrite.generators.util.OneOfDirectiveSdlGenerator}, whose
 * {@code DEFINITION} literal is emitted from {@link #DEFINITION} so the exact string is
 * single-sourced.
 */
public final class OneOfDirectiveSdl {

    /**
     * The canonical {@code @oneOf} directive definition: no arguments, the single
     * {@code INPUT_OBJECT} location. This is the one thing that could semantically drift between
     * the file arm and the generated runtime arm, so it lives in exactly one place.
     */
    public static final String DEFINITION = "directive @oneOf on INPUT_OBJECT";

    private OneOfDirectiveSdl() {
    }

    /**
     * True when at least one input object in {@code schema} applies {@code @oneOf}
     * ({@link GraphQLInputObjectType#isOneOf()}). graphql-java derives the flag from the applied
     * {@code @oneOf} directive at build time, so this holds for both the SchemaGenerator-built
     * assembled schema and the programmatically-built runtime schema.
     */
    public static boolean usesOneOf(GraphQLSchema schema) {
        return schema.getAllTypesAsList().stream()
            .anyMatch(t -> t instanceof GraphQLInputObjectType inputType && inputType.isOneOf());
    }

    /**
     * Returns {@code sdl} augmented with the {@code @oneOf} definition. No-op when the schema does
     * not use {@code @oneOf}, or when the definition is already present (future-proofing against a
     * graphql-java release that starts printing it): a schema that never uses {@code @oneOf}
     * therefore keeps byte-identical output.
     */
    public static String augment(GraphQLSchema schema, String sdl) {
        if (!usesOneOf(schema)) {
            return sdl;
        }
        if (sdl.contains("directive @oneOf")) {
            return sdl;
        }
        String base = sdl.endsWith("\n") ? sdl : sdl + "\n";
        return base + "\n" + DEFINITION + "\n";
    }
}
