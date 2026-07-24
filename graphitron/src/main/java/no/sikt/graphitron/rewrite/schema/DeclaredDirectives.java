package no.sikt.graphitron.rewrite.schema;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.Set;

/**
 * The Graphitron-declared directive names: every {@code directive @...} definition in the
 * bundled {@code directives.graphqls}. These are the generator's build-time vocabulary;
 * none of them has runtime meaning in the emitted schema.
 *
 * <p>The set is <em>derived</em> from {@link RewriteSchemaLoader#directivesSdl()}, not
 * hand-maintained: a directive belongs here iff it is declared in {@code directives.graphqls}.
 * Editing that file changes the set; {@code SchemaDirectiveRegistryTest} pins the expected
 * membership so the change is made consciously. Sibling of {@link DirectiveSupportTypes},
 * which derives the support-<em>type</em> set from the same parse.
 */
public final class DeclaredDirectives {

    /**
     * Single parse of the bundled {@code directives.graphqls}, shared with
     * {@link DirectiveSupportTypes} so both derived views read the resource once.
     */
    static final TypeDefinitionRegistry PARSED_SDL =
        new SchemaParser().parse(RewriteSchemaLoader.directivesSdl());

    private static final Set<String> NAMES =
        Set.copyOf(PARSED_SDL.getDirectiveDefinitions().keySet());

    private DeclaredDirectives() {}

    /** Every directive name declared in {@code directives.graphqls}. */
    public static Set<String> names() {
        return NAMES;
    }
}
