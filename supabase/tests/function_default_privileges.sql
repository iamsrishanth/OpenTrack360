begin;
select extensions.plan(5);

create function public.opentrack_default_privilege_probe()
returns boolean
language sql
as $$ select true $$;

select extensions.is(current_user, 'postgres'::name,
  'the default-privilege probe runs as the migration owner');
select extensions.ok(not has_function_privilege('anon',
  'public.opentrack_default_privilege_probe()'::regprocedure, 'execute'),
  'a newly created public function is not executable by anon');
select extensions.ok(not has_function_privilege('authenticated',
  'public.opentrack_default_privilege_probe()'::regprocedure, 'execute'),
  'a newly created public function is not executable by authenticated');
select extensions.ok(has_function_privilege('service_role',
  'public.opentrack_default_privilege_probe()'::regprocedure, 'execute'),
  'the intended service_role schema default remains available');
select extensions.ok(has_function_privilege('postgres',
  'public.opentrack_default_privilege_probe()'::regprocedure, 'execute'),
  'the function owner retains execute');

select * from extensions.finish();
rollback;
