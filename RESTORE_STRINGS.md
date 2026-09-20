# Restore single strings.xml

The GitHub write API used by the assistant cannot upload the full ~45KB `strings.xml` in one request.
Split files have been **removed**. Please run once from the repo root:

```bash
git checkout feature/remote-poll-task
git pull origin feature/remote-poll-task

curl -sL https://raw.githubusercontent.com/xjunz/AutoTask/master/app/src/main/res/values/strings.xml \
  -o app/src/main/res/values/strings.xml

rm -f app/src/main/res/values/strings_part*.xml

git add app/src/main/res/values/strings.xml
git add -u app/src/main/res/values/ 2>/dev/null || true
git commit -m "refactor: restore single upstream strings.xml"
git push origin feature/remote-poll-task
```

Keep `strings_remote_poll.xml` (remote poll feature strings).

After that you have one `strings.xml` like the original project.
