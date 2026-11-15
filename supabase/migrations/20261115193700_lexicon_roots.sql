-- Words for the new "Raízes e tubérculos" category (food.produce.roots_tubers), so Jev reads them in English as it reads
-- every other item word (D59). Generic produce names, not taken from any receipt. Existing keys are left as they are.
-- compat: additive; no client reads the lexicon.
insert into public.lexicon (key, pt, en) values
  ('AIPIM', 'aipim', 'cassava'),
  ('MANDIOCA', 'mandioca', 'cassava'),
  ('MACAXEIRA', 'macaxeira', 'cassava'),
  ('INHAME', 'inhame', 'taro'),
  ('MANDIOQUINHA', 'mandioquinha', 'arracacha'),
  ('BAROA', 'baroa', 'arracacha'),
  ('BATATA DOCE', 'batata-doce', 'sweet potato'),
  ('BAT DOCE', 'batata-doce', 'sweet potato'),
  ('BATATA SALSA', 'batata-salsa', 'arracacha')
on conflict (key) do nothing;
