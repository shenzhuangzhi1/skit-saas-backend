# Platform Admin and Agent Tenant Boundary Design

## Goal

Move agent creation into the system user-management surface, make the platform `super_admin` the only role that can create or administer agents, and prevent an agent administrator from selecting or reading another tenant.

## Role model

- `super_admin` is the platform admin. It can view all business functions and all agent data, create and update agents, and may explicitly target an agent tenant through protected management APIs.
- `tenant_admin` is an agent administrator. It is bound to the login tenant and can only read or mutate that tenant's business data.
- There is no independent `admin` role code in this system. Product wording may use “admin”, but authorization uses `super_admin`.

## UI model

- The system user-management page exposes an “新增代理商” action only for `super_admin`; it reuses the existing agent creation form and refreshes the user-management page after success.
- The short-drama tenant page remains the tenant-bound operational workspace. A platform admin sees the global agent list; an agent administrator sees only their own invitation and tenant-bound operational data.
- The header `TenantVisit` selector is removed. It is a global tenant override and violates the binding boundary; platform-wide management uses explicit target-tenant controls instead.

## Server model

- Agent creation and global agent page/get/update endpoints retain platform-admin guard checks in addition to Spring role expressions.
- Every tenant business endpoint resolves the request tenant to the original login tenant unless the original login identity passes the platform-admin guard. A request-supplied `tenantId` from a tenant administrator is rejected before data access.
- Existing member, ad account, commission, revenue, and release-profile services continue to execute inside the resolved tenant context; no endpoint trusts the browser's visit-tenant header for agent administrators.

## Validation

- Unit-test target-tenant resolution for platform admin, same-tenant agent admin, and cross-tenant agent admin rejection.
- Build the management frontend and verify no `TenantVisit` render path remains.
- Use the existing backend Compose topology test. Maven service tests are attempted but network dependency download is reported separately if it blocks.
