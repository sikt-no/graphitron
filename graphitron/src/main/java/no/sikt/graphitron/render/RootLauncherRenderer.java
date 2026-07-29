package no.sikt.graphitron.render;

import no.sikt.graphitron.command.CarrierDsl;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import javax.lang.model.element.Modifier;

/**
 * The root launcher renderer: interprets one {@link LauncherCommand} into the
 * {@code public static <Ret> rows<Field>(DSLContext dsl, DataFetchingEnvironment env)} method
 * owning the coordinate's whole query composition. Total over the command's arms plus the run's
 * {@link CarrierDsl} fact, takes no schema; the launcher cannot say anything its command does
 * not, which is what makes the thin fetcher entry point a consequence of this seam rather than a
 * discipline.
 *
 * <p>{@code dsl} is a parameter, not a local: the entry point owns connection acquisition and
 * invocation strategy, the launcher owns composition. The body composes exactly what the inline
 * fetcher chains composed: the table local, the condition (the row's glue call, or the neutral
 * condition from an absent WHERE slot), the ordering per {@link Ordering} arm, the projection
 * unit's {@code $project} select list, and the {@link ResultShape}'s terminal: {@code fetch()},
 * {@code fetchOne()}, or the connection arm's seek/limit page query wrapped in the generated
 * carrier (whose lazy resolvers read the same {@code (table, condition, page)} the query ran
 * under; on a {@link CarrierDsl#ROUTED} run the routed {@code dsl} rides the carrier too). Every
 * generated class name comes off a ref the producer minted; this renderer derives none.
 *
 * <p>Renders into the coordinate's fetchers class next to its entry point (the launcher hosts on
 * the class its {@code UnitMethodRef} owner names), so this renderer yields a {@code MethodSpec}
 * the fetcher generator folds in blindly.
 */
public final class RootLauncherRenderer {

