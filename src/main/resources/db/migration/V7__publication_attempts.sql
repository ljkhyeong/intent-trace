create table github_publication_attempts (
    id varchar(36) primary key,
    change_record_id varchar(36) not null references change_records(id),
    repository_key varchar(255) not null,
    pull_number integer not null,
    operation varchar(32) not null,
    status varchar(32) not null,
    failure_code varchar(64),
    check_run_id bigint,
    content_digest varchar(64),
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone
);
create index idx_publication_attempts_target
    on github_publication_attempts(change_record_id, repository_key, pull_number, started_at);
