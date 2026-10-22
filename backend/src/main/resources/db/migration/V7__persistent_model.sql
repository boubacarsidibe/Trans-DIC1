-- Généré à partir du modèle persistant (docs/architecture/diagrams/02-domain/persistence-model-uml.mmd).
-- Voir docs/architecture/persistence-mapping.md pour la correspondance des noms.

CREATE TABLE user_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    ip_address VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_user_session_user_id FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_session_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_user_session_expires_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX idx_user_session_user_id ON user_session(user_id);
CREATE INDEX idx_user_session_expires_at ON user_session(expires_at);

CREATE TABLE password_reset_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_password_reset_token_user_id FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_token_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_token_expires_after_creation CHECK (expires_at > created_at)
);
CREATE INDEX idx_password_reset_token_user_id ON password_reset_token(user_id);

CREATE TABLE monitored_service (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    type VARCHAR(32) NOT NULL,
    protocol VARCHAR(32),
    port INTEGER,
    access_point VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_monitored_service_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE CASCADE,
    CONSTRAINT ck_monitored_service_type CHECK (type IN ('TCP_PORT', 'HTTP', 'HTTPS', 'ICMP', 'DNS', 'OTHER')),
    CONSTRAINT uq_monitored_service_equipment_id_name UNIQUE (equipment_id, name),
    CONSTRAINT ck_monitored_service_port_range CHECK (port IS NULL OR (port BETWEEN 1 AND 65535))
);
CREATE INDEX idx_monitored_service_equipment_id ON monitored_service(equipment_id);

CREATE TABLE collection_configuration (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    poll_interval_seconds BIGINT NOT NULL,
    timeout_seconds BIGINT NOT NULL,
    retry_count INTEGER NOT NULL,
    snmp_version VARCHAR(32),
    community_reference VARCHAR(255),
    security_identity VARCHAR(255),
    secret_reference VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_collection_configuration_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE CASCADE,
    CONSTRAINT ck_collection_configuration_mode CHECK (mode IN ('AGENT', 'SNMP')),
    CONSTRAINT ck_collection_configuration_snmp_version CHECK (snmp_version IN ('V2C', 'V3')),
    CONSTRAINT uq_collection_configuration_equipment_id_name UNIQUE (equipment_id, name),
    CONSTRAINT ck_collection_configuration_durations_positive CHECK (poll_interval_seconds > 0 AND timeout_seconds > 0),
    CONSTRAINT ck_collection_configuration_retry_range CHECK (retry_count BETWEEN 0 AND 10),
    CONSTRAINT ck_collection_configuration_snmp_needs_version CHECK (mode <> 'SNMP' OR snmp_version IS NOT NULL),
    CONSTRAINT ck_collection_configuration_agent_has_no_snmp_params CHECK (mode <> 'AGENT' OR (snmp_version IS NULL AND community_reference IS NULL AND security_identity IS NULL))
);
CREATE INDEX idx_collection_configuration_equipment_id ON collection_configuration(equipment_id);

CREATE TABLE metric_definition (
    id UUID PRIMARY KEY,
    configuration_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    technical_key VARCHAR(64) NOT NULL,
    value_type VARCHAR(32) NOT NULL,
    unit VARCHAR(24),
    snmp_oid VARCHAR(255),
    agent_command VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_metric_definition_configuration_id FOREIGN KEY (configuration_id) REFERENCES collection_configuration(id) ON DELETE CASCADE,
    CONSTRAINT ck_metric_definition_value_type CHECK (value_type IN ('INTEGER', 'DECIMAL', 'BOOLEAN', 'TEXT')),
    CONSTRAINT uq_metric_definition_configuration_id_technical_key UNIQUE (configuration_id, technical_key)
);
CREATE INDEX idx_metric_definition_configuration_id ON metric_definition(configuration_id);

CREATE TABLE collection_agent (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL,
    identifier VARCHAR(64) NOT NULL,
    version VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    ip_address VARCHAR(45),
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_communication_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_collection_agent_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT uq_collection_agent_identifier UNIQUE (identifier),
    CONSTRAINT ck_collection_agent_status CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'REVOKED'))
);
CREATE INDEX idx_collection_agent_equipment_id ON collection_agent(equipment_id);

CREATE TABLE site_access (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    site_id UUID NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_site_access_user_id FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_site_access_site_id FOREIGN KEY (site_id) REFERENCES site(id),
    CONSTRAINT uq_site_access_user_id_site_id UNIQUE (user_id, site_id)
);
CREATE INDEX idx_site_access_user_id ON site_access(user_id);
CREATE INDEX idx_site_access_site_id ON site_access(site_id);

