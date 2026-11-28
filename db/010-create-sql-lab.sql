\getenv terminal_password SQL_LAB_PASSWORD

CREATE ROLE sql_terminal LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    CONNECTION LIMIT 3 PASSWORD :'terminal_password';
REVOKE CONNECT ON DATABASE postgres, template1 FROM PUBLIC;
REVOKE ALL ON DATABASE sql_lab FROM PUBLIC;
GRANT CONNECT, TEMP ON DATABASE sql_lab TO sql_terminal;
CREATE SCHEMA workspace AUTHORIZATION sql_terminal;
ALTER ROLE sql_terminal IN DATABASE sql_lab SET search_path = workspace;
ALTER ROLE sql_terminal IN DATABASE sql_lab SET statement_timeout = '4s';
ALTER ROLE sql_terminal IN DATABASE sql_lab SET lock_timeout = '2s';
ALTER ROLE sql_terminal IN DATABASE sql_lab SET idle_in_transaction_session_timeout = '6s';
ALTER ROLE sql_terminal IN DATABASE sql_lab SET temp_file_limit = '32MB';
