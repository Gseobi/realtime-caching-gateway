#!/usr/bin/env python3
"""Verify HTTP durability against disposable Docker services, never production."""
import argparse, datetime, json, subprocess, time, urllib.request, urllib.error, uuid
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--url', default='http://localhost:18080')
p.add_argument('--postgres', required=True)
p.add_argument('--cache', required=True)
p.add_argument('--cache-cli', default='redis-cli')
p.add_argument('--output', required=True)
a = p.parse_args()
prefix = 'fault-' + uuid.uuid4().hex[:12]
conversation = prefix
constraint = 'verify_' + uuid.uuid4().hex[:12]
events = []

def record(stage, **data):
    event = dict(at=datetime.datetime.now(datetime.timezone.utc).isoformat(), stage=stage, **data)
    events.append(event)
    print(json.dumps(event), flush=True)

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def sql(statement):
    return run('docker', 'exec', a.postgres, 'psql', '-U', 'postgres', '-d',
               'realtime_caching_gateway', '-v', 'ON_ERROR_STOP=1', '-Atc', statement)

def http(method, message_id=None, sent_at=None):
    data = None if method == 'GET' else json.dumps(dict(messageId=message_id,
        senderId='synthetic', messageType='TEXT', content=message_id, sentAt=sent_at)).encode()
    request = urllib.request.Request(a.url + '/v1/api/conversations/' + conversation + '/messages',
                                    data=data, method=method, headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

def post(suffix, minute, success=True):
    mid = prefix + '-' + suffix
    code, body = http('POST', mid, f'2026-10-15T01:{minute:02}:00')
    record('post', messageId=mid, status=code, response=body)
    assert (200 <= code < 300) if success else (500 <= code < 600), (code, body)
    return mid

def get(expected, stage):
    code, body = http('GET')
    ids = [m['messageId'] for m in json.loads(body)] if code == 200 else []
    record(stage, status=code, ids=ids)
    assert code == 200 and ids == expected, (code, body, expected)

stopped = False
installed = False
try:
    first = post('before', 1)
    get([first], 'before_stop')
    run('docker', 'stop', a.cache)
    stopped = True
    record('cache_stopped', container=a.cache)
    second = post('during', 2)
    get([second, first], 'during_stop')
    committed = sql(f"SELECT message_id FROM message WHERE conversation_id='{conversation}' ORDER BY sent_at DESC")
    record('db_during_stop', ids=committed.splitlines())
    assert committed.splitlines() == [second, first]
    run('docker', 'start', a.cache)
    stopped = False
    for _ in range(60):
        try:
            if run('docker', 'exec', a.cache, a.cache_cli, 'ping') == 'PONG': break
        except subprocess.CalledProcessError: pass
        time.sleep(1)
    else: raise AssertionError('cache did not restart')
    get([second, first], 'after_restart')
    key = run('docker', 'exec', a.cache, a.cache_cli, 'exists', 'v2:msg:' + second)
    record('cache_reprojected', messageId=second, exists=key)
    assert key == '1'
    sql(f"ALTER TABLE conversation_state ADD CONSTRAINT {constraint} CHECK (conversation_id <> '{conversation}') NOT VALID")
    installed = True
    rejected = post('rollback', 3, success=False)
    count = sql(f"SELECT count(*) FROM message WHERE message_id='{rejected}'")
    state = sql(f"SELECT last_message_id FROM conversation_state WHERE conversation_id='{conversation}'")
    cached = run('docker', 'exec', a.cache, a.cache_cli, 'exists', 'v2:msg:' + rejected)
    record('rollback', rejectedId=rejected, messageCount=count, lastMessageId=state, cacheExists=cached)
    assert count == '0' and state == second and cached == '0'
    get([second, first], 'after_rollback')
    record('result', result='PASS')
finally:
    if installed: sql(f'ALTER TABLE conversation_state DROP CONSTRAINT {constraint}')
    if stopped: run('docker', 'start', a.cache)
    Path(a.output).parent.mkdir(parents=True, exist_ok=True)
    Path(a.output).write_text(json.dumps(events, indent=2) + '\n')
