-- Testers' reports and tickets (D152): anyone with the app writes a report, nobody reads one through the API.
-- Run: npx supabase test db
begin;
select plan(13);

set local role anon;
select lives_ok($$insert into public.feedback (kind, body, about, install_id)
                  values ('comentario', 'A câmera não sai do lugar', 'Caderneta 0.1.0 (3)', '00000000-0000-0000-0000-00000000f001')$$,
                'signed out, a report goes in');
select throws_ok($$select * from public.feedback$$, '42501', null, 'and can''t be read back');
select throws_ok($$insert into public.feedback (kind, body, about, install_id, ticket_id)
                   values ('comentario', 'x', 'y', '00000000-0000-0000-0000-00000000f001', 1)$$,
                 '42501', null, 'a report can''t place itself in a ticket');
select throws_ok($$insert into public.feedback (kind, body, about, install_id)
                   values ('outra', 'x', 'y', '00000000-0000-0000-0000-00000000f001')$$,
                 '23514', null, 'the kinds are the app''s three');
select throws_ok($$insert into public.feedback (kind, body, about, install_id)
                   values ('comentario', '', 'y', '00000000-0000-0000-0000-00000000f001')$$,
                 '23514', null, 'an empty report is refused');
select throws_ok($$select * from public.ticket$$, '42501', null, 'tickets are out of reach');
select throws_ok($$select * from public.tickets_abertos$$, '42501', null, 'and so is their view');

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select lives_ok($$insert into public.feedback (kind, body, about, install_id)
                  values ('travamento', 'java.lang.IllegalStateException', 'Caderneta 0.1.0 (3)', '00000000-0000-0000-0000-00000000f002')$$,
                'signed in, the same');
select throws_ok($$update public.feedback set body = 'x'$$, '42501', null, 'nobody changes a report');
select throws_ok($$delete from public.feedback$$, '42501', null, 'or deletes one');

-- One install sends its 30th report of the day; the 31st is turned away.
reset role;
insert into public.feedback (kind, body, about, install_id)
  select 'comentario', 'spam ' || g, 'x', '00000000-0000-0000-0000-00000000f003' from generate_series(1, 30) g;
set local role anon;
select throws_ok($$insert into public.feedback (kind, body, about, install_id)
                   values ('comentario', 'mais um', 'x', '00000000-0000-0000-0000-00000000f003')$$,
                 'P0001', null, 'a flood from one install stops at 30 a day');

-- Triage, as the developer's tool does it.
reset role;
insert into public.ticket (title, kind, area) values ('Captura trava depois do QR', 'bug', 'captura');
update public.feedback set ticket_id = (select max(id) from public.ticket), triaged_at = now()
 where install_id in ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-00000000f002');
select is((select relatos from public.tickets_abertos), 2::bigint, 'the view counts a ticket''s reports');
update public.ticket set state = 'resolvido', resolved_at = now();
select is((select count(*) from public.tickets_abertos), 0::bigint, 'a resolved ticket leaves the view');

select * from finish();
rollback;
