package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders a table-bound type's projection unit, its {@code $project}, from the facts
 * {@link ProjectionQuery} reads, or names why it cannot yet: additive and uncalled, the walk's
 * {@code ProjectionUnitRenderer} rendering the units every fetcher calls until the flip.
 *
 * <p>A unit is emitted whole or not at all. Its fields are the type's own and, inline beneath a
 * nesting field, the nesting type's, each nesting level a method of the unit's class rather than a
 * class of its own. A column is projected by its identity, as the walk's unit projects it, and a
 * node id by its key columns in key order, the encode being its reader's.
 *
 * <p>Every reason is a fact not yet stated or a shape not yet rendered, and each closes as its node
 * lands; at the flip each that remains becomes a stored defect of basis {@code UNSUPPORTED}, and
 * {@link Result.NotYet} goes.
 */
public final class ProjectionEmitter {

    private ProjectionEmitter() {}

    /** Why an occurrence of a unit is not rendered from the store yet. */
    public enum Reason {
        /** An {@code @externalField}: nothing binds its method at the occurrence yet. */
        EXPRESSION,
        /**
         * A field resolved by a producer of its own, a split, a service, a batched or polymorphic
         * fetch, whose parent row must carry the columns its loader keys on, which nothing states
         * at the occurrence yet.
         */
        CHILD_PRODUCER_COLUMNS,
        /**
         * A child read inline down a path, a single join or a list multiset over the rows the path
         * reaches, until its ordering and its {@code @lookupKey} narrowing are stated at the
         * occurrence.
         */
        INLINE_CHILD,
        /**
         * A nullable nesting field, read off its parent's row: nothing states when it is null. A
         * non-null one is rendered.
         */
        NULL_CONTRACT,
        /**
         * A scalar whose column lies down a path that is not one subselect of column-pair joins
         * from the unit's row: a step joining by a condition method or a routine, or a node id down
         * a path.
         */
        SCALAR_PATH,
        /**
         * A {@code @pivot}: deferred past the flip, where it becomes the stored defect
         * {@code PIVOT_UNSUPPORTED} of basis {@code UNSUPPORTED}, owned by {@code entry-defects}.
         */
        PIVOT,
        /** A field the store states no way of reading on the unit's row. */
        UNSTATED
    }

    /** What emitting a unit yields: its class, or each occurrence not yet rendered with its reason. */
    public sealed interface Result {
        /** The unit's class, named for the type. */
        record Emitted(TypeSpec unit) implements Result {
            public Emitted {
                Objects.requireNonNull(unit, "unit");
            }
        }

        /** The occurrences not yet rendered, each with why. */
        record NotYet(List<Refusal> refusals) implements Result {
            public NotYet {
                refusals = List.copyOf(refusals);
            }
        }
    }

    /** One occurrence not yet rendered and why. */
    public record Refusal(String coordinate, Reason reason) {}

    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LINKED_HASH_SET = ClassName.get("java.util", "LinkedHashSet");
    private static final ClassName ARRAY_LIST = ClassName.get(ArrayList.class);

    /** The alias a unit projecting nothing selects, so its statement stays one column wide. */
    private static final String ROW_PRESENT = "__row_present__";

    /** What a nesting level's result key is followed by in the names written beneath it. */
    static final String PATH_DELIMITER = "/";

    /**
     * What a written name starts with: a marker, then the owner and its delimiter where the name is
     * owned. The field readers compose the same prefix, so the two are held equal by a test until
     * both read their names from one place.
     */
    static String aliasPrefix(String owner) {
        return owner == null ? "__rk_" : "__rk_" + owner + "$";
    }

