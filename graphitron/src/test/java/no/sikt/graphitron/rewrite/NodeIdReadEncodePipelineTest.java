package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ValueLocator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @nodeId} on an output field whose value is <em>read</em> rather than projected.
 *
 * <p>A read is every output field graphitron locates on the parent's in-memory source object: a
 * typed column off a jOOQ table record, an untyped by-name read off a record carrier, or an
 * accessor on a backing class. None of them is a SELECT-side projection, so none of them used to
 * carry a wire direction at all, and the directive was inert at all of them alike. The invariant
 * this pins is the one the directive exists for: a slot carrying the instruction never delivers
 * the raw key where the author asked for an id, and which of the three mechanisms produced the
 * value is not part of that question. So the three arms are asserted as a set, each with a
 * locator and an encode, and the negative control beside them is the same coordinate without the
 * directive, which must stay a plain read.
 *
 * <p>The refusals are the read's own two preconditions rather than a directive grammar.
 * {@code encode<TypeName>} takes the key positionally and a read yields one value, so a composite
 * key cannot be encoded from one; and the value the read yields has to be the key column's own,
 * because encoding a coerced value would put an id on the wire that decodes to something the
 * database does not hold. Each is pinned where its operand lives: the accessor arm's type is the
 * resolver's to compare, the column arm's is the catalog's.
 *
 * <p>Sakila rather than the nodeidfixture catalog, because every operand here is a Java type and
 * the fixtures that carry one ({@code DummyFetcherFixtures}, the generated {@code FilmRecord})
 * are written against sakila's columns.
 *
 * <p>One coordinate is deliberately not here, and knowing which keeps the fixtures readable: an
 * {@code ID} field on a producer <em>carrier</em> parent (a type a {@code @service} binds directly
 * to a jOOQ table record) is claimed by {@code ChildField.SingleRecordIdField}, which has encoded
 * the whole key tuple off that record since before this leaf carried a direction at all. So the
 * typed-column case below reaches its record through a parent accessor instead, which is the shape
 * where the read arm genuinely owns the coordinate.
 */
@PipelineTier
class NodeIdReadEncodePipelineTest {

    private static final String SERVICE = "no.sikt.graphitron.codereferences.dummyreferences.DummyService";

    /** Film's node key is one column, {@code film_id}, binding as {@code java.lang.Integer}. */
    private static final String FILM_NODE = """
        type Film implements Node @table(name: "film") @node { id: ID! }
        """;

    /** A two-column node key: {@code film_actor}'s primary key is (actor_id, film_id). */
    private static final String CAST_NODE = """
        type FilmCast implements Node @table(name: "film_actor") @node { id: ID! }
        """;

    /**
     * A jOOQ table record reached through a parent accessor. The producer returns the holder, not
     * the record, so {@code FilmRow} binds table-record-backed without being a producer carrier:
     * the carrier leaf claims an {@code ID} field on a carrier and this is where the read arm
     * actually owns one.
     */
    private static final String FILM_RECORD_HOLDER = FILM_NODE + """
        type FilmRow {
        %s
        }
        type Holder { film: FilmRow }
        type Query {
            holder: Holder @service(service: {className: "%s", method: "makeFilmRecordHolder"})
        }
        """.formatted("%s", SERVICE);

