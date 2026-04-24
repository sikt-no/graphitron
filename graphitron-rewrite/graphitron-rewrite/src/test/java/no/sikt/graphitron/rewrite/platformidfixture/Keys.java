package no.sikt.graphitron.rewrite.platformidfixture;

import no.sikt.graphitron.rewrite.platformidfixture.tables.Bar;
import no.sikt.graphitron.rewrite.platformidfixture.tables.Baz;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.BarRecord;
import no.sikt.graphitron.rewrite.platformidfixture.tables.records.BazRecord;

import org.jooq.ForeignKey;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;
import org.jooq.impl.QOM.ForeignKeyRule;

/**
 * FK constraint definitions for the platformid test fixture.
 *
 * <p>Defines the single FK used by NodeIdReferenceField pipeline tests:
 * {@code bar.id_1 → baz.id} (i.e. {@link Bar} references {@link Baz}).
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class Keys {

    public static final UniqueKey<BazRecord> BAZ_PKEY =
        Internal.createUniqueKey(Baz.BAZ, DSL.name("baz_pkey"),
            new TableField[] { Baz.BAZ.ID }, true);

    public static final ForeignKey<BarRecord, BazRecord> BAR__BAR_ID_1_FKEY =
        Internal.createForeignKey(Bar.BAR, DSL.name("bar_id_1_fkey"),
            new TableField[] { Bar.BAR.ID_1 },
            Keys.BAZ_PKEY, new TableField[] { Baz.BAZ.ID },
            true, ForeignKeyRule.NO_ACTION, ForeignKeyRule.NO_ACTION);
}
