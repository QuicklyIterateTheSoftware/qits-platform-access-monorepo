# qits wait, on the command line: the waits the calling agent declared (QITS_AGENT_ID, which
# tapes/prepare.sh sets), as a table and as JSON, and a declaration the platform refuses - a wait on
# a CI run (403, exit 1). The answers are qits-workspaces' golden masters.
# Last, the --work option a project's long-running agent names its work item with (epic qits-2520).
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
Type "qits wait list"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits wait list -o json"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits wait ci-run 00000000-0000-4000-8000-000000000004"
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
Type "qits wait --help | grep -A 3 -- '--work'"
Enter
Wait /^\$$/
Show
Sleep 2s
