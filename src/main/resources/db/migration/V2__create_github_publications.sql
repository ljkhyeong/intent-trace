create table github_publications (
    id varchar(36) primary key,
    change_record_id varchar(36) not null,
    repository_owner varchar(100) not null,
    repository_name varchar(100) not null,
    pull_number integer not null,
    head_revision varchar(128) not null,
    check_run_id bigint not null,
    check_run_url varchar(2000) not null,
    content_digest varchar(64) not null,
    published_at timestamp with time zone not null,
    constraint fk_github_publications_change_record
        foreign key (change_record_id) references change_records(id),
    constraint uq_github_publications_target
        unique (change_record_id, repository_owner, repository_name, pull_number)
);

create index idx_github_publications_check_run
    on github_publications(repository_owner, repository_name, check_run_id);
