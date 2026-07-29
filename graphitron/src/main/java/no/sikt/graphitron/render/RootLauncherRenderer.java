package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;

/**
 * The root launcher renderer: interprets one {@link LauncherCommand} into the
 * {@code public static <Ret> rows<Field>(DSLContext dsl, DataFetchingEnvironment env)} method
 * owning the coordinate's whole query composition. Total over the command's arms, takes no
 * schema; the launcher cannot say anything its command does not, which is what makes the thin
 * fetcher entry point a consequence of this seam rather than a discipline.
 *
 * <p>{@code dsl} is a parameter, not a local: the entry point owns connection acquisition and
 * invocation strategy, the launcher owns composition. The body composes exactly what the inline
 * fetcher chains composed: the table local, the condition (the row's glue call, or the neutral
 * condition from an absent WHERE slot), the ordering per {@link Ordering} arm (inline columns,
 * or the {@code <field>OrderBy} helper's sort fields; an absent slot renders no ORDER BY), the
 * projection unit's {@code $project} select list, and the {@link ResultShape}'s terminal fetch.
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
    private static final TypeName SORT_FIELD_LIST = ParameterizedTypeName.get(
        LIST, ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));

    /** Renders one launcher method from its row. */
    public static MethodSpec render(LauncherCommand row) {
        var valueType = valueTypeOf(row.result());
        String tableLocal = TableLocal.name(row.table());

        var builder = MethodSpec.methodBuilder(row.unit().methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(valueType)
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ENV, "env");

        builder.addCode(TableLocal.declare(row.table()));
        builder.addCode(conditionStatement(row, tableLocal));
        if (row.orderBy() != null) {
            builder.addCode(orderByStatement(row.orderBy(), tableLocal));
        }

        var chain = CodeBlock.builder()
            .add("return dsl\n")
            .indent()
            .add(".select($L)\n", ProjectionCall.fromEnvSelection(
                ClassName.get(row.projection().packageName(), row.projection().simpleName()), tableLocal))
            .add(".from($L)\n", tableLocal)
            .add(".where(condition)\n");
        if (row.orderBy() != null) {
            chain.add(".orderBy(orderBy)\n");
        }
        chain.add(row.result() == ResultShape.RECORD_LIST ? ".fetch();\n" : ".fetchOne();\n")
            .unindent();
        builder.addCode(chain.build());
        return builder.build();
    }

    private static TypeName valueTypeOf(ResultShape result) {
        return switch (result) {
            case RECORD_LIST -> ParameterizedTypeName.get(RESULT, RECORD);
            case SINGLE_RECORD -> RECORD;
        };
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
        var glue = row.where().method();
        var call = CodeBlock.builder().add("$T.$L($L, env.getArguments()",
            ClassName.get(glue.owner().packageName(), glue.owner().simpleName()),
            glue.methodName(), tableLocal);
        if (row.where().takesEnv()) {
            call.add(", env");
        }
        call.add(")");
        return CodeBlock.builder().addStatement("$T condition = $L", CONDITION, call.build()).build();
    }

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
}
