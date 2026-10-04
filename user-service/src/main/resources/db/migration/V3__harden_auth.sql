-- Regular users may read only their own profile (enforced in UserController); USER_READ is for ADMIN.
DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE name = 'USER')
  AND permission_id = (SELECT id FROM permissions WHERE name = 'USER_READ');

-- Never used: failed logins are throttled by the Redis rate limiter.
ALTER TABLE users DROP COLUMN failed_login_attempts;
