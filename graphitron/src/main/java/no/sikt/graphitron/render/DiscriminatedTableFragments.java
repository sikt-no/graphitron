package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The discriminated-interface re-projection assembly: the shared body of every query over a
 * single-table discriminated interface (the root launcher, the child twin, the service
 * single-table-interface fetcher, the two DML discriminated follow-ups), total over the
 * {@link LaunchSource.DiscriminatedTable} arm's data. Emits, in order: the discriminator
 * {@code IN} restriction ANDed into the caller's {@code condition} local; the
 * {@code LinkedHashSet<Field<?>> fields} projection (the {@link ReservedAliases#DISCRIMINATOR}
 * routing alias first, each single-table branch's {@code $project}, the arm's base slice, plus
 * {@code alwaysProject}); the cross-table subselect terms and the joined-detail alias
 * declarations; the {@code SelectJoinStep<Record> step} declaration; and the
 * discriminator-gated joined-detail {@code LEFT JOIN} chain. The caller finishes the chain
 * ({@code step.where(condition)...}); this class knows nothing about the fetch cardinality.
 *
 * <p>Every join the assembly emits is proven single-valued: the joined-detail edge is the
 * pattern's own 1:0..1 hop (the detail's FK columns to the base <em>are</em> the detail's
 * primary key, per {@link no.sikt.graphitron.rewrite.TypeBuilder#resolveJoinedTableParticipant}).
 * A participant scalar reached one {@code @reference} hop off the base rides the select list as
 * a capped correlated subselect instead, so one base row stays one entity whatever that hop's
 * cardinality.
 *
 * <p>Preconditions: the caller has declared {@code condition} ({@code Condition}), {@code dsl}
 * ({@code DSLContext}) and the {@code tableLocal} holding the base {@code @table}'s jOOQ
 * instance, and {@code env} is in scope (the selection-set gates and the result-key loop read
 * it).
 *
 * <p>Why the discriminator reads qualify off the FROM table's own jOOQ instance
 * ({@code <tableLocal>.getQualifiedName()}) rather than the {@code @table} directive string:
 * jOOQ's table renderer produces the exact qualifier that appears in the FROM clause (no schema
 * part for a default-schema table, {@code "schema"."table"} for a named-schema one), so the
 * reference matches FROM by construction, and stays unambiguous once a participant join brings
 * in a detail table that re-declares the discriminator column. The routing projection is
 * additionally aliased to {@link ReservedAliases#DISCRIMINATOR} so a queryable discriminator
 * field's own catalog projection cannot collide with the {@code TypeResolver}'s read; the WHERE
 * filter and JOIN ON-clauses keep referencing the real qualified column (they cannot read a
 * SELECT alias).
 *
 * <p>A branch whose participant carries no {@code @discriminator} value renders its projection
 * contribution but nothing that would need a type gate: no joined-detail JOIN arm here, and no
 * cross-table term at all (the producer contributes none, an ungated one resolving the reference
 * for rows of every type). That shape classifies unrejected today, and this is deliberately the
 * one gate that mirrors it (see {@link LaunchSource.DiscriminatedTable.Branch}).
 */
public final class DiscriminatedTableFragments {

    private DiscriminatedTableFragments() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");

    /** The whole assembly, ending with {@code step} joined and ready for the caller's terminal. */
    public static CodeBlock assembly(LaunchSource.DiscriminatedTable source,
            List<ColumnRef> alwaysProject, String tableLocal) {
        var b = CodeBlock.builder();
        b.add(discriminatorFilter(source, tableLocal));
        b.add(fieldsList(source, tableLocal));
        // Extra always-projected base columns (deduped by the LinkedHashSet declared above).
        // Used by the service path to guarantee the shared table's PK reaches the fetched
        // Record for the by-PK re-map; empty for the read paths.
        for (var col : alwaysProject) {
            b.addStatement("fields.add($L.$L)", tableLocal, col.javaName());
        }
        b.add(crossTableProjections(source, tableLocal));
        b.add(joinedDetailAliasDeclarations(source, tableLocal));
        var selectJoinStepOfRecord = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "SelectJoinStep"), RECORD);
        b.addStatement("$T step = dsl.select(new $T<>(fields)).from($L)",
            selectJoinStepOfRecord, ArrayList.class, tableLocal);
        b.add(joinedDetailJoinChain(source, tableLocal));
        return b.build();
    }

    /**
     * {@code condition = condition.and(<qualified discriminator>.in(v1, v2, ...))}, restricting
     * to rows with a known discriminator value; nothing when {@code knownValues} is empty.
     */
    private static CodeBlock discriminatorFilter(LaunchSource.DiscriminatedTable source, String tableLocal) {
        if (source.knownValues().isEmpty()) {
            return CodeBlock.of("");
        }
        var inArgs = source.knownValues().stream()
            .map(v -> CodeBlock.of("$S", v))
            .collect(CodeBlock.joining(", "));
        return CodeBlock.builder()
            .addStatement("condition = condition.and($T.field($L.getQualifiedName().append($T.name($S)), $T.class).in($L))",
                DSL, tableLocal, DSL, source.discriminatorColumn(), Object.class, inArgs)
            .build();
    }

    /**
     * The {@code LinkedHashSet<Field<?>> fields} declaration: the routing alias first
     * (unconditional, so the {@code TypeResolver} can route rows the selection set never asked
     * the discriminator for), each single-table branch's {@code $project} (the set dedupes
     * shared-column over-selection), then the arm's base slice, whose terms carry their own
     * reader-addressing fork (the {@code __rk_} result-key loop versus one aliased projection;
     * aliased {@code Field} objects are fresh instances the set would not dedupe, so the slice
     * arrives pre-deduplicated by output alias).
     */
    private static CodeBlock fieldsList(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        var fieldType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Field"),
            WildcardTypeName.subtypeOf(Object.class));
        var setType = ParameterizedTypeName.get(
            ClassName.get(LinkedHashSet.class), fieldType);
        b.addStatement("$T fields = new $T<>()", setType, LinkedHashSet.class);
        b.addStatement("fields.add($T.field($L.getQualifiedName().append($T.name($S)), $T.class).as($S))",
            DSL, tableLocal, DSL, source.discriminatorColumn(), Object.class, ReservedAliases.DISCRIMINATOR);
        for (var branch : source.branches()) {
            if (branch instanceof LaunchSource.DiscriminatedTable.Branch.SingleTable single) {
                b.addStatement("fields.addAll($L)",
                    ProjectionCall.fromEnvSelection(className(single.projection()), tableLocal));
            }
        }
        for (var term : source.baseSlice()) {
            switch (term) {
                // Inherited base-resident @reference: project the base column once per selected
                // result-key bucket (aliased duplicates each get their own __rk_<key> term), off
                // the base so NULL-through rows (base present, detail absent) still resolve it,
                // under the same reserved alias the standalone correlated-subquery projection
                // mints, so the one registered fetcher reads both queries' rows identically.
                // Explicit entry type: emitted sources may not use `var`
                // (GeneratedSourcesLintTest).
                case LaunchSource.DiscriminatedTable.BaseSliceTerm.InheritedRef inherited -> {
                    var rkEntryType = ParameterizedTypeName.get(
                        ClassName.get("java.util", "Map", "Entry"),
                        ClassName.get(String.class),
                        ParameterizedTypeName.get(LIST, SELECTED_FIELD));
                    b.beginControlFlow(
                        "for ($T rkEntry : env.getSelectionSet().getFieldsGroupedByResultKey().entrySet())",
                        rkEntryType);
                    b.beginControlFlow(
                        "if (!rkEntry.getValue().isEmpty() && rkEntry.getValue().get(0).getName().equals($S))",
                        inherited.fieldName());
                    b.addStatement("fields.add($L.$L.as($S + rkEntry.getKey()))",
                        tableLocal, inherited.baseColumn().javaName(), ReservedAliases.RESULT_KEY_PREFIX);
                    b.endControlFlow();
                    b.endControlFlow();
                }
                case LaunchSource.DiscriminatedTable.BaseSliceTerm.SharedKey shared ->
                    b.addStatement("fields.add($L.$L.as($S))",
                        tableLocal, shared.baseColumn().javaName(), shared.alias());
            }
        }
        return b.build();
    }

    /**
     * Per-single-table-branch cross-table projections: one selection-set-gated
     * {@code fields.add(DSL.field(<capped correlated subselect>).as(<alias>))} per lowered term,
     * rendered through the same fragment the projection unit's scalar {@code @reference} arm
     * calls ({@link PathFragments#scalarInnerSelect}), parameterized on this assembly's base
     * table local. The hop alias is declared inside the gate, so the term contributes nothing at
     * all to a query whose selection never asked for the field, and nothing to the statement's
     * row count when it did.
     *
     * <p>The selection-set pattern is {@code <Type>.<field>} (dot, not slash): graphql-java
     * flattens type-conditioned fields under inline fragments as {@code "<Type>.<fieldName>"};
     * the slash is reserved for parent/child path nesting.
     */
    private static CodeBlock crossTableProjections(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        for (var branch : source.branches()) {
            if (!(branch instanceof LaunchSource.DiscriminatedTable.Branch.SingleTable single)) continue;
            for (var ct : single.crossTableTerms()) {
                var term = ct.term();
                var aliases = PathFragments.generateAliases(term.path());
                b.beginControlFlow("if (env.getSelectionSet().contains($S))",
                    single.participant().typeName() + "." + ct.fieldName());
                for (int i = 0; i < term.path().size(); i++) {
                    var target = ((no.sikt.graphitron.rewrite.model.JoinStep.HasTargetTable)
                        term.path().get(i)).targetTable();
                    b.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                        target.tableClass(), aliases.get(i), target.constantsClass(),
                        target.javaFieldName(), tableLocal, "_" + aliases.get(i));
                }
                b.addStatement("fields.add($T.field($L).as($S))",
                    DSL, PathFragments.scalarInnerSelect(term, aliases, tableLocal), term.asName());
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Per-joined-detail-branch detail-alias declarations plus the selection-set-gated
     * {@code fields.add(detailAlias.<col>)} per detail-exclusive field, mirroring
     * {@link #crossTableAliasDeclarations} but joining the whole detail table once per branch.
     * The column projects under its natural name (no {@code .as(...)}) so the participant's
     * plain column fetcher reads it back by column name.
     */
    private static CodeBlock joinedDetailAliasDeclarations(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        for (var branch : source.branches()) {
            if (!(branch instanceof LaunchSource.DiscriminatedTable.Branch.JoinedDetail joined)) continue;
            var jtb = joined.participant();
            if (jtb.discriminatorValue() == null) continue;
            if (joined.detailFields().isEmpty()) continue;
            String aliasVar = jtb.detailAliasVarName();
            b.addStatement("$T $L = null", jtb.detailTable().tableClass(), aliasVar);
            for (var df : joined.detailFields()) {
                b.beginControlFlow("if (env.getSelectionSet().contains($S))",
                    jtb.typeName() + "." + df.fieldName());
                b.addStatement("$L = $T.$L.as($S)", aliasVar, jtb.detailTable().constantsClass(),
                    jtb.detailTable().javaFieldName(), jtb.detailAliasName());
                b.addStatement("fields.add($L.$L)", aliasVar, df.column().javaName());
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * The conditional {@code step = step.leftJoin(detailAlias).on(...)} block per joined-detail
     * branch whose alias {@link #joinedDetailAliasDeclarations} declared. The ON clause equates
     * the child-to-parent hop ({@code detailAlias.<sourceSide>} on the detail/FK side equals
     * {@code base.<targetSide>} on the base/PK side, AND-chained across composite slots) plus
     * the branch's discriminator value, so non-matching rows carry NULL through the join.
     */
    private static CodeBlock joinedDetailJoinChain(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        for (var branch : source.branches()) {
            if (!(branch instanceof LaunchSource.DiscriminatedTable.Branch.JoinedDetail joined)) continue;
            var jtb = joined.participant();
            if (jtb.discriminatorValue() == null) continue;
            if (joined.detailFields().isEmpty()) continue;
            String aliasVar = jtb.detailAliasVarName();
            CodeBlock keyOn = null;
            for (var slot : jtb.childToParentPairs().slots()) {
                var eq = CodeBlock.of("$L.$L.eq($L.$L)",
                    aliasVar, slot.sourceSide().javaName(), tableLocal, slot.targetSide().javaName());
                keyOn = keyOn == null ? eq : CodeBlock.of("$L.and($L)", keyOn, eq);
            }
            var onCondition = CodeBlock.builder()
                .add("$L.and($T.field($L.getQualifiedName().append($T.name($S)), $T.class).eq($S))",
                    keyOn, DSL, tableLocal, DSL, source.discriminatorColumn(), Object.class,
                    jtb.discriminatorValue())
                .build();
            b.beginControlFlow("if ($L != null)", aliasVar);
            b.addStatement("step = step.leftJoin($L).on($L)", aliasVar, onCondition);
            b.endControlFlow();
        }
        return b.build();
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
