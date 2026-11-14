# Holiday renderer release integration

Spec: REQ-REL-001 / T-20261017. Fetched canonical wsg138 main 28048ca and owner fork main 302dc8d; the parent is an ancestor of the fork and all prior holiday/menu work is retained. This branch starts at the current owner fork to preserve the already-reviewed canonical PR29 contents.

Prove: the network's existing script requires exact consumer/provider versions. Tags pinned pilot.6-chat-colors-test.4 at af9cd77 while merged canonical Advancements 01fac426152737c76cd4d2e570e07a33dde99798 supplies pilot.6-watcher-brackets-test.6. The new bootstrap regression failed with Unexpected renderer version after installing the hash-locked existing defusedxml dependency. The initial missing Python dependency is environment evidence, not the behavioral red result.

Engine/architecture: update the immutable renderer SHA and Maven version together. Extend the existing allowlist only for the explicit watcher-brackets-test numeric suffix family; do not accept arbitrary metadata, URLs or executable text. Existing SHA/POM equality and bounded XML/file validation remain in force. Domain, rewards, GUI layout, persistence and permissions are unchanged.

Refine: record EARS/tooling/bootstrap and clean Maven results after running them. The actual merged provider JAR must replace standalone fixture assumptions during combined verification. Canonical parent merge permission remains with its maintainer; fork merging does not establish canonical release status or authorize production deployment.

Local refinement: 278 Java tests passed with zero failures/errors/skips against the actual merged pilot installed from its clean canonical checkout. All 11 bootstrap tests, 12 Node state/tooling tests and EARS validation pass. Renderer SHA/version/POM and checksum-pinned RoseChat presence contract checks pass. No menu or reward code changed. Generated dependency-reduced-pom.xml is restored to the committed template before source delivery.
