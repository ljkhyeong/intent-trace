alter table change_records add column derived_from_record_id varchar(36) references change_records(id);
