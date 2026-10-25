-- Stage 8 (D80, docs/stage-8-plan.md): what the LLM worker on the user's PC suggests, and its bookkeeping.

-- A pack size the parser couldn't read, proposed for the user to accept. A null amount with a model means "this
-- isn't a size" (a suitcase's "10KG" is capacity).
alter table public.product
  add column size_suggestion_e6 bigint,
  add column size_suggestion_unit text check (size_suggestion_unit in ('kg', 'l')),
  add column size_suggestion_model text,
  add column size_suggestion_at timestamptz,
  -- When the LLM last checked this product's automatic category (D69's audit order: never checked first).
  add column llm_checked_at timestamptz;

-- A drafted one-line description for a category the user created without one (D75).
alter table public.category add column description_suggestion text;

-- "This store item is the same product as that one" (D44 flow 2, D66): the user accepts or refuses on the phone.
create table public.link_suggestion (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  store_item_id uuid not null,
  product_id uuid not null,
  confidence real check (confidence between 0 and 1),
  reason text,
  model text,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'refused')),
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, store_item_id, product_id),
  foreign key (user_id, store_item_id) references public.store_item (user_id, id) on delete cascade,
  foreign key (user_id, product_id) references public.product (user_id, id) on delete cascade
);
create index on public.link_suggestion (user_id, updated_at);
create trigger touch before insert or update on public.link_suggestion for each row execute function public.touch();
alter table public.link_suggestion enable row level security;
create policy own_rows on public.link_suggestion for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.link_suggestion from anon;
grant select, insert, update on public.link_suggestion to authenticated;

-- Each LLM check of a category: the measured error rate comes from the random sample among these (D69).
create table public.audit_log (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users on delete cascade,
  product_id uuid not null,
  category_id uuid,
  llm_category_id uuid,
  agree boolean not null,
  sample boolean not null default false,
  model text not null,
  at timestamptz not null default clock_timestamp()
);

-- Rules learned from a user's corrections ("sliced cured meats are cold cuts"), given to the LLM when it checks.
create table public.category_rule (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users on delete cascade,
  text text not null,
  -- The correction it came from.
  product_id uuid,
  model text not null,
  created_at timestamptz not null default clock_timestamp()
);

-- What each worker run did and found: the health report (D65, D69), readable by its user.
create table public.worker_report (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users on delete cascade,
  at timestamptz not null default clock_timestamp(),
  report jsonb not null,
  updated_at timestamptz not null default clock_timestamp()
);
create index on public.worker_report (user_id, updated_at);
alter table public.worker_report enable row level security;
create policy own_rows on public.worker_report for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.worker_report from anon;
grant select on public.worker_report to authenticated;

alter table public.audit_log enable row level security;
alter table public.category_rule enable row level security;
revoke all on public.audit_log, public.category_rule from anon, authenticated;

-- Explicit, though D74's default privileges already cover new tables.
grant select, insert, update, delete on public.link_suggestion, public.audit_log, public.category_rule,
  public.worker_report to service_role;
-- Identity columns draw from sequences, which need their own grant.
grant usage, select on all sequences in schema public to service_role;
alter default privileges in schema public grant usage, select on sequences to service_role;