    /**
     * The unit's class in {@code outputPackage}, its fields read through the selection adapters in
     * {@code <outputPackage>.util}, or the occurrences that keep it from being emitted.
     */
    public static Result emit(ProjectionQuery.Unit unit, String outputPackage) {
        var refusals = new ArrayList<Refusal>();
        refuse(unit, unit.arms(), refusals);
        if (!refusals.isEmpty()) {
            return new Result.NotYet(refusals);
        }
        var occurrences = ClassName.get(outputPackage + ".util", "SelectionOccurrences");
        var table = ClassName.bestGuess(unit.table().tableClassName());
        var type = TypeSpec.classBuilder(unit.type())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodSpec.methodBuilder("$project")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(LIST, fieldWildcard()))
                .addParameter(groupedType(), "grouped")
                .addParameter(table, "table")
                .addParameter(ENV, "env")
                .addStatement("$T<$T> fields = new $T<>()", SET, fieldWildcard(), LINKED_HASH_SET)
                .addStatement("$L(fields, grouped, table, env, $S)", levelName(List.of()), "")
                .beginControlFlow("if (fields.isEmpty())")
                .addStatement("fields.add($T.inline(1).as($S))", DSL, ROW_PRESENT)
                .endControlFlow()
                .addStatement("return new $T<>(fields)", ARRAY_LIST)
                .build());
        levels(type, List.of(), unit.arms(), table, occurrences, unit.key());
        return new Result.Emitted(type.build());
    }

    /** Each arm beneath {@code arms} not yet rendered, with its reason, in the arms' order. */
    private static void refuse(ProjectionQuery.Unit unit, List<ProjectionQuery.Arm> arms, List<Refusal> refusals) {
        for (var arm : arms) {
            var reason = reasonFor(arm, unit.key());
            if (reason != null) {
                refusals.add(new Refusal(arm.coordinate(), reason));
            } else if (nesting(arm)) {
                refuse(unit, arm.nested(), refusals);
            }
        }
    }

    /** Why {@code arm} is not rendered, or null where it is. */
    private static Reason reasonFor(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable) {
        if (arm.pivot()) return Reason.PIVOT;
        if ("PRODUCER".equals(arm.side())) return Reason.CHILD_PRODUCER_COLUMNS;
        if (arm.reads() == null) return Reason.UNSTATED;
        return switch (arm.reads()) {
            case "EXPRESSION" -> Reason.EXPRESSION;
            case "NESTED" -> !nesting(arm) ? Reason.INLINE_CHILD
                : arm.nonNull() ? null : Reason.NULL_CONTRACT;
            case "COLUMN" -> sameRow(arm) && !projected(arm, unitTable).isEmpty() || terminal(arm, unitTable) != null
                ? null : Reason.SCALAR_PATH;
            default -> Reason.UNSTATED;
        };
    }

    /**
     * The column a scalar down a path reads where its steps land, or null where they cannot be
     * rendered as one correlated subselect: no steps, a step joining on no column pair, steps not
     * chained from the unit's table, a node id, or not one column on the last step's table.
     */
    private static ProjectionQuery.Column terminal(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable) {
        var steps = arm.steps();
        if (steps.isEmpty() || !arm.nodeKeys().isEmpty()) return null;
        var from = unitTable;
        for (var step : steps) {
            if (step.pairs().isEmpty() || !step.from().equals(from)) return null;
            from = step.to();
        }
        var landing = from;
        var columns = arm.columns().stream().filter(c -> c.table().equals(landing)).toList();
        return columns.size() == 1 ? columns.get(0) : null;
    }

    /** A nesting field, its fields read off its parent's row; anything else nested is read down a path. */
    private static boolean nesting(ProjectionQuery.Arm arm) {
        return arm.nesting();
    }

    /** The occurrence's one path is empty: what it reads ends where its parent's row starts. */
    private static boolean sameRow(ProjectionQuery.Arm arm) {
        return arm.pathOrigins().equals(List.of("EMPTY"));
    }

    /**
     * The columns a rendered column arm projects: a node id's key columns where it packs them,
     * else its columns, each on the unit's own table; empty where any lies on another.
     */
    private static List<ProjectionQuery.Column> projected(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable) {
        var columns = arm.nodeKeys().isEmpty() ? arm.columns() : arm.nodeKeys();
        return columns.stream().allMatch(c -> c.table().equals(unitTable)) ? columns : List.of();
    }

    /** One method per nesting level, the unit's own fields first, each nesting field's beneath. */
    private static void levels(TypeSpec.Builder type, List<String> chain, List<ProjectionQuery.Arm> arms,
                               ClassName table, ClassName occurrences, ProjectionQuery.TableKey unitTable) {
        var method = MethodSpec.methodBuilder(levelName(chain))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ParameterizedTypeName.get(SET, fieldWildcard()), "fields")
            .addParameter(groupedType(), "grouped")
            .addParameter(table, "table")
            .addParameter(ENV, "env")
            .addParameter(String.class, "rkPath");
        method.beginControlFlow("for ($T entry : grouped.entrySet())", entryType());
        method.addStatement("$T sf = $T.canonical(entry.getKey(), entry.getValue())", SELECTED_FIELD, occurrences);
        method.beginControlFlow("switch (sf.getName())");
        for (var arm : arms) {
            method.addCode("case $S -> ", arm.field());
            method.addCode(armBody(arm, occurrences, unitTable));
        }
        method.addCode("default -> { }\n");
        method.endControlFlow();
        method.endControlFlow();
        type.addMethod(method.build());
        for (var arm : arms) {
            if (nesting(arm)) {
                levels(type, arm.chain(), arm.nested(), table, occurrences, unitTable);
            }
        }
    }

    /**
     * The statements one rendered arm adds its fields with. A nesting level takes the result keys
     * from the row's owner down to it, each followed by the path delimiter, which a name it writes
     * puts between the prefix and its own key, so every selection set nested into one row gets a
     * name space of its own; the readers compose the same name.
     */
    private static CodeBlock armBody(ProjectionQuery.Arm arm, ClassName occurrences, ProjectionQuery.TableKey unitTable) {
        if (nesting(arm)) {
            return CodeBlock.of("$L(fields, $T.mergeByResultKey(entry.getValue()), table, env, rkPath + entry.getKey() + $S);\n",
                levelName(arm.chain()), occurrences, PATH_DELIMITER);
        }
        var terminal = terminal(arm, unitTable);
        if (terminal != null && !sameRow(arm)) {
            return subselect(arm, terminal);
        }
        var columns = projected(arm, unitTable);
        var body = CodeBlock.builder().add("{\n").indent();
        for (var column : columns) {
            body.addStatement("fields.add(table.$L)", column.javaName());
        }
        return body.unindent().add("}\n").build();
    }

    /**
     * A scalar down a path as one correlated subselect: the steps joined in order, each on its
     * column pairs, the first correlated to the unit's row, selecting the column the last step
     * lands on, at most one row of it, named for the field's result key under its owner.
     */
    private static CodeBlock subselect(ProjectionQuery.Arm arm, ProjectionQuery.Column terminal) {
        var steps = arm.steps();
        var body = CodeBlock.builder().add("{\n").indent();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            body.addStatement("var x$L = $T.$L.as($S)", i, ClassName.bestGuess(step.constantsClassName()),
                step.javaFieldName(), "x" + i);
        }
        var select = CodeBlock.builder().add("$T.select(x$L.$L).from(x0)", DSL, steps.size() - 1, terminal.javaName());
        for (int i = 1; i < steps.size(); i++) {
            select.add(".join(x$L).on($L)", i, pairs(steps.get(i).pairs(), "x" + (i - 1), "x" + i));
        }
        // One row, as a scalar takes: a path that fans out reads its first row rather than failing
        // the statement it is selected into.
        select.add(".where($L).limit(1)", pairs(steps.get(0).pairs(), "table", "x0"));
        body.addStatement("fields.add($T.field($L).as($S + rkPath + entry.getKey()))", DSL, select.build(),
            aliasPrefix(arm.aliasOwner()));
        return body.unindent().add("}\n").build();
    }

    /** A step's column pairs as one condition, each pair's from column on {@code from} equal to its to column on {@code to}. */
    private static CodeBlock pairs(List<ProjectionQuery.Pair> pairs, String from, String to) {
        var condition = CodeBlock.builder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) condition.add(".and(");
            condition.add("$L.$L.eq($L.$L)", to, pairs.get(i).toJavaName(), from, pairs.get(i).fromJavaName());
            if (i > 0) condition.add(")");
        }
        return condition.build();
    }

    /** The method projecting the fields of the nesting level {@code chain} reaches, the unit's own at the empty chain. */
    private static String levelName(List<String> chain) {
        return chain.isEmpty() ? "own" : "nested$" + String.join("$", chain);
    }

    private static TypeName fieldWildcard() {
        return ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
    }

    private static TypeName groupedType() {
        return ParameterizedTypeName.get(MAP, ClassName.get(String.class), ParameterizedTypeName.get(LIST, SELECTED_FIELD));
    }

    private static TypeName entryType() {
        return ParameterizedTypeName.get(ClassName.get("java.util", "Map", "Entry"), ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
    }
}
