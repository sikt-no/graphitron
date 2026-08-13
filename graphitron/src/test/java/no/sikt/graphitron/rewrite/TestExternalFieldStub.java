package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.jooq.tables.Film;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.ActorRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import org.jooq.Field;
import org.jooq.Table;

/**
 * Minimal external-field stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @externalField} fields are correctly classified when the referenced method
 * exists on the classpath.
 *
 * <p>Each method's signature follows the {@code @externalField} contract:
 * <pre>
 *     public static Field&lt;X&gt; methodName(&lt;ParentTable&gt; table)
 * </pre>
 * The classifier-time return-type strictness check in
 * {@code ServiceCatalog.reflectExternalField} requires the return type's parameterised
 * scalar to match the GraphQL field's runtime Java type, and the parameter to be
 * assignable from the parent's jOOQ generated {@code Table<?>} class.
 *
 * <p>Bodies throw {@link UnsupportedOperationException} because these stubs are
 * reflected against (signature only) by classifier-tier tests; they are never executed.
 *
 * <p>Requires the {@code -parameters} compiler flag (the project's {@code pom.xml}
 * already sets this flag).
 */
class TestExternalFieldStub {

    /** Returns {@code Field<String>} — used by the canonical SCALAR_RETURN fixture. */
    public static Field<String> rating(Film table) {
        throw new UnsupportedOperationException();
    }

    /** Returns {@code Field<Boolean>} — used for Boolean-typed fields. */
    public static Field<Boolean> isEnglish(Film table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Wider return type — returns {@code Field<Integer>}. Used to exercise the
     * classifier's strict-return-type rejection path against a {@code String}-typed
     * GraphQL field.
     */
    public static Field<Integer> badType(Film table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Wrong parameter type — takes a {@code String} instead of a {@code Table<?>}.
     * Used to exercise the classifier's parameter-type rejection path.
     */
    public static Field<String> wrongParam(String notATable) {
        throw new UnsupportedOperationException();
    }

    /**
     * Wrong return wrapper — returns {@code String} instead of {@code Field<String>}.
     * Used to exercise the classifier's return-type rejection path.
     */
    public static String notAField(Film table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Non-static — instance method. Used to exercise the classifier's static-modifier
     * rejection path.
     */
    public Field<String> nonStatic(Film table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Widened parameter — accepts any table, so it is assignable from every parent. Used as the
     * over-rejection guard for the table-identity layer of the parent-table check: a
     * {@code Table<?>} parameter resolves to no catalog entry and must classify clean.
     */
    public static Field<String> anyTable(Table<?> table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Parameterised parameter naming the {@code film} record type. Assignable from the generated
     * {@code Film} (which implements {@code Table<FilmRecord>}), so it must classify clean from a
     * {@code film} parent — the accept side of the record-type layer.
     */
    public static Field<String> filmRecordTable(Table<FilmRecord> table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Parameterised parameter naming a <em>different</em> table's record type. Not assignable
     * from the generated {@code Film}, so it must be rejected from a {@code film} parent — the
     * reject side of the record-type layer, which the erased table-identity layer cannot see.
     */
    public static Field<String> actorRecordTable(Table<ActorRecord> table) {
        throw new UnsupportedOperationException();
    }
}
