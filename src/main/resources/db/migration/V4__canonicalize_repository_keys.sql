update change_records
set repository_key = lower(repository_key);

update github_publications
set repository_owner = lower(repository_owner),
    repository_name = lower(repository_name);

alter table change_records
    add constraint ck_change_records_repository_key_lowercase
        check (repository_key = lower(repository_key));

alter table github_publications
    add constraint ck_github_publications_repository_lowercase
        check (
            repository_owner = lower(repository_owner)
            and repository_name = lower(repository_name)
        );
