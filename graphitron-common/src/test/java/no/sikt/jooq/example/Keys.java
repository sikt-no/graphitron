package no.sikt.jooq.example;

import org.jooq.ForeignKey;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;

@SuppressWarnings({"all", "unchecked", "rawtypes"})
public class Keys {
    public static final UniqueKey<VacationRecord> VACATION_PKEY = Internal.createUniqueKey(Vacation.VACATION, DSL.name("vacation_pkey"), new TableField[]{Vacation.VACATION.VACATION_ID}, true);
    public static final UniqueKey<VacationDestinationRecord> VACATION_DESTINATION_PKEY = Internal.createUniqueKey(VacationDestination.VACATION_DESTINATION, DSL.name("vacation_destination_pkey"), new TableField[]{VacationDestination.VACATION_DESTINATION.DESTINATION_ID, VacationDestination.VACATION_DESTINATION.COUNTRY_NAME}, true);

    public static final ForeignKey<VacationDestinationRecord, VacationRecord> VACATION_DESTINATION__VACATION_DESTINATION_VACATION_FKEY = Internal.createForeignKey(VacationDestination.VACATION_DESTINATION, DSL.name("vacation_destination_vacation_fkey"), new TableField[]{VacationDestination.VACATION_DESTINATION.VACATION_ID, VacationDestination.VACATION_DESTINATION.EXTRA_KEY}, Keys.VACATION_PKEY, new TableField[]{Vacation.VACATION.VACATION_ID, Vacation.VACATION.EXTRA_KEY}, true);
}
