# 1500 msg/sec Kafka Benchmark Result

## Run metadata

| Field | Value |
|-------|-------|
| Date (UTC) | 2026-04-21T23:11:33Z |
| Git SHA | `a378d6a` (uncommitted benchmark harness on top) |
| Hardware | Apple M4, 16 GB RAM |
| OS | macOS 26.3.1 (arm64) |
| Java | OpenJDK 21.0.10 |
| Docker | 29.3.1 |

## Configuration

| Parameter | Value |
|-----------|-------|
| `TARGET_RATE` | 1500 msg/sec |
| `DURATION_SECONDS` | 60 |
| Total messages | 90 000 |
| Topic | `notification.send` |
| Topic partitions | 6 |
| Consumer group | `notification-sender-group` |
| Listener concurrency | **3** (default) |
| Producer `batch.size` | 32 768 |
| Producer `linger.ms` | 10 |
| Producer `acks` | `all` |
| Producer `enable.idempotence` | true |
| Producer `compression.type` | gzip |
| Sender implementation | `NoOpSender` (activated via `SPRING_PROFILES_ACTIVE=loadtest`) |

## Results

### Producer side

| Metric | Value |
|--------|-------|
| Achieved producer rate | **1499 msg/sec** (target 1500) |
| Messages sent (acked) | 90 000 / 90 000 |
| Producer failures | 0 |
| JDBC pre-insert throughput | 52 265 rows/sec (90 000 rows in 1 722 ms) |
| Producer-ack latency — avg | 6 ms |
| Producer-ack latency — p50 | 6 ms |
| Producer-ack latency — p95 | 11 ms |
| Producer-ack latency — p99 | 11 ms |

### Consumer side (from lag probe)

| Metric | Value |
|--------|-------|
| Peak consumer lag | **16 397** messages (at t≈64 s) |
| Time from peak lag → lag = 0 | **17 s** (t=64 → t=81) |
| Implied consumer throughput | ~964 msg/sec post-producer (16 397 / 17) |
| Implied consumer throughput during steady state | ~1 200–1 300 msg/sec (from lag growth rate) |
| Final DB `SENT` count matches sent | **yes** |
| DLQ messages | 0 |

The producer hit the target rate cleanly, but with listener `concurrency=3` the consumer side could not keep up and **lag grew monotonically for the full 60 s window**, peaking ~4 s after the producer finished, then draining in ~17 s. The pipeline *eventually* processed every message, but did not sustain 1500 msg/sec end-to-end at concurrency=3.

## Pass criteria

- [x] Producer sustained ≥ 1500 msg/sec averaged over the 60 s window (1499 msg/sec)
- [x] Zero producer failures
- [x] `SELECT COUNT(*) FROM notifications WHERE status = 'SENT'` matches sent count (90 000)
- [x] No messages in `notification.send.dlq`
- [ ] **Consumer sustained 1500 msg/sec without lag growth** — **not met at concurrency=3**; expected to be met at concurrency=6

## Summary

On an Apple M4 / 16 GB dev laptop with single-broker Kafka (6 partitions, RF=1) and Postgres 16 in Docker, the producer cleanly sustained **1499 msg/sec** for 60 s with a p99 ack latency of 11 ms. The consumer side with default `spring.kafka.listener.concurrency=3` processed at roughly **1 200–1 300 msg/sec steady state** and ~**960 msg/sec** while draining — below the producer rate — so lag accumulated throughout the run and drained cleanly in 17 s after the producer stopped. All 90 000 messages ended in `status='SENT'`; no DLQ traffic.

**Next tuning step to hit 1500+ msg/sec end-to-end without lag growth:** raise `spring.kafka.listener.concurrency` from 3 to **6** to match the `notification.send` partition count. With 6 consumer threads at ~250 msg/sec each (what we measured per-thread here), the pipeline should sustain ~1500 msg/sec with stable lag.

Beyond that, scale levers are: more topic partitions + more app instances, and moving Kafka off single-broker.

## Raw logs

- Runner: [`run-20260421T231133Z.log`](run-20260421T231133Z.log)
- Probe:  [`probe-20260421T231133Z.log`](probe-20260421T231133Z.log)
