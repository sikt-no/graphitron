package no.sikt.graphitron.model.lint;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;

/**
 * The rows of {@code lint_violation} as findings a build report can print.
 *
 * <p>Everything a rule decides is decided in SQL and is already decided by the time a row arrives
 * here: which positions offend, which the consumer excluded, which file was the author's to write.
 * What is left is presentation, and this is only that. The split is why the wording lives in Java
 * at all: a view carries its own products and a rendered sentence is a product of how a finding is
 * shown rather than of what the corpus says, so the view supplies the parts and this supplies the
 * grammar around them.
 *
 * <p>The rule alone does not choose the wording, which is the one thing worth knowing before
 * reading the switch below. Nine rules carry twelve templates: a missing description reads one way
 * for a type and another for a root operation's field, and a retired directive reads three ways
 * depending on whether the author wrote the directive, an argument of it, or a field of an input
 * object passed to one. The pair of rule and subject kind decides it, and the kind is
 * {@code graphql_element}'s vocabulary, so there is no branching here that the store cannot answer.
 *
 * <p>Two queries and no more, whatever the corpus holds. The fixes need one fact the findings do
 * not carry, and it is fetched as a set rather than asked per row: a loop that asks the store once
 * per finding is the shape that turns a linter into the slow part of a build.
 */
public final class LintFindings {

    private LintFindings() {}

    // The wording and the shapes the fixes are built from. The visitors this replaces carried these
    // too, and that duplication went with them. What is still spelled twice is the camel-case shape,
    // here and as the view's regexp_like, and the two are doing different jobs: the view decides
    // whether a name draws a row, this decides whether the rename about to be offered is a name at
    // all. They have to agree even so, or a fix proposes a spelling the rule flags again on the next
    // run. Nothing holds them together. NameShapeParityTest holds the view's spelling against a Java
    // one it states itself, which is this shape and not this constant.
    private static final Pattern CAMEL_CASE = Pattern.compile("[a-z][A-Za-z0-9]*");

    /**
     * Whether a rename this reader is about to offer is a camel-case name.
     *
     * <p>Visible so the shape can be held against the one the rule flags by. The two are written
     * separately and have to agree: the view decides whether a name draws a row, this decides
     * whether the replacement offered for it is a name at all, and a disagreement is a fix that
     * proposes a spelling the next run flags again. They agree today for a reason worth stating,
     * since the pattern does not look anchored. {@code Pattern.matches} requires the whole input,
     * so the unanchored spelling here decides what the view's anchored one decides, and a later
     * edit reaching for {@code find} would part them silently.
     */
    public static boolean isCamelCase(String candidate) {
        return CAMEL_CASE.matcher(candidate).matches();
    }
    private static final String CAMEL_CASE_FIX = "Rename field to camelCase";
    private static final String PREFIX_FIX = "Drop the type-name prefix";
    private static final String DESCRIPTION_FIX = "Add a description placeholder";
    private static final String DESCRIPTION_PLACEHOLDER = "\"\"\"TODO: describe.\"\"\"";
    private static final String REASON_FIX = "Add a reason placeholder";
    private static final String REASON_PLACEHOLDER = "(reason: \"TODO: describe the deprecation\")";
    private static final String DEPRECATED = "deprecated";

    /** Every finding this graph's rows assert, in the order the store returns them. */
    public static List<BuildWarning.LintFinding> of(StoreHandle store) {
        Set<String> withArguments = applicationsCarryingArguments(store);
        return store.dsl()
            .select(LINT_VIOLATION.LINT_RULE, LINT_VIOLATION.SUBJECT_KIND,
                LINT_VIOLATION.SUBJECT, LINT_VIOLATION.SUBJECT_PARENT,
                LINT_VIOLATION.SUBJECT_AT_POSITION, LINT_VIOLATION.SOURCE_NAME,
                LINT_VIOLATION.SOURCE_LINE, LINT_VIOLATION.SOURCE_COLUMN)
            .from(LINT_VIOLATION)
            .where(LINT_VIOLATION.GRAPH_NAME.eq(store.graphName()))
            .fetch()
            .map(row -> {
                var location = new SourceLocation(row.value7(), row.value8(), row.value6());
                var rule = ruleOf(row.value1());
                String message = message(row.value1(), row.value2(), row.value3(), row.value4());
                var fix = fix(row.value1(), row.value3(), row.value4(),
                    Boolean.TRUE.equals(row.value5()), location, withArguments);
                return new BuildWarning.LintFinding(message, location, rule, fix);
            });
    }

