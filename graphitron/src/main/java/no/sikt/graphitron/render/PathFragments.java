package no.sikt.graphitron.render;

import no.sikt.graphitron.command.JoinCondition;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The join-path emission fragments that dispatch on a hop's occupants ({@link On},
 * {@link TableExpr}): alias derivation, table-expression materialization, the whole
 * bridging-hop fragments for both walk orders, and the two-argument condition-method call. The
 * proven-FK keyed fragments stay in {@link JoinFragments} (its entry points take a proven
 * {@link On.ColumnPairs}, never a raw {@link On}); these are the arms above that narrowing.
 * Moved here from the legacy join emitter when the projection renderer became their second
 * consumer; {@code JoinPathEmitter} delegates, so migrated and unmigrated hosts keep one
 * derivation.
 */
public final class PathFragments {

    private PathFragments() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    /**
     * The single synthesized alias for the pre-keyed lifted shape's target table: hop-less, so
     * {@link #generateAliases} has no path to walk, and the alias is that scheme specialized to
     * a one-node chain (first character lowercased plus index 0).
     */
    public static String liftedAlias(no.sikt.graphitron.rewrite.model.TableRef targetTable) {
        String javaName = targetTable.tableClass().simpleName();
        String basePrefix = javaName.isEmpty() ? "t" : javaName.substring(0, 1).toLowerCase();
        return basePrefix + 0;
    }

    /**
     * Generates deterministic per-hop aliases from each step's target-table simple class name,
     * one alias per hop: first character lowercased + hop index ({@code "l0"} for
     * {@code Language}, {@code "c1"} for {@code Country} at index 1). When two hops in the same
     * chain share a first character, the later hop falls back to two lowercased characters
     * ({@code "fi0"} when {@code Film} and {@code FilmActor} collide).
     */
    public static List<String> generateAliases(List<JoinStep> path) {
        var aliases = new ArrayList<String>(path.size());
        var prefixCount = new HashMap<String, Integer>();
        for (int i = 0; i < path.size(); i++) {
            String javaName = ((JoinStep.HasTargetTable) path.get(i)).targetTable().tableClass().simpleName();
            String basePrefix = javaName.isEmpty() ? "t" : javaName.substring(0, 1).toLowerCase();
            int occurrence = prefixCount.merge(basePrefix, 1, Integer::sum);
            String prefix = occurrence == 1 || javaName.length() < 2
                ? basePrefix
                : javaName.substring(0, 2).toLowerCase();
            aliases.add(prefix + i);
        }
        return aliases;
    }

    /**
     * Emits the table expression a hop's alias declaration binds: the single materialization
     * switch on the hop's {@link TableExpr} target. Callers append {@code .as(alias)}. All
     * alias-declaration loops route through this helper so a new {@link TableExpr} arm forces
     * exactly one emit-side acknowledgment; {@link JoinStep.HasTargetTable#targetTable()} stays
     * the read for alias <em>naming</em> and terminus checks, never for materialization.
     *
     * @param step         the join step whose FROM/JOIN source is being declared
     * @param previousNode where correlated routine-call bindings read the chain's previous
     *                     node's columns
     * @param argSource    where argument-sourced routine bindings read runtime values
     * @param argHelpers   collects the descent helper a dot-path routine binding needs
     */
    public static CodeBlock emitTableExpression(JoinStep step, PreviousNodeRef previousNode,
            ArgumentValueSource argSource, ArgPathHelperRegistry argHelpers) {
        return switch (step) {
            case JoinStep.Hop hop -> switch (hop.target()) {
                case TableExpr.Catalog c -> CodeBlock.of("$T.$L",
                    c.table().constantsClass(), c.table().javaFieldName());
                // A child-side routine hop carries no projection sink of its own: it is reached
                // through a JoinStep rather than through a command row, so the plan refuses to
                // produce a plan whose projected binding sits at a coordinate no wired emitter
                // owns, and a shape that would arrive here fails the build with its coordinate
                // named. See ProjectedKeyReads.unprojected().
                case TableExpr.RoutineCall rc -> RoutineCallEmitter.emitCall(rc, previousNode,
                    argSource, argHelpers, ProjectedKeyReads.unprojected());
            };
        };
    }

