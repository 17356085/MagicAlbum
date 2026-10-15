# Notification outbox

Remote notification delivery is queued in the core database while the primary
post, like, or follow transaction is still open. The scheduled dispatcher
claims rows with a short conditional lease transaction, performs HTTP outside
that transaction, and completes the row only when the lease owner still
matches.

`app.notifications.outbox.lease-duration` defaults to 30 seconds, which is
longer than the 2 second connect and 5 second read HTTP limits. Delivery is
at-least-once; the UUID `eventId` is sent to notification-service so a lost
HTTP acknowledgement is safe to retry. `PENDING`, `IN_FLIGHT`, `DELIVERED`,
`FAILED`, and `SUPPRESSED` are durable states. Only delivered and suppressed
rows are eligible for retention cleanup; failed rows remain visible and can be
manually retried by an authenticated admin.
