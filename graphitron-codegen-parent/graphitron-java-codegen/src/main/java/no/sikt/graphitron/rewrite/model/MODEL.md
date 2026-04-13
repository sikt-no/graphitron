# Rewrite Model — Visual Reference

Colour legend:

| Symbol | Meaning |
|---|---|
| 🔴 Red / bold | Core sealed interfaces — structural backbone |
| 🟢 Teal | `TableTargetField` group — primary SQL-generation abstraction |
| 🔵 Blue | `QueryField` / `MutationField` — entry-point fields on root types |
| 🟩 Green | `GraphitronType` variants |
| 🟣 Purple | Support / composition types |
| ⚫ Dark grey | Value / leaf types — stable, rarely changed |
| 🟠 Orange dashed border | Model gap — not yet modelled |

---

## 1. Field Hierarchy

```mermaid
graph LR
    classDef core  fill:#922B21,stroke:#7B241C,color:#fff,font-weight:bold
    classDef ttf   fill:#0E6655,stroke:#0A5344,color:#fff,font-weight:bold
    classDef rootf fill:#1A5276,stroke:#154360,color:#fff
    classDef val   fill:#4A5568,stroke:#2C3E50,color:#fff

    GF["GraphitronField\n«sealed»"]:::core

    GF --> RF["RootField\n«sealed»"]:::rootf
    GF --> CF["ChildField\n«sealed»"]:::core
    GF --> INPF["InputField\n«sealed»"]:::rootf
    GF --> UNF["UnclassifiedField"]:::val
    GF --> NGF["NotGeneratedField"]:::val

    RF --> QF["QueryField\n«sealed»"]:::rootf
    RF --> MF["MutationField\n«sealed»"]:::rootf

    INPF --> InpCF["ColumnField"]:::rootf

    CF --> TTF["TableTargetField\n«sealed»"]:::ttf
    CF --> ColF["ColumnField"]:::core
    CF --> CRF["ColumnReferenceField"]:::core
    CF --> NIF["NodeIdField"]:::core
    CF --> NIRF["NodeIdReferenceField"]:::core
    CF --> TMF["TableMethodField"]:::core
    CF --> NstF["NestingField"]:::core
    CF --> IFld["InterfaceField"]:::core
    CF --> UnFld["UnionField"]:::core
    CF --> CnsF["ConstructorField"]:::core
    CF --> SvcRF["ServiceRecordField"]:::core
    CF --> RecF["RecordField"]:::core
    CF --> CmpF["ComputedField"]:::core
    CF --> PropF["PropertyField"]:::core
    CF --> MtRF["MultitableReferenceField"]:::core
```

---

## 2. Root Field Variants

```mermaid
graph LR
    classDef rootf fill:#1A5276,stroke:#154360,color:#fff
    classDef ttf   fill:#0E6655,stroke:#0A5344,color:#fff,font-weight:bold

    QF["QueryField\n«sealed»"]:::rootf
    QF --> QTF["QueryTableField\n+ condition"]:::rootf
    QF --> QLF["QueryLookupTableField\n+ condition"]:::rootf
    QF --> QTI["QueryTableInterfaceField\n+ condition"]:::rootf
    QF --> QTMF["QueryTableMethodField"]:::rootf
    QF --> QNF["QueryNodeField"]:::rootf
    QF --> QEF["QueryEntityField"]:::rootf
    QF --> QIF["QueryInterfaceField"]:::rootf
    QF --> QUF["QueryUnionField"]:::rootf
    QF --> QSTF["QueryServiceTableField"]:::rootf
    QF --> QSRF["QueryServiceRecordField"]:::rootf

    MF["MutationField\n«sealed»"]:::rootf
    MF --> MIF["MutationInsertTableField"]:::rootf
    MF --> MUF["MutationUpdateTableField"]:::rootf
    MF --> MDF["MutationDeleteTableField"]:::rootf
    MF --> MUpF["MutationUpsertTableField"]:::rootf
    MF --> MSTF["MutationServiceTableField"]:::rootf
    MF --> MSRF["MutationServiceRecordField"]:::rootf

    TTF["TableTargetField\n«sealed»"]:::ttf
    TTF --> TF["TableField\n+ arguments"]:::ttf
    TTF --> STF["SplitTableField\n+ arguments"]:::ttf
    TTF --> LF["LookupTableField\n+ arguments"]:::ttf
    TTF --> SLF["SplitLookupTableField\n+ arguments"]:::ttf
    TTF --> TIF["TableInterfaceField"]:::ttf
    TTF --> SVCTF["ServiceTableField\n+ arguments · method"]:::ttf
    TTF --> RTF["RecordTableField\n+ arguments"]:::ttf
    TTF --> RLF["RecordLookupTableField\n+ arguments"]:::ttf
```

