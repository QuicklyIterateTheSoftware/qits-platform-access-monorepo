# qits deployments, on the screen: `qits tui`, down to deployments > settings > list, --application
# typed into its field, run with Ctrl+R, and quit. The output box shows the table the CLI tape
# shows.
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
Type "/deployments "
Wait+Screen /(?s)\/deployments  ┐.*│ . deployments +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ deployments ─/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/settings "
Wait+Screen /(?s)\/settings  ┐.*│ . settings +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ deployments ▸ settings ─/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/list "
Wait+Screen /(?s)\/list  ┐.*│ . list +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ deployments ▸ settings ▸ list ─/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/--application "
Wait+Screen /(?s)\/--application  ┐.*--application +▸/
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
Type "qits-ci"
Enter
Wait+Screen /--application\s+qits-ci/
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
Wait+Screen /exit 0/
Sleep 500ms
Show
Sleep 2s

Hide
Type "q"
Wait /^\$$/
Sleep 500ms
Show
Sleep 500ms
