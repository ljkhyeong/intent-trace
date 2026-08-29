update code_anchors
set relative_path = regexp_replace(relative_path, '^([.]/)+', '');

update code_anchors
set relative_path = regexp_replace(relative_path, '(/[.])+/', '/', 'g');

update code_anchors
set relative_path = regexp_replace(relative_path, '(/[.])+$', '');

update code_anchors
set relative_path = regexp_replace(relative_path, '/+', '/', 'g');

update code_anchors
set relative_path = regexp_replace(relative_path, '/+$', '');

alter table code_anchors
    add constraint ck_code_anchors_canonical_path
        check (
            relative_path <> ''
            and relative_path <> '.'
            and relative_path not like './%'
            and relative_path not like '%//%'
            and relative_path not like '%/./%'
            and relative_path not like '%/.'
            and relative_path not like '%/'
        );
