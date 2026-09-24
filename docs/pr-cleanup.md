# PR cleanup and dependency order

The foundation requires the owner-aware renderer pilot.4 and the checksum-pinned RoseChat presence contract. Upgrade Tags and the renderer together; older ownerless calls deliberately reject writes. No production deployment or release occurs in this cleanup.

Tags PRs are stacked: #1 foundation -> #2 WarzoneDuels -> #3 Commend -> #4 Express -> #5 Diary. The Diary branch also depends on renderer custom-node-icons (pilot.5), whose unresolved visual acceptance remains deferred.

The Commend provider evidence PR targets its pre-existing reputation/menu branch, not main. The DiaryKeeper evidence PR targets the user fork based on wsg138/DiaryKeeper. Provider changes must be present before enabling their consumers.

Run bootstrap_loreitems_release.sh, tools/ci/bootstrap_companions.sh, and mvn -B -ntp clean verify under Java 25. Run node --test tools/spear/review.test.mjs and node tools/spear/ears.mjs docs/requirements.md separately. Do not equate unit tests or CodeRabbit statuses with live acceptance or Codacy approval.

Hosted compiler correction: the first fresh runner reached testCompile but its in-process compiler crashed with an internal "this.hashes" null error. The POM now explicitly uses forked javac, matching every successful local verification command; no tests or compiler diagnostics are suppressed. The next exact-head run must establish hosted success.
