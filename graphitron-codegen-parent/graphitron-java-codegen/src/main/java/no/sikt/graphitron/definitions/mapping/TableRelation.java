package no.sikt.graphitron.definitions.mapping;

import no.sikt.graphitron.generators.context.JoinListSequence;

import static no.sikt.graphitron.mappings.TableReflection.inferRelationType;

public class TableRelation {
    private final JOOQMapping from, toTable, key;
    private final TableRelationType relationType;

    public TableRelation(JOOQMapping from, JOOQMapping toTable) {
        this(from, toTable, null);
    }

    public TableRelation(JOOQMapping from, JOOQMapping toTable, JOOQMapping key) {
        this.from = from;
        this.toTable = toTable;
        this.key = key;

        // For cases where relations exist both ways, we need a way to know which one is intended.
        relationType = from != null && toTable != null
                ? inferRelationType(from.getMappingName(), toTable.getMappingName(), key)
                : TableRelationType.NONE;
    }

    public JoinListSequence inferJoinStep(JoinListSequence sequence) {
        if (sequence.isEmpty()) {
            return inferJoinStep();
        }

        switch (relationType) {
            case IMPLICIT:
                return sequence.cloneAdd(toTable);
            case KEY:
                if (key != null) {
                    return sequence.cloneAdd(key);
                }
            default:
                return sequence; // Needs join or some other more advanced inference.
        }
    }

    private JoinListSequence inferJoinStep() {
        switch (relationType) {
            case IMPLICIT:
                return JoinListSequence.of(from, toTable);
            case KEY:
                if (key != null) {
                    return JoinListSequence.of(from, key);
                }
            default:
                return JoinListSequence.of(from);
        }
    }
    public JOOQMapping getFrom() {
        return from;
    }

    public JOOQMapping getToTable() {
        return toTable;
    }

    public TableRelationType getRelationType() {
        return relationType;
    }

    public boolean hasRelation() {
        return relationType != TableRelationType.NONE;
    }

    public JOOQMapping getKey() {
        return key;
    }

    public boolean isReverse() {
        return relationType == TableRelationType.REVERSE_IMPLICIT || relationType == TableRelationType.REVERSE_KEY;
    }
}
