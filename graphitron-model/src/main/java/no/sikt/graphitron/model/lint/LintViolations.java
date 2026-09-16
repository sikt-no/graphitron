package no.sikt.graphitron.model.lint;

import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_INPUT_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.LINT_VIOLATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_LINT_EXCLUDED_TYPE;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.length;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.replace;
import static org.jooq.impl.DSL.substring;
import static org.jooq.impl.DSL.trim;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.position;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.when;
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
        fieldNamesCamelCase(dsl, graph, touchedAt);
        noTypenamePrefix(dsl, graph, touchedAt);
        typesAndFieldsHaveDescriptions(dsl, graph, touchedAt);
        deprecationsHaveAReason(dsl, graph, touchedAt);
        noDeprecatedDirectiveUsage(dsl, graph, touchedAt);
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

    /**
     * {@code field-names-camel-case}: the fields of an object or an interface. An input object's
     * fields are a different relation and a different rule, the two having been kept apart by the
     * parse rather than by a filter here.
     */
    private static void fieldNamesCamelCase(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        insert(dsl, graph, touchedAt, "field-names-camel-case",
            f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f,
            f.GRAPH_NAME.eq(graph)
                .and(mismatches(f.NAME, CAMEL_CASE))
                .and(authored(f.SOURCE_NAME))
                .and(notExcluded(dsl, graph, f.TYPE_NAME)));
    }

    /**
     * {@code no-typename-prefix}: a field must not repeat its own type's name, so {@code User.name}
     * rather than {@code User.userName}.
     *
     * <p>Three clauses, and the third is what keeps {@code Userland} from reading as {@code User}
     * plus a prefix: the character after the repeated name has to start a new word. Upper-case is
     * tested as a character that changes under lower-casing and not under upper-casing, which is
     * what the walk's {@code Character.isUpperCase} means and what a range of {@code A} to {@code Z}
     * would narrow to the Latin alphabet.
     */
    private static void noTypenamePrefix(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        Field<String> boundary = substring(f.NAME, length(f.TYPE_NAME).plus(inline(1)), inline(1));
        insert(dsl, graph, touchedAt, "no-typename-prefix",
            f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f,
            f.GRAPH_NAME.eq(graph)
                .and(length(f.NAME).gt(length(f.TYPE_NAME)))
                .and(upper(substring(f.NAME, inline(1), length(f.TYPE_NAME))).eq(upper(f.TYPE_NAME)))
                .and(boundary.ne(lower(boundary)))
                .and(boundary.eq(upper(boundary)))
                .and(authored(f.SOURCE_NAME))
                .and(notExcluded(dsl, graph, f.TYPE_NAME)));
    }

    /**
     * {@code types-and-fields-have-descriptions}: every type, and the fields of a root operation
     * type, which are the schema's front door.
     *
     * <p>Two populations rather than one, because the question the rule asks is whether the author
     * documented something they could have documented. A type extension carries no description slot
     * at all, so an undescribed one is not an undocumented type, it is a position where documenting
     * is not a thing that can be done, and a row there would assert a defect nobody can fix. A field
     * declared inside an extension has no such problem and is linted like any other.
     */
    private static void typesAndFieldsHaveDescriptions(DSLContext dsl, String graph,
                                                       LocalDateTime touchedAt) {
        var d = GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
        insert(dsl, graph, touchedAt, "types-and-fields-have-descriptions",
            d.SOURCE_NAME, d.SOURCE_LINE, d.SOURCE_COLUMN, d,
            d.GRAPH_NAME.eq(graph)
                .and(d.IS_EXTENSION.isFalse())
                .and(undocumented(d.DESCRIPTION))
                .and(authored(d.SOURCE_NAME))
                .and(notExcluded(dsl, graph, d.NAME)));

        var f = GRAPHQL_AST_FIELD_DEFINITION_ENTRY;
        var r = GRAPHQL_ROOT_OPERATION;
        insert(dsl, graph, touchedAt, "types-and-fields-have-descriptions",
            f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f,
            f.GRAPH_NAME.eq(graph)
                .and(undocumented(f.DESCRIPTION))
                .and(exists(selectOne().from(r)
                    .where(r.GRAPH_NAME.eq(graph), r.TYPE_NAME.eq(f.TYPE_NAME))))
                .and(authored(f.SOURCE_NAME))
                .and(notExcluded(dsl, graph, f.TYPE_NAME)));
    }

    /**
     * Every directive application the rules may speak about: the four sites whose directives sit on
     * a schema element, as one population of position and name.
     *
     * <p>Two sites are left out and the entry index leaves them out rather than a list here doing
     * it. A directive on the schema block and a directive on a directive definition's own argument
     * enclose no element, so their entry carries no coordinate, and the join below drops them. That
     * is the same boundary the walk draws by having no arm for either, arrived at from the data
     * rather than restated.
     */
    private static Table<?> appliedDirectives(String graph) {
        var t = GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY;
        var f = GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY;
        var i = GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY;
        var v = GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY;
        return select(t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.NAME)
            .from(t).where(t.GRAPH_NAME.eq(graph))
            .unionAll(select(f.SOURCE_NAME, f.SOURCE_LINE, f.SOURCE_COLUMN, f.NAME)
                .from(f).where(f.GRAPH_NAME.eq(graph)))
            .unionAll(select(i.SOURCE_NAME, i.SOURCE_LINE, i.SOURCE_COLUMN, i.NAME)
                .from(i).where(i.GRAPH_NAME.eq(graph)))
            .unionAll(select(v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN, v.NAME)
                .from(v).where(v.GRAPH_NAME.eq(graph)))
            .asTable("application");
    }

    /**
     * {@code deprecations-have-a-reason}: an applied {@code @deprecated} must say why.
     *
     * <p>What counts as saying why is a written string with something in it. An argument passed as
     * anything other than a string says nothing a reader can act on, and neither does a string of
     * spaces, so both draw a row exactly as an omitted argument does. The value is read off the
     * decomposed expression rather than the rendered literal beside it, because that literal keeps
     * the author's quotes and a blank reason would have to be recognised through them.
     */
    private static void deprecationsHaveAReason(DSLContext dsl, String graph,
                                                LocalDateTime touchedAt) {
        var app = appliedDirectives(graph);
        Field<String> source = app.field(GRAPHQL_AST_ENTRY.SOURCE_NAME);
        Field<Integer> line = app.field(GRAPHQL_AST_ENTRY.SOURCE_LINE);
        Field<Integer> column = app.field(GRAPHQL_AST_ENTRY.SOURCE_COLUMN);
        Field<String> directive = app.field(field(name("NAME"), String.class));
        var e = GRAPHQL_AST_ENTRY.as("enclosing");
        var a = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var val = GRAPHQL_AST_VALUE_ENTRY;
        var v = LINT_VIOLATION;

        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.LINT_RULE, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                v.TOUCHED_AT)
            .select(dsl
                .select(val(graph, v.GRAPH_NAME), inline("deprecations-have-a-reason"),
                    source, line, column, val(touchedAt, v.TOUCHED_AT))
                .from(app)
                .join(e).on(e.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)), e.SOURCE_NAME.eq(source),
                    e.SOURCE_LINE.eq(line), e.SOURCE_COLUMN.eq(column))
                .where(directive.eq(inline("deprecated")))
                .and(e.ELEMENT_COORDINATE.isNotNull())
                .and(authored(source))
                .and(notExcluded(dsl, graph, typeOf(e.ELEMENT_COORDINATE)))
                .andNotExists(selectOne()
                    .from(a)
                    .join(val).on(val.GRAPH_NAME.eq(a.GRAPH_NAME),
                        val.SOURCE_NAME.eq(a.SOURCE_NAME),
                        val.HOLDER_LINE.eq(a.SOURCE_LINE),
                        val.HOLDER_COLUMN.eq(a.SOURCE_COLUMN))
                    .where(a.GRAPH_NAME.eq(graph))
                    .and(a.SOURCE_NAME.eq(source))
                    .and(a.PARENT_LINE.eq(line))
                    .and(a.PARENT_COLUMN.eq(column))
                    .and(a.NAME.eq(inline("reason")))
                    .and(val.KIND.eq(inline("STRING")))
                    .and(trim(val.WRITTEN_TEXT).ne(inline("")))))
            .onDuplicateKeyUpdate()
            .set(v.TOUCHED_AT, excluded(v.TOUCHED_AT))
            .execute();
    }

    /**
     * {@code no-deprecated-directive-usage}: an author writing something the vocabulary has
     * retired.
     *
     * <p>Three arms, because three different things can be deprecated and each is written at its
     * own place: the directive, one of its arguments, and an input field named inside an argument's
     * value. Each arm draws its row at the thing the author would edit, so a retired input field
     * puts the row on the value that names it rather than on the whole application, and an editor
     * asked to jump lands on the words that have to change.
     *
     * <p>Which markers say deprecated is not asked here. GraphQL puts the two forms apart, native
     * {@code @deprecated} being illegal on a directive definition so that a retired directive says
     * so in its description instead, and capture has already unified them into three relations. A
     * rule reading them does not have to know which marker was used, and does not.
     *
     * <p>{@code @record} is passed over, as the walk passes over it: its redundancy is a classifier
     * advisory with its own rule id, and two rules reporting one application would be two findings
     * for one edit.
     */
    private static void noDeprecatedDirectiveUsage(DSLContext dsl, String graph,
                                                   LocalDateTime touchedAt) {
        var app = appliedDirectives(graph);
        Field<String> source = app.field(GRAPHQL_AST_ENTRY.SOURCE_NAME);
        Field<Integer> line = app.field(GRAPHQL_AST_ENTRY.SOURCE_LINE);
        Field<Integer> column = app.field(GRAPHQL_AST_ENTRY.SOURCE_COLUMN);
        Field<String> directive = app.field(field(name("NAME"), String.class));
        var e = GRAPHQL_AST_ENTRY.as("enclosing");
        var a = GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY;
        var val = GRAPHQL_AST_VALUE_ENTRY;
        var v = LINT_VIOLATION;
        var dd = GRAPHITRON_DEPRECATED_DIRECTIVE;
        var da = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
        var di = GRAPHITRON_DEPRECATED_INPUT_FIELD;
        var formal = GRAPHQL_DIRECTIVE_ARGUMENT;
        Condition linted = e.ELEMENT_COORDINATE.isNotNull()
            .and(directive.ne(inline("record")))
            .and(authored(source))
            .and(notExcluded(dsl, graph, typeOf(e.ELEMENT_COORDINATE)));

        // The directive itself. The row goes at the application, which is the whole of what the
        // author writes and the whole of what they would remove.
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.LINT_RULE, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                v.TOUCHED_AT)
            .select(dsl
                .select(val(graph, v.GRAPH_NAME), inline("no-deprecated-directive-usage"),
                    source, line, column, val(touchedAt, v.TOUCHED_AT))
                .from(app)
                .join(e).on(e.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)), e.SOURCE_NAME.eq(source),
                    e.SOURCE_LINE.eq(line), e.SOURCE_COLUMN.eq(column))
                .join(dd).on(dd.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)),
                    dd.DIRECTIVE_NAME.eq(directive))
                .where(linted))
            .onDuplicateKeyUpdate().set(v.TOUCHED_AT, excluded(v.TOUCHED_AT)).execute();

        // An argument of it. The row goes at the argument the author passed, not at the application
        // carrying it, the rest of which may be perfectly current.
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.LINT_RULE, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                v.TOUCHED_AT)
            .select(dsl
                .select(val(graph, v.GRAPH_NAME), inline("no-deprecated-directive-usage"),
                    a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, val(touchedAt, v.TOUCHED_AT))
                .from(a)
                .join(app).on(source.eq(a.SOURCE_NAME), line.eq(a.PARENT_LINE),
                    column.eq(a.PARENT_COLUMN))
                .join(e).on(e.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)), e.SOURCE_NAME.eq(source),
                    e.SOURCE_LINE.eq(line), e.SOURCE_COLUMN.eq(column))
                .join(da).on(da.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)),
                    da.DIRECTIVE_NAME.eq(directive), da.ARGUMENT_NAME.eq(a.NAME))
                .where(a.GRAPH_NAME.eq(graph)).and(linted))
            .onDuplicateKeyUpdate().set(v.TOUCHED_AT, excluded(v.TOUCHED_AT)).execute();

        // An input field named inside the value. Every name written anywhere in the expression is
        // checked against the argument's own declared type, at any depth, because a deprecated
        // field is deprecated wherever it appears and none of this vocabulary's nesting changes
        // which type owns one.
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.LINT_RULE, v.SOURCE_NAME, v.SOURCE_LINE, v.SOURCE_COLUMN,
                v.TOUCHED_AT)
            .select(dsl
                .select(val(graph, v.GRAPH_NAME), inline("no-deprecated-directive-usage"),
                    val.SOURCE_NAME, val.SOURCE_LINE, val.SOURCE_COLUMN,
                    val(touchedAt, v.TOUCHED_AT))
                .from(val)
                .join(a).on(a.GRAPH_NAME.eq(val.GRAPH_NAME), a.SOURCE_NAME.eq(val.SOURCE_NAME),
                    a.SOURCE_LINE.eq(val.HOLDER_LINE), a.SOURCE_COLUMN.eq(val.HOLDER_COLUMN))
                .join(app).on(source.eq(a.SOURCE_NAME), line.eq(a.PARENT_LINE),
                    column.eq(a.PARENT_COLUMN))
                .join(e).on(e.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)), e.SOURCE_NAME.eq(source),
                    e.SOURCE_LINE.eq(line), e.SOURCE_COLUMN.eq(column))
                .join(formal).on(formal.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)),
                    formal.DIRECTIVE_NAME.eq(directive), formal.ARGUMENT_NAME.eq(a.NAME))
                .join(di).on(di.GRAPH_NAME.eq(val(graph, v.GRAPH_NAME)),
                    di.TYPE_NAME.eq(formal.NAMED_TYPE), di.FIELD_NAME.eq(val.OBJECT_FIELD_NAME))
                .where(val.GRAPH_NAME.eq(graph))
                .and(val.OBJECT_FIELD_NAME.isNotNull())
                .and(linted))
            .onDuplicateKeyUpdate().set(v.TOUCHED_AT, excluded(v.TOUCHED_AT)).execute();
    }

    /**
     * The type a coordinate names, which is its first part whatever kind of element it is: the
     * whole spelling at a type, and everything before the dot at a field, an input field, an enum
     * value or an argument. What the consumer's excludedTypes globs are matched against, a
     * consumer asking for a type to be left alone meaning the things written inside it too.
     */
    private static Field<String> typeOf(Field<String> coordinate) {
        return when(position(coordinate, inline(".")).gt(inline(0)),
            substring(coordinate, inline(1), position(coordinate, inline(".")).minus(inline(1))))
            .otherwise(coordinate);
    }

    /**
     * Whether the author documented this. A description of nothing but whitespace documents
     * nothing, which is a different question from whether a description token occupies the source,
     * and this is the first one.
     */
    private static Condition undocumented(Field<String> description) {
        return description.isNull().or(trim(description).eq(inline("")));
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
        return notExists(selectOne()
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
