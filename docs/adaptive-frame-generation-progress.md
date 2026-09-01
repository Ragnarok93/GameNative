Current native Adaptive branch head pinned by GameNative: `0afae41844a41428940464b77ff7d286316e228a`.

Implemented architecture:

- Adaptive output FPS is an objective independent from the real/source FPS limiter.
- The native controller learns source throughput, probes generation levels incrementally, and may emit a fractional number of generated frames over time.
- Generated frames and the following source frame are paced on one output timeline so Android can present them instead of replacing a burst in the compositor queue.
- Runtime enable and multiplier changes are hot-reloaded without invalidating the game swapchain. Backend/performance and present-mode changes wait for natural context creation.
- GameNative reads source and output metrics separately. Power, cluster, and automatic performance tuning consume source FPS so generated-frame events cannot trigger false headroom decisions.
- Disabling frame generation preserves configuration but makes the native present path inert.

Fresh local native contract tests pass. Full Android/Legacy builds and on-device cadence validation remain required because this development environment has no Android NDK and cannot download the Gradle distribution.
