# qits service-clients, on the command line: the idp's service clients, as a table and as JSON
# (qits-2531). The listing is the idp's golden master of a database service client.
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
Type "qits service-clients list"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits service-clients list -o json"
Enter
Wait /^\$$/
Show
Sleep 2s
