create table record_activities (
    record_id varchar(36) not null,
    operation varchar(24) not null,
    actor_subject varchar(160) not null,
    previous_version bigint,
    version bigint not null,
    previous_status varchar(32),
    status varchar(32) not null,
    occurred_at timestamp with time zone not null,
    primary key (record_id, version),
    constraint fk_record_activities_record foreign key (record_id) references change_records(id) on delete cascade
);
