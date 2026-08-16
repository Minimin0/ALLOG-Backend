# Group participation Heart integration (M3-C)

## Scope

M3-C charges the ratified MVP participation cost of **one Heart** when a creator gets an owner membership or when a user joins a recruiting group. The cost is code policy in `GroupParticipationHeartPolicy`; it is not a client input or a mutable runtime configuration value.

## Ledger source and transaction boundary

| Event | Ledger source | Atomic boundary |
|---|---|---|
| Create group | Persisted owner `GroupMember.id` | Group, owner member, schedule and Heart spend share one transaction |
| Join group | Persisted joining `GroupMember.id` | Group lock, member insert, Heart spend and possible activation share one transaction |
| Leave, cancel or expiry before start | Original `GroupMember.id` | Membership status transition and original debit refund share one transaction |

`GroupMember.id` is the source for both spend and refund because it is immutable, unique to one participant in one group, and already survives lifecycle status changes. The Heart ledger unique constraint remains the idempotency authority; lifecycle retries do not calculate a new amount or create a second refund.

The group row is locked before membership work, and the existing `HeartAccountService` is invoked inside that transaction. Therefore an insufficient balance rolls back the new membership and any last-slot activation, while a failed refund rolls back the corresponding lifecycle state transition rather than leaving a partially settled group.

## Legacy compatibility

M3-C intentionally does not invent a refund amount for legacy memberships that lack an original `GROUP_JOIN_SPEND` ledger row. Such a missing debit is treated as an invariant failure by the existing Heart service, not as a free refund or a silently skipped lifecycle change. No migration or unrelated historical Heart data is changed in this milestone.
