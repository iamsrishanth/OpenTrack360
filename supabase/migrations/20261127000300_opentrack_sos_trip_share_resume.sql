-- Keep SOS notifications addressable through authenticated Circle routes, and
-- include the sender-only state Android needs to resume arrival monitoring.
alter table private.trips
  add column if not exists started_session_id uuid references private.member_sessions(session_id) on delete cascade,
  add column if not exists sharing_epoch bigint check (sharing_epoch is null or sharing_epoch > 0);
create index if not exists trips_active_confirmed_session_idx
  on private.trips(user_id, started_session_id, sharing_epoch, started_at desc)
  where status='active' and confirmed_drive and drive_classification='driver';

alter table private.notifications add column if not exists sos_id uuid;
do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname='notifications_sos_id_fkey'
      and conrelid='private.notifications'::regclass
  ) then
    alter table private.notifications add constraint notifications_sos_id_fkey
      foreign key (sos_id) references private.api_records(id) on delete cascade;
  end if;
end;
$$;
create index if not exists notifications_sos_recipient_idx
  on private.notifications(sos_id, recipient_user_id) where sos_id is not null;

-- Patch the installed dispatcher in place so all authenticated callers keep
-- the existing authorization checks and transaction boundaries.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint)'::regprocedure);
  -- SQL-editor-installed functions may retain Windows line endings.
  v_definition := replace(v_definition, E'\r\n', E'\n');

  v_old := $old$insert into private.notifications(recipient_user_id,circle_id,kind,title,body,delivery_state)
      select u.recipient_id,v_circle_id,'sos','SOS alert',null,'queued' from unnest(v_ids) as u(recipient_id);$old$;
  v_new := $new$insert into private.notifications(recipient_user_id,circle_id,sos_id,kind,title,body,delivery_state)
      select u.recipient_id,v_circle_id,v_record.id,'sos','SOS alert',null,'queued' from unnest(v_ids) as u(recipient_id);$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected SOS notification insert was not found';
  end if;

  v_old := $old$delete from private.notifications where recipient_user_id in (select (x->>'recipientId')::uuid from jsonb_array_elements(v_record.payload->'recipients') x)
        and circle_id=v_circle_id and kind='sos' and delivery_state='queued' and created_at=v_record.created_at;$old$;
  v_new := $new$delete from private.notifications where recipient_user_id in (select (x->>'recipientId')::uuid from jsonb_array_elements(v_record.payload->'recipients') x)
        and circle_id=v_circle_id and kind='sos' and delivery_state='queued' and sos_id=v_record.id;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected SOS notification cancellation was not found';
  end if;

  v_old := $old$update private.notifications set delivery_state='acknowledged',acknowledged_at=now() where recipient_user_id=p_user_id and circle_id=v_circle_id and kind='sos' and created_at=v_record.created_at;$old$;
  v_new := $new$update private.notifications set delivery_state='acknowledged',acknowledged_at=now() where recipient_user_id=p_user_id and circle_id=v_circle_id and kind='sos' and sos_id=v_resource_id;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected SOS notification acknowledgement was not found';
  end if;

  v_old := $old$jsonb_build_object('id',p.id,'kind',p.kind,'title',p.title,'body',p.body,'deliveryState',p.delivery_state,
      'createdAt',p.created_at,'acknowledgedAt',p.acknowledged_at)$old$;
  v_new := $new$jsonb_build_object('id',p.id,'kind',p.kind,'circleId',p.circle_id,'sosId',p.sos_id,'title',p.title,'body',p.body,'deliveryState',p.delivery_state,
      'createdAt',p.created_at,'acknowledgedAt',p.acknowledged_at)$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected notification response projection was not found';
  end if;

  v_old := $old$'destinationLabel',t.destination_label)$old$;
  v_new := $new$'destinationLabel',t.destination_label,'confirmedDrive',t.confirmed_drive)$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected trip list projection was not found';
  end if;

  v_old := $old$'endReason',v_trip.end_reason,'estimatedArrivalAt',null,'distanceRemainingMeters',null,'destinationLabel',v_trip.destination_label))$old$;
  v_new := $new$'endReason',v_trip.end_reason,'estimatedArrivalAt',null,'distanceRemainingMeters',null,'destinationLabel',v_trip.destination_label,'confirmedDrive',v_trip.confirmed_drive))$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected trip start response projection was not found';
  end if;

  v_old := $old$'endReason',coalesce(v_trip.end_reason,'other'));$old$;
  v_new := $new$'endReason',coalesce(v_trip.end_reason,'other'),'confirmedDrive',v_trip.confirmed_drive);$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected trip stop response projection was not found';
  end if;

  execute v_definition;
