# qits work, on the command line: a project's work items of every archetype and status, as a table
# and as JSON, and a write the platform refuses - blocking an item, which a qits:agent token may
# not do (403, exit 1). The answers are qits-projects' golden masters; --project is the slug, as
# the screen's dropdown fills it in.
# Last, what a project's long-running agent could take next, in the backlog order (epic qits-2520).
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
Type "qits work list --project contract-00000001"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits work list --project contract-00000001 -o json"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits work --entity contract-00000001-1 block --reason 'Waiting on the DB team.'"
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
Type "qits work list --project contract-00000001 --unassigned --by-priority --limit 3"
Enter
Wait /^\$$/
Show
Sleep 2s
