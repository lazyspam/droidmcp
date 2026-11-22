#!/bin/sh
# droid: call DroidMCP's phone tools from Linux shell scripts. Installed by the DroidMCP app.
#
#   droid tools                       list tools and their arguments
#   droid <tool> [key=value ...]      run a tool, print its text; exit 1 if the tool failed
#   droid <tool> '{"x": 1}'           arguments as a JSON object (or - to read it from stdin)
#   droid --json <tool> [...]         print the raw MCP result (images included, base64)
#
# key=value: numbers, true, false and null are sent as JSON; anything else as a string.
# key:=<json> sends raw JSON (key:='[1,2]'); key=@file sends a file's contents as a string.

API_FILE=/etc/droidmcp/api

help() {
  cat <<'EOF'
droid: call the phone's tools from Linux (DroidMCP).

  droid tools                     list tools and their arguments
  droid <tool> [key=value ...]    run a tool, print its text (exit 1 if it failed)
  droid <tool> '{"x":1}'          arguments as JSON (or - to read JSON from stdin)
  droid --json <tool> [...]       raw MCP result as JSON (images included)

  key=value sends numbers/true/false/null as JSON, anything else as a string.
  key:=<json> sends raw JSON; key=@file sends a file's contents.

Examples:
  droid launch_app package=com.android.settings
  droid ui_tree | grep -i wi-fi
  droid tap x=540 y=1200
  droid type_text text="hello world"
  droid key key=BACK

Python (any script run in this distro):
  import droid
  droid.launch_app(package="com.android.chrome")   # every tool is a function, returns its text
  nodes = droid.ui()                                # ui_tree parsed: text, desc, id, cls, flags, x, y
  droid.tap_on(text="Search")                       # find an element and tap its center
  droid.wait_for(text="Results", timeout=15)        # poll ui_tree until it appears (or raise)
  droid.find(desc="Send", clickable=True)           # first match or None (text= etc. match substrings,
                                                    #   case-insensitive; exact=True for exact)
  png = droid.screenshot_png("/tmp/s.png")          # screenshot bytes, optionally saved
  droid.call("tap", x=1, y=2)                       # raw result dict, never raises
  droid.tools()                                     # tool definitions

Node (require or import, from any folder):
  const droid = require("droid")                    // or: import droid from "droid"
  await droid.launch_app({ package: "com.android.chrome" })
  await droid.tap_on({ text: "Search" }); await droid.wait_for({ text: "Results", timeout: 15 })
  const nodes = await droid.ui(); const n = await droid.find({ id: "send" })

Failed tools raise droid.ToolError (Python) or reject with an Error (Node).
EOF
}

die() { echo "droid: $*" >&2; exit 2; }

url=${DROID_API:-}
tok=${DROID_TOKEN:-}
if [ -z "$url" ]; then
  [ -r "$API_FILE" ] || die "DroidMCP isn't running (no $API_FILE). Start it in the app."
  { read -r url; read -r tok; } < "$API_FILE"
fi

tmp=$(mktemp -d 2>/dev/null || { d=/tmp/droid.$$; mkdir -p "$d" && echo "$d"; }) || die "can't create a temp dir"
trap 'rm -rf "$tmp"' EXIT INT TERM

# request METHOD PATH [BODYFILE] -> response body on stdout
request() {
  m=$1 p=$2 f=${3:-}
  if command -v curl >/dev/null 2>&1; then
    if [ -n "$f" ]; then
      curl -sS -X "$m" -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' --data-binary "@$f" "$url$p"
    else
      curl -sS -X "$m" -H "Authorization: Bearer $tok" "$url$p"
    fi
  elif command -v wget >/dev/null 2>&1; then
    if [ -n "$f" ]; then
      wget -q -O - --header "Authorization: Bearer $tok" --header 'Content-Type: application/json' --post-file "$f" "$url$p"
    else
      wget -q -O - --header "Authorization: Bearer $tok" "$url$p"
    fi
  elif command -v python3 >/dev/null 2>&1; then
    python3 -I -c '
import sys, urllib.request
m, url, tok, f = sys.argv[1:5]
data = open(f, "rb").read() if f else None
req = urllib.request.Request(url, data=data, method=m, headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
try:
    r = urllib.request.urlopen(req, timeout=3600)
except urllib.error.HTTPError as e:
    r = e
sys.stdout.buffer.write(r.read())
' "$m" "$url$p" "$tok" "$f"
  elif command -v bash >/dev/null 2>&1; then
    bash -c '
m=$1 url=$2 tok=$3 f=$4
hp=${url#http://}; host=${hp%%:*}; port=${hp##*:}
exec 3<>"/dev/tcp/$host/$port" || exit 1
len=0; [ -n "$f" ] && len=$(wc -c < "$f")
printf "%s %s HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n" "$m" "$5" "$host" "$tok" "$len" >&3
[ -n "$f" ] && cat "$f" >&3
sed "1,/^\r\$/d" <&3
' _ "$m" "$url" "$tok" "$f" "$p"
  else
    die "needs curl, wget, python3 or bash to reach the phone"
  fi
}

# JSON string literal of $1
jstr() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e "s/$(printf '\t')/\\\\t/g" -e "s/$(printf '\r')/\\\\r/g" |
    awk 'BEGIN { ORS = ""; print "\"" } NR > 1 { print "\\n" } { print } END { print "\"" }'
}

isnum() { printf '%s\n' "$1" | awk 'NR == 1 && /^-?[0-9]+(\.[0-9]+)?$/ { f = 1 } END { exit !(f && NR == 1) }'; }

json=0
case "${1:-}" in
  ""|-h|--help|help) help; exit 0 ;;
  --json) json=1; shift; [ $# -gt 0 ] || die "--json needs a tool name" ;;
esac

if [ "$1" = tools ]; then
  request GET "/tools?format=text" > "$tmp/out" || die "can't reach DroidMCP at $url (is the app running?)"
  tail -n +2 "$tmp/out"
  exit 0
fi

tool=$1; shift
body=$tmp/body
if [ $# -eq 1 ] && [ "$1" = - ]; then
  cat > "$body"
elif [ $# -eq 1 ] && case "$1" in "{"*) true ;; *) false ;; esac; then
  printf '%s' "$1" > "$body"
else
  sep=""
  printf '{' > "$body"
  for a in "$@"; do
    case "$a" in
      *:=*) k=${a%%:=*}; v=${a#*:=}; val=$v ;;
      *=*)
        k=${a%%=*}; v=${a#*=}
        case "$v" in
          @*) [ -r "${v#@}" ] || die "can't read ${v#@}"; val=$(jstr "$(cat "${v#@}")") ;;
          true|false|null) val=$v ;;
          *) if isnum "$v"; then val=$v; else val=$(jstr "$v"); fi ;;
        esac ;;
      *) die "bad argument '$a': use key=value, key:=json, a JSON object, or -" ;;
    esac
    printf '%s%s:%s' "$sep" "$(jstr "$k")" "$val" >> "$body"
    sep=,
  done
  printf '}' >> "$body"
fi

if [ $json = 1 ]; then
  request POST "/call/$tool" "$body" || die "can't reach DroidMCP at $url (is the app running?)"
  echo
  exit 0
fi

request POST "/call/$tool?format=text" "$body" > "$tmp/out" || die "can't reach DroidMCP at $url (is the app running?)"
status=$(head -n 1 "$tmp/out")
tail -n +2 "$tmp/out"
echo
case "$status" in
  ok) exit 0 ;;
  error) exit 1 ;;
  *) echo "droid: unexpected reply: $status" >&2; exit 2 ;;
esac
