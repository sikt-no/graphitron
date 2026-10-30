package no.sikt.graphitron.model.capture.jooq.handwritten.hand;

import no.sikt.graphitron.model.capture.jooq.handwritten.DefaultCatalog;
import org.jooq.Catalog;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SchemaImpl;

import java.util.List;

/** The hand-written catalog's one schema. */
public class Hand extends SchemaImpl {

    private static final long serialVersionUID = 1L;

    public static final Hand HAND = new Hand();

    private Hand() {
        super(DSL.name("HAND"), null);
    }

    @Override
    public Catalog getCatalog() {
        return DefaultCatalog.DEFAULT_CATALOG;
    }

    @Override
    public final List<Table<?>> getTables() {
        return List.of(Tables.PERSONBILDE, Tables.RENAMED);
    }
}
