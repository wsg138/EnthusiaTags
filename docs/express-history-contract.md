# Express return-history attribution

Reviewed provider source: [MailRepository.kt at 7277770](https://github.com/FainNeito/Enthusia-Express/blob/7277770802068175a859fb063606ff5556ecb9d9/src/main/kotlin/io/enthusia/express/infrastructure/db/MailRepository.kt), specifically expire, updateClaim, and confirmDelivery.

The return lifecycle rewrites recipient_uuid to COALESCE(sender_uuid, recipient_uuid) before setting RETURNED. A sender-less expired package is purged rather than returned. The subsequent claim and delivery confirmation are authorized by this current recipient UUID. Therefore recipient_uuid on a normally returned package is the original sender, not the original intended recipient.

The advancement reader groups claims by the current mailbox owner. It continues to reject pending claims (delivery_pending=1), and supports the older schema without that field. Grouping RETURN_CLAIMED by recipient_uuid credits the original sender under the inspected provider contract. The reader additionally requires sender and current recipient to match, and represents confirmed recovery as a boolean milestone.

Regression fixtures begin with different sender and recipient UUIDs, reproduce the relevant expiration/claim/acknowledgment transitions, and assert no credit before delivery, credit only to the original sender after acknowledgment, and no database changes from the reader. A second fixture covers the legacy schema. These are consumer contract fixtures, not a claim that a live server or the entire provider was exercised.

## Distinct-correspondent recovery

Normal package and letter milestones count distinct other UUIDs separately and case-insensitively, rather than database row totals. Self-mail and senderless mail do not earn these milestones; returned packages are excluded from normal sender progress. Claimed packages require delivery_pending=0 when the column exists, and letters require unread=0 for read progress. Existing stable keys and numerical thresholds remain; descriptions state the different-player requirement.

Because expiration rewrites recipient_uuid, returned rows no longer reliably identify their original intended recipient. The reader does not invent that missing historical attribution. A history snapshot can therefore have lower normal sending progress after a return; current session projection remains monotonic, reconnect/history observations remain silent, and previously awarded reward ownership is not reset. The legacy schema still supports distinct identities but cannot prove an acknowledgment field it never recorded. Consumer fixtures cover this boundary; actual provider/client acceptance is pending.
