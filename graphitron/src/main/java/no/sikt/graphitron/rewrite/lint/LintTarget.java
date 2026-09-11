package no.sikt.graphitron.rewrite.lint;

import graphql.language.SourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One node the engine's traversal dispatches to a subscribed visitor, as values rather than as a
 * parse tree: what it is, what it is called, what it says about itself, and the enclosing-type
 * context a rule may need.
 *
 * <p>Values because every one of them is a column the fact store already holds. A rule reading
 * {@link #name()} and {@link #description()} off this record reads what {@code graphql_type},
 * {@code graphql_field}, {@code graphql_argument} and {@code graphql_enum_value} carry, and a rule
 * reading {@link AppliedArgument#namedFields()} reads what {@code graphql_ast_value_entry} carries.
 * So the traversal that fills it can become a query without any rule changing, which is the whole
 * point of the shape: the rules do not know there is a parse tree, and the engine is the one place
 * that does.
 *
 * @param kind         which node position this is; also what a rule tests instead of asking the
 *                     parse tree what class the node is, the two being the same question
 * @param name         the node's name, or null where its position has none
 * @param description  the node's own description as written, or null where it wrote none. Not
 *                     normalised, because two rules ask different questions of it: whether the
 *                     author documented the node ({@link #described()}, where blank does not
 *                     count) and whether a description token occupies the source before it (this
 *                     column being non-null, where blank does), the latter deciding whether a
 *                     rename fix may treat the node's location as its name token
 * @param arguments    for an applied directive, the arguments it was written with, in written
 *                     order. Empty at every other kind
 * @param enclosingTypeName the type this node was written inside, or its own name at a type
 * @param enclosingTypeIsRootOperation whether that type is a root operation type
 * @param location     the node's source location (1-based line/column), the default finding range
 */
public record LintTarget(
    LintNodeKind kind,
    String name,
    String description,
    Map<String, AppliedArgument> arguments,
    String enclosingTypeName,
    boolean enclosingTypeIsRootOperation,
    SourceLocation location
) {

    /**
     * One argument an applied directive was written with.
     *
     * @param value       the argument's value where the author wrote a string, null where they
     *                    wrote anything else. The null is load-bearing: an argument written as a
     *                    number is an argument they passed, so a rule asking whether they passed
     *                    anything has to see it, while a rule wanting the text must not read a
     *                    number as one
     * @param namedFields every object-field name written anywhere inside the value, flattened. A
     *                    rule asking which input fields an application named asks this rather than
     *                    descending a value, and the flattening is what the one rule that asks
     *                    already does: it checks every descendant name against the argument's own
     *                    input type rather than tracking the nesting
     */
    public record AppliedArgument(String value, Set<String> namedFields) {
        public AppliedArgument {
            namedFields = Set.copyOf(namedFields == null ? Set.of() : namedFields);
        }
    }

    public LintTarget {
        // Not Map.copyOf: insertion order is the order the author wrote the arguments in, and a
        // rule reporting on several of them reports in that order.
        arguments = arguments == null || arguments.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Whether the author documented this node; a blank description documents nothing. */
    public boolean described() {
        return description != null && !description.isBlank();
    }
}