CREATE TABLE equipment_access (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_equipment_access_user_id FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_equipment_access_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT uq_equipment_access_user_id_equipment_id UNIQUE (user_id, equipment_id)
);
CREATE INDEX idx_equipment_access_user_id ON equipment_access(user_id);
CREATE INDEX idx_equipment_access_equipment_id ON equipment_access(equipment_id);

CREATE TABLE alert_rule (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    metric_type VARCHAR(64) NOT NULL,
    operator VARCHAR(32) NOT NULL,
    threshold NUMERIC(12, 4) NOT NULL,
    unit VARCHAR(24),
    confirmation_duration_seconds BIGINT NOT NULL,
    initial_severity VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_alert_rule_name UNIQUE (name),
    CONSTRAINT ck_alert_rule_operator CHECK (operator IN ('GREATER_THAN', 'GREATER_OR_EQUAL', 'LESS_THAN', 'LESS_OR_EQUAL', 'EQUAL')),
    CONSTRAINT ck_alert_rule_initial_severity CHECK (initial_severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_alert_rule_confirmation_not_negative CHECK (confirmation_duration_seconds >= 0)
);


CREATE TABLE rule_site_scope (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    site_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_rule_site_scope_rule_id FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_site_scope_site_id FOREIGN KEY (site_id) REFERENCES site(id),
    CONSTRAINT uq_rule_site_scope_rule_id_site_id UNIQUE (rule_id, site_id)
);
CREATE INDEX idx_rule_site_scope_rule_id ON rule_site_scope(rule_id);
CREATE INDEX idx_rule_site_scope_site_id ON rule_site_scope(site_id);

CREATE TABLE rule_equipment_scope (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_rule_equipment_scope_rule_id FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_equipment_scope_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT uq_rule_equipment_scope_rule_id_equipment_id UNIQUE (rule_id, equipment_id)
);
CREATE INDEX idx_rule_equipment_scope_rule_id ON rule_equipment_scope(rule_id);
CREATE INDEX idx_rule_equipment_scope_equipment_id ON rule_equipment_scope(equipment_id);

CREATE TABLE alert (
    id UUID PRIMARY KEY,
    equipment_id UUID NOT NULL,
    service_id UUID,
    rule_id UUID,
    occurrence_key VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    taken_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    deleted_at TIMESTAMP WITH TIME ZONE,
    resolution_type VARCHAR(32),
    CONSTRAINT fk_alert_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT fk_alert_service_id FOREIGN KEY (service_id) REFERENCES monitored_service(id),
    CONSTRAINT fk_alert_rule_id FOREIGN KEY (rule_id) REFERENCES alert_rule(id),
    CONSTRAINT uq_alert_occurrence_key UNIQUE (occurrence_key),
    CONSTRAINT ck_alert_type CHECK (type IN ('THRESHOLD', 'COLLECTION')),
    CONSTRAINT ck_alert_state CHECK (state IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_alert_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_alert_resolution_type CHECK (resolution_type IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT ck_alert_type_rule_consistency CHECK ((type = 'THRESHOLD' AND rule_id IS NOT NULL) OR (type = 'COLLECTION' AND rule_id IS NULL)),
    CONSTRAINT ck_alert_resolved_has_date_and_type CHECK (state <> 'RESOLVED' OR (resolved_at IS NOT NULL AND resolution_type IS NOT NULL))
);
CREATE INDEX idx_alert_equipment_id ON alert(equipment_id);
CREATE INDEX idx_alert_service_id ON alert(service_id);
CREATE INDEX idx_alert_rule_id ON alert(rule_id);
CREATE INDEX idx_alert_equipment_id_state ON alert(equipment_id, state);
CREATE INDEX idx_alert_triggered_at ON alert(triggered_at);

CREATE TABLE alert_assignment (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL,
    user_id UUID NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_alert_assignment_alert_id FOREIGN KEY (alert_id) REFERENCES alert(id),
    CONSTRAINT fk_alert_assignment_user_id FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_alert_assignment_alert_id ON alert_assignment(alert_id);
CREATE INDEX idx_alert_assignment_user_id ON alert_assignment(user_id);

CREATE TABLE alert_event (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL,
    author_id UUID,
    type VARCHAR(32) NOT NULL,
    previous_state VARCHAR(32),
    new_state VARCHAR(32),
    comment VARCHAR(2000),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_alert_event_alert_id FOREIGN KEY (alert_id) REFERENCES alert(id) ON DELETE CASCADE,
    CONSTRAINT fk_alert_event_author_id FOREIGN KEY (author_id) REFERENCES app_user(id),
    CONSTRAINT ck_alert_event_type CHECK (type IN ('OPENED', 'TAKEN', 'ASSIGNED', 'UNASSIGNED', 'SEVERITY_CHANGED', 'COMMENTED', 'NOTIFIED', 'RESOLVED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_alert_event_previous_state CHECK (previous_state IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_alert_event_new_state CHECK (new_state IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'ARCHIVED', 'DELETED'))
);
CREATE INDEX idx_alert_event_alert_id ON alert_event(alert_id);
CREATE INDEX idx_alert_event_author_id ON alert_event(author_id);
CREATE INDEX idx_alert_event_alert_id_occurred_at ON alert_event(alert_id, occurred_at);

CREATE TABLE maintenance (
    id UUID PRIMARY KEY,
    planner_id UUID NOT NULL,
    site_id UUID,
    equipment_id UUID,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_maintenance_planner_id FOREIGN KEY (planner_id) REFERENCES app_user(id),
    CONSTRAINT fk_maintenance_site_id FOREIGN KEY (site_id) REFERENCES site(id),
    CONSTRAINT fk_maintenance_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT ck_maintenance_state CHECK (state IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_maintenance_exactly_one_target CHECK ((site_id IS NOT NULL AND equipment_id IS NULL) OR (site_id IS NULL AND equipment_id IS NOT NULL)),
    CONSTRAINT ck_maintenance_end_after_start CHECK (end_at > start_at)
);
CREATE INDEX idx_maintenance_planner_id ON maintenance(planner_id);
CREATE INDEX idx_maintenance_site_id ON maintenance(site_id);
CREATE INDEX idx_maintenance_equipment_id ON maintenance(equipment_id);
CREATE INDEX idx_maintenance_start_at_end_at ON maintenance(start_at, end_at);

CREATE TABLE notification_preference (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    channel VARCHAR(32) NOT NULL,
    minimum_severity VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_notification_preference_user_id FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_notification_preference_user_id UNIQUE (user_id),
    CONSTRAINT ck_notification_preference_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notification_preference_minimum_severity CHECK (minimum_severity IN ('INFO', 'WARNING', 'CRITICAL'))
);
CREATE INDEX idx_notification_preference_user_id ON notification_preference(user_id);

CREATE TABLE report_schedule (
    id UUID PRIMARY KEY,
    creator_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    report_type VARCHAR(32) NOT NULL,
    frequency VARCHAR(32) NOT NULL,
    execution_time TIME NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_report_schedule_creator_id FOREIGN KEY (creator_id) REFERENCES app_user(id),
    CONSTRAINT ck_report_schedule_report_type CHECK (report_type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'CUSTOM')),
    CONSTRAINT ck_report_schedule_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY'))
);
CREATE INDEX idx_report_schedule_creator_id ON report_schedule(creator_id);

CREATE TABLE report (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    schedule_id UUID,
    type VARCHAR(32) NOT NULL,
    period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL,
    format VARCHAR(32) NOT NULL,
    file_path VARCHAR(512),
    generated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_report_requester_id FOREIGN KEY (requester_id) REFERENCES app_user(id),
    CONSTRAINT fk_report_schedule_id FOREIGN KEY (schedule_id) REFERENCES report_schedule(id),
    CONSTRAINT ck_report_type CHECK (type IN ('DAILY', 'WEEKLY', 'MONTHLY', 'CUSTOM')),
    CONSTRAINT ck_report_state CHECK (state IN ('PENDING', 'GENERATED', 'FAILED')),
    CONSTRAINT ck_report_format CHECK (format IN ('PDF', 'CSV')),
    CONSTRAINT ck_report_period_order CHECK (period_end > period_start)
);
CREATE INDEX idx_report_requester_id ON report(requester_id);
CREATE INDEX idx_report_schedule_id ON report(schedule_id);
CREATE INDEX idx_report_period_start_period_end ON report(period_start, period_end);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    alert_id UUID,
    report_id UUID,
    channel VARCHAR(32) NOT NULL,
    destination_address VARCHAR(254) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_notification_user_id FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_notification_alert_id FOREIGN KEY (alert_id) REFERENCES alert(id),
    CONSTRAINT fk_notification_report_id FOREIGN KEY (report_id) REFERENCES report(id),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notification_state CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_exactly_one_origin CHECK ((alert_id IS NOT NULL AND report_id IS NULL) OR (alert_id IS NULL AND report_id IS NOT NULL))
);
CREATE INDEX idx_notification_user_id ON notification(user_id);
CREATE INDEX idx_notification_alert_id ON notification(alert_id);
CREATE INDEX idx_notification_report_id ON notification(report_id);
CREATE INDEX idx_notification_state_created_at ON notification(state, created_at);

CREATE TABLE notification_attempt (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    number INTEGER NOT NULL,
    result VARCHAR(32) NOT NULL,
    sanitized_error VARCHAR(1000),
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_notification_attempt_notification_id FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_attempt_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT uq_notification_attempt_notification_id_number UNIQUE (notification_id, number),
    CONSTRAINT ck_notification_attempt_number_positive CHECK (number >= 1)
);
CREATE INDEX idx_notification_attempt_notification_id ON notification_attempt(notification_id);

CREATE TABLE schedule_recipient (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT fk_schedule_recipient_schedule_id FOREIGN KEY (schedule_id) REFERENCES report_schedule(id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_recipient_user_id FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uq_schedule_recipient_schedule_id_user_id UNIQUE (schedule_id, user_id)
);
CREATE INDEX idx_schedule_recipient_schedule_id ON schedule_recipient(schedule_id);
CREATE INDEX idx_schedule_recipient_user_id ON schedule_recipient(user_id);

CREATE TABLE schedule_site_scope (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL,
    site_id UUID NOT NULL,
    CONSTRAINT fk_schedule_site_scope_schedule_id FOREIGN KEY (schedule_id) REFERENCES report_schedule(id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_site_scope_site_id FOREIGN KEY (site_id) REFERENCES site(id),
    CONSTRAINT uq_schedule_site_scope_schedule_id_site_id UNIQUE (schedule_id, site_id)
);
CREATE INDEX idx_schedule_site_scope_schedule_id ON schedule_site_scope(schedule_id);
CREATE INDEX idx_schedule_site_scope_site_id ON schedule_site_scope(site_id);

CREATE TABLE schedule_equipment_scope (
    id UUID PRIMARY KEY,
    schedule_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    CONSTRAINT fk_schedule_equipment_scope_schedule_id FOREIGN KEY (schedule_id) REFERENCES report_schedule(id) ON DELETE CASCADE,
    CONSTRAINT fk_schedule_equipment_scope_equipment_id FOREIGN KEY (equipment_id) REFERENCES equipment(id),
    CONSTRAINT uq_schedule_equipment_scope_schedule_id_equipment_id UNIQUE (schedule_id, equipment_id)
);
CREATE INDEX idx_schedule_equipment_scope_schedule_id ON schedule_equipment_scope(schedule_id);
CREATE INDEX idx_schedule_equipment_scope_equipment_id ON schedule_equipment_scope(equipment_id);

-- ---------------------------------------------------------------------------
-- Évolution des tables existantes (additif : aucune donnée n'est supprimée).
-- ---------------------------------------------------------------------------
ALTER TABLE app_user ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE site ADD COLUMN description VARCHAR(1000);
ALTER TABLE site ADD COLUMN address VARCHAR(255);
ALTER TABLE site ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE site ADD CONSTRAINT ck_site_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE equipment ADD COLUMN manufacturer VARCHAR(120);
ALTER TABLE equipment ADD COLUMN model VARCHAR(120);
ALTER TABLE equipment ADD COLUMN operating_system VARCHAR(120);
ALTER TABLE equipment ADD COLUMN monitoring_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE equipment ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE metric ADD COLUMN definition_id UUID;
ALTER TABLE metric ADD COLUMN service_id UUID;
ALTER TABLE metric ADD COLUMN agent_id UUID;
ALTER TABLE metric ADD CONSTRAINT fk_metric_definition_id FOREIGN KEY (definition_id) REFERENCES metric_definition(id);
ALTER TABLE metric ADD CONSTRAINT fk_metric_service_id FOREIGN KEY (service_id) REFERENCES monitored_service(id);
ALTER TABLE metric ADD CONSTRAINT fk_metric_agent_id FOREIGN KEY (agent_id) REFERENCES collection_agent(id);
ALTER TABLE metric ADD CONSTRAINT ck_metric_quality CHECK (quality IN ('GOOD', 'DEGRADED', 'BAD'));
CREATE INDEX idx_metric_definition_id ON metric(definition_id);
CREATE INDEX idx_metric_service_id ON metric(service_id);
CREATE INDEX idx_metric_agent_id ON metric(agent_id);

ALTER TABLE audit_entry ADD COLUMN author_id UUID;
ALTER TABLE audit_entry ADD COLUMN correlation_id VARCHAR(64);
ALTER TABLE audit_entry ADD CONSTRAINT fk_audit_entry_author_id FOREIGN KEY (author_id) REFERENCES app_user(id) ON DELETE SET NULL;
CREATE INDEX idx_audit_entry_author_id ON audit_entry(author_id);
CREATE INDEX idx_audit_entry_target ON audit_entry(target_type, target_id);
CREATE INDEX idx_audit_entry_performed_at ON audit_entry(performed_at);
