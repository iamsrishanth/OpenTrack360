-- Attach every drive-end guest share to the exact confirmed driver trip whose
-- end condition it is observing. An unlinked legacy row must be reviewed
-- before upgrade; this migration never rewrites existing share records.
alter table private.guest_shares add column if not exists trip_id uuid;

do $$
begin
  if exists (
    select 1 from private.guest_shares
    where mode='drive_end' and trip_id is null
  ) then
    raise exception 'Legacy drive-end shares have no trip_id; explicitly review and attach or remove them before applying this migration';
  end if;
end;
$$;

alter table private.guest_shares drop constraint if exists guest_shares_trip_id_fkey;
alter table private.trips drop constraint if exists trips_id_user_key;
alter table private.trips add constraint trips_id_user_key unique (id,user_id);
alter table private.guest_shares add constraint guest_shares_trip_id_fkey
  foreign key (trip_id,owner_id) references private.trips(id,user_id) on delete cascade;
alter table private.guest_shares drop constraint if exists guest_shares_trip_mode_check;
alter table private.guest_shares add constraint guest_shares_trip_mode_check check (
  (mode='drive_end' and trip_id is not null)
  or (mode<>'drive_end' and trip_id is null)
);
create index if not exists guest_shares_active_trip_idx
  on private.guest_shares(trip_id) where mode='drive_end' and status='active';

drop function if exists public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text);

create or replace function public.opentrack_share_create(
  p_user_id uuid, p_session_id uuid, p_sharing_epoch bigint, p_mode text,
  p_expires_at timestamptz, p_destination jsonb, p_secret_hash text, p_confirmed_drive boolean
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  v_share private.guest_shares%rowtype;
  v_trip private.trips%rowtype;
  v_trip_id uuid;
  v_lat double precision;
  v_lon double precision;
  v_radius integer;
  v_end_condition text;
  v_now timestamptz;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active' for update;
  if a.account_id is null or not exists(select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id and s.session_epoch=a.session_epoch and s.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then
    return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required')));
  end if;
  if p_sharing_epoch is distinct from a.sharing_epoch then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','SHARING_EPOCH_MISMATCH','message','Refresh the member profile and discard queued samples from an older sharing epoch.')));
  end if;
  v_now := pg_catalog.clock_timestamp();
  if p_mode is null or p_mode not in ('time','destination','drive_end') or p_secret_hash is null or p_secret_hash !~ '^[0-9a-f]{64}$' then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid share details')));
  end if;
  if p_mode <> 'drive_end' and coalesce(p_confirmed_drive,false) then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Drive confirmation applies only to drive-end shares')));
  end if;
  if p_mode='time' and (p_expires_at is null or p_expires_at <= v_now or p_expires_at > v_now+interval '30 days') then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_expiry','message','Expiry must be within 30 days')));
  end if;
  if p_mode='destination' then
    begin
      v_lat := (p_destination->>'latitude')::double precision;
      v_lon := (p_destination->>'longitude')::double precision;
      v_radius := coalesce((p_destination->>'radiusMeters')::integer,150);
    exception when others then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_destination','message','Invalid destination')));
    end;
    if v_lat not between -90 and 90 or v_lon not between -180 and 180 or v_radius not between 50 and 1000 then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_destination','message','Invalid destination')));
    end if;
  end if;
  if p_mode='drive_end' then
    select * into v_trip from private.trips t
    where t.user_id=p_user_id and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
    order by t.started_at desc,t.id desc limit 1 for update;
    if found then
      v_trip_id := v_trip.id;
    elsif p_confirmed_drive is true then
      -- A guest-only drive has no Circle, Circle recipients, or Circle-read path.
      insert into private.trips(user_id,circle_id,recipients,status,confirmed_drive,drive_classification,started_at)
      values(p_user_id,null,'{}'::uuid[],'active',true,'driver',v_now)
      returning id into v_trip_id;
    else
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','confirmed_drive_required','message','Start and confirm a drive before creating a drive-end share')));
    end if;
  end if;
  v_end_condition := case p_mode when 'time' then 'Until selected expiry' when 'destination' then 'After confirmed arrival at destination' else 'After confirmed drive end' end;
  insert into private.guest_shares(owner_id,token_hash,mode,sharing_epoch,created_at,started_at,expires_at,
    destination_latitude,destination_longitude,destination_radius_meters,trip_id)
  values(p_user_id,p_secret_hash,p_mode,p_sharing_epoch,v_now,v_now,case when p_mode='time' then p_expires_at end,
    case when p_mode='destination' then v_lat end,case when p_mode='destination' then v_lon end,
    case when p_mode='destination' then v_radius end,v_trip_id)
  returning * into v_share;
  return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_share.id,'mode',v_share.mode,'status','active',
    'startedAt',v_share.started_at,'createdAt',v_share.created_at,'expiresAt',v_share.expires_at,'endCondition',v_end_condition));
