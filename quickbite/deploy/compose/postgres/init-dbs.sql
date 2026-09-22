-- One Postgres server, one database per service: services never share tables.
create database orders;
create database payments;