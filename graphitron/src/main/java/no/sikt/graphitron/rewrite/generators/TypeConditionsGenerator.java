package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Generates one {@code <TypeName>Conditions.java} per type that has fields with a
 * {@link GeneratedConditionFilter}.
 *
 * <p>Each condition method is a pure function: it takes the jOOQ table alias and typed argument
 * values, and returns a {@code Condition}. No dependency on graphql-java runtime types.
 */
public class TypeConditionsGenerator {

    // CONDITION and DSL come from GeneratorUtils via static import.

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        // Collect GeneratedConditionFilters grouped by their conditions class name
        var filtersByClass = new LinkedHashMap<String, List<GeneratedConditionFilter>>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                for (var gcf : extractGeneratedConditionFilters(field)) {
                    filtersByClass
                        .computeIfAbsent(gcf.className(), k -> new ArrayList<>())
                        .add(gcf);
                }
            }
        }

        return filtersByClass.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey()))
            .map(e -> generateConditionsClass(e.getKey(), e.getValue(), outputPackage))
            .toList();
    }

    private static List<GeneratedConditionFilter> extractGeneratedConditionFilters(GraphitronField field) {
        // R363: multi-table polymorphic root fields are not SqlGeneratingField (their return type is
        // polymorphic, not table-bound); their @field filters live per-participant, each lowered
        // against the participant's own table with a participant-named conditions class. Emit one
        // method per participant's GeneratedConditionFilter.
        if (field instanceof QueryField.QueryInterfaceField f) {
            return extractParticipantConditionFilters(f.participantFilters());
        }
        if (field instanceof QueryField.QueryUnionField f) {
            return extractParticipantConditionFilters(f.participantFilters());
        }
        // LookupField variants have their lookup-key args emitted via VALUES + JOIN by
        // LookupValuesJoinEmitter; they do not need a generated condition method.
        // Note: a lookup field with a mixed non-lookup-key column filter is not yet supported
        // (no such schema exists today); that case would need to emit the non-key filter here.
        if (field instanceof LookupField) return List.of();
        if (!(field instanceof SqlGeneratingField sgf)) return List.of();
        return sgf.filters().stream()
            .filter(f -> f instanceof GeneratedConditionFilter)
            .map(f -> (GeneratedConditionFilter) f)
            .findFirst()
            .map(List::of)
            .orElse(List.of());
    }

    private static List<GeneratedConditionFilter> extractParticipantConditionFilters(
            List<no.sikt.graphitron.rewrite.model.ParticipantFilters> participantFilters) {
        var out = new ArrayList<GeneratedConditionFilter>();
        for (var pf : participantFilters) {
            pf.filters().stream()
                .filter(f -> f instanceof GeneratedConditionFilter)
                .map(f -> (GeneratedConditionFilter) f)
                .findFirst()
                .ifPresent(out::add);
        }
        return out;
    }

    private static TypeSpec generateConditionsClass(String fqClassName, List<GeneratedConditionFilter> filters,
                                                    String outputPackage) {
        // Class simple name is the last segment of the fully qualified name
        String simpleName = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
        var builder = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC);

        for (var gcf : filters) {
            builder.addMethod(buildConditionMethod(gcf, outputPackage));
        }

        return builder.build();
    }

    static MethodSpec buildConditionMethod(GeneratedConditionFilter gcf, String outputPackage) {
        var tableRef = gcf.tableRef();
        var jooqTableClass = GeneratorUtils.ResolvedTableNames.ofTable(tableRef).jooqTableClass();

        var builder = MethodSpec.methodBuilder(gcf.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table");

        for (var bp : gcf.bodyParams()) {
            builder.addParameter(paramType(bp), bp.name());
        }

        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var bp : gcf.bodyParams()) {
            switch (bp) {
                // Local column predicate: bind directly against the method's own `table` alias.
                case BodyParam.ColumnPredicate cp ->
                    appendGuardedAnd(builder, cp, emitColumnPredicateTerm(cp, "table"));
                // Remote column predicate: the column lives on a joined table, so AND in a
                // correlated EXISTS that joins through the path and applies the inner predicate
                // against the terminal alias. The null / empty-list guard wraps the whole EXISTS
                // term, identical to the local case. emitRemoteExists declares the per-hop alias
                // locals on the builder (unconditionally, before the guard) and returns the bare
                // DSL.exists(...) term.
                case BodyParam.RemoteColumnPredicate r ->
                    appendGuardedAnd(builder, r.inner(), emitRemoteExists(builder, r));
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    /**
     * Emits the bare jOOQ predicate expression for a {@link BodyParam.ColumnPredicate}, binding
     * its column(s) against {@code alias}. No {@code condition.and(...)} wrapping and no null /
     * empty-list guard — those are applied uniformly by {@link #appendGuardedAnd} so the local and
     * remote (EXISTS) sites share one guard policy while differing only in the term they AND in.
     */
    private static CodeBlock emitColumnPredicateTerm(BodyParam.ColumnPredicate cp, String alias) {
        return switch (cp) {
            case BodyParam.Eq eq -> {
                String col = eq.column().javaName();
                yield CodeBlock.of("$L.$L.eq($T.val($L, $L.$L))", alias, col, DSL, eq.name(), alias, col);
            }
            case BodyParam.In in -> CodeBlock.of("$L.$L.in($L)", alias, in.column().javaName(), in.name());
            // DSL.row(alias.c1, ..., alias.cN).eq(arg) — typed Field<T> overload produces a
            // Row<N><T1, ..., TN> matching the method parameter exactly.
            case BodyParam.RowEq req -> CodeBlock.of("$T.row($L).eq($L)",
                DSL, buildTypedCols(req.columns(), alias), req.name());
            // DSL.row(alias.c1, ..., alias.cN).in(rows) — typed Row<N>.in takes
            // Collection<? extends Row<N><T1, ..., TN>>.
            case BodyParam.RowIn rin -> CodeBlock.of("$T.row($L).in($L)",
                DSL, buildTypedCols(rin.columns(), alias), rin.name());
        };
    }

    /**
     * ANDs {@code term} into the method's {@code condition} local, guarded by the same null /
     * empty-list policy the four local arms used before R380:
     * <ul>
     *   <li>{@code Eq} / {@code RowEq} (scalar): unguarded when non-null, else {@code if (arg != null)};</li>
     *   <li>{@code In} / {@code RowIn} (list): {@code if (!arg.isEmpty())} when non-null, else
     *       {@code if (arg != null && !arg.isEmpty())}. An empty list contributes no predicate
     *       (DSL.noCondition() identity) rather than rendering {@code IN ()} as the constant
     *       {@code false} and zeroing the query.</li>
     * </ul>
     * The guard depends only on {@code cp}'s arm identity and {@code nonNull}, so the same call
     * wraps a bare local predicate ({@code term} over {@code table}) and a remote
     * {@code DSL.exists(...)} term identically.
     */
    private static void appendGuardedAnd(MethodSpec.Builder builder, BodyParam.ColumnPredicate cp, CodeBlock term) {
        String name = cp.name();
        if (cp.list()) {
            if (cp.nonNull()) {
                builder.addStatement("if (!$L.isEmpty()) condition = condition.and($L)", name, term);
            } else {
                builder.addStatement("if ($L != null && !$L.isEmpty()) condition = condition.and($L)", name, name, term);
            }
        } else {
            if (cp.nonNull()) {
                builder.addStatement("condition = condition.and($L)", term);
            } else {
                builder.addStatement("if ($L != null) condition = condition.and($L)", name, term);
            }
        }
    }

    /**
     * Builds the correlated {@code DSL.exists(DSL.selectOne().from(terminalAlias).join(...)
     * .where(<correlation back to table>.and(<inner predicate on terminalAlias>)))} term for a
     * {@link BodyParam.RemoteColumnPredicate}. Mirrors {@link InlineTableFieldEmitter#buildInnerSelect}
     * and {@link FkTargetConditionEmitter} (FROM terminal, JOIN chain walking back toward step 0,
     * WHERE step-0 parent correlation). The hop aliases are method-local statics prefixed with the
     * parameter name so multiple remote predicates in one method never collide; this method does
     * not recurse, so no runtime alias prefixing is needed.
     *
     * <p>The per-hop alias locals are declared as statements on {@code builder} directly (they
     * must precede the EXISTS expression and sit outside any null-guard {@code if}); the returned
     * {@link CodeBlock} is only the {@code DSL.exists(...)} term that {@link #appendGuardedAnd}
     * then ANDs in.
     */
    private static CodeBlock emitRemoteExists(MethodSpec.Builder builder, BodyParam.RemoteColumnPredicate r) {
        var path = r.joinPath();
        String prefix = r.inner().name() + "_ref";

        var hopAliases = new ArrayList<String>(path.size());
        for (int i = 0; i < path.size(); i++) {
            var ht = (JoinStep.HasTargetTable) path.get(i);
            String local = prefix + i;
            builder.addStatement("$T $L = $T.$L.as($S)",
                ht.targetTable().tableClass(), local,
                ht.targetTable().constantsClass(), ht.targetTable().javaFieldName(), local);
            hopAliases.add(local);
        }
        String terminalAlias = hopAliases.get(hopAliases.size() - 1);

        var sel = CodeBlock.builder();
        sel.add("$T.selectOne()", DSL);
        sel.add("\n        .from($L)", terminalAlias);
        // JOIN chain: walk from terminal back toward step 0, joining the previous hop's alias.
        for (int i = path.size() - 1; i >= 1; i--) {
            if (!(path.get(i) instanceof JoinStep.Hop hop
                    && hop.on() instanceof On.ColumnPairs cp)) {
                throw new IllegalStateException(
                    "RemoteColumnPredicate join hop " + i + " for '" + r.inner().name()
                    + "' is not FK-derived (" + path.get(i)
                    + "); the validator must reject non-foreign-key reference-filter paths before emission");
            }
            sel.add("\n        $L",
                JoinPathEmitter.emitBridgingJoin(cp, hopAliases.get(i - 1), hopAliases.get(i)));
        }
        if (!(path.get(0) instanceof JoinStep.Hop firstHop
                && firstHop.on() instanceof On.ColumnPairs firstPairs)) {
            throw new IllegalStateException(
                "RemoteColumnPredicate first hop for '" + r.inner().name()
                + "' is not FK-derived; the validator must reject non-foreign-key reference-filter paths before emission");
        }
        var correlation = JoinPathEmitter.emitCorrelationWhere(firstPairs, hopAliases.get(0), "table");
        var predicate = emitColumnPredicateTerm(r.inner(), terminalAlias);
        sel.add("\n        .where($L.and($L))", correlation, predicate);

        return CodeBlock.of("$T.exists($L)", DSL, sel.build());
    }

    /**
     * Computes the method parameter type for a {@link BodyParam}. Eq/In use their stored
     * {@code javaType}; row-shape variants build {@code Row<N><T1, ..., TN>} (or {@code List<...>}
     * for IN) from the column tuple so that {@code DSL.row(Field<T1>, ..., Field<TN>).eq/.in}
     * matches without coercion.
     */
    private static TypeName paramType(BodyParam bp) {
        return switch (bp) {
            case BodyParam.Eq eq -> ClassName.bestGuess(eq.javaType());
            case BodyParam.In in -> ParameterizedTypeName.get(LIST, ClassName.bestGuess(in.javaType()));
            case BodyParam.RowEq req -> rowTypeName(req.columns());
            case BodyParam.RowIn rin -> ParameterizedTypeName.get(LIST, rowTypeName(rin.columns()));
            // A remote predicate's parameter type is the inner predicate's: the value still arrives
            // through env.getArgument; only the SQL shape (EXISTS vs a local column) differs.
            case BodyParam.RemoteColumnPredicate r -> paramType(r.inner());
        };
    }

    /** Builds {@code Row<N><T1, ..., TN>} from a column tuple. */
    private static ParameterizedTypeName rowTypeName(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName rowN = ClassName.get("org.jooq", "Row" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = columns.get(i).columnType();
        }
        return ParameterizedTypeName.get(rowN, typeArgs);
    }

    /** Comma-separated {@code alias.c1, ..., alias.cN} for {@code DSL.row(Field<T>...)}. */
    private static CodeBlock buildTypedCols(List<ColumnRef> columns, String alias) {
        var cells = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) cells.add(", ");
            cells.add("$L.$L", alias, columns.get(i).javaName());
        }
        return cells.build();
    }

}
