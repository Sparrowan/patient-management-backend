#!/usr/bin/env bash
#
# Generates the DEV mTLS certificates for inter-service gRPC (patient <-> billing).
#
# WHEN TO RUN: once, on the host, BEFORE building/running — locally or with Docker:
#     ./generate-certs.sh
#     ./mvnw spring-boot:run        # per service (local), OR
#     docker compose up --build     # whole stack (certs are baked into the images)
#
# WHY a host script (not a Dockerfile step): every service must share ONE certificate authority
# so they trust each other. Generating certs inside each image would give each a DIFFERENT CA and
# mTLS would fail. So we generate once here and distribute to each service's resources/certs/.
#
# WHY not commit the certs: nothing cert-related lives in git (certs/ is git-ignored). Regenerating
# per environment mirrors production, where a service mesh (Istio/SPIFFE) or Vault issues and
# rotates short-lived certs — the CA private key never leaves an HSM/Vault. THESE ARE DEV CERTS.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

echo "==> 1. Private CA (in prod this is Vault / an HSM / the mesh)"
openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout ca-key.pem -out ca-cert.pem -subj "/CN=patient-management-dev-ca" 2>/dev/null

echo "==> 2. billing = gRPC SERVER (SANs cover every hostname clients dial: docker + local)"
openssl req -newkey rsa:2048 -nodes -keyout billing-key.pem -out billing.csr \
  -subj "/CN=billing-service" 2>/dev/null
openssl x509 -req -in billing.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial -days 3650 \
  -sha256 -out billing-cert.pem \
  -extfile <(printf "subjectAltName=DNS:billing-service,DNS:localhost") 2>/dev/null

echo "==> 3. patient = gRPC CLIENT (its cert proves identity to billing — the 'mutual' in mTLS)"
openssl req -newkey rsa:2048 -nodes -keyout patient-key.pem -out patient.csr \
  -subj "/CN=patient-service" 2>/dev/null
openssl x509 -req -in patient.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial -days 3650 \
  -sha256 -out patient-cert.pem 2>/dev/null

echo "==> 4. Distribute (each service: the CA cert to verify peers + its own cert & key)"
BILL="$ROOT/billing-service/src/main/resources/certs"
PAT="$ROOT/patient-service/src/main/resources/certs"
mkdir -p "$BILL" "$PAT"
cp ca-cert.pem billing-cert.pem billing-key.pem "$BILL/"
cp ca-cert.pem patient-cert.pem patient-key.pem "$PAT/"
# NOTE: ca-key.pem (the CA private key — the crown jewel) is intentionally NOT distributed.

echo "==> Done. Certs written to:"
echo "    $BILL"
echo "    $PAT"
