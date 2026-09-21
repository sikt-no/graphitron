package no.sikt.graphitron.model.capture.code.fixtures;

import org.jooq.DSLContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A stand-in for the code {@code @service(service:)} names, carrying one method per shape the
 * delivery walk has to answer for.
 *
 * <p>What a service method delivers is the question every reader of a producing method asks: a
 * field backed by one is backed by whatever the method finally hands back, and a return type is a
 * tree rather than a name. So the methods here are a list of containers to peel rather than a list
 * of services anybody would write.
 */
public final class ServiceFixture {

    private ServiceFixture() {}

    /** The ordinary shape: a context in, a list of something out. */
    public static List<String> manyStrings(DSLContext dsl, String rating) {
        return List.of();
    }

    /** One value in a wrapper, which is one value. */
    public static Optional<String> maybeAString(DSLContext dsl) {
        return Optional.empty();
    }

    /** A map delivers its value and not its key, which is why the vocabulary carries an index. */
    public static Map<Integer, String> stringsByKey(DSLContext dsl) {
        return Map.of();
    }

    /** No container at all. */
    public static String oneString(DSLContext dsl) {
        return "";
    }

    /** A container whose payload position names nothing, so the walk stops at the container. */
    public static List<?> anythingAtAll(DSLContext dsl) {
        return List.of();
    }

    /** A primitive names no class, so there is nothing delivered to record. */
    public static int aCount(DSLContext dsl) {
        return 0;
    }

    /** Neither does a void. */
    public static void nothingAtAll(DSLContext dsl) {
    }

    /**
     * Five containers deep. The rule spelled in SQL peels four and reports the fifth container;
     * a walk at capture has no such bound and reports what the source actually named.
     */
    public static CompletableFuture<Optional<List<Set<Collection<String>>>>> deeplyNested(
            DSLContext dsl) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /** A parameter is asked the same question as a return. */
    public static String fromManyInputs(DSLContext dsl, List<String> inputs) {
        return "";
    }

    /** A checked exception, which a field naming this method owes an @error handler for. */
    public static String mayFail(DSLContext dsl) throws java.io.IOException {
        return "";
    }

    /** Takes a record, which is what reaches it: a class is constructible because something is passed one. */
    public static String describeCard(DSLContext dsl, SlotRecord card) {
        return card.title();
    }

    /** Takes a bean whose members are partly inherited, which is what reaches it. */
    public static String describeChild(DSLContext dsl, BeanChild child) {
        return child.getTitle();
    }

    /** And one nothing can make, which is what reaches it without making it constructible. */
    public static String describeHolder(DSLContext dsl, AbstractHolder holder) {
        return holder.toString();
    }
}
