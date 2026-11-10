#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../mediaplayer/src/jvmTest/resources/dash"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=320x180:rate=24 -f lavfi -i sine=frequency=880:sample_rate=48000 -t 12 -map 0:v -map 0:v -map 1:a -filter:v:0 scale=160:90 -c:v libx264 -preset ultrafast -g 48 -keyint_min 48 -sc_threshold 0 -pix_fmt yuv420p -b:v:0 100k -b:v:1 240k -c:a aac -b:a 64k -ac 2 -f dash -seg_duration 2 -use_template 1 -use_timeline 1 -adaptation_sets 'id=0,streams=v id=1,streams=a' manifest.mpd
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc2=size=160x90:rate=24 -f lavfi -i sine=frequency=880:sample_rate=48000 -t 6 -c:v libx264 -preset ultrafast -g 48 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 -movflags +faststart regression.mp4
ffmpeg -hide_banner -loglevel error -y -i regression.mp4 -c copy -f hls -hls_time 2 -hls_list_size 0 regression.m3u8