end;
$$;
-- Circle trip starts record the confirming member session and current epoch.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_api_dispatch(uuid,uuid,text,jsonb,bigint)'::regprocedure);
  v_old := $old$insert into private.trips(user_id,circle_id,recipients,destination_label,destination_latitude,destination_longitude,share_lock_screen_progress,confirmed_drive)
    values(p_user_id,v_circle_id,v_ids,nullif(v_body->>'destinationLabel',''),nullif(v_body->>'destinationLatitude','')::double precision,
      nullif(v_body->>'destinationLongitude','')::double precision,coalesce((v_body->>'shareLockScreenProgress')::boolean,false),
      coalesce((v_body->>'confirmedDrive')::boolean,false)) returning * into v_trip;$old$;
  v_new := $new$insert into private.trips(user_id,circle_id,recipients,destination_label,destination_latitude,destination_longitude,share_lock_screen_progress,confirmed_drive,started_session_id,sharing_epoch)
    values(p_user_id,v_circle_id,v_ids,nullif(v_body->>'destinationLabel',''),nullif(v_body->>'destinationLatitude','')::double precision,
      nullif(v_body->>'destinationLongitude','')::double precision,coalesce((v_body->>'shareLockScreenProgress')::boolean,false),
      coalesce((v_body->>'confirmedDrive')::boolean,false),p_session_id,p_sharing_epoch) returning * into v_trip;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected trip-start insert was not found';
  end if;
  execute v_definition;
end;
$$;

-- Reuse a confirmed drive only when it belongs to this session and epoch.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_share_create(uuid,uuid,bigint,text,timestamptz,jsonb,text,boolean)'::regprocedure);
  v_old := $old$  if p_mode='drive_end' then
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
  end if;$old$;
  v_new := $new$  if p_mode='drive_end' then
    select * into v_trip from private.trips t
    where t.user_id=p_user_id and t.status='active' and t.confirmed_drive and t.drive_classification='driver'
      and t.started_session_id=p_session_id and t.sharing_epoch=p_sharing_epoch
    order by t.started_at desc,t.id desc limit 1 for update;
    if found then
      v_trip_id := v_trip.id;
    elsif exists(select 1 from private.trips t where t.user_id=p_user_id and t.status='active'
        and t.confirmed_drive and t.drive_classification='driver') then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','active_drive_session_mismatch','message','The active confirmed drive has no matching device session and sharing epoch; stop and restart it on this device')));
    elsif p_confirmed_drive is true then
      -- A guest-only drive has no Circle, Circle recipients, or Circle-read path.
      insert into private.trips(user_id,circle_id,recipients,status,confirmed_drive,drive_classification,started_at,started_session_id,sharing_epoch)
      values(p_user_id,null,'{}'::uuid[],'active',true,'driver',v_now,p_session_id,p_sharing_epoch)
      returning id into v_trip_id;
    else
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','confirmed_drive_required','message','Start and confirm a drive before creating a drive-end share')));
    end if;
  end if;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected drive-end share trip selection was not found';
  end if;
  execute v_definition;
end;
$$;

