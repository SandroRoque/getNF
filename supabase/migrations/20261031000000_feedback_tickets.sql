-- Testers' reports and the tickets made from them (D152). The app writes reports, signed in or not; nobody reads them
-- through the Data API: the developer does, with the secret key (tools/tickets.py) or in the dashboard. The code is
-- public, the rows aren't.

create table public.ticket (
  id bigint generated always as identity primary key,  -- shown as T-<id>
  title text not null check (length(title) between 1 and 200),
  kind text not null check (kind in ('bug', 'ideia', 'duvida')),
  area text not null check (area in ('captura', 'sefaz', 'sync', 'telas', 'dados', 'outro')),
  priority text not null default 'normal' check (priority in ('urgente', 'normal', 'baixa')),
  state text not null default 'aberto' check (state in ('aberto', 'andamento', 'resolvido', 'descartado')),
  notes text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- When resolved: the commit and the app version that carry the fix.
  resolved_at timestamptz,
  resolved_commit text,
  resolved_version text
);

create table public.feedback (
  id bigint generated always as identity primary key,
  kind text not null check (kind in ('comentario', 'travamento', 'sincronizacao')),
  body text not null check (length(body) between 1 and 30000),
  -- The app's version and the phone (CrashLog.about).
  about text not null check (length(about) <= 300),
  -- A random id kept by the app's install: tells reports from one phone apart, says nothing of who.
  install_id uuid not null,
  written_at timestamptz,  -- the phone's clock, when the person sent it (it may go up later)
  received_at timestamptz not null default now(),
  -- Triage: a report joins a ticket, or is set aside with a note (a test, a duplicate of nothing, spam).
  ticket_id bigint references public.ticket (id) on delete set null,
  triaged_at timestamptz,
  note text
);
create index feedback_untriaged on public.feedback (received_at) where triaged_at is null;
create index feedback_ticket on public.feedback (ticket_id);
create index feedback_install on public.feedback (install_id, received_at);

alter table public.ticket enable row level security;
alter table public.feedback enable row level security;

-- Insert only, and only the report's own columns; no policy reads, so a tester never sees another's report.
revoke all on public.ticket, public.feedback from anon, authenticated;
grant insert (kind, body, about, install_id, written_at) on public.feedback to anon, authenticated;
create policy anyone_reports on public.feedback for insert to anon, authenticated with check (true);

grant select, insert, update, delete on public.ticket, public.feedback to service_role;

-- The publishable key is in every copy of the app: a flood from one install, or from all, is turned away. P0001 is
-- a 400 for the Data API, which the app takes as final (a 5xx it would retry).
create function public.feedback_limits() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if (select count(*) from public.feedback where install_id = new.install_id and received_at > now() - interval '1 day') >= 30 then
    raise exception 'too many reports from this install today' using errcode = 'P0001';
  end if;
  if (select count(*) from public.feedback where received_at > now() - interval '1 hour') >= 300 then
    raise exception 'too many reports this hour' using errcode = 'P0001';
  end if;
  return new;
end $$;
revoke execute on function public.feedback_limits() from public, anon, authenticated;
create trigger feedback_limits before insert on public.feedback for each row execute function public.feedback_limits();

create function public.ticket_touch() returns trigger language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;
revoke execute on function public.ticket_touch() from public, anon, authenticated;
create trigger ticket_touch before update on public.ticket for each row execute function public.ticket_touch();

-- What the dashboard's table editor shows first: the open tickets, the urgent and the most reported on top.
create view public.tickets_abertos with (security_invoker = true) as
select 'T-' || t.id as ticket, t.priority as prioridade, t.title as titulo, t.kind as tipo, t.area, t.state as estado,
       count(f.id) as relatos, count(distinct f.install_id) as aparelhos, max(f.received_at) as ultimo_relato,
       t.created_at as aberto_em
  from public.ticket t left join public.feedback f on f.ticket_id = t.id
 where t.state in ('aberto', 'andamento')
 group by t.id
 order by case t.priority when 'urgente' then 0 when 'normal' then 1 else 2 end, count(distinct f.install_id) desc, t.id;
revoke all on public.tickets_abertos from anon, authenticated;
grant select on public.tickets_abertos to service_role;
