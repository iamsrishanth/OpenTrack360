-- Rebuild the member dispatcher with pgcrypto calls qualified. Its fixed empty
-- search_path intentionally excludes extension schemas from implicit lookup.
do $$
declare
  v_definition text;
begin
  v_definition := pg_get_functiondef(
    'public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint)'::regprocedure
  );
  v_definition := replace(
    v_definition,
    'encode(gen_random_bytes(',
    'encode(extensions.gen_random_bytes('
  );
  execute v_definition;
end;
$$;