    private RootLauncherRenderer() {}

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SORT_FIELD = ClassName.get("org.jooq", "SortField");
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final TypeName RESULT_OF_RECORD = ParameterizedTypeName.get(RESULT, RECORD);
    private static final TypeName SORT_FIELD_LIST = ParameterizedTypeName.get(
        LIST, ParameterizedTypeName.get(SORT_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)));

    /** Renders one launcher method from its row and the run's carrier-routing fact. */
    public static MethodSpec render(LauncherCommand row, CarrierDsl carrierDsl) {
        var builder = MethodSpec.methodBuilder(row.unit().methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueTypeOf(row.result()))
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ENV, "env");

        String tableLocal = TableLocal.name(row.table());
        builder.addCode(TableLocal.declare(row.table()));
        builder.addCode(conditionStatement(row, tableLocal));
        switch (row.result()) {
            case ResultShape.SingleRecord ignored -> builder.addCode(plainChain(row, tableLocal, false, false));
            case ResultShape.RecordList list -> {
                boolean ordered = list.ordering() != null;
                if (ordered) {
                    builder.addCode(orderByStatement(list.ordering(), tableLocal));
                }
                builder.addCode(plainChain(row, tableLocal, true, ordered));
            }
            case ResultShape.Connection connection ->
                builder.addCode(connectionBody(row, connection, tableLocal, carrierDsl));
        }
        return builder.build();
    }

    /** The launcher's return type, read off the shape (the connection arm's is its carrier ref). */
    public static TypeName valueTypeOf(ResultShape result) {
        return switch (result) {
            case ResultShape.RecordList ignored -> RESULT_OF_RECORD;
            case ResultShape.SingleRecord ignored -> RECORD;
            case ResultShape.Connection connection -> className(connection.carrier());
        };
    }

    /** The terminal select chain of the single and list shapes. */
    private static CodeBlock plainChain(LauncherCommand row, String tableLocal, boolean isList, boolean ordered) {
        var chain = CodeBlock.builder()
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", ProjectionCall.fromEnvSelection(className(row.projection()), tableLocal))
            .add(".from($L)\n", tableLocal)
            .add(".where(condition)\n");
        if (ordered) {
            chain.add(".orderBy(orderBy)\n");
        }
        chain.add(isList ? ".fetch();\n" : ".fetchOne();\n").unindent();
        return chain.build();
    }

    /**
     * The connection arm: the two-view ordering block, the four fixed pagination argument reads
     * (names fixed by the slot; the classifier rejects custom names), the page request, the
     * seek/limit page query, and the carrier construction over the same
     * {@code (result, page, table, condition)} the query ran under. A faceted plan additionally
     * binds the base fragment, the per-facet condition map, and the decode specs through the
     * carrier's facet-carrying constructor.
     */
    private static CodeBlock connectionBody(LauncherCommand row, ResultShape.Connection connection,
            String tableLocal, CarrierDsl carrierDsl) {
        var code = CodeBlock.builder();
        code.add(OrderingBlock.declareBothViews(connection.ordering(), tableLocal));
        code.addStatement("Integer first = env.getArgument($S)", "first");
        code.addStatement("Integer last = env.getArgument($S)", "last");
        code.addStatement("String after = env.getArgument($S)", "after");
        code.addStatement("String before = env.getArgument($S)", "before");
        var helperClass = className(connection.helper());
        code.addStatement(
            "$T page = $T.pageRequest(first, last, after, before, $L, orderBy, extraFields, $L)",
            helperClass.nestedClass("PageRequest"), helperClass, connection.defaultPageSize(),
            ProjectionCall.fromEnvSelection(className(row.projection()), tableLocal));
        code.add("$T result = dsl\n", RESULT_OF_RECORD)
            .indent()
            .add(".select(page.selectFields())\n")
            .add(".from($L)\n", tableLocal)
            .add(".where(condition)\n")
            .add(".orderBy(page.effectiveOrderBy())\n")
            .add(".seek(page.seekFields())\n")
            .add(".limit(page.limit())\n")
            .add(".fetch();\n")
            .unindent();
        String dslTail = carrierDsl == CarrierDsl.ROUTED ? ", dsl" : "";
        if (connection.facets() == null) {
            code.addStatement("return new $T(result, page, $L, condition$L)",
                className(connection.carrier()), tableLocal, dslTail);
        } else {
            code.add(facetBindings(connection, tableLocal));
            code.addStatement(
                "return new $T(result, page, $L, condition, facetBase, facetConditions, facetSpecs$L)",
                className(connection.carrier()), tableLocal, dslTail);
        }
        return code.build();
    }

    /** The faceted carrier's bindings: the base fragment, the per-facet map, the decode specs. */
    private static CodeBlock facetBindings(ResultShape.Connection connection, String tableLocal) {
        var plan = connection.facets();
        var code = CodeBlock.builder();
        code.addStatement("$T facetBase = $L", CONDITION, glueExpression(plan.base(), tableLocal));
        code.addStatement("$T<String, $T> facetConditions = new $T<>()",
            ClassName.get("java.util", "Map"), CONDITION, ClassName.get("java.util", "LinkedHashMap"));
        for (var facet : plan.facets()) {
            code.addStatement("facetConditions.put($S, $L)",
                facet.spec().inputFieldName(), glueExpression(facet.condition(), tableLocal));
        }
        var facetSpecRuntime = className(connection.carrier()).nestedClass("FacetSpec");
        var specsArgs = CodeBlock.builder();
        boolean firstSpec = true;
        for (var facet : plan.facets()) {
            if (!firstSpec) {
                specsArgs.add(",\n    ");
            }
            firstSpec = false;
            specsArgs.add("new $T($S, $S, $L)", facetSpecRuntime,
                facet.spec().inputFieldName(), facet.spec().columnName(), facet.spec().valueNullable());
        }
        code.addStatement("$T<$T> facetSpecs = $T.of($L)",
            LIST, facetSpecRuntime, LIST, specsArgs.build());
        return code.build();
    }

    /**
     * {@code Condition condition = <Parent>Conditions.<field>Condition(<table>, env.getArguments()[, env]);}
     * from the row's glue call, or the neutral condition when the WHERE slot is absent (the
     * coordinate has no condition row; absence is data, never an inline fold).
     */
    private static CodeBlock conditionStatement(LauncherCommand row, String tableLocal) {
        if (row.where() == null) {
            return CodeBlock.builder().addStatement("$T condition = $T.noCondition()", CONDITION, DSL).build();
        }
        return CodeBlock.builder()
            .addStatement("$T condition = $L", CONDITION, glueExpression(row.where(), tableLocal))
            .build();
    }

    /** {@code <Owner>.<method>(<table>, env.getArguments()[, env])} for any glue reference. */
    private static CodeBlock glueExpression(no.sikt.graphitron.command.GlueCall glue, String tableLocal) {
        var call = CodeBlock.builder().add("$T.$L($L, env.getArguments()",
            className(glue.method().owner()), glue.method().methodName(), tableLocal);
        if (glue.takesEnv()) {
            call.add(", env");
        }
        call.add(")");
        return call.build();
    }

    /** The single/list shapes' sort-view-only ordering statement. */
    private static CodeBlock orderByStatement(Ordering ordering, String tableLocal) {
        return switch (ordering) {
            case Ordering.Columns columns -> CodeBlock.builder()
                .addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST,
                    OrderByFragments.fixedSortParts(columns.spec(), tableLocal))
                .build();
            // The helper is a same-class private static (launcher and helper share the fetchers
            // class by the naming vocabulary), so the call is unqualified, matching the entry
            // points that call it today.
            case Ordering.Helper helper -> CodeBlock.builder()
                .addStatement("$T orderBy = $L(env, $L).sortFields()",
                    SORT_FIELD_LIST, helper.method().methodName(), tableLocal)
                .build();
        };
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
