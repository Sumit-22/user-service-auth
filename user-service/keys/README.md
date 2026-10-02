Generate an RSA key pair for local development:

mkdir -p keys
openssl genrsa -out keys/private.pem 3072
openssl rsa -in keys/private.pem -pubout -out keys/public.pem

Never commit private.pem. In production use a secret manager/KMS/HSM.
