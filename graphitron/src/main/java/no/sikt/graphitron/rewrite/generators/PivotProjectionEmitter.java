package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.PivotSpec;
import no.sikt.graphitron.rewrite.model.TableRef;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.RESERVED_RK_ALIAS_PREFIX;

/**
 * Builds the {@code @pivot} projection: one {@code max(<value>).filterWhere(<disc>.eq(<token>))
 * .as(<readName>)} column per <em>selected</em> slot of the projection type, the slot set gated on
 * the runtime selection exactly as {@code Type.$fields} gates its columns. Two delivery hosts
 * consume the same slot-switch emission so the projected aliases cannot drift between them:
 *
 * <ul>
 *   <li>the inline arm ({@link #buildSwitchArmBody}) folds the projection into the parent query as
 *       a correlated aggregate subselect, wrapped {@code DSL.multiset(...)} and aliased by result
 *       key like the single-cardinality {@link InlineTableFieldEmitter} arm (the read side unwraps
 *       {@code Result.get(0)});</li>
 *   <li>the batched host ({@link SplitRowsMethodEmitter#buildForBatchedPivot}) selects the same
 *       aggregates over the key-preserving left join, via {@link #slotSelectionLoop}.</li>
 * </ul>
 *
 * <p>The filtered-aggregate column form is new with {@code @pivot}; the inline shape needs no
 * {@code GROUP BY} (the correlated aggregate collapses to one row on its own — one projection
 * record per parent, always, with null slots where no row carries the token).
 */
public final class PivotProjectionEmitter {

    private PivotProjectionEmitter() {}

    /**
     * Returns the {@code {...}} body to place inside the {@code $fieldsGrouped} switch arm for an
     * inline {@link ChildField.PivotField}. Does <em>not</em> include the {@code case "name" ->}
     * prefix — the caller composes that, exactly as with {@link InlineTableFieldEmitter}.
     *
     * @param pf          the pivot field to emit
     * @param parentAlias the caller-scope parent-alias variable ({@code "table"})
     * @param entryName   the caller-scope {@code Map.Entry<String, List<SelectedField>>} variable
     *                    holding the field's result-key bucket; the slot selection is the merged
     *                    union of every occurrence's sub-selection
     */
    public static CodeBlock buildSwitchArmBody(ChildField.PivotField pf, String parentAlias,
            String entryName, String outputPackage) {
        PivotSpec spec = pf.spec();
        TableRef pivotTable = spec.pivotTable();
        String prefix = pf.name() + "Pivot";
        String aliasVar = prefix + "Alias";
        String listVar = prefix + "Fields";

        var code = CodeBlock.builder();
        // Alias string is prefixed with the parent alias's runtime name so recursive /
        // self-referential subselects never shadow each other, mirroring the inline table arm.
        code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
            pivotTable.tableClass(), aliasVar, pivotTable.constantsClass(), pivotTable.javaFieldName(),
            parentAlias, "_" + pf.name() + "_pv");
        code.add(slotSelectionLoop(spec, prefix, aliasVar, listVar,
            CodeBlock.of("$T.mergeByResultKey($L.getValue()).values()",
                TypeClassGenerator.selectionOccurrencesClass(outputPackage), entryName)));

        // Correlation: AND-chain over the single FK hop's column pairs — target side on the
        // attribute alias, source side on the parent alias. Arity-generic, so composite keys
        // generate through the same code.
        On.ColumnPairs pairs = spec.pairs();
        var correlation = CodeBlock.builder();
        for (int i = 0; i < pairs.slotCount(); i++) {
            if (i > 0) correlation.add(".and(");
            ColumnRef target = pairs.targetSideColumns().get(i);
            ColumnRef source = pairs.sourceSideColumns().get(i);
            correlation.add("$L.$L.eq($L.$L)", aliasVar, target.javaName(), parentAlias, source.javaName());
            if (i > 0) correlation.add(")");
        }
        // Uniform multiset envelope (single row: the aggregate over the correlated set collapses
        // on its own), aliased by the runtime result key; the read side unwraps Result.get(0).
        code.addStatement("fields.add($T.multiset($T.select($L).from($L).where($L)).as($S + $L.getKey()))",
            DSL, DSL, listVar, aliasVar, correlation.build(), RESERVED_RK_ALIAS_PREFIX, entryName);
        return code.build();
    }

    /**
     * Emits the selection-gated slot collection shared by both delivery hosts: gather the
     * selected slot names (deduped across aliased occurrences — one projected column serves every
     * alias of a slot, since the read is by name, not by result key), then switch each into its
     * filtered aggregate. {@code selectionValuesExpr} supplies the
     * {@code Collection<List<SelectedField>>} to walk: the merged result-key bucket inline, the
     * field's own {@code env.getSelectionSet()} grouping on the batched host.
     *
     * <p>A selection carrying no slot (introspection-only, {@code { __typename }}) would produce
     * an empty SELECT list; a constant column keeps the subselect well-formed and the
     * one-record-per-parent invariant intact.
     */
    static CodeBlock slotSelectionLoop(PivotSpec spec, String prefix, String aliasVar, String listVar,
            CodeBlock selectionValuesExpr) {
        var selectedField = ClassName.get("graphql.schema", "SelectedField");
        var listOfSelectedField = ParameterizedTypeName.get(ClassName.get("java.util", "List"), selectedField);
        var fieldWildcard = ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"),
            WildcardTypeName.subtypeOf(Object.class));
        String slotsVar = prefix + "Slots";
        String occVar = prefix + "Occ";
        String slotVar = prefix + "Slot";

        var code = CodeBlock.builder();
        code.addStatement("$T<String> $L = new $T<>()",
            ClassName.get("java.util", "Set"), slotsVar, ClassName.get("java.util", "LinkedHashSet"));
        code.beginControlFlow("for ($T $L : $L)", listOfSelectedField, occVar, selectionValuesExpr);
        code.addStatement("$L.add($L.get(0).getName())", slotsVar, occVar);
        code.endControlFlow();
        code.addStatement("$T<$T> $L = new $T<>()",
            ClassName.get("java.util", "List"), fieldWildcard, listVar, ClassName.get("java.util", "ArrayList"));
        code.beginControlFlow("for (String $L : $L)", slotVar, slotsVar);
        code.add("switch ($L) {\n", slotVar);
        for (ChildField.PivotSlotField slot : spec.slots()) {
            String token = spec.tokenBySlot().get(slot.name());
            code.add("    case $S -> $L.add($T.max($L.$L).filterWhere($L.$L.eq($T.inline($S))).as($S));\n",
                slot.name(), listVar,
                DSL, aliasVar, spec.value().javaName(),
                aliasVar, spec.discriminator().javaName(),
                DSL, token, slot.readName());
        }
        code.add("    default -> { } // non-slot selections (__typename) project nothing\n");
        code.add("}\n");
        code.endControlFlow();
        code.beginControlFlow("if ($L.isEmpty())", listVar);
        code.addStatement("$L.add($T.inline(1).as($S))", listVar, DSL, "__pivot_present__");
        code.endControlFlow();
        return code.build();
    }
}
