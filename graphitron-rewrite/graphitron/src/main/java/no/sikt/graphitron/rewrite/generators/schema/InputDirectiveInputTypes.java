package no.sikt.graphitron.rewrite.generators.schema;

import java.util.Set;

/**
 * Names of input object types declared in {@code graphitron-common/directives.graphqls} that
 * exist only to shape Graphitron's own build-time directive arguments. They must not reach
 * the user-facing programmatic schema, so the Commit B emitters filter them out.
 *
 * <p>Kept out of {@link no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry}
 * because that registry classifies <em>directive names</em>, not input type names; they are
 * different dimensions of the same "internal-only" idea.
 */
public final class InputDirectiveInputTypes {

    public static final Set<String> NAMES = Set.of(
        "ErrorHandler",
        "ReferencesForType",
        "FieldSort",
        "ExternalCodeReference",
        "ReferenceElement"
    );

    private InputDirectiveInputTypes() {}
}
