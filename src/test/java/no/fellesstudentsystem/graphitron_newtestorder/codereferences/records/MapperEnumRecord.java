package no.fellesstudentsystem.graphitron_newtestorder.codereferences.records;

import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyJOOQEnum;

import java.util.List;

public class MapperEnumRecord {
    private DummyJOOQEnum enum1;
    private String enum2;
    private List<DummyJOOQEnum> enum1List;
    private List<String> enum2List;

    public DummyJOOQEnum getEnum1() {
        return enum1;
    }

    public void setEnum1(DummyJOOQEnum enum1) {
        this.enum1 = enum1;
    }

    public String getEnum2() {
        return enum2;
    }

    public void setEnum2(String enum2) {
        this.enum2 = enum2;
    }

    public List<DummyJOOQEnum> getEnum1List() {
        return enum1List;
    }

    public void setEnum1List(List<DummyJOOQEnum> enum1List) {
        this.enum1List = enum1List;
    }

    public List<String> getEnum2List() {
        return enum2List;
    }

    public void setEnum2List(List<String> enum2List) {
        this.enum2List = enum2List;
    }
}
