package no.sikt.graphitron.record.field;

/**
 * One resolved step in a {@code @reference} path, corresponding to one {@code ReferenceElement}
 * in the schema.
 *
 * <p>The sealed hierarchy distinguishes four valid states and two error states:
 * <ul>
 *   <li>{@link FkStep} — a jOOQ FK was resolved; no condition.</li>
 *   <li>{@link FkWithConditionStep} — a jOOQ FK was resolved; a condition method was also resolved.</li>
 *   <li>{@link ConditionOnlyStep} — a condition method was resolved; no FK (lift conditions).</li>
 *   <li>{@link UnresolvedKeyStep} — a key name was specified but could not be found in the jOOQ catalog.</li>
 *   <li>{@link UnresolvedConditionStep} — a condition was specified but the method could not be resolved via reflection.</li>
 *   <li>{@link UnresolvedKeyAndConditionStep} — both a key name and a condition were specified, but neither could be resolved.</li>
 * </ul>
 *
 * <p>The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedKeyStep} and {@code UnresolvedConditionStep}. The valid variants are consumed
 * by the code generator.
 */
public sealed interface ReferencePathElement
    permits FkStep, FkWithConditionStep, ConditionOnlyStep,
            UnresolvedKeyStep, UnresolvedConditionStep, UnresolvedKeyAndConditionStep {}
