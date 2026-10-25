#!/usr/bin/env bash
# Couple — real-device MQTT delivery test (R6 / 后台保活).
#
# Two paired phones running the DEBUG build (com.qimingnan.couple.debug), both logged in, paired and connected.
# Phone A (sender) publishes presence probes on demand through a debug-only, shell-only broadcast; phone B (receiver)
# is pushed through Doze (force-idle), App Standby buckets, background restriction and network toggles. Delivery and
# latency are read from the app's logcat lines (tag CoupleMqtt: "tx mid=…" on A, "rx mid=…" on B), corrected for the
# clock offset between each phone and this computer.
#
# Usage:
#   app/scripts/reliability-test.sh -a <serial A> -b <serial B> [-p <package>] [-n <probes per phase>] [-i <interval s>]
#                                   [-o <out dir>] [-P <phases>]
#   phases (comma list, default all): baseline,idle,buckets,restricted,wifi,airplane,cellular
#
# Requirements: adb in PATH, both phones authorized, USB connected (network toggles would cut adb-over-Wi-Fi),
# python3 on this computer. Keep phone B on battery-only mode where possible: "dumpsys battery unplug" simulates it.
# The script restores everything it changes on exit (trap), but check Settings if it is interrupted hard.
set -euo pipefail

PKG="com.qimingnan.couple.debug"
A=""; B=""; N=10; INTERVAL=6; OUT="reliability-$(date +%Y%m%d-%H%M%S)"
PHASES="baseline,idle,buckets,restricted,wifi,airplane,cellular"
PROBE_ACTION="com.qimingnan.couple.debug.MQTT_PROBE"
TAG="CoupleMqtt"

while getopts "a:b:p:n:i:o:P:h" opt; do
  case $opt in
    a) A=$OPTARG ;; b) B=$OPTARG ;; p) PKG=$OPTARG ;; n) N=$OPTARG ;; i) INTERVAL=$OPTARG ;;
    o) OUT=$OPTARG ;; P) PHASES=$OPTARG ;;
    h|*) sed -n '2,20p' "$0"; exit 0 ;;
  esac
done
[[ -n $A && -n $B ]] || { echo "need -a <serial A> -b <serial B> (see: adb devices)" >&2; exit 2; }
command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 2; }
mkdir -p "$OUT"
RESULTS="$OUT/results.csv"
echo "phase,sent,received,delivery_pct,received_during_phase,p50_ms,p95_ms,max_ms,reconnect_ms,notes" > "$RESULTS"

