#!/usr/bin/env bash
# Runs one Chatterbox shard on the render host in batches of 25 clips: a fresh process per batch frees the memory the
# model leaks, so the owner's machine never fills up. Stops when a batch renders nothing (the shard is done).
# Usage: tools/voices/render_loop.sh JOBS SHARD LOG
set -u
jobs="$1"; shard="$2"; log="$3"
while true; do
  ssh -o BatchMode=yes -o ServerAliveInterval=60 gpu5090 "cd /d C:\\mokuhyo-render && set \"HF_HOME=C:\\mokuhyo-render\\hf\" && .venv\\Scripts\\python.exe chatterbox_render.py $jobs clips refs --shard $shard --threads 4 --max-jobs 25" >> "$log" 2>&1
  tail -c 2000 "$log" | grep -q "chatterbox_render: 0 rendered" && break
  sleep 5
done
echo "loop done" >> "$log"
