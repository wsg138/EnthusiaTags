# KOTH rewards navigation

Tags integrates the owner-bound KothRewardsMenuV1 through its existing rewards infrastructure. The production framed dashboard includes KOTH as an additional paginated category when the provider plugin is installed, with a server-side KOTH action rather than a client metadata claim. `/rewards koth [challenges|claims|results]` resolves that same provider. No KOTH reward definitions, storage or grant logic are added to Tags.

The enabled owner and exact ServicesManager registration are checked afresh on each request. Missing, disabled or incompatible providers fail clearly. The payload is only the caller UUID and an allowlisted view. Fatal VM errors are not swallowed. Normal dashboard navigation uses the existing current-holder, online-session and permission guards.

Integrated source: corrected production/Holidays browser 136b14a plus prior optional adapter a94be80; conflicting old bare-menu code was replaced by the production browser action contract. KothRewardsHookTest proves disabled/missing/replaced-owner cases; HolidayMenuTest proves paginated KOTH action inclusion without a claim. KothMenuArtifactProbe passes against the actual newly built KOTH shadow JAR and Tags JAR. This is artifact/API proof, not installed runtime or client acceptance.
