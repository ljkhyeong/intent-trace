alter table change_records add column base_revision varchar(64);

alter table change_records add column creation_digest varchar(64);

create index idx_change_records_catalog
    on change_records(repository_key, status, created_at, id);

create index idx_change_records_author_catalog
    on change_records(repository_key, created_by_subject, status, created_at, id);
