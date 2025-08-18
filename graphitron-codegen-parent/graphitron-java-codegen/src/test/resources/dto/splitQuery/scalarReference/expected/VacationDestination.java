package no.sikt.graphitron.example.generated.graphitron.model;

import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.util.Objects;
import org.jooq.Record1;
import org.jooq.Row1;

public class VacationDestination {
    private Row1<Long> vacation_destination_vacation_fkey;

    private Row1<Long> vacationDescriptionKey;

    public VacationDestination() {
    }

    public VacationDestination(Record1<Long> vacation_destination_vacation_fkey) {
        this.vacation_destination_vacation_fkey = vacation_destination_vacation_fkey.valuesRow();
        this.vacationDescriptionKey = vacation_destination_vacation_fkey.valuesRow();
    }

    public Row1<Long> getVacation_destination_vacation_fkey() {
        return vacation_destination_vacation_fkey;
    }

    public Row1<Long> getVacationDescriptionKey() {
        return vacationDescriptionKey;
    }

    public void setVacationDescriptionKey(Row1<Long> vacationDescriptionKey) {
        this.vacationDescriptionKey = vacationDescriptionKey;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vacation_destination_vacation_fkey, vacationDescriptionKey);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final VacationDestination that = (VacationDestination) obj;
        return Objects.equals(vacation_destination_vacation_fkey, that.vacation_destination_vacation_fkey) && Objects.equals(vacationDescriptionKey, that.vacationDescriptionKey);
    }
}
