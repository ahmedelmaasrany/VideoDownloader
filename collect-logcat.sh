#!/usr/bin/env bash
set -euo pipefail

SDK="/c/Users/USER/AppData/Local/Android/Sdk"
export PATH="$PATH:$SDK/platform-tools"

mkdir -p reports
RAW="$(mktemp)"
trap 'rm -f "$RAW"' EXIT

REPORT="reports/logcat-latest.txt"

adb logcat -d -b all -v threadtime > "$RAW"

{
    echo "========== VIDEO DOWNLOADER RELIABILITY REPORT =========="
    echo "Date: $(date)"
    echo "Package: com.example.videodownloader"
    echo
    echo "========== DEVICE =========="
    echo "Model: $(adb shell getprop ro.product.model | tr -d '\r')"
    echo "Android: $(adb shell getprop ro.build.version.release | tr -d '\r')"
    echo "SDK: $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo
    echo "========== FILTERED DIAGNOSTIC LOGS =========="
    echo "Source: All available Logcat buffers"
    echo
} > "$REPORT"

grep -Ei -B 4 -A 8 \
'com\.example\.videodownloader|DL_REPORT|FFmpegKit|FFmpeg|FFprobe|yt_dlp|yt-dlp|Chaquopy|Python|DownloadForegroundService|ForegroundService|startForeground|stopForeground|FATAL EXCEPTION|AndroidRuntime|ANR in|Fatal signal|OutOfMemoryError|SecurityException|IllegalStateException|NullPointerException|TimeoutException|InterruptedException|IOException|Permission Denial|Foreground service did not start|RemoteServiceException|onTimeout|am_crash|am_anr|denied|timed out|failed|failure|exception|error|warning' \
"$RAW" >> "$REPORT" || true

echo "========== SUMMARY ==========" >> "$REPORT"
echo "Collected lines: $(wc -l < "$RAW")" >> "$REPORT"
echo "Filtered lines: $(wc -l < "$REPORT")" >> "$REPORT"

clip.exe < "$REPORT"
notepad "$(cygpath -w "$REPORT")"
