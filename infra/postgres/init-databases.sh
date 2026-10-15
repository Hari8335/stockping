#!/bin/sh
set -eu
# psql variables and format() quote all configured passwords as SQL literals.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set=catalog_password="$CATALOG_DB_PASSWORD" \
  --set=demand_password="$DEMAND_DB_PASSWORD" \
  --set=messaging_password="$MESSAGING_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE catalog_user LOGIN PASSWORD %L', :'catalog_password') \gexec
SELECT format('CREATE ROLE demand_user LOGIN PASSWORD %L', :'demand_password') \gexec
SELECT format('CREATE ROLE messaging_user LOGIN PASSWORD %L', :'messaging_password') \gexec
CREATE DATABASE catalog_db OWNER catalog_user;
CREATE DATABASE demand_db OWNER demand_user;
CREATE DATABASE messaging_db OWNER messaging_user;
REVOKE CONNECT, TEMPORARY ON DATABASE catalog_db FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE demand_db FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE messaging_db FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE catalog_db TO catalog_user;
GRANT CONNECT, TEMPORARY ON DATABASE demand_db TO demand_user;
GRANT CONNECT, TEMPORARY ON DATABASE messaging_db TO messaging_user;
SQL

