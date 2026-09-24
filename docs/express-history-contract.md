# Express return-history attribution

Reviewed provider source: [MailRepository.kt at 7277770](https://github.com/FainNeito/Enthusia-Express/blob/7277770802068175a859fb063606ff5556ecb9d9/src/main/kotlin/io/enthusia/express/infrastructure/db/MailRepository.kt), specifically expire, updateClaim, and confirmDelivery.

The return lifecycle rewrites recipient_uuid to COALESCE(sender_uuid, recipient_uuid) before setting RETURNED. A sender-less expired package is purged rather than returned. The subsequent claim and delivery confirmation are authorized by this current recipient UUID. Therefore recipient_uuid on a normally returned package is the original sender, not the original intended recipient.

The advancement reader groups claims by the current mailbox owner. It continues to reject pending claims (delivery_pending=1), and supports the older schema without that field. Grouping RETURN_CLAIMED by recipient_uuid already credits the original sender under the inspected provider contract; no behavioral attribution change is required.

Regression fixtures begin with different sender and recipient UUIDs, reproduce the relevant expiration/claim/acknowledgment transitions, and assert no credit before delivery, credit only to the original sender after acknowledgment, and no database changes from the reader. A second fixture covers the legacy schema. These are consumer contract fixtures, not a claim that a live server or the entire provider was exercised.
