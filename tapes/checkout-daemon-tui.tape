# qits checkout-daemon, on the screen: `qits tui`, down to checkout-daemon, --path typed into its
# field and --once switched on, run with Ctrl+R, and quit.
#
# The nearest honest equivalent of the CLI tape, not the same run: the path is a Git repository
# with no origin, which the daemon refuses before it touches anything (exit 1). A run that holds
# a checkout prints its notes with a wall-clock stamp, and the box shows stderr with stdout, so
# no two plays of it would show the same screen; the CLI tape shows that run, with the notes
# unstamped.
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
Type "mkdir -p /tmp/qits-tapes/plain && git init -q /tmp/qits-tapes/plain"
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
Type "/checkout-daemon "
Wait+Screen /(?s)\/checkout-daemon  ┐.*│ . checkout-daemon +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ checkout-daemon ─/
Sleep 500ms
Show
Sleep 1s

Hide
Type "/--path "
Wait+Screen /(?s)\/--path  ┐.*--path +▸/
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
Type "/tmp/qits-tapes/plain"
Enter
Wait+Screen /--path\s+\/tmp\/qits-tapes\/plain/
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
Type "/--once "
Wait+Screen /(?s)\/--once  ┐.*--once +▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /plain --once/
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
Wait+Screen /exit 1/
Sleep 500ms
Show
Sleep 2s

Hide
Type "q"
Wait /^\$$/
Sleep 500ms
Show
Sleep 500ms
