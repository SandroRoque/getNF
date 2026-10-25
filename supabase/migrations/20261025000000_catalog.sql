-- Receipts, lines and catalog per user (docs/data-model.md, docs/stage-4-plan.md, D70).
-- Every row belongs to one user. Row Level Security keeps users apart, and composite foreign keys that include
-- user_id make a reference to another user's row impossible, even for code that bypasses RLS.
-- Money is cents, quantities and unit prices are scale 4, sizes are millionths of a kg or litre, as on the phone.
-- Rows are never hard-deleted by the app: `deleted` tombstones let the phone's pull see removals (sync by updated_at).

create table public.issuer (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  cnpj text not null,
  name text not null,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, cnpj)
);

create table public.receipt (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  chave text not null check (chave ~ '^[0-9]{44}$'),
  qr_url text,
  captured_at timestamptz not null,
  page_url text,
  -- Storage object holding the saved SEFAZ page (bucket `receipts`, `<user>/<chave>/page.html`).
  page_path text,
  page_fetched_at timestamptz,
  issuer_cnpj text,
  issued_at timestamptz,
  gross_cents bigint,
  discount_cents bigint,
  paid_cents bigint,
  parser_version int,
  parse_error text,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, chave),
  foreign key (user_id, issuer_cnpj) references public.issuer (user_id, cnpj)
);

create table public.receipt_photo (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  chave text not null,
  local_name text not null,
  position int not null,
  session_id text,
  -- CaptureSegment JSON (mask name, polygon, bands, session) for continuous-capture photos (D42).
  segment jsonb,
  captured_at timestamptz,
  -- The small archive copy (D61) and its mask, in bucket `receipts`.
  object_path text,
  mask_path text,
  bytes bigint,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, chave, local_name),
  foreign key (user_id, chave) references public.receipt (user_id, chave) on delete cascade
);

-- The seed every user's categories and tags start from: taxonomy/*.txt, loaded by generated migrations.
create table public.taxonomy_node (
  path text primary key,
  label text not null,
  parent_path text references public.taxonomy_node (path),
  sort int not null
);
create table public.taxonomy_tag (
  slug text primary key,
  description text not null
);

create table public.category (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  -- Path in taxonomy/categories.txt when it came from there; null for one the user created.
  seed_path text,
  label text not null,
  parent_id uuid,
  sort int not null default 0,
  -- The seed no longer overwrites label, parent or order.
  user_modified boolean not null default false,
  -- Tombstone: the seed doesn't recreate it.
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, id),
  unique (user_id, seed_path),
  foreign key (user_id, parent_id) references public.category (user_id, id)
);

create table public.tag (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  slug text not null,
  description text not null,
  seed boolean not null default true,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, id),
  unique (user_id, slug)
);

-- Provenance of interpreted values (D51, D69): source, model, confidence, review state and time.
create type public.value_source as enum ('user', 'regex', 'jev', 'laya', 'llm', 'import');
create type public.review_state as enum ('suggested', 'automatic', 'confirmed', 'corrected');

create table public.product (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  -- Given by the user; null shows the latest store item description.
  name text,
  category_id uuid,
  category_source public.value_source,
  category_review public.review_state,
  category_model text,
  category_confidence real check (category_confidence between 0 and 1),
  category_at timestamptz,
  -- Set only by hand; parsed sizes live on the store item. Manual with no size: the parsed size doesn't apply.
  size_e6 bigint,
  size_unit text check (size_unit in ('kg', 'l')),
  size_source text check (size_source in ('manual')),
  archived boolean not null default false,
  deleted boolean not null default false,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, id),
  foreign key (user_id, category_id) references public.category (user_id, id)
);

-- One per code as receipts show it: a GTIN anywhere, or a store code together with its store's CNPJ.
create table public.store_item (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  code_kind text not null check (code_kind in ('gtin', 'store')),
  code text not null,
  -- Empty for gtin.
  store_cnpj text not null default '',
  description text not null,
  parsed_size_e6 bigint,
  parsed_size_unit text check (parsed_size_unit in ('kg', 'l')),
  product_id uuid not null,
  -- Who linked it to its product: the parser (a product of its own), the user, or a model's proposal.
  link_source public.value_source not null default 'regex',
  link_review public.review_state,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  unique (user_id, id),
  unique (user_id, code_kind, code, store_cnpj),
  foreign key (user_id, product_id) references public.product (user_id, id)
);

create table public.product_tag (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  product_id uuid not null,
  tag_id uuid not null,
  deleted boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, product_id, tag_id),
  foreign key (user_id, product_id) references public.product (user_id, id),
  foreign key (user_id, tag_id) references public.tag (user_id, id)
);

-- One printed line of a receipt. Extracted fields are evidence; corrections have their own columns.
create table public.receipt_line (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  chave text not null,
  -- The receipt's own item number (nItem): stable across re-parses.
  line_no int not null,
  description text not null,
  code text not null,
  qty_e4 bigint not null,
  unit text,
  unit_price_e4 bigint not null,
  gross_cents bigint not null,
  store_item_id uuid not null,
  -- A correction for this line only.
  product_override_id uuid,
  discount_cents bigint not null default 0,
  discount_source text not null check (discount_source in ('html', 'photo', 'manual', 'prorated')),
  discount_review text not null default 'unreviewed' check (discount_review in ('unreviewed', 'confirmed')),
  orphaned boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, chave, line_no),
  foreign key (user_id, chave) references public.receipt (user_id, chave) on delete cascade,
  foreign key (user_id, store_item_id) references public.store_item (user_id, id),
  foreign key (user_id, product_override_id) references public.product (user_id, id)
);

