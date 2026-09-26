-- OUR side: only tokens + display data. No PAN, no CVC.
create table payment_methods (
  id           varchar(40) primary key,             -- "pm_..." (what clients and order-svc see)
  customer_id  varchar(64) not null,
  psp_token    varchar(64) not null,                -- "tok_..." (meaningful only to the PSP)
  brand        varchar(20) not null,
  last4        varchar(4)  not null,
  exp_month    int not null,
  exp_year     int not null,
  holder_name  varchar(100),
  is_default   boolean not null default false,
  created_at   timestamptz not null default now()
);
create index idx_pm_customer on payment_methods(customer_id);
-- The DATABASE enforces "at most one default card per customer" (a partial unique index):
create unique index ux_pm_one_default on payment_methods(customer_id) where is_default;

-- Snapshot of the card used, so receipts survive card deletion
alter table payments add column payment_method_id varchar(40);
alter table payments add column card_brand varchar(20);
alter table payments add column card_last4 varchar(4);

-- Stand-in for the PSP's own systems. Stores the TEST BEHAVIOUR of a token, never the card number.
create table psp_fake_vault (
  token        varchar(64) primary key,
  brand        varchar(20) not null,
  last4        varchar(4)  not null,
  behavior     varchar(30) not null,                -- APPROVE | DECLINE | PROCESSING_ERROR | SLOW | CAPTURE_FAIL
  decline_code varchar(40),
  created_at   timestamptz not null default now()
);