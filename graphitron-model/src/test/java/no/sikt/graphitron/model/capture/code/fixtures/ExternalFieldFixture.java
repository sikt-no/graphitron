package no.sikt.graphitron.model.capture.code.fixtures;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;

/**
 * A stand-in for the lifters {@code @externalField(reference:)} names, carrying one method per
 * clause of that directive's contract so the arm's admission can be pinned against each.
 *
 * <p>{@link #notATable} is the one that matters most. It takes one argument and returns a
 * {@code Field}, which is the shape an editor with no relation to ask has to settle for, and it is
 * not a lifter: its argument is not a table, so the generator refuses it. A relation that states
 * the contract tells the two apart where a shape cannot.
 */
public final class ExternalFieldFixture {

    private ExternalFieldFixture() {}

    /** A generated table class's stand-in: what a consumer's lifter actually takes. */
    public static final class FixtureTable extends TableImpl<Record> {

        /** A generated table is serializable, so a stand-in for one has to be too. */
        private static final long serialVersionUID = 1L;

        public FixtureTable() {
            super(DSL.name("fixture"));
        }
    }

    /** The ordinary shape, reaching Table through a superclass chain rather than directly. */
    public static Field<String> titleUpper(FixtureTable table) {
        return DSL.field(DSL.name("title"), String.class);
    }

    /** The same contract satisfied at the interface itself, which is the other way to write it. */
    public static Field<String> byInterface(Table<?> table) {
        return DSL.field(DSL.name("title"), String.class);
    }

    /** One argument in, a Field out, and not a lifter: the argument is not a table. */
    public static Field<String> notATable(String name) {
        return DSL.field(DSL.name(name), String.class);
    }

    /** A table in, and not a Field out. */
    public static String notAField(FixtureTable table) {
        return table.getName();
    }

    /** The contract's shape, but an instance method, which the generator refuses outright. */
    public Field<String> notStatic(FixtureTable table) {
        return DSL.field(DSL.name("title"), String.class);
    }

    /** A lifter takes the table and nothing else. */
    public static Field<String> twoParameters(FixtureTable table, String suffix) {
        return DSL.field(DSL.name("title"), String.class);
    }
}
