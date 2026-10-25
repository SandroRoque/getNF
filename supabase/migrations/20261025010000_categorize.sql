-- Stage 4b (D70): Jev suggests a category for every new store item whose product has none.
-- A store item insert queues a job; pg_cron calls the `categorize` Edge Function every minute while jobs wait; the
-- function asks Jev (one choice over the user's leaf categories, D67) and writes the answer back: at or above the
-- threshold as the category ("automatic, not confirmed", D69), below it as a suggestion for the review list.

alter table public.taxonomy_node add column description_en text;

-- A suggestion waits for the user and doesn't count in reports; the category columns hold what does count.
alter table public.product
  add column suggestion_category_id uuid,
  add column suggestion_confidence real check (suggestion_confidence between 0 and 1),
  add column suggestion_model text,
  add column suggestion_at timestamptz,
  add foreign key (user_id, suggestion_category_id) references public.category (user_id, id);

-- A write that doesn't say where its value came from (a phone that hasn't pulled a categorization yet) can't clear
-- one that does; only the user can. Added to D51's rule that nothing but the user replaces the user's choice.
create or replace function public.keep_user_choices() returns trigger language plpgsql as $$
begin
  if tg_table_name = 'product' then
    if (old.category_source = 'user' and new.category_source is distinct from 'user')
       or (old.category_source is not null and new.category_source is null) then
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

-- Abbreviation translations shared by everyone (receipt words, not anyone's data): `QJO` -> cheese. Loaded by
-- tools/load-lexicon.py and, later, kept by the LLM (D69). Read only by the function (service role).
create table public.lexicon (
  key text primary key,
  pt text not null,
  en text not null,
  source text not null default 'claude',
  updated_at timestamptz not null default clock_timestamp()
);

-- What Open Food Facts says about a barcode (D60, D68): looked up once, for everyone.
create table public.barcode_lookup (
  gtin text primary key,
  product jsonb,
  fetched_at timestamptz not null default clock_timestamp()
);

create table public.categorize_job (
  user_id uuid not null references auth.users on delete cascade,
  store_item_id uuid not null,
  status text not null default 'pending' check (status in ('pending', 'running', 'done', 'failed')),
  attempts int not null default 0,
  -- now(), not clock_timestamp(): comparable with the now() that claims and kicks compare it to.
  next_try_at timestamptz not null default now(),
  last_error text,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, store_item_id),
  foreign key (user_id, store_item_id) references public.store_item (user_id, id) on delete cascade
);
create index on public.categorize_job (status, next_try_at);
create trigger touch before update on public.categorize_job for each row execute function public.touch();

-- Service role only: RLS on with no policies, and no grants to app users.
alter table public.lexicon enable row level security;
alter table public.barcode_lookup enable row level security;
alter table public.categorize_job enable row level security;
revoke all on public.lexicon, public.barcode_lookup, public.categorize_job from anon, authenticated;

-- Runs as the inserting user, so it needs definer rights to write the queue.
create function public.queue_categorize() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  if exists (select 1 from public.product p where p.user_id = new.user_id and p.id = new.product_id
             and p.category_id is null and p.suggestion_category_id is null) then
    insert into public.categorize_job (user_id, store_item_id) values (new.user_id, new.id) on conflict do nothing;
  end if;
  return new;
end $$;
create trigger queue_categorize after insert on public.store_item for each row execute function public.queue_categorize();

-- Whatever is uncategorized already.
insert into public.categorize_job (user_id, store_item_id)
select s.user_id, s.id from public.store_item s join public.product p on p.user_id = s.user_id and p.id = s.product_id
where p.category_id is null and not s.deleted and not p.deleted on conflict do nothing;

