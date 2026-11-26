create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create table private.member_accounts (
  account_id uuid primary key default gen_random_uuid(),
  auth_user_id uuid unique references auth.users(id) on delete set null,
  username_normalized text not null unique check (username_normalized ~ '^[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]$'),
  display_name text not null check (length(display_name) between 1 and 80),
  auth_email text not null unique,
  status text not null default 'provisioning' check (status in ('provisioning', 'active', 'disabled', 'deleting')),
  is_platform_admin boolean not null default false,
  must_rotate_password boolean not null default true,
  reset_pending boolean not null default false,
  session_epoch bigint not null default 1 check (session_epoch > 0),
  sharing_epoch bigint not null default 1 check (sharing_epoch > 0),
  sharing_enabled boolean not null default false,
  activity_sharing_enabled boolean not null default false,
  created_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  disabled_at timestamptz,
  last_login_at timestamptz
);

create table private.member_sessions (
  session_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  session_epoch bigint not null,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz
);
create index member_sessions_user_active_idx on private.member_sessions(user_id, session_epoch) where revoked_at is null;

create table private.admin_audit_events (
  audit_id bigint generated always as identity primary key,
  actor_user_id uuid,
  actor_username text,
  target_account_id uuid,
  event_type text not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index admin_audit_events_created_idx on private.admin_audit_events(created_at desc, audit_id desc);

create table private.account_provision_requests (
  request_id uuid primary key,
  account_id uuid not null unique references private.member_accounts(account_id) on delete cascade,
  created_by uuid not null,
  lease_token uuid not null default gen_random_uuid(),
  lease_expires_at timestamptz not null default (now()+interval '15 minutes'),
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create table private.admin_reset_requests (
  request_id uuid primary key,
  account_id uuid not null references private.member_accounts(account_id) on delete cascade,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create table private.bootstrap_state (
  singleton boolean primary key default true check (singleton),
  claim_id uuid,
  claimed_at timestamptz,
  consumed_at timestamptz
);
insert into private.bootstrap_state(singleton) values (true) on conflict do nothing;
create table private.login_rate_limits (
  username_digest bytea primary key,
  window_started_at timestamptz not null,
  attempts integer not null default 0,
  blocked_until timestamptz
);

create table private.circles (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(name) between 1 and 80),
  kind text not null default 'standard' check (kind in ('standard', 'temporary', 'lightweight')),
  created_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  archived_at timestamptz
);
create table private.circle_members (
  circle_id uuid not null references private.circles(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('admin', 'member')),
  membership_state text not null default 'active' check (membership_state in ('active', 'left', 'removed')),
  sharing_enabled boolean not null default false,
  activity_sharing_enabled boolean not null default false,
  precision text not null default 'precise' check (precision in ('precise', 'approximate', 'hidden')),
  joined_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (circle_id, user_id)
);
create index circle_members_user_idx on private.circle_members(user_id, circle_id) where membership_state = 'active';
create table private.circle_invites (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references private.circles(id) on delete cascade,
  token_hash text not null unique,
  created_by uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('admin', 'member')),
  client_request_id uuid,
  expires_at timestamptz not null,
  max_uses integer not null default 1 check (max_uses between 1 and 100),
  uses integer not null default 0,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index circle_invites_request_idx on private.circle_invites(circle_id, created_by, client_request_id) where client_request_id is not null;

create table private.location_samples (
  sample_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  session_id uuid not null references private.member_sessions(session_id) on delete cascade,
  sharing_epoch bigint not null,
  captured_at timestamptz not null,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy_meters double precision not null check (accuracy_meters between 0 and 10000),
  speed_mps double precision check (speed_mps is null or speed_mps between 0 and 150),
  received_at timestamptz not null default now()
);
create index location_samples_user_time_idx on private.location_samples(user_id, captured_at desc);
create table private.latest_locations (
  user_id uuid primary key references auth.users(id) on delete cascade,
  sample_id uuid not null unique references private.location_samples(sample_id) on delete cascade,
  captured_at timestamptz not null,
  latitude double precision not null,
  longitude double precision not null,
  accuracy_meters double precision not null,
  speed_mps double precision,
  sharing_epoch bigint not null,
  updated_at timestamptz not null default now()
);
create table private.member_bubbles (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references private.circles(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  radius_meters integer not null check (radius_meters between 50 and 10000),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  ended_at timestamptz
);

create table private.trips (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  circle_id uuid references private.circles(id) on delete set null,
  status text not null default 'active' check (status in ('active', 'ended')),
  confirmed_drive boolean not null default false,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  destination_label text,
  destination_latitude double precision,
  destination_longitude double precision,
  recipients uuid[] not null default '{}',
  end_reason text
);
create index trips_user_active_idx on private.trips(user_id, started_at desc) where status = 'active';
create table private.guest_shares (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  token_hash text not null unique,
  mode text not null check (mode in ('time', 'destination', 'drive_end')),
  status text not null default 'active' check (status in ('active', 'ended')),
  sharing_epoch bigint not null,
  created_at timestamptz not null default now(),
  started_at timestamptz not null default now(),
  expires_at timestamptz,
  destination_latitude double precision,
  destination_longitude double precision,
  destination_radius_meters integer,
  ended_at timestamptz,
  end_reason text,
  check ((mode = 'time' and expires_at is not null and destination_latitude is null and destination_longitude is null and destination_radius_meters is null)
      or (mode = 'destination' and destination_latitude between -90 and 90 and destination_longitude between -180 and 180 and destination_radius_meters between 50 and 1000)
      or (mode = 'drive_end' and expires_at is null and destination_latitude is null and destination_longitude is null and destination_radius_meters is null))
);
create index guest_shares_owner_active_idx on private.guest_shares(owner_id, created_at desc) where status = 'active';
create table private.share_events (
  client_event_id uuid primary key,
  share_id uuid not null references private.guest_shares(id) on delete cascade,
  owner_id uuid not null references auth.users(id) on delete cascade,
  event_type text not null check (event_type in ('arrival', 'drive_end')),
  status text not null check (status in ('confirmed', 'pending', 'duplicate')),
  observed_at timestamptz not null,
  result jsonb not null,
  created_at timestamptz not null default now()
);
create table private.client_commands (
  user_id uuid not null references auth.users(id) on delete cascade,
  client_request_id uuid not null,
  command_type text not null,
  response jsonb not null,
  created_at timestamptz not null default now(),
  primary key(user_id, client_request_id, command_type)
);

create table private.api_records (
  id uuid primary key default gen_random_uuid(),
  resource_type text not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  circle_id uuid references private.circles(id) on delete cascade,
  parent_id uuid,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index api_records_circle_type_idx on private.api_records(circle_id, resource_type, created_at desc) where deleted_at is null;
create index api_records_owner_type_idx on private.api_records(owner_id, resource_type, created_at desc) where deleted_at is null;

create table private.pets (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (length(name) between 1 and 80),
  species text,
  photo_object_path text,
  recovery_enabled boolean not null default false,
  recovery_fields text[] not null default '{}',
  owner_message text not null default '',
  contact_route text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table private.pet_circles (
  pet_id uuid not null references private.pets(id) on delete cascade,
  circle_id uuid not null references private.circles(id) on delete cascade,
  primary key(pet_id, circle_id)
);
create table private.pet_recovery_tokens (
  id uuid primary key default gen_random_uuid(),
  pet_id uuid not null references private.pets(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create table private.pet_sightings (
  id uuid primary key default gen_random_uuid(),
  pet_id uuid not null references private.pets(id) on delete cascade,
  message text not null check (length(message) between 1 and 1000),
  latitude double precision,
  longitude double precision,
  accuracy_meters double precision,
  contact_route text,
  created_at timestamptz not null default now()
);
create table private.pet_sighting_rate_limits (
  token_id uuid primary key references private.pet_recovery_tokens(id) on delete cascade,
  window_started_at timestamptz not null,
  attempts integer not null default 0
);

create table private.guardian_consents (
  id uuid primary key default gen_random_uuid(),
  child_user_id uuid not null references auth.users(id) on delete cascade,
  guardian_username_normalized text not null,
  relationship text not null check (relationship in ('parent', 'guardian')),
  token_hash text not null unique,
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected', 'expired')),
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
create table private.notifications (
  id uuid primary key default gen_random_uuid(),
  recipient_user_id uuid not null references auth.users(id) on delete cascade,
  circle_id uuid references private.circles(id) on delete cascade,
  kind text not null,
  title text,
  body text,
  delivery_state text not null default 'queued' check (delivery_state in ('queued', 'submitted', 'received', 'acknowledged', 'failed')),
  provider_message_id text,
  created_at timestamptz not null default now(),
  acknowledged_at timestamptz
);
create index notifications_recipient_created_idx on private.notifications(recipient_user_id, created_at desc);

do $$
declare r record;
begin
  for r in select schemaname, tablename from pg_tables where schemaname = 'private' loop
    execute format('alter table %I.%I enable row level security', r.schemaname, r.tablename);
  end loop;
end;
$$;

revoke all on all tables in schema private from public, anon, authenticated;
grant select, insert, update, delete on all tables in schema private to service_role;
grant usage, select on all sequences in schema private to service_role;
alter default privileges in schema private revoke all on tables from public, anon, authenticated;
