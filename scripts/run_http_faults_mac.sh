#!/usr/bin/env bash
set -euo pipefail
cache="$1"
cache_port="$2"
cache_cli="$3"
engine="$4"
app="codex-durable-http-${engine}-20261015"
docker run -d --name "$app" -p 127.0.0.1:18080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:15432/realtime_caching_gateway \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal -e SPRING_DATA_REDIS_PORT="$cache_port" \
  -e SPRING_DATA_REDIS_TIMEOUT=2s -e SPRING_DATA_REDIS_CONNECT_TIMEOUT=2s \
  codex-durable-verify:20261015
trap 'docker stop "$app" >/dev/null' EXIT
for i in {1..60}; do
  if curl -fsS --max-time 2 http://localhost:18080/v1/api/conversations/readiness/messages >/dev/null 2>&1; then break; fi
  sleep 1
done
python3 scripts/verify_http_faults.py --postgres codex-durable-pg-20261015 \
  --cache "$cache" --cache-cli "$cache_cli" \
  --output "docs/evidence/mac-docker-20261015/$engine/http-faults.json"
docker logs "$app" > "docs/evidence/mac-docker-20261015/$engine/http-app.log" 2>&1
