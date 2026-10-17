# Staging acceptance

Use a separate shared PostgreSQL or MariaDB database and two Paper/Purpur 26.1.2 backends with the production plugin set. Back up both worlds/playerdata and database before migration.

1. Set the correct database type/port and verified TLS, then configure unique server IDs, identical group/version/field settings, authenticated UUID forwarding, and shared storage. Confirm `ready=true` on both backends.
2. Verify first-join import or empty policy with a test UUID. Fill every inventory category, cursor, armour/offhand and ender chest. Include enchantments, custom names/lore, damage, PDC, custom-model data, books and shulkers containing items. Set XP and selected slot.
3. Switch backends repeatedly, including immediately after changing items and while a GUI/cursor is occupied. Verify exact amounts/metadata, no stale local inventory, and one active database owner. Test groups that must remain isolated.
4. Exercise enabled health/hunger and disabled fields. Test death/respawn, keepInventory settings, disconnect during login, full inventory/cursor overflow, and interactions with your kit, invsee, trading and world plugins.
5. Attempt a concurrent login and mutations while loading: drops/pickups, crafting/creative clicks, swaps, containers, commands, damage, projectiles and XP. Verify the second owner is refused and blocked sessions cannot move items.
6. Interrupt the selected database, fill the worker queue, stop a backend during save, then abruptly terminate a process. Verify gameplay freezes/kicks, locks do not expire automatically, and failures preserve recovery files where available. Inspect before choosing recovery; inventory snapshots cannot roll back external world/trade effects.
7. Stop the owning backend. Test stale-time and revision-confirmed recovery, matching journal replay, and offline history restore. Wrong token/revision, active/fresh ownership and changed snapshots must be refused. Check history retention and checksum rejection.
8. Test graceful shutdown/restart with online players and database latency. Monitor main-thread capture time, database latency, disk fsync and queue depth at expected load. Tune batch/interval/pool/queue settings without weakening the ownership rules.

For MariaDB, verify InnoDB, group-name case sensitivity, packet limits with your largest inventories, and that both backends use the same writable endpoint. Test a CA/hostname mismatch and confirm initialization blocks logins. For an existing PostgreSQL setup, upgrade without adding `database.type` and confirm the same snapshot/revision is retained. A database-engine switch is an offline migration, never a live config toggle.

Automated tests cover the transaction/codec/lifecycle rules and real Paper's item implementation. Actual client transfers and your other gameplay plugins require this separate acceptance step.
