-- Execute only on local Supabase; creates session-local temporary tables.
\set ON_ERROR_STOP off
begin;
create temp table review_category (like public.category including defaults including constraints);
create unique index on review_category(ledger_id,id);
insert into review_category (ledger_id,id,label) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','Teste');
insert into review_category (ledger_id,id,description) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','Nova descrição') on conflict(ledger_id,id) do update set description=excluded.description;
rollback;
begin;
create temp table review_store_item (like public.store_item including defaults including constraints);
create unique index on review_store_item(ledger_id,id);
insert into review_store_item (ledger_id,id,code_kind,code,description,product_id) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','store','TEST','Teste','00000000-0000-0000-0000-000000000003');
insert into review_store_item (ledger_id,id,sold_by_user) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','weight') on conflict(ledger_id,id) do update set sold_by_user=excluded.sold_by_user;
rollback;
