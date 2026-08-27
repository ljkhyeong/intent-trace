create table change_records (
    id varchar(36) primary key,
    request_id varchar(120) not null unique,
    repository_key varchar(255) not null,
    base_revision varchar(128),
    target_revision varchar(128),
    snapshot_digest varchar(64) not null,
    title varchar(200) not null,
    request_summary varchar(2000) not null,
    status varchar(32) not null,
    created_by varchar(120) not null,
    created_at timestamp with time zone not null,
    confirmed_at timestamp with time zone,
    published_at timestamp with time zone,
    superseded_by varchar(36),
    version bigint not null,
    constraint fk_change_records_superseded_by
        foreign key (superseded_by) references change_records(id)
);

create index idx_change_records_lookup
    on change_records(repository_key, target_revision, status);

create table change_decisions (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    summary varchar(1000) not null,
    rationale varchar(2000),
    source varchar(48) not null,
    constraint fk_change_decisions_record
        foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_change_decisions_sequence
        unique (record_id, sequence_number)
);

create table code_anchors (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    relative_path varchar(1000) not null,
    symbol_name varchar(500),
    start_line integer not null,
    end_line integer not null,
    content_hash varchar(64) not null,
    constraint fk_code_anchors_record
        foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_code_anchors_sequence
        unique (record_id, sequence_number)
);

create index idx_code_anchors_path
    on code_anchors(relative_path, start_line, end_line);

create table verification_runs (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    command_text varchar(2000) not null,
    exit_code integer not null,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone not null,
    snapshot_digest varchar(64) not null,
    output_digest varchar(64) not null,
    summary varchar(2000) not null,
    constraint fk_verification_runs_record
        foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_verification_runs_sequence
        unique (record_id, sequence_number)
);

create table open_questions (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    description varchar(1000) not null,
    constraint fk_open_questions_record
        foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_open_questions_sequence
        unique (record_id, sequence_number)
);
