# qits observe, on the command line: the live telemetry stream of one service, over a WebSocket,
# stopped with Ctrl+C; the same filter over a window of the past (`observe query`, two instants,
# so it does not move with the clock), as lines and as JSON; the command refusing to stream
# without a filter (exit 2); and the stored error issues (epic qits-2538): the list, one issue in
# full with the ticket made from it, and a dismissal the agent's token is refused (exit 1).
#
# The stream's notes (connected, stopped) go to stderr with a wall-clock stamp in front, which no
# two plays share, so the tape sends them to a file and shows them after the stop without the
# stamp. The records are qits-observability's golden masters, and the stream adds a fixture's
# (src/test/resources/tapes/observe-stream.json). The error issues and their ticket are fixtures
# too (error-issues.json, error-issue.json, error-issue-tickets.json), copies of the recordings
# until the pinned golden masters carry them.
#
# Against the fake platform tapes/prepare.sh starts (FakePlatformMain). Each command is typed while
# hidden and shown once its prompt is back, so every frame of the text golden is a finished screen
# and the golden is the same on every play.
Set Shell bash
Set FontFamily "DejaVu Sans Mono"
Set FontSize 14
Set Width 930
Set Height 504
Set Padding 0
Set TypingSpeed 10ms
Set WaitTimeout 60s
Env PROMPT_COMMAND "sleep 2"
Env TZ "UTC"
Env LANG "C.UTF-8"
Env QITS_TOKEN "qits-tapes-not-a-token"

# The shell, with a fixed prompt. bash's first prompt waits for PROMPT_COMMAND's sleep, so the
# frames vhs takes while it applies the settings above are all of an empty screen.
Hide
Type "unset PROMPT_COMMAND; export PS1='$ '; clear"
Enter
Wait /^\$$/

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe --filter service=qits-example 2> /tmp/qits-tapes/observe.log"
Enter
Wait+Screen /00:00:06\.000/
Show
Sleep 2s

Hide
Ctrl+C
Wait /\$$/
Show
Sleep 2s

Hide
Type "cut -d' ' -f2- /tmp/qits-tapes/observe.log"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe query --filter service=qits-example --since 2026-01-01T00:00:00Z --until 2026-01-01T01:00:00Z"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe query --filter service=qits-example --since 2026-01-01T00:00:00Z --until 2026-01-01T01:00:00Z -o json"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe issues list"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe issues show 0123456789abcdef0123456789abcdef --project contract-00000001"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits observe issues dismiss 0123456789abcdef0123456789abcdef"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s
