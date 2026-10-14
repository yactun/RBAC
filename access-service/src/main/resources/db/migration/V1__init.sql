-- V1__init.sql — RBAC core schema (design §5.3)
create extension if not exists "pgcrypto";

create table profile (
  id          uuid primary key default gen_random_uuid(),
  name        text unique not null,
  description text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table permission (
  id          uuid primary key default gen_random_uuid(),
  key         text unique not null,
  description text,
  created_at  timestamptz not null default now()
);

create table profile_permission (
  profile_id    uuid not null references profile(id) on delete cascade,
  permission_id uuid not null references permission(id) on delete cascade,
  primary key (profile_id, permission_id)
);

create table resource_type (
  id               uuid primary key default gen_random_uuid(),
  name             text unique not null,
  owner_service    text,
  dimension_schema jsonb
);

create table profile_scope (
  id               uuid primary key default gen_random_uuid(),
  profile_id       uuid not null references profile(id) on delete cascade,
  resource_type_id uuid not null references resource_type(id) on delete cascade,
  dimensions       jsonb not null,
  unique (profile_id, resource_type_id)
);

create table template (
  id     uuid primary key default gen_random_uuid(),
  app_id text not null,
  name   text not null,
  unique (app_id, name)
);

create table template_element (
  id            uuid primary key default gen_random_uuid(),
  template_id   uuid not null references template(id) on delete cascade,
  element_key   text not null,
  permission_id uuid not null references permission(id) on delete cascade,
  unique (template_id, element_key)
);

create table profile_template (
  profile_id  uuid not null references profile(id) on delete cascade,
  template_id uuid not null references template(id) on delete cascade,
  primary key (profile_id, template_id)
);

-- Resolve hot-path indexes
create index ix_pp_profile   on profile_permission (profile_id);
create index ix_pt_profile   on profile_template (profile_id);
create index ix_ps_profile   on profile_scope (profile_id, resource_type_id);
create index ix_te_template  on template_element (template_id);
