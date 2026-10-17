# Network Inventory Sync

Part of the [Planetry Minecraft network](https://github.com/FlintSH/planetry). [Open an issue](https://github.com/FlintSH/network-inventory-sync/issues/new/choose) to report a problem, request a feature or ask for help. The Planetry development agent replies in the issue and can publish tested fixes automatically. Reply there to continue the conversation; no T3 account is needed.

[![Test and release](https://github.com/FlintSH/network-inventory-sync/actions/workflows/release.yml/badge.svg)](https://github.com/FlintSH/network-inventory-sync/actions/workflows/release.yml)

A backend inventory-sync plugin for **Paper/Purpur 26.1.2**, **Java 25**, and shared **PostgreSQL 14+ or MariaDB 10.11+** storage. Install it on participating Minecraft servers, not Velocity. No proxy plugin, Redis, or player client mod is required.

It synchronizes inventory storage/hotbar, armour, offhand, ender chest, XP, and selected slot, with optional health and hunger. Each field is independently configurable. Named groups isolate clusters such as survival and creative. Item data uses Paper's native NBT byte serialization, including custom item metadata, PDC, enchantments, and nested containers.

The main design choice is conservative recovery: **a crashed server's lock never expires into an automatic takeover**. Otherwise an older inventory snapshot could silently restore items the player already dropped or traded. Staff must verify the owning server is stopped, inspect the record, and explicitly recover or replay it.

## Install

1. Provision a shared PostgreSQL or MariaDB database and dedicated database user. All servers in a sync group must connect to the same database and table prefix. The role needs connection, table creation, select/insert/update/delete permissions in its schema/database; it does not need superuser privileges. Take database/world backups before your initial migration.
2. Download the shaded `network-inventory-sync-*.jar` from [Releases](https://github.com/FlintSH/network-inventory-sync/releases) into every participating backend's `plugins/` directory.
3. Start once to generate `plugins/NetworkInventorySync/config.yml`. The default `server-id: change-me` intentionally **blocks new logins** until configured. Stop the server, edit the file, and restart.
4. Give each backend a unique `server-id`. Use the same `group`, Minecraft version, database prefix, and `sync` field settings throughout a cluster. Use distinct groups for inventories that must remain separate.
5. Set `database.type` to `postgresql` (default) or `mariadb`, the correct port (5432/3306), database host/name/user/password, and TLS options. Prefer `password-env` to a password in the file. Use `ssl-mode: verify-full` and a trusted `ssl-root-cert` for hostname/certificate verification; `require` encrypts without verifying the hostname. `disable` is for an isolated local development database.
6. Confirm `/invsync status` reports `ready=true` on every participating backend, then perform the [staging acceptance checks](docs/ACCEPTANCE.md).

A local development database example is included in [compose.example.yml](compose.example.yml). It offers PostgreSQL and MariaDB profiles, binds each only to loopback, and stores data in separate persistent volumes. Set `INVSYNC_DB_PASSWORD` before starting it. The example has no TLS, so use `ssl-mode: disable` only for this loopback development setup. For servers on different hosts, provision a reachable shared database with appropriate network access and TLS.

Minimal per-server configuration (missing keys inherit bundled defaults):

```yaml
server-id: survival-1       # survival-2 on the other backend
group: survival
database:
  type: postgresql
  host: database.internal
  port: 5432
  name: invsync
  username: invsync
  password-env: INVSYNC_DB_PASSWORD
  ssl-mode: verify-full
  ssl-root-cert: /etc/invsync/postgres-ca.crt
```


For MariaDB, use the same configuration with these database settings:

```yaml
database:
  type: mariadb
  host: database.internal
  port: 3306
  name: invsync
  username: invsync
  password-env: INVSYNC_DB_PASSWORD
  ssl-mode: verify-full
  ssl-root-cert: /etc/invsync/mariadb-ca.pem
```

Both engines bundle their JDBC driver; no additional JAR is needed. `ssl-mode` has the same meaning on both: `disable`, `require` (encryption without server identity verification), `verify-ca`, or `verify-full`. For MariaDB, the plugin translates `require` into Connector/J's `trust` mode and passes `ssl-root-cert` as `serverSslCert`. MariaDB can also use the JVM truststore when no certificate file is supplied. See the [official Connector/J TLS documentation](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector).

MariaDB tables use **InnoDB**, `LONGBLOB` payloads, and case-sensitive ASCII identifiers so groups such as `Survival` and `survival` remain separate. Startup rejects existing non-InnoDB or incompatible group-collation tables. Set MariaDB `max_allowed_packet` to at least **64 MiB** if allowing large payloads (the plugin permits up to 16 MiB). Use a single authoritative writable database endpoint, with durable storage and `innodb_flush_log_at_trx_commit=1`; load-balanced replicas, asynchronous failover, Galera multi-writer clusters, and MySQL are not validated configurations.

Existing PostgreSQL installations keep working without adding `database.type`; existing tables, profiles and journals are unchanged. **Changing the type does not migrate data.** To change database engines, take all participating backends offline, back up the database and recovery files, and arrange a verified migration preserving snapshots, revisions, groups and ownership before reconnecting. Pointing at an empty database starts first-join imports and can select stale local playerdata. Do not split a sync group across separate databases.

Forward authenticated player UUIDs consistently on all backends. With Velocity, configure secure modern forwarding and prevent direct unauthorized backend access. A different UUID is a different inventory owner; untrusted UUID spoofing would expose another player's data.

## What happens during a transfer

1. Asynchronous pre-login reserves the `(group, UUID)` record under a database row lock. An unguessable per-session owner token fences every later write. Competing logins wait briefly for a clean handoff, then are refused if the row is still owned.
2. Before gameplay is allowed, the server decodes and validates the full snapshot and native item NBT on the server thread. Players are frozen while loading and until the initial snapshot is committed. A player joining on a death screen stays frozen until respawn, then receives the database inventory; ordinary deaths after activation never replay an older autosave. If the player has no database record yet, `sync.first-join: import` seeds it from the first participating backend they join. `empty` instead starts enabled fields empty/default. This is a first-writer import, not a merge of historical inventories from several servers.
3. During play, asynchronous database heartbeats verify ownership. Periodic snapshots run in configurable batches of main-thread captures, then write off-thread. Ordinary inventory interactions, commands, movement, item pickup/drop/use, XP pickup, damage, and other guarded actions are blocked while a session is not ready or its last verification is too old.
4. On disconnect, the current inventory is captured after closing the GUI and returning carried cursor items to storage (overflow drops normally). Saving the snapshot, appending history, pruning old history, and releasing ownership happen in **one transaction**. The next backend cannot load the player until that transaction commits.
5. Every save uses both owner token and expected revision. A stale server cannot overwrite a later owner's data. Interrupted, fenced, failed, or overloaded operations freeze/kick the session instead of falling back to local playerdata.

No database, network, or filesystem work occurs in normal gameplay event handlers; native inventory/NBT capture and apply must run on the Minecraft thread. Asynchronous pre-login may wait within its configured limits. Shutdown drains final saves for a bounded period. Disk writes are forced before committing database writes, and unsuccessful saves retain recovery files.

## Configuration

See the [annotated defaults](src/main/resources/config.yml). Unknown keys, invalid booleans/ranges, weak identifiers, incompatible group profiles, and the placeholder server ID are rejected. Configuration is immutable until restart; there is deliberately no live reload that could change inventory ownership rules mid-session.

| Area | Controls |
| --- | --- |
| `sync` | Storage, armour, offhand, ender chest, XP, selected slot, health, hunger, first-join policy |
| `group` | Separate inventory namespace per backend cluster |
| `database` | Engine, host/port/database, credentials or environment variable, TLS/CA, table prefix, pool size, connection/query timeouts |
| `safety` | Handoff wait/poll, incomplete-login cleanup, heartbeat/verification window, recovery staleness, payload limit, history retention, shutdown drain |
| `performance` | Bounded I/O worker count and queue capacity, autosave interval, captures per tick |
| `messages` | MiniMessage text for loading, busy, failure, version mismatch, shutdown and permissions |

Group metadata stores the exact Minecraft version and a fingerprint of the snapshot schema, enabled fields and first-join policy. Servers with incompatible profiles refuse synchronization. Use **the same 26.1.2 version and item definitions/plugins/datapacks** within each group. Item downgrades and cross-version conversion are not attempted. To change an established group's field profile, plan an offline data migration; simply toggling it on one server is intentionally rejected. Different groups may use different field settings.

By default, storage/hotbar, armour, offhand, ender chest, experience and selected slot sync. Health and hunger are disabled by default. Disabled fields remain local. Health is clamped to the recipient's maximum and applied only while alive; death/respawn and drops still follow the backend's normal rules. Location, worlds, gamemode, potion effects, economy, permissions, advancements, entities, and open container contents are not synchronized. Do not put creative and survival servers in one group unless sharing creative items is intentional.

Autosaves default to 30 seconds, with up to four captures per tick, eight pooled connections, four I/O workers, and a bounded 1,024-operation worker queue. Heartbeats are five seconds; the verification window is 20 seconds. These are tunable starting values, not a throughput guarantee. Tune against your player count, item sizes, storage and database latency. Snapshot payloads default to at most 4 MiB and 20 history entries per player.

## Administration and recovery

`invsync.admin` defaults to operators. Status/inspect/history are read-only. **Recovery, replay and restore are console-only** and use UUIDs; there is no blocking external username lookup.

| Command | Behavior |
| --- | --- |
| `/invsync status` | Storage readiness, backend/group identity, session and I/O queue count |
| `/invsync inspect <uuid>` | Revision, owner backend/token, seconds since heartbeat, checksum |
| `/invsync history <uuid>` | Retained revisions, checksums and timestamps |
| `invsync recover <uuid> <current-revision>` | Release a sufficiently stale orphan lock; retain the latest committed snapshot |
| `invsync replay <uuid> <owner-token>` | Commit a local recovery file with matching owner/revision and sufficiently stale ownership, then release the lock |
| `invsync restore <uuid> <history-revision> <current-revision>` | Restore history as a new revision, only while the database record is unlocked |

**Always stop the owning backend and verify the player is offline before mutating recovery state.** Never use recovery to take over a live paused or network-partitioned server. The default staleness requirement is 120 seconds. It is a recovery prerequisite, not a lock TTL: waiting alone never transfers ownership.

For a failed final save, prefer replaying its local recovery file before releasing the lock. Files live on the owning backend at `plugins/NetworkInventorySync/recovery/<group>/<uuid>.<token>.bin`. Replay checks identity, payload checksum, schema/version/profile, original owner token, expected revision and stale heartbeat. An obsolete file cannot overwrite a changed owner/revision. Files are not blindly replayed at startup. Keep them private, and do not delete unresolved files until you have inspected their contents/state and chosen the intended recovery.

If the process crashed without a final file, `recover` allows the last committed snapshot to load. That can lose progress since the last snapshot or restore items already moved outside the inventory. A database inventory snapshot cannot transact atomically with the Minecraft world, chests, drops, trades, or another plugin's database. Coordinate world/database backups and investigate those side effects before choosing an older snapshot. The plugin deliberately does **not** claim crash-proof item duplication prevention.

History restore also requires an explicit current-revision confirmation, so a stale administration command cannot overwrite a newer record. If an operation reports an error after a connection failure, inspect ownership/revision before retrying: a database commit may have succeeded even if the response was lost. If the record is already unlocked at the confirmed revision, `recover` acknowledges it and clears a local failed-session marker without changing the snapshot.

## Compatibility and limits

- Targets Paper/Purpur **26.1.2**, Java 25, PostgreSQL 14+ or MariaDB 10.11+. Real database tests use PostgreSQL 17 and MariaDB 10.11; the runtime probe uses Paper 26.1.2 build 74. Linux with atomic rename and directory fsync is tested. Other OS/filesystem combinations are not claimed; journal durability failure blocks saves.
- Not a Velocity, Spigot, Fabric, or Folia plugin. A version/configuration/database failure leaves the login gate closed. A Paper API-version load rejection must be fixed by installing the matching server version; an unloaded plugin cannot guard the server.
- Use one inventory authority. Other inventory-sync, per-world inventory, starter-kit, invsee/edit, death-item, or plugins that mutate inventories directly can conflict. Players must be offline during recovery and schema/profile migrations. Do not bypass the freeze or modify loading inventories from another plugin.
- No permission bypass, offline inventory editor, automatic stale-owner takeover, live cross-version conversion, or automatic conflict merging. These can defeat the handoff invariants.
- Restart normally for upgrades; do not use plugin hot-reload tools. Disabling while players are present kicks managed sessions and attempts bounded final saves. A failed save keeps ownership held. Other plugins cannot be prevented from overriding cancellation or editing state directly.

## Build, tests and releases

```sh
mvn -B -ntp clean verify
```

The shaded deployable JAR is `target/network-inventory-sync-*.jar`; do not install `original-*.jar`. Unit tests run without a server. Real database integration tests are enabled by `INVSYNC_TEST_DATABASE`. Select `INVSYNC_TEST_TYPE=postgresql` (default) or `mariadb`, with optional `INVSYNC_TEST_HOST`, `INVSYNC_TEST_PORT`, `INVSYNC_TEST_USER` and `INVSYNC_TEST_PASSWORD` (default local test port is 55432).

```sh
INVSYNC_TEST_DATABASE=invsync mvn -B -ntp clean verify dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
INVSYNC_TEST_DATABASE=invsync python3 scripts/smoke.py target/network-inventory-sync-1.0.0.jar
# Repeat against MariaDB (set matching test user/password environment variables):
INVSYNC_TEST_TYPE=mariadb INVSYNC_TEST_PORT=3306 INVSYNC_TEST_DATABASE=invsync mvn -B -ntp test
INVSYNC_TEST_TYPE=mariadb INVSYNC_TEST_PORT=3306 INVSYNC_TEST_DATABASE=invsync python3 scripts/smoke.py target/network-inventory-sync-1.0.0.jar
```

Optional real MariaDB TLS tests also run when `INVSYNC_TEST_TYPE=mariadb` and `INVSYNC_TEST_TLS_CA` points to a trusted test certificate. Set `INVSYNC_TEST_TLS_WRONG_HOST` to another hostname resolving to the same server but absent from that certificate, and `INVSYNC_TEST_TLS_UNTRUSTED_CA` to an unrelated certificate to cover rejection. Use a disposable database and certificates.

The runtime probe starts an isolated localhost Paper server with a throwaway namespace on the selected engine and accepts the Minecraft server EULA for automated testing. It uses controlled player doubles inside the real server to test native NBT/PDC, nested containers, enabled fields, loading freezes, and sequential quit/rejoin database handoffs. It does not simulate actual client connections or prove compatibility with all gameplay plugins; use the staging checklist before production.

Every push/PR runs unit tests, real PostgreSQL and MariaDB concurrency/fencing/rollback tests, and the real Paper probe against each engine using the same shaded JAR. Every passing `main` commit publishes a uniquely versioned prerelease JAR and `SHA256SUMS`. Release publication downloads and verifies the **exact tested artifact**, rather than rebuilding it. Actions are pinned to commits; pull requests have no write permissions, and publication needs no personal access token. Production database credentials are never needed by CI.

Other Paper plugins may call `NetworkInventorySync.isReady(UUID)` immediately before a player action. It returns true only for an online, bound READY session with fresh ownership verification; it returns false off the main server thread, during loading, while storage is unavailable, and while stopping. This is a readiness check, not a reservation for a later tick.

Plugin code is MIT licensed. Bundled HikariCP, PostgreSQL JDBC, MariaDB Connector/J and Checker annotations are isolated under `dev.flint.invsync.lib`; their notices/licenses are included in the JAR. MariaDB Connector/J is LGPL 2.1 or later, and its matching source JAR accompanies every release. See [third-party notices and rebuild instructions](THIRD_PARTY.md). Paper, Adventure and SLF4J remain server-provided.
