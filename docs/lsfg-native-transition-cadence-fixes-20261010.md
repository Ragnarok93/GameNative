# Native LSFG settings and density fixes

The October 9 device logs show repeated source-only acknowledgement waits during
Native settings changes, reduced 3x/4x admission, and Adaptive generation density
collapsing despite healthy confirmed delivery. These changes address those causes.

## Settings transitions

Legacy source-only acknowledgement is retained for the exact renderer, container,
and backend generation. Target, Flow, queue, and presentation-mode revisions can
reuse that proof while the guest is paused in Quick Menu. New ownership and fresh
contradictory Legacy activity still require a barrier. Source-only configuration
publication continues for every changed policy.

FIFO and Mailbox are effective Native policy fields. Changing mode now goes through
the captured transaction and reaches the renderer instead of taking the reuse path.

## Generation and Adaptive Flow

The logged roughly 30 FPS source/120 FPS target had approximately 96% generated
delivery efficiency but only 102–106 confirmed output FPS. Requested cadence
shortfall alone was classified as delivery loss, backing density down to zero.
Presentation pressure now uses delivery loss, confirmation timeout, imbalance,
and rejection evidence; cadence deficit remains separately reported and physical
targets are bounded by display refresh.

Flow observations reflect the retained Adaptive generation cap and its reachable
output. Density recovery uses healthy delivery and GPU headroom for held probes;
it no longer requires full requested output under a cap that prevents reaching it.
A new Flow preset above its minimum scale cannot permanently pin a retained cap.
Actual delivery/admission pressure and severe GPU pressure still cause backoff.

## 3x/4x admission

The bounded Native presenter handoff accommodates a full four-frame burst plus
overlap from the preceding source. A single validated Native source due within
one refresh uses remaining deadline time plus median service as future work.
Elapsed queue age and a blocked p95 presentation tail no longer consume that same
source period again. Synthetic tails, old sources, and multiple outstanding frames
retain conservative admission. Temporal refresh limits and source reservation
remain enforced. User Frame Queue depth retains its existing meaning.

## Verification and device feedback

New source-extracted C++ regressions cover healthy versus genuine delivery loss,
refresh clamping, capped Flow observations, held density recovery including a new
preset, paced source overlap, real backlog, and temporal limits. Kotlin tests cover
mode changes and ownership-scoped Legacy barriers. Both CI paths run the new C++
regressions alongside existing presentation and stability checks.

Physical cadence, quality, input latency, and sustained thermal behavior remain
unverified after this fix. For fresh device feedback, compare:

| Scenario | Observe |
| --- | --- |
| Native Fixed 2x, 3x, 4x at a stable source rate | Requested/admitted generations, confirmed output, source starvation |
| Adaptive 120 → 90 → 120 while Quick Menu pauses the guest | Apply latency, density recovery, target tracking |
| Flow preset/scale changes after density backoff | Cap recovery, visual quality, transition stalls |
| Frame Queue off and each depth | Smoothness, delivery loss, latency |
| FIFO ↔ Mailbox and Legacy ↔ Native | Actual delivery policy, transition ownership, confirmed cadence |

Capture a fresh LSFG log and app log after each sustained run. Automated checks
validate controller and transaction behavior; they do not establish physical
display or input-latency improvements.