exception when unique_violation then
  return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','conflict','message','Share could not be created')));
end;
$$;

-- Keep the previous call shape safe during rolling API upgrades: it supplies
-- no new confirmation evidence, so a guest-only trip cannot be started by it.
create or replace function public.opentrack_share_create(
  p_user_id uuid, p_session_id uuid, p_sharing_epoch bigint, p_mode text,
  p_expires_at timestamptz, p_destination jsonb, p_secret_hash text
) returns jsonb language sql security definer set search_path = '' as $$
  select public.opentrack_share_create(p_user_id,p_session_id,p_sharing_epoch,p_mode,
    p_expires_at,p_destination,p_secret_hash,false);
$$;

create or replace function public.opentrack_share_event(
  p_user_id uuid, p_session_id uuid, p_share_id uuid, p_event jsonb
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  s private.guest_shares%rowtype;
  v_existing private.share_events%rowtype;
  v_event_id uuid;
  v_event_type text;
  v_observed timestamptz;
  v_fixes jsonb;
  v_fix jsonb;
  v_sample_id uuid;
  v_captured timestamptz;
  v_sample_lat double precision;
  v_sample_lon double precision;
  v_accuracy double precision;
  v_speed double precision;
  v_canonical private.location_samples%rowtype;
  v_evidence_ids uuid[] := array[]::uuid[];
  v_count integer := 0;
  v_first timestamptz;
  v_last timestamptz;
  v_confirmed boolean := false;
  v_status text;
  v_reason text;
  v_result jsonb;
  v_all_good boolean := false;
  v_all_inside boolean := false;
  v_max_gap interval;
  v_max_distance double precision;
  v_motion_count integer := 0;
  v_motion_first timestamptz;
  v_motion_last timestamptz;
  v_motion_max_gap interval;
  v_motion_distance double precision;
  v_lat double precision;
  v_lon double precision;
  v_radius integer;
  v_trip_started timestamptz;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active' for update;
  if a.account_id is null or not exists(select 1 from private.member_sessions x where x.session_id=p_session_id and x.user_id=p_user_id and x.session_epoch=a.session_epoch and x.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required'))); end if;
  begin
    v_event_id := (p_event->>'clientEventId')::uuid;
    v_event_type := p_event->>'eventType';
    v_observed := (p_event->>'observedAt')::timestamptz;
    v_fixes := p_event->'evidenceFixes';
  exception when others then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid event')));
  end;
  if v_event_id is null or v_event_type is null or v_event_type not in ('arrival','drive_end') or jsonb_typeof(v_fixes) is distinct from 'array' then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid event evidence')));
  end if;
  if jsonb_array_length(v_fixes)<1 or jsonb_array_length(v_fixes)>10 or v_observed>now()+interval '5 minutes' or v_observed<now()-interval '30 days' then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid event evidence')));
  end if;
  -- Serialize globally unique event IDs, even across different owners.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_event_id::text,0));
  select * into s from private.guest_shares where id=p_share_id and owner_id=p_user_id for update;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  select * into v_existing from private.share_events where client_event_id=v_event_id;
  if found then
    if v_existing.owner_id<>p_user_id or v_existing.share_id<>s.id or v_existing.event_type<>v_event_type then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','idempotency_conflict','message','Event ID was already used for another share')));
    end if;
    v_result := v_existing.result;
    return jsonb_build_object('httpStatus',200,'body',v_result || jsonb_build_object('status','duplicate'));
  end if;
  if s.status <> 'active' or (s.expires_at is not null and s.expires_at<=now()) or s.sharing_epoch<>a.sharing_epoch then
    return jsonb_build_object('httpStatus',410,'body',jsonb_build_object('error',jsonb_build_object('code','share_ended','message','Share ended')));
  end if;
  if (s.mode='destination' and v_event_type<>'arrival') or (s.mode='drive_end' and v_event_type<>'drive_end') or s.mode='time' then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','event_not_allowed','message','Event does not match this share mode')));
  end if;

  -- Canonicalize offline evidence; altered retries never replace stored fixes.
  for v_fix in select value from jsonb_array_elements(v_fixes) loop
    begin
      v_sample_id := (v_fix->>'sampleId')::uuid;
      v_captured := (v_fix->>'capturedAt')::timestamptz;
      v_sample_lat := (v_fix->>'latitude')::double precision;
      v_sample_lon := (v_fix->>'longitude')::double precision;
      v_accuracy := (v_fix->>'accuracyMeters')::double precision;
      v_speed := case when v_fix->'speedMps' is null or v_fix->'speedMps'='null'::jsonb then null else (v_fix->>'speedMps')::double precision end;
    exception when others then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid evidence fix')));
    end;
    if v_sample_lat not between -90 and 90 or v_sample_lon not between -180 and 180 or v_accuracy not between 0 and 10000
      or (v_speed is not null and v_speed not between 0 and 150) or v_captured<s.started_at or v_captured>v_observed
      or v_captured>now()+interval '5 minutes' or v_captured<now()-interval '30 days' then
      return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid evidence fix')));
    end if;
    insert into private.location_samples(sample_id,user_id,session_id,sharing_epoch,captured_at,latitude,longitude,accuracy_meters,speed_mps)
    values(v_sample_id,p_user_id,p_session_id,s.sharing_epoch,v_captured,v_sample_lat,v_sample_lon,v_accuracy,v_speed)
    on conflict(sample_id) do nothing;
    select * into v_canonical from private.location_samples where sample_id=v_sample_id and user_id=p_user_id and sharing_epoch=s.sharing_epoch;
    if found and v_canonical.captured_at=v_captured and v_canonical.latitude=v_sample_lat and v_canonical.longitude=v_sample_lon
      and v_canonical.accuracy_meters=v_accuracy and v_canonical.speed_mps is not distinct from v_speed then
      v_evidence_ids := array_append(v_evidence_ids,v_sample_id);
    end if;
  end loop;

  if s.mode='destination' then
    v_lat := s.destination_latitude; v_lon := s.destination_longitude; v_radius := s.destination_radius_meters;
    select count(*),min(captured_at),max(captured_at),coalesce(bool_and(
      6371000*2*asin(sqrt(least(1,power(sin(radians((latitude-v_lat)/2)),2)
        +cos(radians(v_lat))*cos(radians(latitude))*power(sin(radians((longitude-v_lon)/2)),2)))) <= v_radius),false)
      into v_count,v_first,v_last,v_all_inside
      from private.location_samples where sample_id=any(v_evidence_ids) and user_id=p_user_id and sharing_epoch=s.sharing_epoch
        and captured_at between s.started_at and v_observed and accuracy_meters<=50;
    v_confirmed := v_count>=2 and v_last-v_first>=interval '60 seconds' and v_all_inside;
    v_reason := case when v_confirmed then 'arrival_confirmed' else 'insufficient_evidence' end;
  else
    select t.started_at into v_trip_started from private.trips t
    where t.id=s.trip_id and t.user_id=p_user_id and t.confirmed_drive and t.drive_classification='driver'
      and t.status='active' and t.started_at<=v_observed for update;
    if v_trip_started is not null then
      with evidence as (
        select l.* from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
          and l.sharing_epoch=s.sharing_epoch and l.captured_at between greatest(s.started_at,v_trip_started) and v_observed
      ), stationary as (
        select * from evidence where speed_mps is not null and speed_mps<=1.5 and accuracy_meters<=50
      ), stationary_origin as (
        select latitude,longitude from stationary order by captured_at limit 1
      ), stationary_gaps as (
        select e.*,lag(e.captured_at) over(order by e.captured_at) as previous_at from stationary e
      ), stationary_stats as (
        select count(*) as fix_count,min(g.captured_at) as first_at,max(g.captured_at) as last_at,
          coalesce(bool_and(g.speed_mps is not null and g.speed_mps<=1.5 and g.accuracy_meters<=50),false) as all_good,
          max(g.captured_at-g.previous_at) as max_gap,
          max(6371000*2*asin(sqrt(least(1,power(sin(radians((g.latitude-o.latitude)/2)),2)
            +cos(radians(o.latitude))*cos(radians(g.latitude))*power(sin(radians((g.longitude-o.longitude)/2)),2))))) as max_distance
        from stationary_gaps g cross join stationary_origin o
      ), moving as (
        select * from evidence where speed_mps between 5 and 55 and accuracy_meters<=50
          and captured_at<(select min(captured_at) from stationary)
      ), moving_origin as (
        select latitude,longitude from moving order by captured_at limit 1
      ), moving_gaps as (
        select e.*,lag(e.captured_at) over(order by e.captured_at) as previous_at from moving e
      ), moving_stats as (
        select count(*) as fix_count,min(g.captured_at) as first_at,max(g.captured_at) as last_at,
          max(g.captured_at-g.previous_at) as max_gap,
          max(6371000*2*asin(sqrt(least(1,power(sin(radians((g.latitude-o.latitude)/2)),2)
            +cos(radians(o.latitude))*cos(radians(g.latitude))*power(sin(radians((g.longitude-o.longitude)/2)),2))))) as max_distance
        from moving_gaps g cross join moving_origin o
      )
      select ss.fix_count,ss.first_at,ss.last_at,ss.all_good,ss.max_gap,ss.max_distance,
        ms.fix_count,ms.first_at,ms.last_at,ms.max_gap,ms.max_distance
        into v_count,v_first,v_last,v_all_good,v_max_gap,v_max_distance,
          v_motion_count,v_motion_first,v_motion_last,v_motion_max_gap,v_motion_distance
      from stationary_stats ss cross join moving_stats ms;
    end if;
    v_confirmed := v_trip_started is not null and v_count>=2 and v_last-v_first>=interval '300 seconds'
      and v_all_good and coalesce(v_max_gap,interval '1 day')<=interval '90 seconds' and coalesce(v_max_distance,1000000)<=75
      and not exists(select 1 from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
        and l.sharing_epoch=s.sharing_epoch and l.captured_at between v_first and v_last
        and (l.speed_mps is null or l.speed_mps>1.5 or l.accuracy_meters>50))
      and v_motion_count>=2 and v_motion_last-v_motion_first>=interval '30 seconds'
      and coalesce(v_motion_max_gap,interval '1 day')<=interval '90 seconds'
      and v_first-v_motion_last<=interval '90 seconds'
      and v_motion_distance>=150
      and v_motion_distance<=55.0*extract(epoch from (v_motion_last-v_motion_first))::double precision+100.0;
    v_reason := case when v_confirmed then 'drive_end_confirmed' else 'insufficient_evidence' end;
  end if;
  v_status := case when v_confirmed then 'confirmed' else 'pending' end;
  if v_confirmed then
    insert into private.latest_locations(user_id,sample_id,captured_at,latitude,longitude,accuracy_meters,speed_mps,sharing_epoch)
    select p_user_id,l.sample_id,l.captured_at,l.latitude,l.longitude,l.accuracy_meters,l.speed_mps,s.sharing_epoch
      from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
      order by l.captured_at desc limit 1
    on conflict(user_id) do update set sample_id=excluded.sample_id,captured_at=excluded.captured_at,latitude=excluded.latitude,
      longitude=excluded.longitude,accuracy_meters=excluded.accuracy_meters,speed_mps=excluded.speed_mps,
      sharing_epoch=excluded.sharing_epoch,updated_at=now() where private.latest_locations.captured_at<excluded.captured_at;
    if v_event_type='drive_end' then
      update private.trips set status='ended',ended_at=now(),end_reason='drive_end_confirmed'
        where id=s.trip_id and user_id=p_user_id and status='active';
      update private.guest_shares set status='ended',ended_at=now(),end_reason=v_reason
        where trip_id=s.trip_id and mode='drive_end' and status='active';
    else
      update private.guest_shares set status='ended',ended_at=now(),end_reason=v_reason where id=s.id;
    end if;
  end if;
  v_result := jsonb_build_object('clientEventId',v_event_id,'status',v_status,'serverTime',now(),
    'endedAt',case when v_confirmed then now() else null end,'reason',v_reason);
  insert into private.share_events(client_event_id,share_id,owner_id,event_type,status,observed_at,result)
  values(v_event_id,s.id,p_user_id,v_event_type,v_status,v_observed,v_result);
  return jsonb_build_object('httpStatus',200,'body',v_result);
end;
$$;

revoke execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text,boolean) from public, anon, authenticated;
revoke execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text) from public, anon, authenticated;
revoke execute on function public.opentrack_share_event(uuid,uuid,uuid,jsonb) from public, anon, authenticated;
grant execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text,boolean) to service_role;
grant execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text) to service_role;
grant execute on function public.opentrack_share_event(uuid,uuid,uuid,jsonb) to service_role;
