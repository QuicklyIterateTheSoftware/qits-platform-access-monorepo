# qits toggles, in qits tui: the toggles group, its list, and the run's answer
# (qits-2704).

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
Type "/toggles "
Wait+Screen /(?s)\/toggles  ┐.*│ . toggles *▸/
Sleep 500ms
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ toggles ─/
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
Wait+Screen /┌ toggles ▸ list ─/
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
