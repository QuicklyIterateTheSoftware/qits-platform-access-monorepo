# qits checkout sync, on the command line: a workstation clone of a wrapper brought up to date. The
# scene (tapes/scenes/checkout-sync.sh) is a wrapper with a service (which has a nested webui) and a
# library, and a clone of it with every submodule on its own main; the origins have moved on since,
# and the wrapper declares a new submodule. The first sync fast-forwards the wrapper, the service and
# its webui, initialises the new submodule on main, and skips the library, whose tracked file is
# edited here (exit 1). With the edit undone the second sync moves the library too (exit 0). Last,
# the same clone with its root detached at a release tag - a project agent container's checkout -
# is refused, nothing touched (exit 2).
#
# Pure git: the command reads no token and calls no platform, so the fake platform tapes/prepare.sh
# starts (FakePlatformMain) is not asked anything. Each command is typed while hidden and shown
# once its prompt is back, so every frame of the text golden is a finished screen and the golden is
# the same on every play. No -tui tape: the command is LOCAL, and TapesCoverageTest lists it as
# CLI-only.
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
Type "source tapes/scenes/checkout-sync.sh"
Enter
Wait /^\$$/

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits checkout sync"
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
Type "git -C components/qits-demo/qits-demo-javalib restore Lib.java"
Enter
Wait /^\$$/
Type "qits checkout sync"
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
Type "git tag 2026.101.90000 && git switch -q --detach 2026.101.90000"
Enter
Wait /^\$$/
Type "qits checkout sync"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s