create index on public.product (user_id, category_id);
create index on public.store_item (user_id, product_id);
create index on public.receipt_line (user_id, store_item_id);

-- Pull cursors: the phone asks for rows changed since the last time.
create index on public.issuer (user_id, updated_at);
create index on public.receipt (user_id, updated_at);
create index on public.receipt_photo (user_id, updated_at);
create index on public.category (user_id, updated_at);
create index on public.tag (user_id, updated_at);
create index on public.product (user_id, updated_at);
create index on public.store_item (user_id, updated_at);
create index on public.product_tag (user_id, updated_at);
create index on public.receipt_line (user_id, updated_at);

create function public.touch() returns trigger language plpgsql as $$
begin
  new.updated_at := clock_timestamp();
  return new;
end $$;

-- A user's own choice is final (D51): a model or a re-parse can't replace it. Only another user value can.
create function public.keep_user_choices() returns trigger language plpgsql as $$
begin
  if tg_table_name = 'product' then
    if old.category_source = 'user' and new.category_source is distinct from 'user' then
      new.category_id := old.category_id; new.category_source := old.category_source;
      new.category_review := old.category_review; new.category_model := old.category_model;
      new.category_confidence := old.category_confidence; new.category_at := old.category_at;
    end if;
    if old.size_source = 'manual' and new.size_source is distinct from 'manual' then
      new.size_e6 := old.size_e6; new.size_unit := old.size_unit; new.size_source := old.size_source;
    end if;
  elsif tg_table_name = 'store_item' then
    if old.link_source = 'user' and new.link_source is distinct from 'user' then
      new.product_id := old.product_id; new.link_source := old.link_source; new.link_review := old.link_review;
    end if;
  end if;
  return new;
end $$;

create trigger keep_user_choices before update on public.product for each row execute function public.keep_user_choices();
create trigger keep_user_choices before update on public.store_item for each row execute function public.keep_user_choices();

do $$
declare t text;
begin
  foreach t in array array['issuer', 'receipt', 'receipt_photo', 'category', 'tag', 'product', 'store_item',
                           'product_tag', 'receipt_line'] loop
    execute format('create trigger touch before insert or update on public.%I for each row execute function public.touch()', t);
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy own_rows on public.%I for all to authenticated
                      using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end $$;

-- The seed is readable by any signed-in user and written only by migrations.
alter table public.taxonomy_node enable row level security;
alter table public.taxonomy_tag enable row level security;
create policy read_seed on public.taxonomy_node for select to authenticated using (true);
create policy read_seed on public.taxonomy_tag for select to authenticated using (true);
revoke all on public.taxonomy_node, public.taxonomy_tag from anon;
grant select on public.taxonomy_node, public.taxonomy_tag to authenticated;

-- Brings a user's categories and tags up to the seed: adds new nodes, updates labels, parents and order the user
-- hasn't changed, and never recreates a deleted (tombstoned) one. Parents come first, so ids resolve.
create function public.sync_taxonomy_for(uid uuid) returns void language plpgsql security definer
set search_path = '' as $$
declare node record;
begin
  for node in
    with recursive t as (
      select path, label, parent_path, sort, 0 as depth from public.taxonomy_node where parent_path is null
      union all
      select n.path, n.label, n.parent_path, n.sort, t.depth + 1 from public.taxonomy_node n join t on n.parent_path = t.path
    ) select * from t order by depth, sort
  loop
    insert into public.category (user_id, seed_path, label, parent_id, sort)
    values (uid, node.path, node.label,
            (select id from public.category where user_id = uid and seed_path = node.parent_path), node.sort)
    on conflict (user_id, seed_path) do update
      set label = excluded.label, parent_id = excluded.parent_id, sort = excluded.sort
      where not public.category.user_modified and not public.category.deleted
        and (public.category.label, public.category.parent_id, public.category.sort)
            is distinct from (excluded.label, excluded.parent_id, excluded.sort);
  end loop;
  insert into public.tag (user_id, slug, description)
  select uid, slug, description from public.taxonomy_tag
  on conflict (user_id, slug) do nothing;
end $$;

-- For the signed-in user only; the phone calls it after signing in, so taxonomy changes reach existing accounts.
create function public.sync_taxonomy() returns void language sql security definer
set search_path = '' as $$ select public.sync_taxonomy_for((select auth.uid())) $$;

create function public.seed_new_user() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  perform public.sync_taxonomy_for(new.id);
  return new;
end $$;
create trigger seed_new_user after insert on auth.users for each row execute function public.seed_new_user();

revoke execute on function public.sync_taxonomy_for(uuid) from public, anon, authenticated;
revoke execute on function public.sync_taxonomy() from public, anon;
grant execute on function public.sync_taxonomy() to authenticated;

-- Pages, photos and masks: one private bucket, one folder per user.
insert into storage.buckets (id, name, public) values ('receipts', 'receipts', false);
create policy own_folder on storage.objects for all to authenticated
  using (bucket_id = 'receipts' and (storage.foldername(name))[1] = (select auth.uid())::text)
  with check (bucket_id = 'receipts' and (storage.foldername(name))[1] = (select auth.uid())::text);