    private static String schemaFor(String readType, String producer) {
        return FILM_NODE + readType + """
            type Query {
                read: Reader @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(SERVICE, producer);
    }

    // ===== The three read arms, each encoding =====

    @Test
    void accessorRead_encodesWhatTheAccessorYields() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
            type Reader {
                filmRef: ID @nodeId(typeName: "Film") @field(name: "filmKey")
            }
            """, "makeFilmKeyHolder"));

        var read = recordRead(schema, "Reader", "filmRef");
        assertThat(read.locator())
            .as("a class-backed parent locates the value through its accessor")
            .isInstanceOf(ValueLocator.JavaAccessor.class);
        assertThat(encoderOf(read)).isEqualTo("encodeFilm");
    }

    @Test
    void typedColumnRead_encodesTheColumnOffTheRecord() {
        var schema = TestSchemaHelper.buildSchema(FILM_RECORD_HOLDER.formatted("""
                filmRef: ID @nodeId(typeName: "Film") @field(name: "film_id")
            """));

        var read = recordRead(schema, "FilmRow", "filmRef");
        assertThat(read.locator())
            .as("a jOOQ table-record parent resolves the read to a typed column constant")
            .isInstanceOf(ValueLocator.TypedColumn.class);
        assertThat(((ValueLocator.TypedColumn) read.locator()).column().sqlName()).isEqualTo("film_id");
        assertThat(encoderOf(read)).isEqualTo("encodeFilm");
    }

    @Test
    void byNameRead_encodesTheUntypedRecordRead() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
            type Reader {
                filmRef: ID @nodeId(typeName: "Film") @field(name: "film_id")
            }
            """, "makePlainJooqRecord"));

        var read = recordRead(schema, "Reader", "filmRef");
        assertThat(read.locator())
            .as("a non-table-bound jOOQ record carrier resolves no typed constant, so the read is by name")
            .isInstanceOf(ValueLocator.ByName.class);
        assertThat(encoderOf(read)).isEqualTo("encodeFilm");
    }

    /**
     * The negative control. Without the directive the same coordinate on the same fixture is a
     * plain read, so the three cases above are pinning the directive's effect and not the arm's.
     */
    @Test
    void theSameReadWithoutTheDirective_staysDirect() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
            type Reader {
                filmRef: Int @field(name: "filmKey")
            }
            """, "makeFilmKeyHolder"));

        assertThat(recordRead(schema, "Reader", "filmRef").compaction())
            .isInstanceOf(CallSiteCompaction.Direct.class);
    }

    // ===== The two preconditions =====

    @Test
    void aCompositeKeyAtAReadCoordinate_isRefusedNamingTheCount() {
        var schema = TestSchemaHelper.buildSchema(FILM_NODE + CAST_NODE + """
            type Reader {
                castRef: ID @nodeId(typeName: "FilmCast") @field(name: "filmKey")
            }
            type Query {
                read: Reader @service(service: {className: "%s", method: "makeFilmKeyHolder"})
            }
            """.formatted(SERVICE));

        var f = (UnclassifiedField) schema.field("Reader", "castRef");
        assertThat(f.reason())
            .contains("FilmCast")
            .contains("key of 2 columns")
            .contains("actor_id")
            .contains("film_id");
    }

    @Test
    void anAccessorYieldingAnotherType_isRefusedNamingBoth() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
            type Reader {
                filmRef: ID @nodeId(typeName: "Film") @field(name: "filmLabel")
            }
            """, "makeFilmKeyHolder"));

        var f = (UnclassifiedField) schema.field("Reader", "filmRef");
        assertThat(f.reason())
            .as("the key column's type is the expectation and the accessor's is what it found")
            .contains("key column 'film_id' of type java.lang.Integer")
            .contains("return type String is not assignable to Integer");
    }

    @Test
    void aColumnBindingAnotherType_isRefusedNamingBoth() {
        var schema = TestSchemaHelper.buildSchema(FILM_RECORD_HOLDER.formatted("""
                filmRef: ID @nodeId(typeName: "Film") @field(name: "title")
            """));

        var f = (UnclassifiedField) schema.field("FilmRow", "filmRef");
        assertThat(f.reason())
            .as("the column arm compares the catalog's two binding types, no accessor involved")
            .contains("key column 'film_id' of type java.lang.Integer")
            .contains("column 'title' on this record binds as java.lang.String");
    }

    /**
     * Bare {@code @nodeId} inherits its node from the enclosing type, and a read-backed type
     * stands for no table, so nothing here could ever supply one. A distinct refusal from the
     * column-backed arm's, which is about a parent that could have carried {@code @node} and does
     * not.
     */
    @Test
    void bareNodeIdOnAReadCoordinate_isRefusedNamingTheArgumentThatWouldWork() {
        var schema = TestSchemaHelper.buildSchema(schemaFor("""
            type Reader {
                filmRef: ID @nodeId @field(name: "filmKey")
            }
            """, "makeFilmKeyHolder"));

        var f = (UnclassifiedField) schema.field("Reader", "filmRef");
        assertThat(f.reason())
            .contains("stands for no table")
            .contains("typeName:");
    }

    // ===== Fixture =====

    private static ChildField.RecordReadField recordRead(
            GraphitronSchema schema, String typeName, String fieldName) {
        var field = schema.field(typeName, fieldName);
        assertThat(field)
            .as("%s.%s classifies as a record read", typeName, fieldName)
            .isInstanceOf(ChildField.RecordReadField.class);
        return (ChildField.RecordReadField) field;
    }

    /** The encode helper the leaf's compaction names, which is the whole of what it emits. */
    private static String encoderOf(ChildField.RecordReadField read) {
        assertThat(read.compaction())
            .as("the read carries an encode rather than delivering the key")
            .isInstanceOf(CallSiteCompaction.NodeIdEncodeKeys.class);
        return ((CallSiteCompaction.NodeIdEncodeKeys) read.compaction()).encodeMethod().methodName();
    }
}
