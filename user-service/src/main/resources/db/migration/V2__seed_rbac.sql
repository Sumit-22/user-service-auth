INSERT INTO roles(name) VALUES ('USER'), ('ADMIN');

INSERT INTO permissions(name) VALUES
('USER_READ'),
('USER_CREATE'),
('USER_UPDATE'),
('USER_DELETE');

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.name='USER' AND p.name='USER_READ';

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.name='ADMIN';