    /**
     * The wording, by rule and by what the finding is about.
     *
     * <p>A pair the view produces and this does not name is a finding nobody wrote a sentence for,
     * which is a defect in one of the two and not something to paper over with a default: an
     * unknown pair throws rather than printing a rule id at an author.
     */
    private static String message(String rule, String kind, String subject, String parent) {
        return switch (rule + "/" + kind) {
            case "input-object-name-suffix/NAMED_TYPE" ->
                "Input object type '%s' should have an 'Input' suffix.".formatted(subject);
            case "type-names-pascal-case/NAMED_TYPE" ->
                "Type name '%s' should be PascalCase.".formatted(subject);
            case "enum-values-screaming-snake-case/ENUM_VALUE" ->
                "Enum value '%s' should be SCREAMING_SNAKE_CASE.".formatted(subject);
            case "input-and-argument-names-camel-case/INPUT_FIELD",
                 "input-and-argument-names-camel-case/FIELD_ARGUMENT" ->
                "Name '%s' should be camelCase.".formatted(subject);
            case "field-names-camel-case/FIELD" ->
                "Field name '%s' should be camelCase.".formatted(subject);
            case "no-typename-prefix/FIELD" ->
                "Field '%s.%s' is prefixed with its type name; drop the prefix."
                    .formatted(parent, subject);
            case "types-and-fields-have-descriptions/NAMED_TYPE" ->
                "Type '%s' should have a description.".formatted(subject);
            case "types-and-fields-have-descriptions/FIELD" ->
                "Root-operation field '%s' should have a description.".formatted(subject);
            case "deprecations-have-a-reason/DIRECTIVE" ->
                "@deprecated should carry a non-empty 'reason'.";
            case "no-deprecated-directive-usage/DIRECTIVE" ->
                "Directive @%s is deprecated; see its description for the replacement."
                    .formatted(subject);
            case "no-deprecated-directive-usage/DIRECTIVE_ARGUMENT" ->
                "Argument '%s' of @%s is deprecated; see its definition for the replacement."
                    .formatted(subject, parent);
            case "no-deprecated-directive-usage/INPUT_FIELD" ->
                "Field '%s' of input '%s' is deprecated; see its definition for the replacement."
                    .formatted(subject, parent);
            default -> throw new IllegalStateException(
                "no wording for rule " + rule + " about a " + kind);
        };
    }

    /**
     * The edit offered beside the finding, where one is safe to offer.
     *
     * <p>Four rules carry a fix and each withholds it on its own terms. Three withhold where the
     * position is not the declaration's own, a described declaration being reported at its
     * description instead, and they withhold there for two different reasons. The two renames would
     * replace characters, so the edit would land in the author's prose. The description insert
     * would write a second description above an existing one, which is not a document any longer:
     * two strings in front of a declaration parse as a description and then as nothing the grammar
     * admits. That the rule fires at all on a described declaration is what a blank description
     * means, the row asserting that a description exists and says nothing.
     *
     * <p>The camel-case rename withholds again when the candidate is not a name or is the name it
     * started with. The reason placeholder withholds where the application already carries
     * arguments, its insertion point being the character after the directive's own name.
     *
     * <p>Withholding is the whole remedy and not a step towards one. Offering to replace the blank
     * description would need the extent it was written across, and the entry stratum holds the
     * decoded value rather than the spelling: {@code ""}, {@code "   "} and a block string of
     * newlines all arrive here as a blank, and nothing in the store says which was written. The
     * finding still lands on the description, which is the line the author has to edit.
     */
    private static Optional<LintFix> fix(String rule, String subject, String parent,
                                         boolean atPosition, SourceLocation at,
                                         Set<String> withArguments) {
        return switch (rule) {
            case "field-names-camel-case" -> {
                if (!atPosition) yield Optional.empty();
                String candidate = toCamelCase(subject);
                yield candidate.equals(subject) || !CAMEL_CASE.matcher(candidate).matches()
                    ? Optional.empty()
                    : Optional.of(LintFix.replaceToken(
                        CAMEL_CASE_FIX, at, subject.length(), candidate));
            }
            case "no-typename-prefix" -> {
                if (!atPosition) yield Optional.empty();
                String remainder = subject.substring(parent.length());
                String candidate = Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1);
                yield Optional.of(LintFix.replaceToken(PREFIX_FIX, at, subject.length(), candidate));
            }
            case "types-and-fields-have-descriptions" -> {
                if (!atPosition) yield Optional.empty();
                String indent = " ".repeat(Math.max(0, at.getColumn() - 1));
                yield Optional.of(LintFix.insertAt(
                    DESCRIPTION_FIX, at, DESCRIPTION_PLACEHOLDER + "\n" + indent));
            }
            case "deprecations-have-a-reason" -> {
                if (withArguments.contains(positionKey(at))) yield Optional.empty();
                var after = new SourceLocation(
                    at.getLine(), at.getColumn() + 1 + DEPRECATED.length(), at.getSourceName());
                yield Optional.of(LintFix.insertAt(REASON_FIX, after, REASON_PLACEHOLDER));
            }
            default -> Optional.empty();
        };
    }

    /**
     * The positions of directive applications that were written with at least one argument, as one
     * query. The reason placeholder is inserted straight after the directive's name, which is only
     * where the text ends when nothing follows it.
     */
    private static Set<String> applicationsCarryingArguments(StoreHandle store) {
        var g = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var positions = new HashSet<String>();
        store.dsl()
            .selectDistinct(g.SOURCE_NAME, g.PARENT_LINE, g.PARENT_COLUMN)
            .from(g)
            .where(g.GRAPH_NAME.eq(store.graphName()))
            .fetch()
            .forEach(row -> positions.add(row.value1() + ":" + row.value2() + ":" + row.value3()));
        return positions;
    }

    private static String positionKey(SourceLocation at) {
        return at.getSourceName() + ":" + at.getLine() + ":" + at.getColumn();
    }

    private static LintRule ruleOf(String id) {
        for (LintRule rule : LintRule.values()) {
            if (rule.id().equals(id)) {
                return rule;
            }
        }
        throw new IllegalStateException("no rule declared for id " + id);
    }

    /** The camel-case candidate, on the spelling the visitor this replaces uses. */
    private static String toCamelCase(String name) {
        String[] parts = name.split("_");
        var sb = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (first) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
                first = false;
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
