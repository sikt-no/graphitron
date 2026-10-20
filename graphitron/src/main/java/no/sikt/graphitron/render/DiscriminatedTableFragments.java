package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The discriminated-interface re-projection assembly: the shared body of every query over a
 * single-table discriminated interface (the root launcher, the child twin, the service
 * single-table-interface fetcher, the two DML discriminated follow-ups), total over the
 * {@link LaunchSource.DiscriminatedTable} arm's data. It splits on the fact boundary:
 * {@link #projection} emits what the query selects (the discriminator {@code IN} restriction
 * ANDed into the caller's {@code condition} local, then the {@link #FIELDS_LOCAL} set: the
 * {@link ReservedAliases#DISCRIMINATOR} routing alias first, each single-table branch's
 * {@code $project}, the arm's base slice, {@code alwaysProject}, the cross-table subselects and
 * the joined-detail alias declarations), and {@link #joinedStep} emits what it selects from (the
 * {@code SelectJoinStep<Record> step} declaration and the discriminator-gated joined-detail
 * {@code LEFT JOIN} chain). {@link #assembly} is the two composed, which is what every caller
 * that reads the field list only through the select expression wants; a paginating caller binds
 * the halves itself, because its page request has to observe {@link #FIELDS_LOCAL} before the
 * statement is composed. Either way the caller finishes the chain
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
 * <p>The discriminator plays two roles on two axes, and this class types them differently on
 * purpose. In WHERE and in a JOIN's ON clause it is a SQL comparison operand, so the value it is
 * compared against binds through the column's own {@code getDataType()}
 * ({@code DSL.val("<value>", <tableLocal>.<COL>.getDataType())}, the generator's standing typed-bind
 * idiom): the value reaches the database through the column's registered converter, which is what
 * lets a Postgres-enum discriminator compare against an operand of its own type instead of against
 * a {@code character varying} the database has no operator for. jOOQ decides the rendering from the
 * bind's type, so the enum arm gets {@code cast(? as "<schema>"."<enum_type>")} and a varchar column
 * keeps a plain {@code ?}. In SELECT the discriminator is instead a routing token whose vocabulary
 * is the authored {@code @discriminator(value:)} literal set the generated {@code TypeResolver}
 * switches on as {@code String}, so {@link #fieldsList}'s {@link ReservedAliases#DISCRIMINATOR}
 * projection stays deliberately untyped ({@code Object.class}, read back as {@code String}). The
 * asymmetry is the seam, not a half-measure: typing the projection would put the routing token in
 * the column's namespace for no gain.
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
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");

    /**
     * The name of the {@code LinkedHashSet<Field<?>>} local {@link #projection} declares and
     * populates. It crosses the seam: {@link #assembly} composes it into the select expression,
     * and a paginating caller reads it between the two halves (the page request takes the
     * selection as an argument and returns the merged select list the statement must receive), so
     * the name is minted once here rather than spelled at each site.
     */
    public static final String FIELDS_LOCAL = "fields";

    /**
     * Namespace prefix for the Java locals a cross-table term's hop aliases bind. The alias
     * scheme ({@code PathFragments.generateAliases}) emits a lowercase letter run plus the hop
     * index and never an underscore, so a prefixed local cannot collide with one; the projected
     * SQL alias keeps the bare token. See {@link #crossTableProjections} for why the separation is
     * load-bearing.
     */
    private static final String CROSS_TABLE_LOCAL_PREFIX = "ct_";

    /**
     * The local holding the fold's shared-alias field names, declared once by {@link #fieldsList}
     * and read by every single-table branch's {@code restrictTo} call.
     */
    private static final String SHARED_ALIAS_FIELDS_LOCAL = "sharedAliasFields";

    /**
     * The local holding the fold's scoped selection, the fetched field's own fields or the node
     * level of its connection, computed once by {@link #fieldsList} and read by every gate and
     * every branch, so no read in the fold reaches below the level its participants sit at.
     */
    private static final String SELECTION_LOCAL = "foldSelection";

    /**
     * The whole assembly, ending with {@code step} joined and ready for the caller's terminal:
     * {@link #projection} composed with {@link #joinedStep} over the populated field list. A
     * caller that must observe the field list before the statement is composed calls the two
     * halves itself.
     */
    public static CodeBlock assembly(LaunchSource.DiscriminatedTable source,
            List<ColumnRef> alwaysProject, String tableLocal, ProjectionCall.SelectionScope scope) {
        return CodeBlock.builder()
            .add(projection(source, alwaysProject, tableLocal, scope))
            .add(joinedStep(source, tableLocal,
                CodeBlock.of("new $T<>($L)", ArrayList.class, FIELDS_LOCAL)))
            .build();
    }

    /**
     * Everything that decides <em>what the query selects</em>: the discriminator {@code IN}
     * restriction ANDed into the caller's {@code condition}, the {@link #FIELDS_LOCAL}
     * declaration and every term that lands in it (the routing alias, the per-branch
     * {@code $project} calls, the base slice, the caller's {@code alwaysProject} columns, and the
     * gated cross-table subselects). Emits no statement, so a caller may read the populated
     * field list before deciding the statement's shape.
     *
     * <p>{@code scope} says where the participants' fields sit relative to the fetched field:
     * its own children, or the node level of a connection. Every selection read in the fold
     * reads that one scoped map.
     */
    public static CodeBlock projection(LaunchSource.DiscriminatedTable source,
            List<ColumnRef> alwaysProject, String tableLocal, ProjectionCall.SelectionScope scope) {
        var b = CodeBlock.builder();
        b.add(discriminatorFilter(source, tableLocal));
        b.add(fieldsList(source, tableLocal, scope));
        // Extra always-projected base columns (deduped by the LinkedHashSet declared above).
        // Used by the service path to guarantee the shared table's PK reaches the fetched
        // Record for the by-PK re-map; empty for the read paths.
        for (var col : alwaysProject) {
            b.addStatement("fields.add($L.$L)", tableLocal, col.javaName());
        }
        b.add(crossTableProjections(source, tableLocal));
        b.add(joinedDetailAliasDeclarations(source, tableLocal));
        return b.build();
    }

    /**
     * Everything that decides <em>what the query selects from</em>: the
     * {@code SelectJoinStep<Record> step} declaration over the caller's select expression, then
     * the discriminator-gated joined-detail {@code LEFT JOIN} chain, which after the cross-table
     * conversion is this fragment's only join chain.
     *
     * <p>Every join it emits is proven single-valued at build time: a joined-table participant's
     * detail table joins on columns that <em>are</em> the detail's own primary key
     * ({@link no.sikt.graphitron.rewrite.TypeBuilder#resolveJoinedTableParticipant} rejects
     * anything else), so one base row is one entity. That is what lets a paginating caller put
     * {@code .limit()} on this step: {@code .limit()} slices rows, not entities.
     *
     * <p>{@code selectExpression} is what {@code dsl.select(...)} receives: the field list
     * itself for a plain fetch, or the connection helper's merged selection for a paginating one.
     */
    public static CodeBlock joinedStep(LaunchSource.DiscriminatedTable source, String tableLocal,
            CodeBlock selectExpression) {
        var selectJoinStepOfRecord = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "SelectJoinStep"), RECORD);
        return CodeBlock.builder()
            .addStatement("$T step = dsl.select($L).from($L)",
                selectJoinStepOfRecord, selectExpression, tableLocal)
            .add(joinedDetailJoins(source, tableLocal))
            .build();
    }

    /**
     * The join half of {@link #joinedStep} alone, for a caller whose FROM clause is not the base
     * table: the batched child anchors on the parent-input VALUES derived table and reaches the
     * base through the correlated chain's step-0 attach, so it composes its own {@code step}
     * declaration and appends this. Precondition: a {@code step} local of type
     * {@code SelectJoinStep<Record>} is in scope, and {@link #projection} has run (it declares
     * the detail aliases these arms test).
     */
    public static CodeBlock joinedDetailJoins(LaunchSource.DiscriminatedTable source, String tableLocal) {
        return joinedDetailJoinChain(source, tableLocal);
    }

    /**
     * The qualified discriminator reference,
     * {@code DSL.field(<tableLocal>.getQualifiedName().append(DSL.name("<sqlName>")), Object.class)},
     * minted once for every site that reads the column: the three comparison sites here and in
     * {@link PathFragments#parentColumnEquals}, which attach a typed operand, and
     * {@link #fieldsList}'s routing projection, which attaches an alias instead (the axis split in
     * the class javadoc). Sharing the mint is what keeps the qualification argument one decision
     * rather than four literals a later edit can split.
     */
    static CodeBlock discriminatorRef(String tableLocal, ColumnRef discriminator) {
        return CodeBlock.of("$T.field($L.getQualifiedName().append($T.name($S)), $T.class)",
            DSL, tableLocal, DSL, discriminator.sqlName(), Object.class);
    }

    /**
     * One {@code @discriminator(value:)} literal as a comparison operand:
     * {@code DSL.val("<value>", <tableLocal>.<COL>.getDataType())}. Typing the bind off the
     * column's own data type routes the literal through the column's registered converter, so a
     * Postgres-enum discriminator binds enum-typed (jOOQ renders the bind as
     * {@code cast(? as "<schema>"."<enum_type>")}, the operand the database accepts) while a
     * varchar column's conversion is the identity and its bind is unchanged. The build rejects a
     * literal outside a closed value domain, so the converter cannot silently yield {@code null}
     * here (see
     * {@link no.sikt.graphitron.rewrite.TypeBuilder#discriminatorLiteralRejection}).
     */
    static CodeBlock discriminatorValue(String tableLocal, ColumnRef discriminator, String value) {
        return CodeBlock.of("$T.val($S, $L.$L.getDataType())",
            DSL, value, tableLocal, discriminator.javaName());
    }

    /**
     * {@code condition = condition.and(<qualified discriminator>.in(v1, v2, ...))}, restricting
     * to rows with a known discriminator value; nothing when {@code knownValues} is empty. Each
     * value is a {@link #discriminatorValue} typed bind.
     */
    private static CodeBlock discriminatorFilter(LaunchSource.DiscriminatedTable source, String tableLocal) {
        if (source.knownValues().isEmpty()) {
            return CodeBlock.of("");
        }
        var inArgs = source.knownValues().stream()
            .map(v -> discriminatorValue(tableLocal, source.discriminatorColumn(), v))
            .collect(CodeBlock.joining(", "));
        return CodeBlock.builder()
            .addStatement("condition = condition.and($L.in($L))",
                discriminatorRef(tableLocal, source.discriminatorColumn()), inArgs)
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
    private static CodeBlock fieldsList(LaunchSource.DiscriminatedTable source, String tableLocal,
            ProjectionCall.SelectionScope scope) {
        var b = CodeBlock.builder();
        var fieldType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Field"),
            WildcardTypeName.subtypeOf(Object.class));
        var setType = ParameterizedTypeName.get(
            ClassName.get(LinkedHashSet.class), fieldType);
        b.addStatement("$T $L = new $T<>()", setType, FIELDS_LOCAL, LinkedHashSet.class);
        // The routing projection is the untyped arm of the axis split (class javadoc): the alias
        // carries a routing token the TypeResolver reads back as String, not a comparison operand.
        b.addStatement("fields.add($L.as($S))",
            discriminatorRef(tableLocal, source.discriminatorColumn()), ReservedAliases.DISCRIMINATOR);
        var restriction = source.selectionRestriction();
        var occurrences = className(restriction.helper());
        // Explicit types, never `var`: emitted sources are lint-gated against it.
        b.addStatement("$T $L = $L", groupedType(), SELECTION_LOCAL,
            ProjectionCall.envSelection(occurrences, scope));
        // One set per fold, not one per arm: the names are the interface's fact, so hoisting the
        // literal out of the branch loop drops a per-request allocation per participant.
        b.addStatement("$T<$T> $L = $T.of($L)", SET, ClassName.get(String.class),
            SHARED_ALIAS_FIELDS_LOCAL, SET,
            restriction.sharedAliasFieldNames().stream()
                .map(name -> CodeBlock.of("$S", name))
                .collect(CodeBlock.joining(", ")));
        for (var branch : source.branches()) {
            if (branch instanceof LaunchSource.DiscriminatedTable.Branch.SingleTable single) {
                b.addStatement("fields.addAll($L)",
                    ProjectionCall.fromGrouped(className(single.projection()),
                        CodeBlock.of("$T.restrictTo($L, $S, $L)", occurrences, SELECTION_LOCAL,
                            single.participant().typeName(), SHARED_ALIAS_FIELDS_LOCAL),
                        CodeBlock.of("$L", tableLocal)));
            }
        }
        for (var term : source.baseSlice()) {
            switch (term) {
                // Inherited base-resident @reference: project the base column once per selected
                // result-key bucket (aliased duplicates each get their own __rk_<key> term), off
                // the base so NULL-through rows (base present, detail absent) still resolve it,
                // under the same reserved alias the standalone correlated-subquery projection
                // mints, so the one registered fetcher reads both queries' rows identically.
                // The bucket test reads every occurrence, not the first: a bucket may carry
                // another participant's field first, and the base term must not be dropped.
                // Explicit entry type: emitted sources may not use `var`
                // (GeneratedSourcesLintTest).
                case LaunchSource.DiscriminatedTable.BaseSliceTerm.InheritedRef inherited -> {
                    var rkEntryType = ParameterizedTypeName.get(
                        ClassName.get("java.util", "Map", "Entry"),
                        ClassName.get(String.class),
                        ParameterizedTypeName.get(LIST, SELECTED_FIELD));
                    b.beginControlFlow("for ($T rkEntry : $L.entrySet())", rkEntryType, SELECTION_LOCAL);
                    b.beginControlFlow("if (rkEntry.getValue().stream().anyMatch(foldField -> foldField.getName().equals($S)))",
                        inherited.fieldName());
                    b.addStatement("fields.add($L.$L.as($S + rkEntry.getKey()))",
                        tableLocal, inherited.baseColumn().javaName(),
                        ReservedAliases.resultKeyPrefix(no.sikt.graphitron.rewrite.model.AliasOwner.shared()));
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
     * <p>The gate reads the fold's scoped selection ({@link #typeConditionedGate}).
     */
    private static CodeBlock crossTableProjections(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        for (var branch : source.branches()) {
            if (!(branch instanceof LaunchSource.DiscriminatedTable.Branch.SingleTable single)) continue;
            for (var ct : single.crossTableTerms()) {
                var term = ct.term();
                var aliases = PathFragments.generateAliases(term.path());
                // The Java locals carry a namespace prefix the alias scheme cannot produce (it
                // emits a lowercase letter run followed by the hop index, never an underscore).
                // The projected SQL alias keeps the bare token: the batched host declares its
                // base-table local from the same scheme, and Java forbids an inner block from
                // shadowing an enclosing local, so an unprefixed hop local would fail to compile
                // whenever the hop's target and the discriminated base share a first letter.
                var locals = aliases.stream().map(a -> CROSS_TABLE_LOCAL_PREFIX + a).toList();
                b.beginControlFlow("if ($L)",
                    typeConditionedGate(single.participant().typeName(), ct.fieldName()));
                for (int i = 0; i < term.path().size(); i++) {
                    var target = ((no.sikt.graphitron.rewrite.model.JoinStep.HasTargetTable)
                        term.path().get(i)).targetTable();
                    b.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                        CatalogRefs.tableClass(target), locals.get(i), CatalogRefs.constantsClass(target),
                        target.javaFieldName(), tableLocal, "_" + aliases.get(i));
                }
                b.addStatement("fields.add($T.field($L).as($S))",
                    DSL, PathFragments.scalarInnerSelect(term, locals, tableLocal), term.asName());
                b.endControlFlow();
            }
        }
        return b.build();
    }

    /**
     * Per-joined-detail-branch detail-alias declarations plus the selection-set-gated
     * {@code fields.add(detailAlias.<col>)} per detail-exclusive field. It shares the selection
     * gate of {@link #crossTableProjections} but not its SQL shape: the whole detail table joins
     * once per branch (see {@link #joinedStep}) instead of a per-term correlated subselect. The
     * column projects under its natural name (no {@code .as(...)}) so the participant's plain
     * column fetcher reads it back by column name.
     */
    private static CodeBlock joinedDetailAliasDeclarations(LaunchSource.DiscriminatedTable source, String tableLocal) {
        var b = CodeBlock.builder();
        for (var branch : source.branches()) {
            if (!(branch instanceof LaunchSource.DiscriminatedTable.Branch.JoinedDetail joined)) continue;
            var jtb = joined.participant();
            if (jtb.discriminatorValue() == null) continue;
            if (joined.detailFields().isEmpty()) continue;
            String aliasVar = jtb.detailAliasVarName();
            b.addStatement("$T $L = null", CatalogRefs.tableClass(jtb.detailTable()), aliasVar);
            for (var df : joined.detailFields()) {
                b.beginControlFlow("if ($L)",
                    typeConditionedGate(jtb.typeName(), df.fieldName()));
                b.addStatement("$L = $T.$L.as($S)", aliasVar, CatalogRefs.constantsClass(jtb.detailTable()),
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
                var eq = ColumnComparison.equality(
                    aliasVar, slot.sourceSide(), tableLocal, slot.targetSide());
                keyOn = keyOn == null ? eq : CodeBlock.of("$L.and($L)", keyOn, eq);
            }
            var onCondition = CodeBlock.builder()
                .add("$L.and($L.eq($L))", keyOn,
                    discriminatorRef(tableLocal, source.discriminatorColumn()),
                    discriminatorValue(tableLocal, source.discriminatorColumn(),
                        jtb.discriminatorValue()))
                .build();
            b.beginControlFlow("if ($L != null)", aliasVar);
            b.addStatement("step = step.leftJoin($L).on($L)", aliasVar, onCondition);
            b.endControlFlow();
        }
        return b.build();
    }

    /**
     * The runtime gate for a type-conditioned participant field: a membership test on the fold's
     * scoped selection, true when some occurrence selects {@code fieldName} for
     * {@code typeName}. Reading the scoped map rather than the whole subtree is what keeps a
     * same-named field selected deeper (under a child of the participant) from opening the gate;
     * the scope itself already puts a connection's node level at the top.
     */
    private static CodeBlock typeConditionedGate(String typeName, String fieldName) {
        // Lambda parameters may not shadow a host local, so they carry the fold's own prefix.
        return CodeBlock.of("$L.values().stream().anyMatch(foldBucket -> foldBucket.stream()"
                + ".anyMatch(foldField -> foldField.getName().equals($S)"
                + " && foldField.getObjectTypeNames().contains($S)))",
            SELECTION_LOCAL, fieldName, typeName);
    }

    private static ParameterizedTypeName groupedType() {
        return ParameterizedTypeName.get(ClassName.get("java.util", "Map"), ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