    /**
     * The whole bridging-hop join fragment for chains emitted start-first: one exhaustive
     * dispatch on the hop's {@link On} covering the keyed join
     * ({@link JoinFragments#emitForwardJoin}), the condition join
     * ({@code .join(hop).on(method(prev, hop))}), and the lateral routine hop
     * ({@code .crossJoin(DSL.lateral(hop))}, whose correlation rides the call arguments the
     * caller's alias declaration rendered). Callers supply their own surrounding whitespace.
     */
    public static CodeBlock emitForwardBridging(JoinStep.Hop hop, String prevAlias, String hopAlias) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> JoinFragments.emitForwardJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                hopAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> CodeBlock.of(".crossJoin($T.lateral($L))",
                ClassName.get("org.jooq.impl", "DSL"), hopAlias);
        };
    }

    /**
     * Terminal-first sibling of {@link #emitForwardBridging} for chains walked from the terminal
     * back towards step 0: the hop's own alias is already in scope, so the <em>previous</em>
     * node's alias is joined in. A lateral routine hop cannot appear on these paths (multi-node
     * routine chains classify as typed Deferred); {@code pathKindLabel} names the caller's path
     * family in the guard.
     */
    public static CodeBlock emitBackwardBridging(JoinStep.Hop hop, String prevAlias,
            String hopAlias, String pathKindLabel) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> JoinFragments.emitBridgingJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                prevAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> throw new IllegalStateException(
                "a lateral routine hop cannot appear in a " + pathKindLabel + " path; "
                + "multi-node routine chains classify as typed Deferred");
        };
    }

    /**
     * The whole correlated single-column subselect of a {@link SelectTerm.ScalarSubselect}: JOIN
     * chain walked terminal-first, step-0 correlation off the borrowed {@link ParentCorrelation},
     * per-hop filters, the term's optional parent gate, {@code .limit(1)}. The cap is what makes
     * the term row-neutral: however many rows the hop reaches, the projecting statement keeps
     * exactly its own, so a subselect may sit in the select list of a paginating query where a
     * join of unproven cardinality may not.
     *
     * <p>{@code parentLocal} is the enclosing statement's parent table local, the one thing that
     * differs between the two consumers: the projection unit's {@code $project} parameter, or the
     * discriminated assembly's base-table local.
     */
    public static CodeBlock scalarInnerSelect(SelectTerm.ScalarSubselect s, List<String> aliases,
            String parentLocal) {
        var path = s.path();
        String terminalAlias = aliases.get(aliases.size() - 1);
        var sel = CodeBlock.builder();
        sel.add("$T.select($L.$L)", DSL, terminalAlias, s.terminal().javaName());
        sel.add("\n        .from($L)", terminalAlias);
        for (int i = path.size() - 1; i >= 1; i--) {
            switch (path.get(i)) {
                case JoinStep.Hop hop -> sel.add("\n        $L",
                    emitBackwardBridging(hop, aliases.get(i - 1), aliases.get(i), "column-reference"));
            }
        }
        var where = CodeBlock.builder();
        where.add("$L", correlationWhere(s.correlation(), aliases.get(0), parentLocal, "column-reference"));
        appendHopFilters(where, path, aliases, parentLocal, ".and($L)");
        if (s.gate() != null) {
            where.add(".and($L)", parentColumnEquals(s.gate(), parentLocal));
        }
        sel.add("\n        .where($L)", where.build());
        sel.add("\n        .limit(1)");
        return sel.build();
    }

    /**
     * {@code DSL.field(<parent>.getQualifiedName().append(DSL.name("<col>")), Object.class)
     * .eq(DSL.val("<value>", <parent>.<COL>.getDataType()))}: a
     * {@link SelectTerm.ScalarSubselect.ParentColumnEquals} rendered off the parent table local's
     * own jOOQ instance, so the qualifier matches the enclosing statement's FROM clause exactly.
     * Both halves are {@link DiscriminatedTableFragments}'s mints
     * ({@link DiscriminatedTableFragments#discriminatorRef} for the reference,
     * {@link DiscriminatedTableFragments#discriminatorValue} for the typed operand): this gate is
     * the cross-table participant subselect's discriminator comparison, so it shares the
     * qualification argument and the bind typing with the assembly's other two comparison sites
     * rather than restating either.
     */
    private static CodeBlock parentColumnEquals(SelectTerm.ScalarSubselect.ParentColumnEquals gate,
            String parentLocal) {
        return CodeBlock.of("$L.eq($L)",
            DiscriminatedTableFragments.discriminatorRef(parentLocal, gate.column()),
            DiscriminatedTableFragments.discriminatorValue(parentLocal, gate.column(), gate.value()));
    }

    /**
     * Step-0 correlation against the parent: the sealed dispatch on the borrowed
     * {@link ParentCorrelation}. A filtered or condition-join first hop folds its correlation
     * into the two-argument condition-method call; a lateral routine first hop correlates
     * through its call arguments (the step-0 WHERE contributes nothing).
     */
    public static CodeBlock correlationWhere(ParentCorrelation correlation, String firstAlias,
            String parentLocal, String pathKindLabel) {
        return switch (correlation) {
            case ParentCorrelation.OnFkSlots fk ->
                JoinFragments.emitCorrelationWhere(fk.slots(), firstAlias, parentLocal);
            case ParentCorrelation.OnParentJoin pj ->
                hopZeroCorrelation(pj.firstHop(), firstAlias, parentLocal);
            case ParentCorrelation.OnLateralArgs ignored -> CodeBlock.of("$T.noCondition()", DSL);
            case ParentCorrelation.OnLiftedSlots ignored -> throw new IllegalStateException(
                "ParentCorrelation.OnLiftedSlots never reaches a " + pathKindLabel + " projection "
                + "arm; the pre-keyed lifted shape is DataLoader-batched through the rows methods");
        };
    }

    /**
     * The hop-0 correlation of a path walked back to its first hop: an exhaustive dispatch on the
     * hop's {@link On}, tying the first hop's alias back to the enclosing statement's parent
     * local. Column pairs correlate on the FK slots; a developer-supplied predicate correlates
     * through its two-argument call, {@code (parentLocal, firstAlias)}, the same
     * {@code (source, target)} convention {@link #appendHopFilters} and the bridging arms emit.
     *
     * <p>Both the projection rail ({@link #correlationWhere}'s
     * {@link ParentCorrelation.OnParentJoin} arm) and the filter rail's correlated {@code EXISTS}
     * come through here, so a future {@code On} arm has exactly one place to land. The lateral arm
     * throws rather than naming either caller: both of them reject a lateral hop at construction
     * ({@code ParentCorrelation.OnParentJoin}'s compact constructor and {@code ReachPath}'s), so
     * this is the backstop under two guards rather than a reachable diagnostic.
     */
    public static CodeBlock hopZeroCorrelation(JoinStep.Hop hop, String firstAlias, String parentLocal) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> JoinFragments.emitCorrelationWhere(cp, firstAlias, parentLocal);
            case On.Predicate pred -> emitTwoArgMethodCall(pred.condition(), parentLocal, firstAlias);
            case On.Lateral ignored -> throw new IllegalStateException(
                "a lateral routine hop cannot correlate a subquery back to its parent; its "
                + "correlation rides the call arguments, so it never reaches this dispatch");
        };
    }

    /**
     * Per-hop {@code filter()} condition-method calls appended to the enclosing WHERE. Takes the
     * path at the sealed root so both the projection rail's {@code List<JoinStep>} and the filter
     * rail's hop-typed reach can hand it their own list unchanged.
     */
    public static void appendHopFilters(CodeBlock.Builder where, List<? extends JoinStep> path,
            List<String> aliases, String parentLocal, String andFormat) {
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) instanceof JoinStep.Hop hop && hop.filter() != null) {
                String srcAlias = i == 0 ? parentLocal : aliases.get(i - 1);
                where.add(andFormat, emitTwoArgMethodCall(hop.filter(), srcAlias, aliases.get(i)));
            }
        }
    }

    /**
     * Emits a {@code <className>.<methodName>(srcAlias, tgtAlias)} invocation used by
     * {@link JoinStep.Hop#filter()} (added to the enclosing WHERE) and by
     * {@link On.Predicate#condition()} (used as the join ON clause). Takes the
     * {@link JoinConditionRef} wrapper directly: the two-argument calling convention is the
     * wrapper's contract.
     */
    public static CodeBlock emitTwoArgMethodCall(JoinConditionRef condition, String srcAlias, String tgtAlias) {
        var method = condition.method();
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(method.className()), method.methodName(), srcAlias, tgtAlias);
    }

    /**
     * {@link #emitTwoArgMethodCall(JoinConditionRef, String, String)} for a caller whose condition
     * arrived as a command row. One convention, two carriers of it: the row states which method,
     * this states how it is called.
     */
    public static CodeBlock emitTwoArgMethodCall(JoinCondition condition, String srcAlias,
            String tgtAlias) {
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(condition.className()), condition.methodName(), srcAlias, tgtAlias);
    }
}
