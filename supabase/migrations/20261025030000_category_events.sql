-- Stage 4c (D75): every edit of a user's category tree is recorded, and categories get a one-line description.

-- What the category is for, in a line; models read it instead of the label (the seed's English description stays
-- the fallback). Written by the user, or drafted by the LLM for the user to accept.
alter table public.category add column description text;

-- Append-only: one row per tree edit, written by the phone in the same transaction as the edit. Nothing updates or
-- deletes it (no grants for that). No foreign keys: history must outlive the categories it mentions.
create table public.category_event (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  op text not null check (op in ('create', 'rename', 'move', 'merge', 'split', 'delete')),
  -- The category edited, created, merged away, split or deleted.
  category_id uuid not null,
  -- Merge: the category it went into. Split: the new subcategories. Delete: where its products went.
  target_ids uuid[] not null default '{}',
  old_label text,
  new_label text,
  old_parent_id uuid,
  new_parent_id uuid,
  -- How many products the edit moved or sent to review, for reading the history later.
  products int not null default 0,
  at timestamptz not null,
  updated_at timestamptz not null default clock_timestamp()
);
create index on public.category_event (user_id, updated_at);

alter table public.category_event enable row level security;
create policy own_rows on public.category_event for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
revoke all on public.category_event from anon;
-- Insert and read only. A re-push of the same event (a lost response) is an upsert that changes nothing, so the
-- phone sends it with `ignore-duplicates`.
grant select, insert on public.category_event to authenticated;
