package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.generated.util.ConnectionHelper;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page a producer builds a connection from, read off its over-fetched rows by the generated
 * {@code ConnectionHelper}: {@code trim} drops the lookahead row and puts a backward page back in
 * order, {@code hasNextPage} and {@code hasPreviousPage} answer from the lookahead row on the
 * paging side and from the cursor on the other. A producer fetches {@code pageSize + 1} rows, in
 * reverse order when paging backward, which is what the rows here stand for.
 */
@UnitTier
class PageFlagsTest {

    private static final Field<Integer> ID = DSL.field("id", Integer.class);

    /** {@code n} rows with ids 1..n, in the order the producer fetched them. */
    private static List<Record> rows(int... ids) {
        var dsl = DSL.using(SQLDialect.DEFAULT);
        return IntStream.of(ids).mapToObj(id -> {
            Record r = dsl.newRecord(ID);
            r.set(ID, id);
            return r;
        }).toList();
    }

    private static List<Integer> ids(List<Record> page) {
        return page.stream().map(r -> r.get(ID)).toList();
    }

    static Stream<Arguments> pages() {
        return Stream.of(
            // rows fetched, pageSize, after, backward, page ids, hasNext, hasPrevious
            Arguments.of("forward, a short page, no cursor", rows(1, 2), 3, null, false, List.of(1, 2), false, false),
            Arguments.of("forward, rows == pageSize", rows(1, 2, 3), 3, null, false, List.of(1, 2, 3), false, false),
            Arguments.of("forward, the lookahead row", rows(1, 2, 3, 4), 3, null, false, List.of(1, 2, 3), true, false),
            Arguments.of("forward after a cursor", rows(4, 5, 6), 3, "c", false, List.of(4, 5, 6), false, true),
            Arguments.of("forward after a cursor, the lookahead row", rows(4, 5, 6, 7), 3, "c", false, List.of(4, 5, 6), true, true),
            Arguments.of("forward, empty", rows(), 3, null, false, List.of(), false, false),
            Arguments.of("forward after a cursor, empty", rows(), 3, "c", false, List.of(), false, true),
            // Backward: the query ran in reverse order, so the rows arrive last first.
            Arguments.of("backward, before a cursor only, a short page", rows(2, 1), 3, null, true, List.of(1, 2), false, false),
            Arguments.of("backward, rows == pageSize", rows(3, 2, 1), 3, null, true, List.of(1, 2, 3), false, false),
            Arguments.of("backward, the lookahead row", rows(9, 8, 7, 6), 3, null, true, List.of(7, 8, 9), false, true),
            Arguments.of("backward with an after cursor", rows(9, 8, 7, 6), 3, "c", true, List.of(7, 8, 9), true, true),
            Arguments.of("backward, empty", rows(), 3, null, true, List.of(), false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pages")
    void thePageAndItsFlags(String name, List<Record> fetched, int pageSize, String after, boolean backward,
            List<Integer> page, boolean hasNext, boolean hasPrevious) {
        assertThat(ids(ConnectionHelper.trim(fetched, pageSize, backward))).as("page").isEqualTo(page);
        assertThat(ConnectionHelper.hasNextPage(fetched, pageSize, after, backward)).as("hasNextPage").isEqualTo(hasNext);
        assertThat(ConnectionHelper.hasPreviousPage(fetched, pageSize, after, backward)).as("hasPreviousPage")
            .isEqualTo(hasPrevious);
    }
}