-- The function takes a batch atomically: two runs never take the same job. A job stuck in `running` (a run that
-- died) is taken again after ten minutes.
create function public.claim_categorize_jobs(batch int) returns table (
  user_id uuid, store_item_id uuid, product_id uuid, code_kind text, code text, description text, attempts int
) language sql security definer set search_path = '' as $$
  update public.categorize_job j set status = 'running', attempts = j.attempts + 1
  from public.store_item s
  where s.user_id = j.user_id and s.id = j.store_item_id
    and (j.user_id, j.store_item_id) in (
      select user_id, store_item_id from public.categorize_job
      where (status = 'pending' and next_try_at <= now()) or (status = 'running' and updated_at < now() - interval '10 minutes')
      order by created_at limit batch for update skip locked)
  returning j.user_id, j.store_item_id, s.product_id, s.code_kind, s.code, s.description, j.attempts
$$;

-- Writes Jev's answer, unless the product got a category or a suggestion meanwhile (the user, or another item of
-- the same product). Returns what it did.
create function public.apply_categorization(
  uid uuid, item uuid, product uuid, category uuid, confidence real, model text, threshold real
) returns text language plpgsql security definer set search_path = '' as $$
declare outcome text;
begin
  if apply_categorization.confidence >= apply_categorization.threshold then
    update public.product p set category_id = apply_categorization.category, category_source = 'jev',
      category_review = 'automatic', category_model = apply_categorization.model,
      category_confidence = apply_categorization.confidence, category_at = now()
    where p.user_id = uid and p.id = apply_categorization.product and p.category_id is null;
    outcome := case when found then 'automatic' else 'skipped' end;
  else
    update public.product p set suggestion_category_id = apply_categorization.category,
      suggestion_confidence = apply_categorization.confidence, suggestion_model = apply_categorization.model,
      suggestion_at = now()
    where p.user_id = uid and p.id = apply_categorization.product and p.category_id is null and p.suggestion_category_id is null;
    outcome := case when found then 'suggested' else 'skipped' end;
  end if;
  update public.categorize_job j set status = 'done', last_error = null where j.user_id = uid and j.store_item_id = item;
  return outcome;
end $$;

create function public.fail_categorize_job(uid uuid, item uuid, error text) returns void
language sql security definer set search_path = '' as $$
  update public.categorize_job
  set status = case when attempts >= 6 then 'failed' else 'pending' end, last_error = error,
      next_try_at = now() + make_interval(mins => power(2, attempts)::int)
  where user_id = uid and store_item_id = item
$$;

revoke execute on function public.queue_categorize(), public.claim_categorize_jobs(int),
  public.apply_categorization(uuid, uuid, uuid, uuid, real, text, real), public.fail_categorize_job(uuid, uuid, text)
  from public, anon, authenticated;
grant execute on function public.claim_categorize_jobs(int),
  public.apply_categorization(uuid, uuid, uuid, uuid, real, text, real), public.fail_categorize_job(uuid, uuid, text)
  to service_role;

-- Every minute, if anything waits, call the function. Its address and shared secret live in Vault (set once per
-- project, never in git): `categorize_url` and `categorize_secret`. Without them nothing is called.
create extension if not exists pg_net;
create extension if not exists pg_cron;

create function public.kick_categorize() returns void language plpgsql security definer set search_path = '' as $$
declare url text; secret text;
begin
  if not exists (select 1 from public.categorize_job
                 where (status = 'pending' and next_try_at <= now())
                    or (status = 'running' and updated_at < now() - interval '10 minutes')) then
    return;
  end if;
  select decrypted_secret into url from vault.decrypted_secrets where name = 'categorize_url';
  select decrypted_secret into secret from vault.decrypted_secrets where name = 'categorize_secret';
  if url is null or secret is null then return; end if;
  perform net.http_post(url := url, body := '{}'::jsonb,
                        headers := jsonb_build_object('content-type', 'application/json', 'x-categorize-secret', secret),
                        timeout_milliseconds := 60000);
end $$;
revoke execute on function public.kick_categorize() from public, anon, authenticated;

select cron.schedule('categorize', '* * * * *', 'select public.kick_categorize()');
