-- D78: medicine moves from personal care ("Medicamentos sem receita") to Saúde as "Remédios", prescription or not.
-- A seed path is a category's identity across taxonomy updates, so it's renamed in place: every user keeps the same
-- category (same id, same products), now under the new path. Users who hadn't changed it get the new label and
-- parent; those who had keep theirs (D45's rule: the seed never overwrites a user's edit). The regenerated taxonomy
-- migration that follows adds Saúde's other child and the descriptions.
insert into public.taxonomy_node (path, label, parent_path, sort, description_en)
values ('health.medicine', 'Remédios', 'health', 1, 'Medicine and vitamins, with or without a prescription')
on conflict (path) do nothing;

update public.category set seed_path = 'health.medicine' where seed_path = 'personal_care.pharmacy_otc';
update public.category c set label = 'Remédios', sort = 1,
  parent_id = (select h.id from public.category h where h.user_id = c.user_id and h.seed_path = 'health')
where c.seed_path = 'health.medicine' and not c.user_modified and not c.deleted;

delete from public.taxonomy_node where path = 'personal_care.pharmacy_otc';
