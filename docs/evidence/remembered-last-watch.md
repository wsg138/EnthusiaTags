# Remembered reward — SPEAR candidate

REQ-REM-001: WHEN Holidays' durable 2026 eligibility has reached its original event end and Tags has loaded the matching account state THE SYSTEM SHALL allow a one-time `/rewards` claim for the muted silver-gray Remembered tag. The event-specific counter is reconciled by Holidays; absent or unavailable state remains locked.

REQ-REM-002: The Remembered reward and tag SHALL remain hidden until the reward's durable eligibility is unlocked or claimed. The public Last Watch advancement remains independent. Absent, malformed, or disabled privacy flags on these reserved identifiers SHALL NOT reveal them; a mismatched tag reference SHALL fail closed. Existing unrelated Tags policies remain unchanged.

Candidate stacks on generic private-reward draft #19. Runtime compatibility, event-end cross-plugin retry, migration, client UI, and native acceptance remain unverified. No TEST or production activity.

Local proof: initial focused tests failed for missing reserved-ID protections; the implementation forces the specific reward private, fails closed for mismatched tag references or altered eligibility/action definitions, and adds missing reward configuration without overwriting an existing entry. Full Maven verify passed 318 tests; EARS validator and 12 project tooling tests passed. Existing migration and layout tests were updated for the added reward. The matching Holidays bridge retries while Tags loads and restores after loss of an unflushed counter. A separate earned signal now reveals the reward after Holidays reconciliation; the claim signal is not set until the recorded event end. Native UI, cross-plugin ABI, delivery and event-end behavior remain unverified.

The local HTML preview is a simulation only and is not a mobile-accessible hosted link. The generic privacy draft remains a dependency; its general pairing failure paths remain a separate release gate.

Additional gate check: the real bundled rewards.yml was loaded through RewardService with a mocked tag registry; the exact Remembered entry loaded private, and an altered placeholder source caused the entry to be omitted. This is a local configuration-level check, not proof of client packets or the deployed runtime. The two public drafts' hosted verification and Codacy checks passed on their previously reviewed heads.

Startup review found reward definitions were validated before the bundled holiday catalog was installed. The plugin now installs the holiday and portable catalogs before loading the tag registry and validating rewards. A local first-install regression loads the real Remembered tag into a real registry before loading bundled reward configuration and verifies the action is valid; no server startup was exercised.

After the startup-order fix, full Maven verify passed 320 tests. The unmerged local Tags build SHA-256 is 8cf795b77e53a1aca125abd32f63fc76dbf78fb2a2caf7328408e305ab0f0566; its public reward-service method descriptors match the Holidays reflection calls. This local artifact is not identified as the deployed runtime.

REQ-REM-003: WHEN Holidays has durably recorded the natural Last Watch encounter and reconciles an account's separate earned signal THE SYSTEM SHALL reveal Remembered to that account immediately, including before the event ends. The claim criterion remains a different signal, issued only at or after the stored event end. Missing, unloaded, or unearned state SHALL remain hidden. Reconciliation of the authoritative ledger must repair a lost unflushed visibility signal after restart.

The visibility boundary regression failed before implementation and now passes: an account with the stored earned signal can see Remembered while its separate post-event criterion remains locked. The signal is reconciled from Holidays' durable ledger; like other Tags custom counters, it is writable by trusted server plugins and privileged administrative tools. Full local Maven verify passed 321 tests. The local unmerged Tags JAR SHA-256 is fb94cf796f21139b06bdc9110d36fcf0dc2c620039f527097cb4e2734dc565c3. Native UI, actual server runtime and end-to-end claim behavior remain unverified.
