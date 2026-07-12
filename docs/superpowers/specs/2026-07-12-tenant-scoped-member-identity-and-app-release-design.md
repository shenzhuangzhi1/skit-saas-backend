# Tenant-Scoped Member Identity and App Release Design

## Goal

Keep each agent in an independent tenant while making SaaS and app updates
simple to operate. A member's phone number is an identity inside its agent
tenant, not a global identity. A member must never type a tenant name or an
agent code to log in.

## Domain Contract

- A platform super administrator creates an agent and its dedicated tenant.
- An agent owns a root invitation code. A member invitation code resolves both
  the destination tenant and, when applicable, the inviter in that tenant.
- `skit_member.mobile` is unique only within a tenant. The same mobile can be
  a separate member in another agent tenant.
- An agent app is bound to exactly one agent tenant. It provides that context
  automatically during registration and login; it is not a user-entered form
  field.
- Management-console users remain bound to a tenant by their own unique admin
  account. Their password, SMS, social, and reset-password flows never ask for
  a tenant name.

## Authentication Flow

1. The app is installed from an agent-specific release profile or opened from
   its agent-specific invitation/deep link.
2. The app reads its immutable agent binding profile and obtains a short-lived,
   signed bootstrap token from the platform.
3. Registration sends the invitation code and bootstrap token. The backend
   resolves the invitation target and rejects a token for another agent.
4. Login sends mobile, password, and the bootstrap token. The backend obtains
   the tenant solely from the verified token, then queries the member by
   `(tenant_id, mobile)`.
5. A request without valid agent context fails with a generic entry-context
   error. It never falls back to a tenant picker or globally guesses a member.

The app may carry a public agent code as bootstrap input, but API authorization
uses the signed bootstrap token rather than trusting a caller-supplied code.

## Data Migration

- Remove `uk_skit_member_mobile`.
- Restore and enforce `uk_skit_member_tenant_mobile (tenant_id, mobile)` in
  both the initial MySQL schema and the schema initializer.
- Remove the global-mobile registration check.
- Preserve existing members. Duplicate mobiles across tenants are valid data
  after this migration; duplicate mobiles within one tenant are not.

## Release Model

### Shared SaaS releases

Backend and management frontend remain single deployments. A normal SaaS
feature release uses the existing backend-then-frontend CI/CD flow and does
not require app rebuilding.

### App release profiles

Each agent has a release profile, maintained by the platform administrator:

- agent tenant binding and public bootstrap identifier;
- Android/iOS package identifier and signing-secret reference;
- Pangle SDK setting-file reference and its package/license compatibility;
- Taku and other tenant ad configuration references;
- active release channel and supported native-runtime versions.

Secrets and SDK setting files are held in the release system or CI secrets,
never in Git or browser-delivered configuration.

### Two update paths

1. **Hot update (default):** a signed app web bundle is published to a stable
   or beta channel with a minimum compatible native-runtime version. On launch,
   the app checks a release manifest, downloads a compatible bundle, verifies
   its signature/hash, activates it atomically, and keeps the previous bundle
   for rollback.
2. **Native update (exception):** CI builds only the selected agent profiles
   when a native SDK, Pangle App ID/license/package name, signing identity, or
   native plugin changes. The produced APK/IPA becomes the profile's next
   native release. Android can offer an in-app download; iOS follows App Store
   distribution requirements.

The Pangle setting file and license are currently packaged in the native app,
so a universal runtime cannot safely switch arbitrary Pangle accounts at
runtime. This is why native shells remain agent-specific while ordinary
business updates stay shared.

## Reliability and Security

- Release manifests are versioned, signed, and channel-scoped.
- The app accepts a hot update only when the manifest's minimum native version
  is compatible with the installed shell.
- Activation is atomic: download to a staging path, verify, then switch the
  active pointer. A failed launch rolls back to the previous pointer.
- Bootstrap tokens are short-lived and bound to the agent profile; they are not
  reusable to choose another tenant.
- All server-side tenant selection is derived from the verified token or the
  authenticated management user, never from a visible login field.

## Acceptance Criteria

1. The same mobile can register in two different agent tenants and receives
   distinct member identities.
2. A member can log in only through a valid agent-bound app context; no tenant
   name or agent code field is visible.
3. An invitation cannot register a member into a tenant other than its bound
   agent tenant.
4. A standard SaaS release updates backend/frontend once and does not require
   native app rebuilds.
5. A compatible app business update is delivered through the signed hot-update
   channel and can roll back.
6. A Pangle/native configuration change builds only the affected agent profile
   and never exposes its credentials in repository files or public manifests.