All `TableTargetField` variants carry `returnType · joinPath · condition`.
`QueryTableField`, `QueryLookupTableField`, and `QueryTableInterfaceField` carry `returnType · condition · arguments` (no `joinPath` — no parent table to navigate from).

---

## 3. Type Hierarchy

```mermaid
graph LR
    classDef core  fill:#922B21,stroke:#7B241C,color:#fff,font-weight:bold
    classDef typeh fill:#145A32,stroke:#196F3D,color:#fff
    classDef val   fill:#4A5568,stroke:#2C3E50,color:#fff

    GT["GraphitronType\n«sealed»"]:::core

    GT --> TBT["TableBackedType\n«sealed»"]:::typeh
    TBT --> TT["TableType"]:::typeh
    TBT --> NT["NodeType"]:::typeh
    TBT --> TIT["TableInterfaceType"]:::typeh

    GT --> ResT["ResultType\n«sealed»"]:::typeh
    ResT --> JRT["JavaRecordType"]:::typeh
    ResT --> PRT["PojoResultType"]:::typeh
    ResT --> JRRT["JooqRecordType"]:::typeh
    ResT --> JTRT["JooqTableRecordType"]:::typeh

    GT --> InpT["InputType\n«sealed»"]:::typeh
    InpT --> JRIT["JavaRecordInputType"]:::typeh
    InpT --> PIT["PojoInputType"]:::typeh
    InpT --> JooqRI["JooqRecordInputType"]:::typeh
    InpT --> JTRIT["JooqTableRecordInputType"]:::typeh

    GT --> RootT["RootType"]:::typeh
    GT --> IntT["InterfaceType"]:::typeh
    GT --> UnT["UnionType"]:::typeh
    GT --> ErrT["ErrorType"]:::typeh
    GT --> TInpT["TableInputType"]:::typeh
    GT --> UncT["UnclassifiedType"]:::typeh
```

---

## 4. Support / Composition Types

```mermaid
graph LR
    classDef sup fill:#6C3483,stroke:#5B2C6F,color:#fff
    classDef val fill:#4A5568,stroke:#2C3E50,color:#fff
    classDef gap stroke:#D35400,stroke-dasharray:5 3

    RTR["ReturnTypeRef\n«sealed»"]:::sup
    RTR --> TBRT["TableBoundReturnType"]:::sup
    RTR --> PolRT["PolymorphicReturnType"]:::sup
    RTR --> ResRTR["ResultReturnType"]:::sup
    RTR --> ScRTR["ScalarReturnType"]:::sup

    AR["ArgumentRef\n«sealed»"]:::sup
    AR --> MPA["MethodParamArg\n«sealed»"]:::sup
    MPA --> SPA["ScalarParamArg"]:::sup
    MPA --> OPA["ObjectParamArg"]:::sup
    AR --> TA["TableArg\n«sealed»"]:::sup
    TA --> CFA["ColumnFilterArg"]:::gap
    TA --> IFA["InputFilterArg"]:::gap
    TA --> OBA["OrderByArg"]:::sup
    TA --> PagA["First / Last\nAfter / Before"]:::sup

    FW["FieldWrapper\n«sealed»"]:::sup
    FW --> SFW["Single"]:::sup
    FW --> LFW["List"]:::sup
    FW --> CFW["Connection"]:::sup

    JS["JoinStep\n«sealed»"]:::sup
    JS --> FKJ["FkJoin"]:::sup
    JS --> CJ["ConditionJoin"]:::sup

    FC["FieldCondition\nmethod · override · contextArgs"]:::sup

    TR["TableRef"]:::val
    CR["ColumnRef"]:::val
    MR["MethodRef"]:::val
    PS["ParamSource\n«sealed»"]:::val
    SR["BatchKey\n«sealed»"]:::val
    PR["ParticipantRef"]:::val
```

`ColumnFilterArg` and `InputFilterArg` are shown with orange dashed borders — they are missing a `FieldCondition condition` component for `@condition` on `ARGUMENT_DEFINITION`.

---

## 5. Key Compositions (HAS-A)

