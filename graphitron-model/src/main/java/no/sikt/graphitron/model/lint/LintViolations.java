package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.replace;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The lint rules, as statements: each one selects the positions where the author wrote something
 * the rule objects to, and the rows it writes are the findings.
 *
 * <p>A row asserts a defect, which decides each rule's population and not merely its predicate. The
 * question every rule answers is whether the author could have written it otherwise at this
 * position, and where they could not there is no row, because a finding pointing at a place nothing
 * can be done about is a false fact rather than a noisy one.
 *
 * <p>Findings are keyed by the position the offending thing was written at, so a name spelled
 * wrongly at a base declaration and again at two extensions draws three rows. That is three places
 * the author has to edit, and each row is true.
 */
public final class LintViolations {

    private LintViolations() {}

    /** What the sweep empties, which is everything this writer owns. */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(LINT_VIOLATION);

    /**
     * Makes {@code graph}'s findings be what its rows now say.
     *
     * <p>The instant is the caller's and must be the one the rows it reads carry, the sweep telling
     * readings apart by it. Runs after the anchors, whose entry index every row here keys into.
     */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        inputObjectNameSuffix(dsl, graph, touchedAt);
        typeNamesPascalCase(dsl, graph, touchedAt);
        enumValuesScreamingSnakeCase(dsl, graph, touchedAt);
        inputAndArgumentNamesCamelCase(dsl, graph, touchedAt);
        sweep(dsl, graph, touchedAt);
    }

    /**
     * The name shapes, anchored at both ends.
     *
     * <p>The anchors are the whole difference between these and the patterns the rules carry in
     * Java, where {@code matches} anchors for you. {@code regexp_like} does not, so the unanchored
     * camel-case pattern would hold of any name containing a lowercase run, which is nearly every
     * name there is, and the rule would go quiet rather than loud.
     */
    private static final String PASCAL_CASE = "^[A-Z][A-Za-z0-9]*$";
    private static final String CAMEL_CASE = "^[a-z][A-Za-z0-9]*$";
    private static final String SCREAMING_SNAKE_CASE = "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$";

    /**
     * {@code input-object-name-suffix}: an input object's name must end in {@code Input}.
     *
     * <p>Every declaration site of one, extensions included, because the name is spelled at each of
     * them and renaming the type means editing every line that spells it.
     */
    private static void inputObjectNameSuffix(DSLContext dsl, String graph,
                                              LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        insert(dsl, graph, touchedAt, "input-object-name-suffix",
            d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d,
            d.GRAPH_NAME.eq(graph)
                .and(d.KIND.eq("INPUT_OBJECT"))
                .and(d.NAME.notLike("%Input"))
                .and(authored(d.SOURCE_NAME))
                .and(notExcluded(dsl, graph, d.NAME)));
    }

    /** {@code type-names-pascal-case}: every declared type, at every site that names one. */
    private static void typeNamesPascalCase(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        insert(dsl, graph, touchedAt, "type-names-pascal-case",
            d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d,
            d.GRAPH_NAME.eq(graph)
                .and(mismatches(d.NAME, PASCAL_CASE))
                .and(authored(d.SOURCE_NAME))
                .and(notExcluded(dsl, graph, d.NAME)));
    }

    /** {@code enum-values-screaming-snake-case}: the values one enum declares. */
    private static void enumValuesScreamingSnakeCase(DSLContext dsl, String graph,
                                                     LocalDateTime touchedAt) {
        var v = GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
        insert(dsl, graph, touchedAt, "enum-values-screaming-snake-case",
            v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN, v,
            v.GRAPH_NAME.eq(graph)
                .and(mismatches(v.NAME, SCREAMING_SNAKE_CASE))
                .and(authored(v.SOURCE_NAME))
                .and(notExcluded(dsl, graph, v.TYPE_NAME)));
    }

    /**
     * {@code input-and-argument-names-camel-case}: the two input positions the rule treats alike,
     * an input object's field and a field's argument, each from its own relation because the parse
     * keeps them apart and nothing here needs them together beyond sharing a predicate.
     */
    private static void inputAndArgumentNamesCamelCase(DSLContext dsl, String graph,
                                                       LocalDateTime touchedAt) {
        var f = GRAPHQL_AST_INPUT_FIELD_ENTRY;
        insert(dsl, graph, touchedAt, "input-and-argument-names-camel-case",
            f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f,
            f.GRAPH_NAME.eq(graph)
                .and(mismatches(f.NAME, CAMEL_CASE))
                .and(authored(f.SOURCE_NAME))
                .and(notExcluded(dsl, graph, f.TYPE_NAME)));
        var a = GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
        insert(dsl, graph, touchedAt, "input-and-argument-names-camel-case",
            a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, a,
            a.GRAPH_NAME.eq(graph)
                .and(mismatches(a.NAME, CAMEL_CASE))
                .and(authored(a.SOURCE_NAME))
                .and(notExcluded(dsl, graph, a.TYPE_NAME)));
    }

    /** Whether a written name fails a shape, which is what each of these rules objects to. */
    private static Condition mismatches(Field<String> name, String shape) {
        return condition("regexp_like({0}, {1})", name, inline(shape)).not();
    }

    /**
     * One rule's statement. The rule id is inlined rather than passed as a bind value so that the
     * statement reads as the rule it is, and the columns are the position the rule objects to.
     */
    private static void insert(DSLContext dsl, String graph, LocalDateTime touchedAt, String rule,
                               Field<String> sourceName, Field<Integer> sourceLine,
                               Field<Integer> sourceColumn, Table<?> from, Condition where) {
        var v = LINT_VIOLATION;
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.LINT_RULE, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                v.TOUCHED_AT)
            .select(dsl
                .select(val(graph, v.GRAPH_NAME), inline(rule), sourceName, sourceLine,
                    sourceColumn, val(touchedAt, v.TOUCHED_AT))
                .from(from)
                .where(where))
            .onDuplicateKeyUpdate()
            .set(v.TOUCHED_AT, excluded(v.TOUCHED_AT))
            .execute();
    }

    /**
     * Whether a position is the author's to fix. The two names the generator injects itself are the
     * bundled directive vocabulary and the tag-link synthesiser's, and an author can neither rename
     * nor document what either of them wrote.
     */
    private static Condition authored(Field<String> sourceName) {
        return sourceName.notIn(SchemaLoader.DIRECTIVES_SOURCE_NAME,
            TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME);
    }

    /**
     * Whether the consumer asked for this type to be left alone, against the globs their
     * {@code excludedTypes} configuration captured.
     *
     * <p>The glob becomes a {@code LIKE} pattern in the statement rather than being matched in Java
     * after the fetch, which is what keeps the relation honest: a row the consumer asked not to see
     * is one a reader of these rows would have to know to filter, and nobody reading a findings
     * relation should have to reproduce a configuration filter to believe it. The escape runs before
     * the translation, so a glob writing a literal {@code %} means that character rather than
     * turning into a wildcard the author never asked for.
     */
    private static Condition notExcluded(DSLContext dsl, String graph, Field<String> typeName) {
        var x = STORE_GRAPH_LINT_EXCLUDED_TYPE;
        Field<String> pattern = replace(
            replace(
                replace(
                    replace(
                        replace(x.TYPE_PATTERN, inline("!"), inline("!!")),
                        inline("%"), inline("!%")),
                    inline("_"), inline("!_")),
                inline("*"), inline("%")),
            inline("?"), inline("_"));
        return org.jooq.impl.DSL.notExists(selectOne()
            .from(x)
            .where(x.GRAPH_NAME.eq(graph))
            .and(typeName.like(pattern, '!')));
    }

    /**
     * Mark and sweep, per graph. A finding the corpus stopped drawing is one this reading did not
     * restamp, and an upsert cannot find it because there is no incoming row to match. The
     * positions an author deleted outright are gone already, carried off by the entry index's
     * cascade, so what this removes is the other half: the rules the author fixed in place.
     */
    private static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var named = LINT_VIOLATION;
        for (Table<?> table : TABLES_TO_SWEEP) {
            dsl.deleteFrom(table)
                .where(table.field(named.GRAPH_NAME).eq(graph))
                .and(table.field(named.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }
}
