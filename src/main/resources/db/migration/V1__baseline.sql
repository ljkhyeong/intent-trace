-- 운영 전 V1~V11을 통합한 기준 스키마다. 이후 변경은 새 버전으로 추가한다.

create table change_records (
    id varchar(36) primary key,
    request_id varchar(120) not null,
    repository_key varchar(255) not null,
    base_revision varchar(64),
    target_revision varchar(64),
    snapshot_digest varchar(64) not null,
    title varchar(200) not null,
    request_summary varchar(2000) not null,
    status varchar(32) not null,
    created_by_subject varchar(160) not null,
    created_by_login varchar(120) not null,
    created_at timestamp with time zone not null,
    confirmed_at timestamp with time zone,
    published_at timestamp with time zone,
    superseded_by varchar(36),
    derived_from_record_id varchar(36),
    version bigint not null,
    creation_digest varchar(64) not null,
    constraint uq_change_records_request_id unique (request_id),
    constraint ck_change_records_repository_key_lowercase check (repository_key = lower(repository_key)),
    constraint fk_change_records_superseded_by foreign key (superseded_by) references change_records(id),
    constraint fk_change_records_derived_from foreign key (derived_from_record_id) references change_records(id)
);

create index idx_change_records_catalog on change_records(repository_key, status, created_at, id);
create index idx_change_records_author_catalog on change_records(repository_key, created_by_subject, status, created_at, id);
create index idx_change_records_lookup on change_records(repository_key, target_revision, status);

create table change_decisions (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    summary varchar(1000) not null,
    rationale varchar(2000),
    source varchar(48) not null,
    constraint fk_change_decisions_record foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_change_decisions_sequence unique (record_id, sequence_number)
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
    anchor_side varchar(16) not null,
    related_path varchar(1000),
    constraint fk_code_anchors_record foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_code_anchors_sequence unique (record_id, sequence_number),
    constraint ck_code_anchors_canonical_path check (
        relative_path <> ''
        and relative_path <> '.'
        and relative_path not like './%'
        and relative_path not like '%//%'
        and relative_path not like '%/./%'
        and relative_path not like '%/.'
        and relative_path not like '%/'
    )
);

create index idx_code_anchors_path on code_anchors(relative_path, start_line, end_line);

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
    source varchar(32) not null,
    constraint fk_verification_runs_record foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_verification_runs_sequence unique (record_id, sequence_number)
);

create table open_questions (
    id varchar(36) primary key,
    record_id varchar(36) not null,
    sequence_number integer not null,
    description varchar(1000) not null,
    constraint fk_open_questions_record foreign key (record_id) references change_records(id) on delete cascade,
    constraint uq_open_questions_sequence unique (record_id, sequence_number)
);

create table record_activities (
    record_id varchar(36) not null,
    version bigint not null,
    operation varchar(24) not null,
    actor_subject varchar(160) not null,
    previous_version bigint,
    previous_status varchar(32),
    status varchar(32) not null,
    occurred_at timestamp with time zone not null,
    constraint pk_record_activities primary key (record_id, version),
    constraint fk_record_activities_record foreign key (record_id) references change_records(id) on delete cascade
);

create table github_publications (
    id varchar(36) primary key,
    change_record_id varchar(36) not null,
    repository_owner varchar(100) not null,
    repository_name varchar(100) not null,
    pull_number integer not null,
    head_revision varchar(64) not null,
    check_run_id bigint not null,
    check_run_url varchar(2000) not null,
    content_digest varchar(64) not null,
    published_at timestamp with time zone not null,
    constraint fk_github_publications_change_record foreign key (change_record_id) references change_records(id),
    constraint uq_github_publications_target unique (change_record_id, repository_owner, repository_name, pull_number),
    constraint ck_github_publications_repository_lowercase check (
        repository_owner = lower(repository_owner) and repository_name = lower(repository_name)
    )
);

create table github_publication_attempts (
    id varchar(36) primary key,
    change_record_id varchar(36) not null,
    repository_key varchar(255) not null,
    pull_number integer not null,
    operation varchar(32) not null,
    status varchar(32) not null,
    failure_code varchar(64),
    check_run_id bigint,
    content_digest varchar(64),
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    constraint fk_publication_attempts_change_record foreign key (change_record_id) references change_records(id),
    constraint ck_publication_attempts_repository_lowercase check (repository_key = lower(repository_key))
);

create index idx_publication_attempts_target
    on github_publication_attempts(change_record_id, repository_key, pull_number, started_at);