| Holder | Field | Type |
|---|---|---|
| `TableTargetField` | `returnType` | `TableBoundReturnType` |
| `TableTargetField` | `joinPath` | `List<JoinStep>` |
| `TableTargetField` | `condition` | `FieldCondition?` |
| `TableTargetField` | `arguments` | `List<ArgumentRef>` |
| `QueryTableField` / `QueryLookupTableField` / `QueryTableInterfaceField` | `condition` | `FieldCondition?` |
| `TableBoundReturnType` | `table` | `TableRef` |
| `TableBackedType` | `table` | `TableRef` |
| `TableRef` | `primaryKey?` | `List<ColumnRef>?` |
| `FieldCondition` | `method` | `MethodRef` — signature: `(Table tgt, Arg...)` |
| `FkJoin` | `whereFilter?` | `MethodRef?` — signature: `(SourceTable src, Table tgt)` |
| `ConditionJoin` | `condition` | `MethodRef` — signature: `(SourceTable src, Table tgt)` |
| `QueryServiceTableField` | `method` | `MethodRef` |
| `MutationServiceTableField` | `method` | `MethodRef` |
| `ServiceTableField` (child) | `method` | `MethodRef` |

---

## Notes on potential cleanup

### Structural redundancy in `TableTargetField`

`TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordTableField`,
and `RecordLookupTableField` all share the same component set
(`returnType · joinPath · condition · arguments`). The only classifying difference is:

| Type | Parent context | Split query | Lookup key |
|---|---|---|---|
| `TableField` | table-mapped | ✗ | ✗ |
| `SplitTableField` | table-mapped | ✓ | ✗ |
| `LookupTableField` | table-mapped | ✗ | ✓ |
| `SplitLookupTableField` | table-mapped | ✓ | ✓ |
| `RecordTableField` | result-mapped | ✗ | ✗ |
| `RecordLookupTableField` | result-mapped | — | ✓ |

These could potentially be collapsed into fewer types with boolean flags, or further intermediate
sealed interfaces (e.g., `StandardTableField permits TableField, SplitTableField`,
`RecordBoundField permits RecordTableField, RecordLookupTableField`).

### `QueryField` mirrors `ChildField`

Several `QueryField` variants structurally mirror their `ChildField` counterparts:

| `QueryField` | `ChildField` counterpart |
|---|---|
| `QueryTableField` | `TableField` / `SplitTableField` |
| `QueryLookupTableField` | `LookupTableField` / `SplitLookupTableField` |
| `QueryTableInterfaceField` | `TableInterfaceField` |
| `QueryServiceTableField` | `ServiceTableField` |
| `QueryServiceRecordField` | `ServiceRecordField` |

The only structural difference is that root fields have no `joinPath` — there is no parent table
to FK-navigate from. `QueryTableField`, `QueryLookupTableField`, and `QueryTableInterfaceField`
now carry `FieldCondition condition` alongside their `ChildField` counterparts.
`QueryTableMethodTableField` and `QueryServiceTableField` intentionally do not carry condition —
the developer-controlled method/service replaces SQL generation entirely.

Whether a shared interface between root and child table-bound fields could capture the common
parts (`returnType · condition · arguments`) is worth exploring.

### `TableTargetField` interface vs. `NestingField`

`NestingField` carries `ReturnTypeRef.TableBoundReturnType` but is intentionally excluded from
`TableTargetField` because it does not navigate to a new table scope. This exclusion is
architecturally correct but worth documenting clearly at the use sites.

### `ConditionJoin` vs. `FkJoin.whereFilter` vs. `FieldCondition`

All three hold a `MethodRef`, which is intentional: `MethodRef` is the general model-level
representation of any user-provided Java method (the javadoc says so explicitly). What varies is
the calling convention, and that is already encoded per-parameter in `MethodRef.Param.source`
via `ParamSource`:

| Use site | `ParamSource` sequence | Generated call |
|---|---|---|
| `ConditionJoin.condition` | `SourceTable`, `Table` | `method(srcAlias, tgtAlias)` → ON clause |
| `FkJoin.whereFilter` | `SourceTable`, `Table` | `method(srcAlias, tgtAlias)` → WHERE clause |
| `FieldCondition.method` | `Table`, then `Arg`/`Context`... | `method(tgtTable, arg1, ...)` → WHERE predicate |

The interesting structural observation is that `ConditionJoin.condition` and `FkJoin.whereFilter`
share an identical calling convention (`SourceTable, Table → Condition`), while
`FieldCondition.method` is structurally different (`Table, Arg... → Condition`). The model does
not express this grouping. A potential improvement: introduce a `JoinConditionRef` wrapper used
in both join-step types to make the shared `(source, target)` contract explicit in the type system
and separate it cleanly from the field-condition contract.
