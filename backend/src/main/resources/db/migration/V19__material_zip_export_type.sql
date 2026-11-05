ALTER TABLE export_job
    DROP CHECK chk_export_type;

ALTER TABLE export_job
    ADD CONSTRAINT chk_export_type CHECK (
        type IN ('INDICATORS', 'TASKS', 'STATS_REPORT', 'SETTLEMENT', 'AUDIT_LOGS', 'MATERIAL_ZIP')
    );
