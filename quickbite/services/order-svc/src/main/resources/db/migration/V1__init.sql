create table orders (
  id                bigint primary key,              -- Snowflake id
  customer_id       varchar(64)  not null,
  restaurant_id     varchar(64)  not null,
  status            varchar(32)  not null,
  total_amount      numeric(12,2) not null,
  currency          varchar(3)   not null default 'INR',
  rider_id          varchar(64),
  delivery_lat      double precision not null,
  delivery_lon      double precision not null,
  client_request_id varchar(64) unique,              -- Idempotency-Key
  version           bigint not null default 0,       -- optimistic locking
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index idx_orders_customer on orders(customer_id, created_at desc);
create index idx_orders_rider on orders(rider_id) where rider_id is not null;
create index idx_orders_paid on orders(created_at) where status = 'PAID';   -- partial index: dispatcher's hot query

create table order_lines (
  order_id     bigint not null references orders(id),
  menu_item_id varchar(64)  not null,
  name         varchar(200) not null,
  quantity     int not null check (quantity > 0),
  unit_price   numeric(10,2) not null
);
create index idx_order_lines_order on order_lines(order_id);

create table outbox (
  id uuid primary key, topic varchar(200) not null, msg_key varchar(200) not null,
  event_type varchar(100) not null, payload text not null,
  session_id varchar(100), correlation_id varchar(100),
  created_at timestamptz not null default now(), published_at timestamptz
);
create index idx_outbox_unpublished on outbox(created_at) where published_at is null;

create table processed_events (event_id varchar(64) primary key, processed_at timestamptz not null default now());