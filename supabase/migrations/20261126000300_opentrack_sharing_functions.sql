create or replace function public.opentrack_location_batch(
  p_user_id uuid, p_session_id uuid, p_sharing_epoch bigint, p_samples jsonb
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  v_sample jsonb;
  v_sample_id uuid;
  v_captured timestamptz;
  v_lat double precision;
  v_lon double precision;
  v_accuracy double precision;
  v_speed double precision;
  v_rows integer;
  v_accepted jsonb := '[]'::jsonb;
  v_duplicates jsonb := '[]'::jsonb;
  v_rejected jsonb := '[]'::jsonb;
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
  if not exists(select 1 from private.circle_members cm where cm.user_id=p_user_id and cm.membership_state='active' and cm.sharing_enabled)
    and not exists(select 1 from private.guest_shares gs where gs.owner_id=p_user_id and gs.status='active'
      and gs.sharing_epoch=a.sharing_epoch and (gs.expires_at is null or gs.expires_at>now())) then
    return jsonb_build_object('httpStatus',403,'body',jsonb_build_object('error',jsonb_build_object('code','sharing_disabled','message','Enable Circle sharing or create a guest share before uploading locations')));
  end if;
  if jsonb_typeof(p_samples) is distinct from 'array' then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Provide between 1 and 100 samples')));
  end if;
  if jsonb_array_length(p_samples) < 1 or jsonb_array_length(p_samples) > 100 then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Provide between 1 and 100 samples')));
  end if;
  for v_sample in select value from jsonb_array_elements(p_samples) loop
    begin
      v_sample_id := (v_sample->>'sampleId')::uuid;
      v_captured := (v_sample->>'capturedAt')::timestamptz;
      v_lat := (v_sample->>'latitude')::double precision;
      v_lon := (v_sample->>'longitude')::double precision;
      v_accuracy := (v_sample->>'accuracyMeters')::double precision;
      v_speed := case when v_sample->'speedMps' is null or v_sample->'speedMps' = 'null'::jsonb then null else (v_sample->>'speedMps')::double precision end;
      if v_lat not between -90 and 90 or v_lon not between -180 and 180 or v_accuracy not between 0 and 10000
        or (v_speed is not null and v_speed not between 0 and 150) or v_captured > now()+interval '5 minutes'
        or v_captured < now()-interval '30 days' then
        v_rejected := v_rejected || jsonb_build_array(jsonb_build_object('sampleId',v_sample_id,'code','out_of_bounds'));
        continue;
      end if;
    exception when others then
      v_rejected := v_rejected || jsonb_build_array(jsonb_build_object('sampleId',coalesce(v_sample->>'sampleId',''),'code','invalid_sample'));
      continue;
    end;
    insert into private.location_samples(sample_id,user_id,session_id,sharing_epoch,captured_at,latitude,longitude,accuracy_meters,speed_mps)
    values(v_sample_id,p_user_id,p_session_id,p_sharing_epoch,v_captured,v_lat,v_lon,v_accuracy,v_speed)
    on conflict(sample_id) do nothing;
    get diagnostics v_rows = row_count;
    if v_rows = 0 then
      v_duplicates := v_duplicates || jsonb_build_array(v_sample_id);
      continue;
    end if;
    v_accepted := v_accepted || jsonb_build_array(v_sample_id);
    insert into private.latest_locations(user_id,sample_id,captured_at,latitude,longitude,accuracy_meters,speed_mps,sharing_epoch)
    values(p_user_id,v_sample_id,v_captured,v_lat,v_lon,v_accuracy,v_speed,p_sharing_epoch)
    on conflict(user_id) do update set sample_id=excluded.sample_id,captured_at=excluded.captured_at,
      latitude=excluded.latitude,longitude=excluded.longitude,accuracy_meters=excluded.accuracy_meters,
      speed_mps=excluded.speed_mps,sharing_epoch=excluded.sharing_epoch,updated_at=now()
    where private.latest_locations.captured_at < excluded.captured_at;
  end loop;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('serverTime',now(),'accepted',v_accepted,'duplicates',v_duplicates,'rejected',v_rejected));
end;
$$;

