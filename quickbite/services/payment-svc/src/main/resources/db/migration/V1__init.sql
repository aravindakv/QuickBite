create table payments (
  id              uuid primary key,
  order_id        varchar(32) not null unique,     -- business-level idempotency
  customer_id     varchar(64) not null,
  amount          numeric(12,2) not null,
  currency        varchar(3) not null,
  status          varchar(20) not null,            -- AUTHORIZED | FAILED | CAPTURED
  psp_reference   varchar(100),
  failure_reason  varchar(200),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create table outbox (
  id uuid primary key, topic varchar(200) not null, msg_key varchar(200) not null,
  event_type varchar(100) not null, payload text not null,
  session_id varchar(100), correlation_id varchar(100),
  created_at timestamptz not null default now(), published_at timestamptz
);
create index idx_outbox_unpublished on outbox(created_at) where published_at is null;

create table processed_events (event_id varchar(64) primary key, processed_at timestamptz not null default now());