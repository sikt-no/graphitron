package no.sikt.graphitron.rewrite.schema;

import graphql.schema.idl.SchemaParser;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Graphitron-declared support types: every type definition in the bundled
 * {@code directives.graphqls}. These exist so Graphitron's build-time directive arguments
 * type-check during classification; they are fully consumed at generate time.
 *
 * <p>The set is <em>derived</em> from {@link RewriteSchemaLoader#directivesSdl()}, not
 * hand-maintained: a type belongs here iff it is declared in {@code directives.graphqls}.
 * Editing that file changes the set; {@code DirectiveSupportTypesTest} pins the expected
 * membership so the change is made consciously.
 *
 * <p>Within the set there are two tiers:
 * <ul>
 *   <li><b>Published</b> ({@link #published()}; today exactly {@code SortDirection}): a
 *       client-facing reference is sanctioned. The classifier retains the type (classified,
 *       registered at runtime, printed in the published SDL) iff some coordinate of a
 *       non-support type references it.</li>
 *   <li><b>Strictly internal</b> ({@link #strictlyInternal()}; the rest): never retained. A
 *       client-facing reference is an authoring mistake the classifier rejects as a typed
 *       {@code Rejection.AuthorError} so it fails at validate time rather than dangling in
 *       generated code.</li>
 * </ul>
 *
 * <p>Both consumers of the retention decision ({@code TypeBuilder}'s classification skip and
 * {@code SchemaSdlEmitter}'s print filter) read {@code GraphitronSchema.types()} membership;
 * this class only names the candidate set and its tiers.
 */
public final class DirectiveSupportTypes {

    private static final Set<String> PUBLISHED = Set.of("SortDirection");
    private static final Set<String> ALL = derive();
    private static final Set<String> STRICTLY_INTERNAL = strictlyInternalOf(ALL);

    private DirectiveSupportTypes() {}

    /** Every type name declared in {@code directives.graphqls}. */
    public static Set<String> all() {
        return ALL;
    }

    /** Support types whose client-facing references are sanctioned. */
    public static Set<String> published() {
        return PUBLISHED;
    }

    /** Support types that must never reach the published schema. */
    public static Set<String> strictlyInternal() {
        return STRICTLY_INTERNAL;
    }

    public static boolean isSupportType(String typeName) {
        return ALL.contains(typeName);
    }

    public static boolean isPublished(String typeName) {
        return PUBLISHED.contains(typeName);
    }

    public static boolean isStrictlyInternal(String typeName) {
        return STRICTLY_INTERNAL.contains(typeName);
    }

    private static Set<String> derive() {
        var registry = new SchemaParser().parse(RewriteSchemaLoader.directivesSdl());
        var names = new LinkedHashSet<>(registry.types().keySet());
        registry.scalars().values().stream()
            .filter(def -> def.getSourceLocation() != null)  // drop graphql-java's built-in scalars
            .forEach(def -> names.add(def.getName()));
        return Set.copyOf(names);
    }

    private static Set<String> strictlyInternalOf(Set<String> all) {
        if (!all.containsAll(PUBLISHED)) {
            throw new IllegalStateException(
                "published support types " + PUBLISHED + " must be declared in directives.graphqls; found " + all);
        }
        var internal = new LinkedHashSet<>(all);
        internal.removeAll(PUBLISHED);
        return Set.copyOf(internal);
    }
}
