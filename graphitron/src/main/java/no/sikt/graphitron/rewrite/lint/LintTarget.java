package no.sikt.graphitron.rewrite.lint;

import graphql.language.Node;
import graphql.language.SourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One node the engine's traversal dispatches to a subscribed visitor, as values rather than as a
 * parse tree: what it is, what it is called, what it says about itself, and the enclosing-type
 * context a rule may need.
 *
 * <p>Values because every one of them is a column the fact store already holds. A rule reading
 * {@link #name()} and {@link #description()} off this record reads what {@code graphql_type},
 * {@code graphql_field}, {@code graphql_argument} and {@code graphql_enum_value} carry, so the
 * traversal that fills it can become a query without any rule changing. That is the whole point of
 * the shape: the rules stop knowing there is a parse tree, and the engine is left as the one place
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
 * @param arguments    for an applied directive, the arguments it was written with: the argument
 *                     name against its value where that value is a string, and against null where
 *                     it is anything else. Empty at every other kind. Keyed insertion-ordered, so a
 *                     reader asking whether the author passed anything at all asks this map
 * @param enclosingTypeName the type this node was written inside, or its own name at a type
 * @param enclosingTypeIsRootOperation whether that type is a root operation type
 * @param location     the node's source location (1-based line/column), the default finding range
 * @param node         the parse tree node. The one residue: {@code NoDeprecatedDirectiveUsageVisitor}
 *                     descends an applied directive's argument <em>values</em> to report deprecated
 *                     input fields used inside an application, and the store holds those values as
 *                     rendered SDL rather than as a structure to descend. Every other rule is off
 *                     the parse tree, so this field is the remaining work rather than the shape
 */
public record LintTarget(
    LintNodeKind kind,
    String name,
    String description,
    Map<String, String> arguments,
    String enclosingTypeName,
    boolean enclosingTypeIsRootOperation,
    SourceLocation location,
    Node<?> node
) {

    public LintTarget {
        // Not Map.copyOf: a null value is meaningful here, an argument the author wrote as
        // something other than a string, and that factory rejects one.
        arguments = arguments == null || arguments.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** Whether the author documented this node; a blank description documents nothing. */
    public boolean described() {
        return description != null && !description.isBlank();
    }
}
