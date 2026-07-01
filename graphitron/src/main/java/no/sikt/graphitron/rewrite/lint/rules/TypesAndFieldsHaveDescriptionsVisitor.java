package no.sikt.graphitron.rewrite.lint.rules;

import graphql.language.Description;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.Node;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.UnionTypeDefinition;
import no.sikt.graphitron.rewrite.lint.LintContext;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintNodeKind;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.lint.LintTarget;
import no.sikt.graphitron.rewrite.lint.LintVisitor;

import java.util.Set;

/**
 * {@code types-and-fields-have-descriptions}: type definitions and their root-operation fields carry
 * descriptions. Default scope keeps noise down: every type, plus fields on the root operation types
 * (Query / Mutation / Subscription). Advisory, not an error.
 *
 * <p>Carries an additive fix: insert a triple-quoted description placeholder on its own line
 * immediately above the definition, re-indented to the definition's column. The rule fires only when
 * the definition has no description, so its source location is the definition keyword / name token
 * (a described node's location would point at the description), and the insertion point is exact.
 */
public final class TypesAndFieldsHaveDescriptionsVisitor implements LintVisitor {

    public static final String TYPE_MESSAGE = "Type '%s' should have a description.";
    public static final String FIELD_MESSAGE = "Root-operation field '%s' should have a description.";
    public static final String FIX_DESCRIPTION = "Add a description placeholder";
    public static final String DESCRIPTION_PLACEHOLDER = "\"\"\"TODO: describe.\"\"\"";

    @Override
    public LintRule rule() {
        return LintRule.TYPES_AND_FIELDS_HAVE_DESCRIPTIONS;
    }

    @Override
    public Set<LintNodeKind> kinds() {
        return Set.of(
            LintNodeKind.OBJECT_TYPE, LintNodeKind.INTERFACE_TYPE, LintNodeKind.UNION_TYPE,
            LintNodeKind.ENUM_TYPE, LintNodeKind.INPUT_OBJECT_TYPE, LintNodeKind.SCALAR_TYPE,
            LintNodeKind.FIELD_DEFINITION);
    }

    @Override
    public void inspect(LintTarget target, LintContext ctx) {
        boolean described = hasDescription(target.node());
        if (target.kind() == LintNodeKind.FIELD_DEFINITION) {
            if (target.enclosingTypeIsRootOperation() && !described) {
                ctx.report(FIELD_MESSAGE.formatted(target.name()), descriptionFix(target.location()));
            }
            return;
        }
        if (!described) {
            ctx.report(TYPE_MESSAGE.formatted(target.name()), descriptionFix(target.location()));
        }
    }

    /**
     * Insert the placeholder plus a newline and the definition's own indentation just before the
     * definition, so the description lands on its own line and the definition keeps its column.
     */
    private static LintFix descriptionFix(SourceLocation at) {
        String indent = " ".repeat(Math.max(0, at.getColumn() - 1));
        return LintFix.insertAt(FIX_DESCRIPTION, at, DESCRIPTION_PLACEHOLDER + "\n" + indent);
    }

    private static boolean hasDescription(Node<?> node) {
        Description description = switch (node) {
            case ObjectTypeDefinition n -> n.getDescription();
            case InterfaceTypeDefinition n -> n.getDescription();
            case UnionTypeDefinition n -> n.getDescription();
            case EnumTypeDefinition n -> n.getDescription();
            case InputObjectTypeDefinition n -> n.getDescription();
            case ScalarTypeDefinition n -> n.getDescription();
            case FieldDefinition n -> n.getDescription();
            default -> null;
        };
        return description != null && description.getContent() != null && !description.getContent().isBlank();
    }
}
