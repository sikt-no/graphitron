package no.sikt.graphitron.model.capture.jooq.handwritten;

import no.sikt.graphitron.model.capture.jooq.handwritten.hand.Hand;
import org.jooq.Schema;
import org.jooq.impl.CatalogImpl;

import java.util.List;

/**
 * A catalog written by hand in the shape jOOQ generates one, for what the fixture catalog cannot
 * show: its records are generated with the default strategy and lower-case names, so every getter
 * is where the default rule looks for it. See {@code JooqRecordAccessorCaptureTest}.
 */
public class DefaultCatalog extends CatalogImpl {

    private static final long serialVersionUID = 1L;

    public static final DefaultCatalog DEFAULT_CATALOG = new DefaultCatalog();

    private DefaultCatalog() {
        super("");
    }

    @Override
    public final List<Schema> getSchemas() {
        return List.of(Hand.HAND);
    }
}