adbA() { adb -s "$A" "$@"; }
adbB() { adb -s "$B" "$@"; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

# Device clock minus host clock, in ms (median of 5 samples, half the adb round trip subtracted).
clock_offset() {
  local serial=$1 samples=()
  for _ in 1 2 3 4 5; do
    local t0 dev t1
    t0=$(now_ms); dev=$(adb -s "$serial" shell 'echo $(($(date +%s%N)/1000000))' | tr -d '\r'); t1=$(now_ms)
    samples+=($(( dev - (t0 + t1) / 2 )))
  done
  printf '%s\n' "${samples[@]}" | sort -n | sed -n 3p
}

for s in "$A" "$B"; do
  adb -s "$s" get-state >/dev/null || { echo "device $s not available" >&2; exit 2; }
  adb -s "$s" shell pm path "$PKG" >/dev/null || { echo "$PKG not installed on $s" >&2; exit 2; }
done
SDK_B=$(adbB shell getprop ro.build.version.sdk | tr -d '\r')
log "A=$A ($(adbA shell getprop ro.product.manufacturer | tr -d '\r') $(adbA shell getprop ro.product.model | tr -d '\r'))"
log "B=$B ($(adbB shell getprop ro.product.manufacturer | tr -d '\r') $(adbB shell getprop ro.product.model | tr -d '\r'), SDK $SDK_B)"

restore() {
  log "restoring phone B settings"
  adbB shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adbB shell dumpsys battery reset >/dev/null 2>&1 || true
  adbB shell am set-standby-bucket "$PKG" active >/dev/null 2>&1 || true
  adbB shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
  adbB shell svc wifi enable >/dev/null 2>&1 || true
  adbB shell svc data enable >/dev/null 2>&1 || true
  adbB shell cmd connectivity airplane-mode disable >/dev/null 2>&1 \
    || adbB shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
  [[ -n ${LOGPID_A:-} ]] && kill "$LOGPID_A" 2>/dev/null || true
  [[ -n ${LOGPID_B:-} ]] && kill "$LOGPID_B" 2>/dev/null || true
}
trap restore EXIT

# Fresh logcat capture (epoch timestamps) on both phones.
adbA logcat -c; adbB logcat -c
adbA logcat -v epoch -s "$TAG:V" > "$OUT/A.log" & LOGPID_A=$!
adbB logcat -v epoch -s "$TAG:V" > "$OUT/B.log" & LOGPID_B=$!

OFF_A=$(clock_offset "$A"); OFF_B=$(clock_offset "$B")
log "clock offsets vs host: A=${OFF_A}ms B=${OFF_B}ms"
echo "$OFF_A $OFF_B" > "$OUT/offsets"

# Bring B's app to a known state (started once, then backgrounded) and let it connect.
adbB shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 5
adbB shell input keyevent KEYCODE_HOME
adbA shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 5

# Sends N probes from A, one every INTERVAL s. Each probe gets a unique tag recorded in A's log.
send_probes() {
  local phase=$1 count=$2
  for i in $(seq 1 "$count"); do
    adbA shell am broadcast -a "$PROBE_ACTION" -p "$PKG" --es tag "$phase-$i" >/dev/null
    sleep "$INTERVAL"
  done
}

screen_off_B() { adbB shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || adbB shell input keyevent 26; }
screen_on_B() { adbB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || adbB shell input keyevent 26; }

# Reconnect time on B after a network event: first "stats[connected]" line after the given host time.
reconnect_ms_after() {
  local since_host_ms=$1
  python3 - "$OUT/B.log" "$since_host_ms" "$OFF_B" <<'PY'
import sys, re
path, since, off = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
for line in open(path, errors="replace"):
    m = re.match(r"\s*(\d+)\.(\d{3})", line)
    if not m or "stats[connected]" not in line:
        continue
    t = int(m.group(1)) * 1000 + int(m.group(2)) - off   # device epoch -> host clock
    if t >= since:
        print(t - since); break
else:
    print("")
PY
}

# Evaluates one phase: which probes A sent in [t0, t1+grace] reached B, and how fast.
evaluate() {
  local phase=$1 t0=$2 t_end=$3 reconnect=$4 notes=$5
  python3 - "$OUT/A.log" "$OUT/B.log" "$OFF_A" "$OFF_B" "$phase" "$t0" "$t_end" "$reconnect" "$notes" >> "$RESULTS" <<'PY'
import sys, re
a_log, b_log, off_a, off_b, phase, t0, t_end, reconnect, notes = sys.argv[1:]
off_a, off_b, t0, t_end = int(off_a), int(off_b), int(t0), int(t_end)
tx = {}   # mid -> send time (host clock)
for line in open(a_log, errors="replace"):
    m = re.search(r"tx mid=(\S+) ts=(\d+)(?: tag=(\S+))?", line)
    if m and (m.group(3) or "").startswith(phase + "-"):
        tx[m.group(1)] = int(m.group(2)) - off_a
rx = {}   # mid -> receive time (host clock), first delivery only (dedup also happens in-app)
for line in open(b_log, errors="replace"):
    m = re.search(r"rx mid=(\S+) .*rxTs=(\d+)", line)
    if m and m.group(1) in tx and m.group(1) not in rx:
        rx[m.group(1)] = int(m.group(2)) - off_b
lat = sorted(rx[k] - tx[k] for k in rx)
def pct(p):
    if not lat: return ""
    import math
    return str(lat[max(1, math.ceil(p / 100 * len(lat))) - 1])
during = sum(1 for k in rx if rx[k] <= t_end)
sent = len(tx)
print(",".join([phase, str(sent), str(len(rx)), f"{(100.0 * len(rx) / sent) if sent else 0:.0f}", str(during),
                pct(50), pct(95), str(lat[-1]) if lat else "", reconnect, notes.replace(",", ";")]))
PY
  log "$(tail -1 "$RESULTS")"
}

phase_enabled() { [[ ",$PHASES," == *",$1,"* ]]; }

# --- phases -------------------------------------------------------------------------------------------------------

if phase_enabled baseline; then
  log "phase baseline: B screen on, app in background"
  screen_on_B; t0=$(now_ms); send_probes baseline "$N"; t1=$(now_ms); sleep 5
  evaluate baseline "$t0" "$t1" "" "screen on"
fi

if phase_enabled idle; then
  log "phase idle: B unplugged, screen off, deviceidle force-idle (deep Doze)"
  adbB shell dumpsys battery unplug; screen_off_B; sleep 2
  adbB shell dumpsys deviceidle force-idle | tee -a "$OUT/run.log"
  t0=$(now_ms); send_probes idle "$N"; t1=$(now_ms)
  log "leaving idle (unforce + screen on); messages delivered only now count as late"
  adbB shell dumpsys deviceidle unforce; adbB shell dumpsys battery reset; screen_on_B
  sleep 20
  evaluate idle "$t0" "$t1" "" "received_during_phase = delivered while still in Doze (expect 0 unless whitelisted / FGS)"
fi

if phase_enabled buckets; then
  for bucket in working_set frequent rare restricted; do
    log "phase bucket-$bucket: B screen off, standby bucket=$bucket"
    adbB shell am set-standby-bucket "$PKG" "$bucket" || { log "bucket $bucket not supported"; continue; }
    adbB shell dumpsys battery unplug; screen_off_B; sleep 2
    t0=$(now_ms); send_probes "bucket-$bucket" "$N"; t1=$(now_ms); sleep 5
    adbB shell dumpsys battery reset; screen_on_B; sleep 15
    evaluate "bucket-$bucket" "$t0" "$t1" "" "bucket now: $(adbB shell am get-standby-bucket "$PKG" | tr -d '\r')"
  done
  adbB shell am set-standby-bucket "$PKG" active
fi

if phase_enabled restricted; then
  log "phase restricted: RUN_ANY_IN_BACKGROUND ignore (= user restricted background usage)"
  adbB shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND ignore
  adbB shell input keyevent KEYCODE_HOME; screen_off_B; sleep 2
  t0=$(now_ms); send_probes restricted "$N"; t1=$(now_ms); sleep 5
  adbB shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow; screen_on_B; sleep 15
  evaluate restricted "$t0" "$t1" "" "background restricted"
fi

if phase_enabled wifi; then
  log "phase wifi: Wi-Fi off (fall back to cellular) mid-stream, then back on"
  screen_on_B; t0=$(now_ms)
  ( sleep "$((INTERVAL * 2))"; adbB shell svc wifi disable; echo "$(now_ms)" > "$OUT/wifi_off_at"
    sleep "$((INTERVAL * 3))"; adbB shell svc wifi enable; echo "$(now_ms)" > "$OUT/wifi_on_at" ) &
  send_probes wifi "$N"; wait; t1=$(now_ms); sleep 10
  r_off=$(reconnect_ms_after "$(cat "$OUT/wifi_off_at")"); r_on=$(reconnect_ms_after "$(cat "$OUT/wifi_on_at")")
  evaluate wifi "$t0" "$t1" "${r_off}/${r_on}" "reconnect_ms = after wifi off / after wifi on"
fi

if phase_enabled airplane; then
  log "phase airplane: no network for 3 intervals (QoS1 messages must be queued and delivered after)"
  screen_on_B; t0=$(now_ms)
  ( sleep "$INTERVAL"
    adbB shell cmd connectivity airplane-mode enable 2>/dev/null \
      || { adbB shell settings put global airplane_mode_on 1; adbB shell am broadcast -a android.intent.action.AIRPLANE_MODE >/dev/null; }
    sleep "$((INTERVAL * 3))"
    adbB shell cmd connectivity airplane-mode disable 2>/dev/null \
      || { adbB shell settings put global airplane_mode_on 0; adbB shell am broadcast -a android.intent.action.AIRPLANE_MODE >/dev/null; }
    echo "$(now_ms)" > "$OUT/airplane_off_at" ) &
  send_probes airplane "$N"; wait; t1=$(now_ms); sleep 20
  evaluate airplane "$t0" "$t1" "$(reconnect_ms_after "$(cat "$OUT/airplane_off_at")")" "reconnect_ms = network back -> connected"
fi

if phase_enabled cellular; then
  log "phase cellular: Wi-Fi off and mobile data toggled (cellular-only path, then no data)"
  adbB shell svc wifi disable; sleep 10; t0=$(now_ms)
  ( sleep "$((INTERVAL * 2))"; adbB shell svc data disable; sleep "$((INTERVAL * 2))"; adbB shell svc data enable
    echo "$(now_ms)" > "$OUT/data_on_at" ) &
  send_probes cellular "$N"; wait; t1=$(now_ms); sleep 20
  adbB shell svc wifi enable
  evaluate cellular "$t0" "$t1" "$(reconnect_ms_after "$(cat "$OUT/data_on_at")")" "reconnect_ms = mobile data back -> connected"
fi

log "app counters on B (last stats line):"
grep "stats" "$OUT/B.log" | tail -1 | tee -a "$OUT/run.log" || true
echo
column -s, -t < "$RESULTS" || cat "$RESULTS"
log "raw logs and results in $OUT/"
