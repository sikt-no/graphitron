package no.sikt.graphitron.rewrite.platformidfixture;

import java.util.List;

import org.jooq.Schema;
import org.jooq.impl.CatalogImpl;

/**
 * Minimal jOOQ catalog used exclusively by platform-id pipeline tests. The layout mirrors the
 * stock jOOQ generator's output ({@code DefaultCatalog}/{@code Public}/{@code Tables}) so that
 * {@link no.sikt.graphitron.rewrite.JooqCatalog#loadDefaultCatalog} can load it by
 * reflection when tests configure the jOOQ package to this package.
 *
 * <p>Contains a single table {@link no.sikt.graphitron.rewrite.platformidfixture.tables.Bar}
 * that adds legacy platform-id accessor methods ({@code getId()} / {@code getPersonId()})
 * to the table class, and a paired
 * {@link no.sikt.graphitron.rewrite.platformidfixture.tables.records.BarRecord} with the
 * corresponding {@code get*Id()} / {@code set*Id(String)} record-level accessors.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class DefaultCatalog extends CatalogImpl {

    private static final long serialVersionUID = 1L;

    public static final DefaultCatalog DEFAULT_CATALOG = new DefaultCatalog();

    public final Public PUBLIC = Public.PUBLIC;

    private DefaultCatalog() {
        super("");
    }

    @Override
    public final List<Schema> getSchemas() {
        return List.of(Public.PUBLIC);
    }
}
