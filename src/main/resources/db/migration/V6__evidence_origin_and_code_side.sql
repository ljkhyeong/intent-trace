alter table code_anchors add column anchor_side varchar(16) not null default 'TARGET';
alter table code_anchors add column related_path varchar(1000);
alter table verification_runs add column source varchar(32) not null default 'CLIENT_REPORTED';
