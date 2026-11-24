#!/usr/bin/env python3
"""Copy the notifications database between MySQL and PostgreSQL with writers paused."""

import argparse
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
import sys


BATCH_SIZE = 500
SCHEMA_VERSION = '4'


@dataclass(frozen=True)
class Table:
    name: str
    columns: str
    order: str

    @property
    def select(self):
        return f'SELECT {self.columns} FROM {self.name} ORDER BY {self.order}'

    @property
    def insert(self):
        placeholders = ','.join(['%s'] * len(self.columns.split(',')))
        return f'INSERT INTO {self.name} ({self.columns}) VALUES ({placeholders})'


TABLES = (
    Table('notifications', 'id,user_id,type,title,content,thread_id,target_id,target_type,'
          'read_flag,created_at,idempotency_key', 'id'),
    Table('notification_stream_cursor', 'user_id,next_sequence', 'user_id'),
    Table('notification_stream_event', 'user_id,sequence_id,notification_id,event_kind,occurred_at',
          'user_id,sequence_id'),
)


def connect(engine, role):
    prefix = f'NOTIFICATION_COPY_{role.upper()}_'
    settings = {key: os.environ[prefix + key.upper()]
                for key in ('host', 'port', 'user', 'password')}
    settings['port'] = int(settings['port'])
    database = os.environ[prefix + 'DATABASE']
    if engine == 'mysql':
        import pymysql

        return pymysql.connect(**settings, database=database, charset='utf8mb4', connect_timeout=10)

    import psycopg

    return psycopg.connect(**settings, dbname=database, connect_timeout=10)


def begin(connection, engine, source):
    with connection.cursor() as cursor:
        if engine == 'mysql':
            cursor.execute("SET time_zone = '+00:00'")
            cursor.execute('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            connection.commit()
            cursor.execute('START TRANSACTION READ ONLY' if source else 'START TRANSACTION')
        else:
            cursor.execute("SET TIME ZONE 'UTC'")
            connection.commit()
            mode = 'READ ONLY' if source else 'READ WRITE'
            cursor.execute(f'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, {mode}')
            if not source:
                cursor.execute("SET LOCAL lock_timeout = '10s'")
                names = ','.join(table.name for table in TABLES)
                cursor.execute(f'LOCK TABLE {names} IN ACCESS EXCLUSIVE MODE')
        cursor.execute('SELECT version, success FROM notification_flyway_schema_history '
                       'WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1')
        if cursor.fetchone() != (SCHEMA_VERSION, True):
            raise ValueError('Both databases must have the notification Flyway V4 schema.')


def require_empty(connection):
    with connection.cursor() as cursor:
        for table in TABLES:
            cursor.execute(f'SELECT {table.order.split(",")[0]} FROM {table.name} '
                           'LIMIT 1 FOR UPDATE')
            if cursor.fetchone() is not None:
                raise ValueError(f'Destination table {table.name} contains rows. Use an empty target.')


def rows(connection, engine, table):
    if engine == 'mysql':
        from pymysql.cursors import SSCursor

        stream = connection.cursor(SSCursor)
    else:
        stream = connection.cursor(name='copy_' + table.name)

    with stream:
        stream.execute(table.select)
        while batch := stream.fetchmany(BATCH_SIZE):
            yield batch


def canonical_value(value):
    if isinstance(value, datetime):
        utc = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
        return utc.isoformat(timespec='microseconds')
    return value


def measure(connection, engine, table, writer=None):
    digest = hashlib.sha256()
    count = 0
    highest_id = 0
    for batch in rows(connection, engine, table):
        converted = []
        for row in batch:
            if table.name == 'notifications':
                highest_id = row[0]
                row = (*row[:8], bool(row[8]), *row[9:])
            if writer is not None:
                converted.append(row)
            values = tuple(canonical_value(value) for value in row)
            digest.update(json.dumps(values, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))
            digest.update(b'\n')
        if writer is not None:
            writer.executemany(table.insert, converted)
        count += len(batch)
    return {'rows': count, 'sha256': digest.hexdigest()}, highest_id


def copy(source, source_engine, target, target_engine):
    begin(source, source_engine, source=True)
    begin(target, target_engine, source=False)
    require_empty(target)
    evidence = {}
    with target.cursor() as writer:
        for table in TABLES:
            expected, highest_id = measure(source, source_engine, table, writer)
            actual, _ = measure(target, target_engine, table)
            if expected != actual:
                raise ValueError(f'Destination rows or SHA-256 did not match for {table.name}.')
            evidence[table.name] = actual
            if table.name == 'notifications' and target_engine == 'postgresql':
                # Supplying explicit IDs does not advance the PostgreSQL identity sequence.
                writer.execute(f'ALTER TABLE notifications ALTER COLUMN id RESTART WITH {highest_id + 1}')
    target.commit()
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    engines = ('mysql', 'postgresql')
    parser.add_argument('--source', choices=engines, required=True)
    parser.add_argument('--target', choices=engines, required=True)
    args = parser.parse_args()
    if args.source == args.target:
        parser.error('Source and destination must use different engines.')

    try:
        with closing(connect(args.source, 'source')) as source, closing(connect(args.target, 'target')) as target:
            evidence = copy(source, args.source, target, args.target)
    except (ImportError, KeyError, ValueError) as error:
        parser.exit(1, f'Notification copy failed: {error}\n')
    except Exception as error:
        # Driver messages can include connection information and business data.
        state = getattr(error, 'sqlstate', None)
        details = f', SQLSTATE {state}' if state else ''
        parser.exit(1, f'Notification copy failed: {type(error).__name__}{details}.\n')

    print(json.dumps({'source': args.source, 'target': args.target, 'tables': evidence}, indent=2))


if __name__ == '__main__':
    main()
