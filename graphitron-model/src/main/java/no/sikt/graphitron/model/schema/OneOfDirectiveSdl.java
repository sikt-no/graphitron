package no.sikt.graphitron.model.schema;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;

/**
 * Codegen-side source of truth for reinstating the GraphQL {@code @oneOf} directive
 * <em>definition</em> in the federation SDL outputs.
 *
 * <p>The federation printer graphitron drives,
 * {@link com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter#generateServiceSDLV2},
 * strips definitions of spec-defined directives ({@code @oneOf} among them, via
 * {@code graphql.schema.idl.DirectiveInfo.isGraphqlSpecifiedDirective}), so a schema applying
 * {@code @oneOf} prints the <em>application</em> (<code>input Foo @oneOf</code>) without the
 * <em>definition</em>. graphql-java serves such a schema fine because it knows {@code @oneOf}
 * intrinsically; Apollo's composer predates the {@code @oneOf} spec addition and rejects the
 * subgraph SDL with {@code Unknown directive "@oneOf"}.
 *
 * <p>graphql-java's own {@code SchemaPrinter} (the non-federation arm) prints the definition, so
 * only the two federation seams need correcting: the on-disk {@code schema.graphqls}
 * ({@code SchemaSdlEmitter}) and the runtime
 * {@code _Service.sdl} baked into the generated
 * {@code GraphitronSchema.build}. Both string-augment the printer's output against this one
 * constant. The file arm calls this class directly; the runtime arm cannot link against the
 * {@code graphitron} module, so
 * {@code OneOfDirectiveSdlGenerator} emits a mirror
 * into {@code <outputPackage>.util.OneOfDirectiveSdl} whose literal comes from
 * {@link #DEFINITION}, keeping the exact string single-sourced.
 */
public final class OneOfDirectiveSdl {

    /**
     * The canonical {@code @oneOf} directive definition. The one thing that could drift between
     * the file arm and the generated runtime arm, so it lives in exactly one place.
     */
    public static final String DEFINITION = "directive @oneOf on INPUT_OBJECT";

    private OneOfDirectiveSdl() {
    }

    /**
     * True when at least one input object in {@code schema} applies {@code @oneOf}.
     * graphql-java derives {@link GraphQLInputObjectType#isOneOf()} from the applied directive at
     * build time, so this holds for both the SchemaGenerator-built assembled schema and the
     * programmatically-built runtime schema.
     */
    public static boolean usesOneOf(GraphQLSchema schema) {
        return schema.getAllTypesAsList().stream()
            .anyMatch(t -> t instanceof GraphQLInputObjectType inputType && inputType.isOneOf());
    }

    /**
     * Returns {@code sdl} augmented with the {@code @oneOf} definition. No-op when the schema does
     * not use {@code @oneOf}, or when the definition is already present (guards against a
     * graphql-java release that prints it): a schema that never uses {@code @oneOf} keeps
     * byte-identical output.
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