-- For drive-end events, moving fixes may predate share creation only when they
-- belong to the same confirmed trip, member session, and sharing epoch. The
-- stationary end-of-drive window and destination evidence remain post-share.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_share_event(uuid,uuid,uuid,jsonb)'::regprocedure);

  v_old := $old$  -- Canonicalize offline evidence; altered retries never replace stored fixes.
  for v_fix in select value from jsonb_array_elements(v_fixes) loop$old$;
  v_new := $new$  if s.mode='drive_end' then
    select t.started_at into v_trip_started from private.trips t
    where t.id=s.trip_id and t.user_id=p_user_id and t.confirmed_drive and t.drive_classification='driver'
      and t.status='active' and t.started_at<=v_observed
      and t.started_session_id=p_session_id and t.sharing_epoch=s.sharing_epoch for update;
    if v_trip_started is null then
      return jsonb_build_object('httpStatus',409,'body',jsonb_build_object('error',jsonb_build_object('code','drive_session_mismatch','message','The confirmed drive session is no longer available')));
    end if;
  end if;

  -- Canonicalize offline evidence; altered retries never replace stored fixes.
  for v_fix in select value from jsonb_array_elements(v_fixes) loop$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected drive-end session validation insertion point was not found';
  end if;

  v_old := $old$or (v_speed is not null and v_speed not between 0 and 150) or v_captured<s.started_at or v_captured>v_observed$old$;
  v_new := $new$or (v_speed is not null and v_speed not between 0 and 150)
      or (s.mode='destination' and v_captured<s.started_at)
      or (s.mode='drive_end' and v_captured<v_trip_started) or v_captured>v_observed$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected evidence capture-time validation was not found';
  end if;

  v_old := $old$select * into v_canonical from private.location_samples where sample_id=v_sample_id and user_id=p_user_id and sharing_epoch=s.sharing_epoch;$old$;
  v_new := $new$select * into v_canonical from private.location_samples where sample_id=v_sample_id and user_id=p_user_id
      and session_id=p_session_id and sharing_epoch=s.sharing_epoch;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected evidence canonicalization lookup was not found';
  end if;

  v_old := $old$from private.location_samples where sample_id=any(v_evidence_ids) and user_id=p_user_id and sharing_epoch=s.sharing_epoch
        and captured_at between s.started_at and v_observed and accuracy_meters<=50;$old$;
  v_new := $new$from private.location_samples where sample_id=any(v_evidence_ids) and user_id=p_user_id
        and session_id=p_session_id and sharing_epoch=s.sharing_epoch
        and captured_at between s.started_at and v_observed and accuracy_meters<=50;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected destination evidence scope was not found';
  end if;

  v_old := $old$  else
    select t.started_at into v_trip_started from private.trips t
    where t.id=s.trip_id and t.user_id=p_user_id and t.confirmed_drive and t.drive_classification='driver'
      and t.status='active' and t.started_at<=v_observed for update;
    if v_trip_started is not null then
      with evidence as (
        select l.* from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
          and l.sharing_epoch=s.sharing_epoch and l.captured_at between greatest(s.started_at,v_trip_started) and v_observed
      ), stationary as (
        select * from evidence where speed_mps is not null and speed_mps<=1.5 and accuracy_meters<=50$old$;
  v_new := $new$  else
    with evidence as (
      select l.* from private.location_samples l where l.sample_id=any(v_evidence_ids) and l.user_id=p_user_id
        and l.session_id=p_session_id and l.sharing_epoch=s.sharing_epoch
        and l.captured_at between v_trip_started and v_observed
    ), stationary as (
      select * from evidence where captured_at>=s.started_at and speed_mps is not null and speed_mps<=1.5 and accuracy_meters<=50$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected drive-end evidence window was not found';
  end if;

  v_old := $old$      from stationary_stats ss cross join moving_stats ms;
    end if;$old$;
  v_new := $new$      from stationary_stats ss cross join moving_stats ms;$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected drive-end evidence guard was not found';
  end if;

  v_old := $old$and l.sharing_epoch=s.sharing_epoch and l.captured_at between v_first and v_last$old$;
  v_new := $new$and l.session_id=p_session_id and l.sharing_epoch=s.sharing_epoch and l.captured_at between v_first and v_last$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected stationary evidence session scope was not found';
  end if;

  execute v_definition;
end;
$$;

-- Only the sender's own share list receives a destination. Public guest reads
-- continue to return the latest permitted fix and share state only.
do $$
declare
  v_definition text;
  v_old text;
  v_new text;
begin
  v_definition := pg_get_functiondef('public.opentrack_share_list(uuid,uuid)'::regprocedure);
  v_old := $old$'startedAt',s.started_at,'expiresAt',s.expires_at,'endedAt',s.ended_at,'endCondition',$old$;
  v_new := $new$'startedAt',s.started_at,'expiresAt',s.expires_at,'endedAt',s.ended_at,'destination',
    case when s.mode='destination' then jsonb_build_object('latitude',s.destination_latitude,'longitude',s.destination_longitude,'radiusMeters',s.destination_radius_meters) else null end,
    'endCondition',$new$;
  if position(v_old in v_definition)>0 then
    v_definition := replace(v_definition,v_old,v_new);
  elsif position(v_new in v_definition)=0 then
    raise exception 'Expected sender share-list projection was not found';
  end if;
  execute v_definition;
end;
$$;
