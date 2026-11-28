-- The schema-scoped revoke in 20261126000200 cannot remove PostgreSQL's
-- built-in global PUBLIC EXECUTE grant for functions. Restrict future
-- postgres-owned functions to explicit grants; public RPCs can still grant
-- service_role through the existing per-schema default.
do $$
begin
  if current_user <> 'postgres' then
    raise exception 'function default-privilege migration must run as postgres';
  end if;
end;
$$;

alter default privileges for role postgres
  revoke execute on functions from public, anon, authenticated;
