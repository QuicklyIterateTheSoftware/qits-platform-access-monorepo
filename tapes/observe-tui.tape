# qits observe, on the screen: `qits tui`, down to observe, --filter typed into its field, run
# with Ctrl+R - a stream, so the box says `streaming` and fills with the records - and then stopped
# and quit.
#
# The stop is not shown. The stream's first and last notes (connected, stopped) carry
# a wall-clock stamp, and the box shows stdout and stderr as one: sixteen records push the first one
# out of the box before the screen is shown, but Ctrl+C would print the second into it. The CLI
# tape shows both notes, without their stamps.
#
# Against the fake platform tapes/prepare.sh starts (FakePlatformMain). Every key is pressed while
# hidden, the tape waits for what the key paints, then lets the screen settle for 500ms while still
# hidden before Show: a Wait+Screen match proves only the row it matched, and vhs writes a text
# frame the instant Show runs (qits-2221).
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
Type "qits tui"
Enter
Wait+Screen /⌃R run · q quit/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/observe "
Wait+Screen /(?s)\/observe  ┐.*│ . observe +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ observe ─/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/--filter "
Down 1
Wait+Screen /(?s)\/--filter  ┐.*--filter +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /type the value/
Sleep 500ms
Show
Sleep 1s

Hide
Type "service=qits-example"
Enter
Wait+Screen /--filter\s+service=qits-example/
Sleep 500ms
Show
Sleep 1s

Hide
Escape
Wait+Screen /\/ search/
Sleep 500ms
Show
Sleep 1s

Hide
Ctrl+R
Wait+Screen /00:00:06\.000/
Sleep 500ms
Show
Sleep 2s

Hide
Ctrl+C
Sleep 1s
Type "q"
Wait /^\$$/
Sleep 500ms
Show
Sleep 500ms
