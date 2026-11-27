SELECT DISTINCT o.graph_name, o.coordinate, 'recordParameter' AS rule, 'TABLE' AS kind, bc.record_source_name AS table_source_name, bc.record_schema AS table_schema, bc.record_table AS table_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence_built_class bc
    ON bc.graph_name = o.graph_name AND bc.coordinate = o.coordinate AND bc.stated_by = 'SERVICE'
   AND bc.record_table IS NOT NULL
   -- A member a service's bean takes, or a service's own parameter; a write's record is not this rule's.
   AND (bc.declared_via = 'MEMBER'
        OR bc.declared_via = 'PARAMETER'
           AND EXISTS (SELECT 1 FROM graphitron_occurrence_call_parameter c
                        WHERE c.graph_name = o.graph_name AND c.landing_coordinate = o.coordinate
                          AND c.call_kind = 'SERVICE' AND c.record_table IS NOT NULL))
 WHERE o.kind IN ('FIELD_ARGUMENT', 'INPUT_FIELD')
