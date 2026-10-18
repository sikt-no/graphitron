package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.CallParam;

import java.util.Optional;

/**
 * One argument value a condition predicate consumes, paired with the producer-named glue body
 * local that holds it. The extraction itself is the borrowed {@link CallParam} (name, extraction
 * chain, list-ness, Java type), always rooted at the glue signature's argument map; the local
 * name is the producer's decision, qualified and collision-free within its method, so no
 * generated parameter list exists to collide on bare input-field names.
 *
 * <p>This pairs a borrowed ref with producer data, like {@link ReachPath} (borrowed model hops
 * plus the producer's one narrowing); it is not a cut {@code Binding} type that would copy the
 * extraction vocabulary.
 *
 * <p>{@code reachedTable} names the table the value's columns live on, which is what a read
 * lifting the value at a column's {@code DataType} reads that column off: empty means the glue's
 * own {@code table}, present means the terminal table of the term's reach, whose columns the own
 * table's class does not have. The producer decides it once, where it narrows the reach.
 */
public record ArgBinding(CallParam param, String localName, Optional<TableRef> reachedTable) {

    /** A binding whose columns are on the glue's own table. */
    public ArgBinding(CallParam param, String localName) {
        this(param, localName, Optional.empty());
    }

    public ArgBinding {
        if (param == null) {
            throw new IllegalArgumentException("an argument binding carries the borrowed call param");
        }
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("an argument binding requires a producer-named body local");
        }
        if (reachedTable == null) {
            throw new IllegalArgumentException("an argument binding states its reached table, empty for its own");
        }
    }

    /** This binding with its columns on {@code table}, the terminal table of a reach. */
    public ArgBinding reachedAt(TableRef table) {
        return new ArgBinding(param, localName, Optional.of(table));
    }
}
