# The checkout tape's scene, sourced while hidden: a wrapper and its origins, side by side under
# /tmp/qits-tapes/checkout-sync/origins so every relative submodule URL (../<name>) resolves among
# them, the way the estate's do. The wrapper declares a service (which declares its webui, nested)
# and a library, every entry with ignore = all, update = merge and branch = main. A workstation
# clone of it is made the way the wrapper's CLAUDE.md says (init, then every submodule on its own
# main), and then the origins move on: the wrapper gains a commit and a new submodule, the service
# one commit, its webui two and the library one, while the clone's library has an edited tracked
# file. Fixed names and dates, so every commit has the same id on every play.
export GIT_AUTHOR_NAME=tapes GIT_AUTHOR_EMAIL=tapes@example.test GIT_AUTHOR_DATE=2026-01-01T09:00:00Z
export GIT_COMMITTER_NAME=tapes GIT_COMMITTER_EMAIL=tapes@example.test GIT_COMMITTER_DATE=2026-01-01T09:00:00Z
qits_scene=/tmp/qits-tapes/checkout-sync
qits_origins=$qits_scene/origins
rm -rf "$qits_scene"

qits_repository() {
  git init -q -b main "$qits_origins/$1"
}
qits_commit() {
  echo "$3" > "$qits_origins/$1/$2"
  git -C "$qits_origins/$1" add "$2"
  git -C "$qits_origins/$1" commit -qm "$2: $3"
}
qits_declare() {
  git -C "$qits_origins/$1" submodule add -q --name "$2" "../$2" "$3"
  git -C "$qits_origins/$1" config -f .gitmodules "submodule.$2.ignore" all
  git -C "$qits_origins/$1" config -f .gitmodules "submodule.$2.update" merge
  git -C "$qits_origins/$1" config -f .gitmodules "submodule.$2.branch" main
  git -C "$qits_origins/$1" add .gitmodules "$3"
  git -C "$qits_origins/$1" commit -qm "declare $2"
}

qits_repository qits-demo-webui && qits_commit qits-demo-webui index.html one
qits_repository qits-demo-service && qits_commit qits-demo-service Service.java one
qits_declare qits-demo-service qits-demo-webui service/src/main/webui
qits_repository qits-demo-javalib && qits_commit qits-demo-javalib Lib.java one
qits_repository qits-demo-extra && qits_commit qits-demo-extra Extra.java one
qits_repository qits-qits && qits_commit qits-qits README.md one
qits_declare qits-qits qits-demo-service components/qits-demo/qits-demo-service
qits_declare qits-qits qits-demo-javalib components/qits-demo/qits-demo-javalib

git clone -q "$qits_origins/qits-qits" "$qits_scene/qits-qits"
git -C "$qits_scene/qits-qits" submodule update -q --init --recursive
git -C "$qits_scene/qits-qits" submodule foreach -q --recursive 'git switch -q main'

qits_commit qits-qits README.md two
qits_declare qits-qits qits-demo-extra components/qits-demo-extra/qits-demo-extra
qits_commit qits-demo-service Service.java two
qits_commit qits-demo-webui index.html two
qits_commit qits-demo-webui index.html three
qits_commit qits-demo-javalib Lib.java two
echo "edited here" > "$qits_scene/qits-qits/components/qits-demo/qits-demo-javalib/Lib.java"

cd "$qits_scene/qits-qits"
unset -f qits_repository qits_commit qits_declare
unset qits_scene qits_origins
