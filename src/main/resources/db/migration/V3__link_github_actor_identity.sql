alter table change_records
    add column created_by_subject varchar(160);

update change_records
set created_by_subject = concat('legacy:', lower(created_by))
where created_by_subject is null;

alter table change_records
    alter column created_by_subject set not null;

create index idx_change_records_actor
    on change_records(repository_key, created_by_subject, status);
