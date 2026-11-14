# Remembered integration review

The private-reward and Remembered candidates are integrated in one branch based on the current fork main. The original private-reward PR's exact-head Codacy gate reported a nine-argument constructor over the configured limit. The public seven- and eight-argument constructors retain their existing signatures and default public behavior; private visibility is now applied by an immutable copy operation. The unmerged nine-argument test-only API has not been released from canonical main. Tests exercise the same private visibility, projection, claim and Remembered boundaries.

Java 25 offline Maven verify passed 321 tests and the shaded SQLite check. The project's EARS validator was invoked. The initial issue was a reported static-analysis gate, not a behavioral red test. Native client privacy, producer ABI, restart and post-event claim remain separate acceptance gates.
