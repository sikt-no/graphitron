package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.ColumnComparison;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a table-bound type's projection unit, its {@code $project}, from the facts
 * {@link ProjectionQuery} reads, or names why it cannot yet: additive and uncalled, the walk's
 * {@code ProjectionUnitRenderer} rendering the units every fetcher calls until the flip.
 *
 * <p>A unit is emitted whole or not at all. Its fields are the type's own and, inline beneath a
 * nesting field, the nesting type's, each nesting level a method of the unit's class rather than a
 * class of its own. A column is projected by its identity, as the walk's unit projects it, and a
 * node id by its key columns in key order, the encode being its reader's. A list read inline is a
 * multiset over the rows its path reaches, projected by the listed type's own unit, so it is
 * emitted only where that unit is.
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
         * fetch, whose parent row must carry the columns its loader keys on, where no lift states
         * them on the unit's table: a producer whose source has no primary key, or one under a
         * record a producer handed over rather than a row the unit selects.
         */
        CHILD_PRODUCER_COLUMNS,
        /**
         * A child read inline down a path not rendered yet: one whose path is not one chain of
         * column-pair joins from the unit's row to its type's table, whose type's unit is not
         * emitted, a list whose order reads a key that is no column of its table, or a single child
         * looked up by a key the read does not render, a node id or an input object's field.
         */
        INLINE_CHILD,
        /**
         * A list read inline that an argument other than its {@code @orderBy} or a
         * {@code @condition} narrows: the multiset renders no filter yet.
         */
        NARROWED_LIST,
        /**
         * A scalar whose column lies down a path that is not one subselect of column-pair joins
         * from the unit's row: a step joining by a condition method or a routine, or a node id down
         * a path.
         */
        SCALAR_PATH,
        /**
         * A {@code @pivot}: deferred past the flip, where it becomes the stored defect
         * {@code PIVOT_UNSUPPORTED} of basis {@code UNSUPPORTED}, raised by the stage holding its facts.
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
    private static final ClassName SORT_FIELD = ClassName.get("org.jooq", "SortField");
    private static final ClassName HASH_SET = ClassName.get("java.util", "HashSet");

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
     * {@code <outputPackage>.util}, or the occurrences that keep it from being emitted; a list it
     * reads inline is emitted only where it lists its own type.
     */
    public static Result emit(ProjectionQuery.Unit unit, String outputPackage) {
        return emitAll(Map.of(unit.type(), unit), outputPackage).get(unit.type());
    }

    /**
     * Each of {@code units} emitted into {@code outputPackage} or refused, by type. A list read
     * inline calls its listed type's unit, so a unit is emitted where its own occurrences render and
     * every unit it lists is emitted too: the units that can be are found by striking out, until
     * none is left to strike, each unit refusing an occurrence among those still standing, so two
     * units listing each other are emitted together.
     */
    public static Map<String, Result> emitAll(Map<String, ProjectionQuery.Unit> units, String outputPackage) {
        var callable = new LinkedHashMap<>(units);
        boolean struck = true;
        while (struck) {
            struck = callable.values().removeIf(unit -> !refusals(unit, callable).isEmpty());
        }
        var results = new LinkedHashMap<String, Result>();
        for (var unit : units.values()) {
            var refusals = refusals(unit, callable);
            results.put(unit.type(), refusals.isEmpty() ? new Result.Emitted(render(unit, callable, outputPackage))
                : new Result.NotYet(refusals));
        }
        return results;
    }

    private static List<Refusal> refusals(ProjectionQuery.Unit unit, Map<String, ProjectionQuery.Unit> callable) {
        var refusals = new ArrayList<Refusal>();
        refuse(unit, unit.arms(), callable, refusals);
        return refusals;
    }

    private static TypeSpec render(ProjectionQuery.Unit unit, Map<String, ProjectionQuery.Unit> callable,
                                   String outputPackage) {
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
        levels(type, List.of(), unit.arms(), table, occurrences, unit.key(), callable, outputPackage);
        return type.build();
    }

    /** Each arm beneath {@code arms} not yet rendered, with its reason, in the arms' order. */
    private static void refuse(ProjectionQuery.Unit unit, List<ProjectionQuery.Arm> arms,
                               Map<String, ProjectionQuery.Unit> callable, List<Refusal> refusals) {
        for (var arm : arms) {
            var reason = reasonFor(arm, unit.key(), callable);
            if (reason != null) {
                refusals.add(new Refusal(arm.coordinate(), reason));
            } else if (nesting(arm)) {
                refuse(unit, arm.nested(), callable, refusals);
            }
        }
    }

    /** Why {@code arm} is not rendered, or null where it is. */
    private static Reason reasonFor(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable,
                                    Map<String, ProjectionQuery.Unit> callable) {
        if (arm.pivot()) return Reason.PIVOT;
        if ("PRODUCER".equals(arm.side())) return handed(arm, unitTable) ? null : Reason.CHILD_PRODUCER_COLUMNS;
        if (arm.reads() == null) return Reason.UNSTATED;
        return switch (arm.reads()) {
            case "EXPRESSION" -> Reason.EXPRESSION;
            case "NESTED" -> nesting(arm) ? null : inlineReason(arm, unitTable, callable);
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

    /**
     * The table a path lands on where it is one chain of column-pair joins from the unit's table,
     * else null.
     */
    private static ProjectionQuery.TableKey landing(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable) {
        if (arm.steps().isEmpty()) return null;
        var from = unitTable;
        for (var step : arm.steps()) {
            if (step.pairs().isEmpty() || !step.from().equals(from)) return null;
            from = step.to();
        }
        return from;
    }

    /** Why a child read inline down a path is not rendered, or null where it is. */
    private static Reason inlineReason(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable,
                                       Map<String, ProjectionQuery.Unit> callable) {
        if (arm.listDepth() > 0) {
            return narrowed(arm) ? Reason.NARROWED_LIST : inlineList(arm, unitTable, callable) ? null : Reason.INLINE_CHILD;
        }
        return singleChild(arm, unitTable, callable) ? null : Reason.INLINE_CHILD;
    }

    /**
     * A single object of a table-bound type read down one chain of column-pair joins to its type's
     * table, nothing narrowing it but lookup keys its read renders, and its type's unit emitted.
     */
    private static boolean singleChild(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable,
                                       Map<String, ProjectionQuery.Unit> callable) {
        var single = arm.single();
        if (!arm.targetTable() || single.narrowed() || single.otherKeys()) return false;
        var callee = callable.get(arm.targetType());
        var landing = landing(arm, unitTable);
        return callee != null && landing != null && landing.equals(callee.key())
            && single.keys().stream().allMatch(k -> k.column().table().equals(landing));
    }

    /** A list read inline that an argument besides its order's or a {@code @condition} narrows. */
    private static boolean narrowed(ProjectionQuery.Arm arm) {
        return arm.listDepth() > 0 && arm.list() != null && arm.list().narrowed();
    }

    /**
     * A list of one table-bound type read inline down one chain of column-pair joins to that type's
     * table, each of its orders' keys a column of that table, and whose type's unit is emitted.
     */
    private static boolean inlineList(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable,
                                      Map<String, ProjectionQuery.Unit> callable) {
        var list = arm.list();
        if (arm.listDepth() != 1 || !arm.targetTable() || list == null || list.bounds().size() != 1) {
            return false;
        }
        var callee = callable.get(arm.targetType());
        var landing = landing(arm, unitTable);
        if (callee == null || landing == null || !landing.equals(callee.key())) return false;
        var orders = new ArrayList<ProjectionQuery.Order>();
        if (list.defaultOrder() != null) orders.add(list.defaultOrder());
        if (list.choice() != null) orders.addAll(list.choice().orders());
        return orders.stream().flatMap(o -> o.keys().stream())
            .allMatch(k -> k.column() != null && k.column().table().equals(landing));
    }

    /**
     * A field fetched by a producer of its own whose lift states the columns of the unit's row its
     * keys are read off, all of them the unit's table's: the unit projects them for the producer.
     */
    private static boolean handed(ProjectionQuery.Arm arm, ProjectionQuery.TableKey unitTable) {
        return !arm.liftColumns().isEmpty() && arm.liftColumns().stream().allMatch(c -> c.table().equals(unitTable));
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
                               ClassName table, ClassName occurrences, ProjectionQuery.TableKey unitTable,
                               Map<String, ProjectionQuery.Unit> callable, String outputPackage) {
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
            method.addCode(armBody(arm, occurrences, unitTable, callable, outputPackage));
        }
        method.addCode("default -> { }\n");
        method.endControlFlow();
        method.endControlFlow();
        type.addMethod(method.build());
        for (var arm : arms) {
            if (nesting(arm)) {
                levels(type, arm.chain(), arm.nested(), table, occurrences, unitTable, callable, outputPackage);
            }
        }
    }

    /**
     * The statements one rendered arm adds its fields with. A nesting level takes the result keys
     * from the row's owner down to it, each followed by the path delimiter, which a name it writes
     * puts between the prefix and its own key, so every selection set nested into one row gets a
     * name space of its own; the readers compose the same name.
     */
    private static CodeBlock armBody(ProjectionQuery.Arm arm, ClassName occurrences, ProjectionQuery.TableKey unitTable,
                                     Map<String, ProjectionQuery.Unit> callable, String outputPackage) {
        if ("NESTED".equals(arm.reads()) && !nesting(arm)) {
            return arm.listDepth() > 0 ? multiset(arm, occurrences, outputPackage) : singleRow(arm, occurrences, outputPackage);
        }
        if (nesting(arm)) {
            return CodeBlock.of("$L(fields, $T.mergeByResultKey(entry.getValue()), table, env, rkPath + entry.getKey() + $S);\n",
                levelName(arm.chain()), occurrences, PATH_DELIMITER);
        }
        var columns = "PRODUCER".equals(arm.side()) ? arm.liftColumns() : projected(arm, unitTable);
        var terminal = terminal(arm, unitTable);
        if (!"PRODUCER".equals(arm.side()) && terminal != null && !sameRow(arm)) {
            return subselect(arm, terminal);
        }
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

    /**
     * A list read inline as one correlated multiset: the steps joined in order, each on its column
     * pairs, the first correlated to the unit's row, selecting the listed type's unit over the
     * table the last step lands on, in the order the client picks or else the default, named for
     * the field's result key under its owner. Each step's alias extends the row's own, so a list
     * nested in a list of its own type never shadows the row it is correlated to.
     */
    private static CodeBlock multiset(ProjectionQuery.Arm arm, ClassName occurrences, String outputPackage) {
        var steps = arm.steps();
        var list = arm.list();
        var body = CodeBlock.builder().add("{\n").indent();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            body.addStatement("var x$L = $T.$L.as(table.getName() + $S)", i,
                ClassName.bestGuess(step.constantsClassName()), step.javaFieldName(), "_" + i);
        }
        String listed = "x" + (steps.size() - 1);
        body.addStatement("$T<$T<?>> order = new $T<>()", LIST, SORT_FIELD, ARRAY_LIST);
        if (list.choice() != null) {
            var choice = list.choice();
            // The order is read off the field's own arguments, so every occurrence under its result
            // key has to agree on them.
            body.addStatement("$T.requireConsistentArguments(entry.getKey(), entry.getValue())", occurrences);
            body.addStatement("$T chosen = sf.getArguments().get($S)", Object.class, choice.argument());
            body.addStatement("$T<?> picks = chosen instanceof $T<?> many ? many : chosen == null ? $T.of() : $T.of(chosen)",
                LIST, LIST, LIST, LIST);
            // A sequence is completed once it is known: the columns its picks listed and the direction
            // its last key took decide what the row key adds.
            boolean completes = choice.sequence() && !list.rowKey().isEmpty();
            if (completes) {
                body.addStatement("$T<$T> columns = new $T<>()", SET, String.class, HASH_SET);
                body.addStatement("boolean lastDesc = false");
            }
            body.beginControlFlow("for ($T one : picks)", Object.class);
            body.beginControlFlow("if (!(one instanceof $T<?, ?> pick))", MAP);
            body.addStatement("continue");
            body.endControlFlow();
            body.addStatement("boolean desc = $S.equals(String.valueOf(pick.get($S)))", "DESC", choice.directionField());
            body.beginControlFlow("switch (String.valueOf(pick.get($S)))", choice.sortField());
            for (var order : choice.orders()) {
                if (!completes) {
                    body.add("case $S -> order.addAll($T.of($L));\n", order.value(), LIST, sortTerms(order, listed, true));
                    continue;
                }
                var last = order.keys().get(order.keys().size() - 1);
                body.add("case $S -> {\n", order.value()).indent();
                body.addStatement("order.addAll($T.of($L))", LIST, sortTerms(order, listed, true));
                body.addStatement("columns.addAll($T.of($L))", LIST, columnNames(order.keys()));
                body.addStatement("lastDesc = $L", order.uniformAscending() ? "desc" : "DESC".equals(last.direction()));
                body.unindent().add("}\n");
            }
            body.add("default -> { }\n");
            body.endControlFlow();
            body.endControlFlow();
            if (completes) {
                // Unless the picks listed an identifying key in full, the row key's other columns
                // follow them in the last key's direction; they identify the row, so no nulls apart.
                var identified = CodeBlock.builder();
                for (int i = 0; i < list.identifyingKeys().size(); i++) {
                    if (i > 0) identified.add(" || ");
                    identified.add("columns.containsAll($T.of($L))", LIST, columnNames(list.identifyingKeys().get(i)));
                }
                body.beginControlFlow("if (!order.isEmpty() && !($L))", identified.build());
                for (var column : list.rowKey()) {
                    body.beginControlFlow("if (!columns.contains($S))", column.sqlName());
                    body.addStatement("order.add(lastDesc ? $L.$L.desc() : $L.$L.asc())", listed, column.javaName(),
                        listed, column.javaName());
                    body.endControlFlow();
                }
                body.endControlFlow();
            }
        }
        if (list.defaultOrder() != null) {
            body.beginControlFlow("if (order.isEmpty())");
            body.addStatement("order.addAll($T.of($L))", LIST, sortTerms(list.defaultOrder(), listed, false));
            body.endControlFlow();
        }
        var select = CodeBlock.builder().add("$T.select($T.$L($T.mergeByResultKey(entry.getValue()), $L, env)).from(x0)",
            DSL, ClassName.get(outputPackage, arm.targetType()), "$project", occurrences, listed);
        for (int i = 1; i < steps.size(); i++) {
            select.add(".join(x$L).on($L)", i, pairs(steps.get(i).pairs(), "x" + (i - 1), "x" + i));
        }
        select.add(".where($L).orderBy(order)", pairs(steps.get(0).pairs(), "table", "x0"));
        body.addStatement("fields.add($T.multiset($L).as($S + rkPath + entry.getKey()))", DSL, select.build(),
            aliasPrefix(arm.aliasOwner()));
        return body.unindent().add("}\n").build();
    }

    /** The SQL names of {@code keys}' columns, as string literals. */
    private static CodeBlock columnNames(List<?> keys) {
        var names = CodeBlock.builder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) names.add(", ");
            var column = keys.get(i) instanceof ProjectionQuery.SortKey key ? key.column() : (ProjectionQuery.Column) keys.get(i);
            names.add("$S", column.sqlName());
        }
        return names.build();
    }

    /**
     * A single child read inline as the readers read one: a multiset of the listed type's unit over
     * the table the steps land on, correlated to the unit's row and narrowed by its lookup keys, each
     * a column equal to the value its argument passes, at most one row of it. Its keys identify a
     * row, or its path reaches one row; a path that fans out reads its first row, as the walk's does.
     */
    private static CodeBlock singleRow(ProjectionQuery.Arm arm, ClassName occurrences, String outputPackage) {
        var steps = arm.steps();
        var keys = arm.single().keys();
        var body = CodeBlock.builder().add("{\n").indent();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            body.addStatement("var x$L = $T.$L.as(table.getName() + $S)", i,
                ClassName.bestGuess(step.constantsClassName()), step.javaFieldName(), "_" + i);
        }
        String listed = "x" + (steps.size() - 1);
        if (!keys.isEmpty()) {
            // The keys are read off the field's own arguments, so every occurrence under its result
            // key has to agree on them.
            body.addStatement("$T.requireConsistentArguments(entry.getKey(), entry.getValue())", occurrences);
        }
        var select = CodeBlock.builder().add("$T.select($T.$L($T.mergeByResultKey(entry.getValue()), $L, env)).from(x0)",
            DSL, ClassName.get(outputPackage, arm.targetType()), "$project", occurrences, listed);
        for (int i = 1; i < steps.size(); i++) {
            select.add(".join(x$L).on($L)", i, pairs(steps.get(i).pairs(), "x" + (i - 1), "x" + i));
        }
        select.add(".where($L)", pairs(steps.get(0).pairs(), "table", "x0"));
        for (var key : keys) {
            select.add(".and($L.$L.eq($T.val(sf.getArguments().get($S), $L.$L)))", listed, key.column().javaName(), DSL,
                key.argument(), listed, key.column().javaName());
        }
        select.add(".limit(1)");
        body.addStatement("fields.add($T.multiset($L).as($S + rkPath + entry.getKey()))", DSL, select.build(),
            aliasPrefix(arm.aliasOwner()));
        return body.unindent().add("}\n").build();
    }

    /**
     * An order's keys over {@code table}, each in its own direction, nulls last where its column
     * admits them; where {@code flips} and every key is ascending, a client's {@code desc} reverses
     * them all, an order mixing directions keeping the ones written.
     */
    private static CodeBlock sortTerms(ProjectionQuery.Order order, String table, boolean flips) {
        var terms = CodeBlock.builder();
        boolean reversible = flips && order.uniformAscending();
        for (int i = 0; i < order.keys().size(); i++) {
            var key = order.keys().get(i);
            if (i > 0) terms.add(", ");
            var column = key.collation() == null ? CodeBlock.of("$L.$L", table, key.column().javaName())
                : CodeBlock.of("$L.$L.collate($S)", table, key.column().javaName(), key.collation());
            String nulls = key.nullable() ? ".nullsLast()" : "";
            if (reversible) {
                terms.add("desc ? $L.desc()$L : $L.asc()$L", column, nulls, column, nulls);
            } else {
                terms.add("$L.$L()$L", column, "DESC".equals(key.direction()) ? "desc" : "asc", nulls);
            }
        }
        return terms.build();
    }

    /**
     * A step's column pairs as one condition, each pair's from column on {@code from} equal to its to
     * column on {@code to}, the two reconciled where a converter has made their Java types differ.
     */
    private static CodeBlock pairs(List<ProjectionQuery.Pair> pairs, String from, String to) {
        var condition = CodeBlock.builder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) condition.add(".and(");
            var pair = pairs.get(i);
            condition.add("$L", ColumnComparison.equality(CodeBlock.of("$L.$L", to, pair.toJavaName()),
                CatalogRefs.decodeBindingType(pair.toType()), CodeBlock.of("$L.$L", from, pair.fromJavaName()),
                CatalogRefs.decodeBindingType(pair.fromType())));
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