create or replace function public.opentrack_share_create(
  p_user_id uuid, p_session_id uuid, p_sharing_epoch bigint, p_mode text,
  p_expires_at timestamptz, p_destination jsonb, p_secret_hash text
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  v_share private.guest_shares%rowtype;
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
  if p_mode='drive_end' and not exists(select 1 from private.trips t where t.user_id=p_user_id and t.status='active'
      and t.confirmed_drive and t.drive_classification='driver') then
    return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','confirmed_drive_required','message','Start and confirm a drive before creating a drive-end share')));
  end if;
  v_end_condition := case p_mode when 'time' then 'Until selected expiry' when 'destination' then 'After confirmed arrival at destination' else 'When a confirmed drive has five continuous minutes of credible stationary evidence' end;
  insert into private.guest_shares(owner_id,token_hash,mode,sharing_epoch,created_at,started_at,expires_at,destination_latitude,destination_longitude,destination_radius_meters)
  values(p_user_id,p_secret_hash,p_mode,p_sharing_epoch,v_now,v_now,case when p_mode='time' then p_expires_at end,
    case when p_mode='destination' then v_lat end,case when p_mode='destination' then v_lon end,case when p_mode='destination' then v_radius end)
  returning * into v_share;
  return jsonb_build_object('httpStatus',201,'body',jsonb_build_object('id',v_share.id,'mode',v_share.mode,'status','active',
    'startedAt',v_share.started_at,'createdAt',v_share.created_at,'expiresAt',v_share.expires_at,'endCondition',v_end_condition));
exception when unique_violation then
  return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','conflict','message','Share could not be created')));
end;
$$;

create or replace function public.opentrack_share_list(p_user_id uuid, p_session_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype; v_data jsonb;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active';
  if a.account_id is null or not exists(select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id and s.session_epoch=a.session_epoch and s.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required'))); end if;
  select coalesce(jsonb_agg(jsonb_build_object('id',s.id,'mode',s.mode,'status',s.status,'createdAt',s.created_at,
    'startedAt',s.started_at,'expiresAt',s.expires_at,'endedAt',s.ended_at,'endCondition',
    case s.mode when 'time' then 'Until selected expiry' when 'destination' then 'After confirmed arrival at destination' else 'When a confirmed drive has five continuous minutes of credible stationary evidence' end)
    order by s.created_at desc),'[]'::jsonb)
  into v_data from private.guest_shares s where s.owner_id=p_user_id;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('data',v_data,'nextCursor',null));
end;
$$;

create or replace function public.opentrack_share_revoke(p_user_id uuid, p_session_id uuid, p_share_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a private.member_accounts%rowtype; v_ended timestamptz;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active' for update;
  if a.account_id is null or not exists(select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id and s.session_epoch=a.session_epoch and s.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  update private.guest_shares set status='ended',ended_at=coalesce(ended_at,now()),end_reason=coalesce(end_reason,'revoked')
    where id=p_share_id and owner_id=p_user_id returning ended_at into v_ended;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('revoked',true,'endedAt',v_ended));
end;
$$;

create or replace function public.opentrack_share_read(p_share_id uuid, p_secret_hash text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  s private.guest_shares%rowtype;
  a private.member_accounts%rowtype;
  l private.latest_locations%rowtype;
  v_owner_id uuid;
  v_now timestamptz;
  v_status text;
  v_latest jsonb := null;
  v_valid_until timestamptz;
begin
  -- Discover the owner without taking a share lock, then use the same account-first
  -- lock order as revoke, stop-sharing, event confirmation, and location ingest.
  -- The account lock gives the read a clear point relative to revocation while
  -- avoiding a share-row/account-row lock inversion.
  select owner_id into v_owner_id from private.guest_shares where id=p_share_id and token_hash=p_secret_hash;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  select * into a from private.member_accounts where auth_user_id=v_owner_id for share;
  select * into s from private.guest_shares where id=p_share_id and owner_id=v_owner_id and token_hash=p_secret_hash for share;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  v_now := pg_catalog.clock_timestamp();
  if s.status <> 'active' or (s.expires_at is not null and s.expires_at <= v_now) or a.account_id is null or a.status <> 'active' or a.sharing_epoch <> s.sharing_epoch then
    return jsonb_build_object('httpStatus',410,'body',jsonb_build_object('error',jsonb_build_object('code','share_ended','message','Share ended')));
  end if;
  select * into l from private.latest_locations where user_id=s.owner_id and sharing_epoch=s.sharing_epoch and captured_at>=s.started_at;
  v_status := case when l.user_id is null then 'waiting' when l.captured_at < v_now-interval '90 seconds' then 'signal_lost' else 'active' end;
  if v_status='active' then
    v_latest := jsonb_build_object('latitude',l.latitude,'longitude',l.longitude,'accuracyMeters',l.accuracy_meters,
      'capturedAt',l.captured_at,'speedMps',case when l.captured_at < v_now-interval '30 seconds' then null else l.speed_mps end);
  end if;
  v_valid_until := least(v_now+interval '30 seconds',coalesce(s.expires_at,'infinity'::timestamptz));
  return jsonb_build_object('httpStatus',200,'body',jsonb_build_object('status',v_status,'serverTime',v_now,'viewValidUntil',v_valid_until,'latest',v_latest));
end;
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
  -- A transaction-scoped advisory lock serializes the globally unique event ID
  -- even when two different owners submit it at the same time.
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

  -- Canonicalize client supplied offline evidence before evaluating it. A conflicting
  -- sampleId reuses the already stored sample, never the altered request body.
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
    select min(t.started_at) into v_trip_started from private.trips t where t.user_id=p_user_id and t.confirmed_drive
      and t.drive_classification='driver' and t.status='active' and t.started_at<=v_observed;
    if v_trip_started is not null then
      with evidence as (
        select l.* from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
          and l.sharing_epoch=s.sharing_epoch and l.captured_at between greatest(s.started_at,v_trip_started) and v_observed
      ), origin as (
        select latitude,longitude from evidence order by captured_at limit 1
      ), gaps as (
        select e.*,lag(e.captured_at) over(order by e.captured_at) as previous_at from evidence e
      )
      select count(*),min(captured_at),max(captured_at),coalesce(bool_and(speed_mps is not null and speed_mps<=1.5 and accuracy_meters<=50),false),
        max(captured_at-previous_at),max(6371000*2*asin(sqrt(least(1,power(sin(radians((g.latitude-o.latitude)/2)),2)
          +cos(radians(o.latitude))*cos(radians(g.latitude))*power(sin(radians((g.longitude-o.longitude)/2)),2)))))
        into v_count,v_first,v_last,v_all_good,v_max_gap,v_max_distance from gaps g cross join origin o;
    end if;
    v_confirmed := v_trip_started is not null and v_count>=2 and v_last-v_first>=interval '300 seconds'
      and v_all_good and coalesce(v_max_gap,interval '1 day')<=interval '90 seconds' and coalesce(v_max_distance,1000000)<=75;
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
    update private.guest_shares set status='ended',ended_at=now(),end_reason=v_reason where id=s.id;
    if v_event_type='drive_end' then update private.trips set status='ended',ended_at=now(),end_reason='drive_end_confirmed' where user_id=p_user_id and status='active'; end if;
  end if;
  v_result := jsonb_build_object('clientEventId',v_event_id,'status',v_status,'serverTime',now(),
    'endedAt',case when v_confirmed then now() else null end,'reason',v_reason);
  insert into private.share_events(client_event_id,share_id,owner_id,event_type,status,observed_at,result)
  values(v_event_id,s.id,p_user_id,v_event_type,v_status,v_observed,v_result);
  return jsonb_build_object('httpStatus',200,'body',v_result);
end;
$$;

create or replace function public.opentrack_stop_sharing(
  p_user_id uuid, p_session_id uuid, p_client_request_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  a private.member_accounts%rowtype;
  v_count integer := 0;
  v_response jsonb;
begin
  select * into a from private.member_accounts where auth_user_id=p_user_id and status='active' for update;
  if a.account_id is null or not exists(select 1 from private.member_sessions s where s.session_id=p_session_id and s.user_id=p_user_id and s.session_epoch=a.session_epoch and s.revoked_at is null) then
    return jsonb_build_object('httpStatus',401,'body',jsonb_build_object('error',jsonb_build_object('code','unauthorized','message','Unauthorized')));
  end if;
  if a.must_rotate_password then return jsonb_build_object('httpStatus',428,'body',jsonb_build_object('error',jsonb_build_object('code','password_rotation_required','message','Password rotation required'))); end if;
  select response into v_response from private.client_commands where user_id=p_user_id and client_request_id=p_client_request_id and command_type='stop_sharing';
  if found then return jsonb_build_object('httpStatus',200,'body',v_response); end if;
  update private.member_accounts set sharing_epoch=sharing_epoch+1, sharing_enabled=false, activity_sharing_enabled=false, updated_at=now()
    where account_id=a.account_id returning * into a;
  update private.circle_members set sharing_enabled=false,activity_sharing_enabled=false where user_id=p_user_id and membership_state='active';
  update private.guest_shares set status='ended',ended_at=now(),end_reason='sharing_stopped' where owner_id=p_user_id and status='active';
  get diagnostics v_count = row_count;
  update private.trips set status='ended',ended_at=now(),end_reason='sharing_stopped' where user_id=p_user_id and status='active';
  delete from private.latest_locations where user_id=p_user_id;
  v_response := jsonb_build_object('stopped',true,'endedShareCount',v_count,'sharingEpoch',a.sharing_epoch);
  insert into private.client_commands(user_id,client_request_id,command_type,response)
  values(p_user_id,p_client_request_id,'stop_sharing',v_response);
  return jsonb_build_object('httpStatus',200,'body',v_response);
end;
$$;

create or replace function public.opentrack_pet_recovery_read(p_pet_id uuid, p_secret_hash text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare p private.pets%rowtype; v_fields jsonb;
begin
  select pet.* into p from private.pets pet join private.pet_recovery_tokens t on t.pet_id=pet.id
  where pet.id=p_pet_id and t.token_hash=p_secret_hash and t.revoked_at is null and (t.expires_at is null or t.expires_at>now()) and pet.recovery_enabled;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  if not ('petName'=any(p.recovery_fields)) or not ('ownerMessage'=any(p.recovery_fields)) then
    return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found')));
  end if;
  v_fields := jsonb_build_object(
    'petName',case when 'petName'=any(p.recovery_fields) then p.name else null end,
    'species',case when 'species'=any(p.recovery_fields) then p.species else null end,
    'photoObjectPath',case when 'photo'=any(p.recovery_fields) then p.photo_object_path else null end,
    'ownerMessage',case when 'ownerMessage'=any(p.recovery_fields) then p.owner_message else null end,
    'contactRoute',case when 'contactRoute'=any(p.recovery_fields) then p.contact_route else null end);
  return jsonb_build_object('httpStatus',200,'body',v_fields);
end;
$$;

create or replace function public.opentrack_pet_sighting(p_pet_id uuid, p_secret_hash text, p_sighting jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare p private.pets%rowtype; v_token_id uuid; v_count integer;
begin
  select pet.* into p from private.pets pet join private.pet_recovery_tokens token on token.pet_id=pet.id
  where pet.id=p_pet_id and token.token_hash=p_secret_hash and token.revoked_at is null
    and (token.expires_at is null or token.expires_at>now()) and pet.recovery_enabled
  for share of pet, token;
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  select token.id into v_token_id from private.pet_recovery_tokens token
    where token.pet_id=p.id and token.token_hash=p_secret_hash and token.revoked_at is null
      and (token.expires_at is null or token.expires_at>now());
  if not found then return jsonb_build_object('httpStatus',404,'body',jsonb_build_object('error',jsonb_build_object('code','not_found','message','Not found'))); end if;
  insert into private.pet_sighting_rate_limits(token_id,window_started_at,attempts) values(v_token_id,now(),1)
  on conflict(token_id) do update set window_started_at=case when private.pet_sighting_rate_limits.window_started_at<now()-interval '1 hour' then now() else private.pet_sighting_rate_limits.window_started_at end,
    attempts=case when private.pet_sighting_rate_limits.window_started_at<now()-interval '1 hour' then 1 else private.pet_sighting_rate_limits.attempts+1 end
  returning attempts into v_count;
  if v_count>5 then return jsonb_build_object('httpStatus',429,'body',jsonb_build_object('error',jsonb_build_object('code','rate_limited','message','Try again later'))); end if;
  if coalesce(length(p_sighting->>'message'),0) not between 1 and 1000 then
    return jsonb_build_object('httpStatus',400,'body',jsonb_build_object('error',jsonb_build_object('code','invalid_request','message','Invalid sighting')));
  end if;
  insert into private.pet_sightings(pet_id,message,latitude,longitude,accuracy_meters,contact_route)
  values(p.id,p_sighting->>'message',nullif(p_sighting->>'latitude','')::double precision,
    nullif(p_sighting->>'longitude','')::double precision,nullif(p_sighting->>'accuracyMeters','')::double precision,
    left(p_sighting->>'contactRoute',200));
  return jsonb_build_object('httpStatus',202,'body',jsonb_build_object('accepted',true));
end;
$$;

revoke execute on function public.opentrack_location_batch(uuid,uuid,bigint,jsonb) from public, anon, authenticated;
revoke execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text) from public, anon, authenticated;
revoke execute on function public.opentrack_share_list(uuid,uuid) from public, anon, authenticated;
revoke execute on function public.opentrack_share_revoke(uuid,uuid,uuid) from public, anon, authenticated;
revoke execute on function public.opentrack_share_read(uuid,text) from public, anon, authenticated;
revoke execute on function public.opentrack_share_event(uuid,uuid,uuid,jsonb) from public, anon, authenticated;
revoke execute on function public.opentrack_stop_sharing(uuid,uuid,uuid) from public, anon, authenticated;
revoke execute on function public.opentrack_pet_recovery_read(uuid,text) from public, anon, authenticated;
revoke execute on function public.opentrack_pet_sighting(uuid,text,jsonb) from public, anon, authenticated;
grant execute on function public.opentrack_location_batch(uuid,uuid,bigint,jsonb) to service_role;
grant execute on function public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text) to service_role;
grant execute on function public.opentrack_share_list(uuid,uuid) to service_role;
grant execute on function public.opentrack_share_revoke(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_share_read(uuid,text) to service_role;
grant execute on function public.opentrack_share_event(uuid,uuid,uuid,jsonb) to service_role;
grant execute on function public.opentrack_stop_sharing(uuid,uuid,uuid) to service_role;
grant execute on function public.opentrack_pet_recovery_read(uuid,text) to service_role;
grant execute on function public.opentrack_pet_sighting(uuid,text,jsonb) to service_role;
