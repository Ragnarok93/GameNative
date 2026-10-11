# LSFG follow-up audit evidence

Baseline: GameNative0440c57025ce87a32b699676ef52f366fb47a82d,
Shared runtime5f14ebc3002401d4c762966cddf1793b3d3ec3f5.
These are audit reproductions, outside the build and CI test inputs. They do
not fix production code or demonstrate physical-device performance.

Run from the repository root with Python3 and a C++20 compiler:

```bash
python3 docs/audit-evidence/lsfg-20261011/legacy_fd_import_audit.py
python3 docs/audit-evidence/lsfg-20261011/legacy_zero_poll_audit.py
python3 docs/audit-evidence/lsfg-20261011/native_prepare_audit.py
python3 docs/audit-evidence/lsfg-20261011/native_density_audit.py transient-delivery-recovery
```

These scripts extract the production methods and substitute Vulkan allocation,
fences, the pacer or engine boundaries. The density case exits1 while its
eventual-recovery assertion fails on the audited implementation. The Legacy
and prepare cases print characterization results; exit0 means their harness
executed, not that the observed production behavior is correct.

The two C++ files preserve extracted Native observation/density code from this
baseline and use the actual Shared Flow controller. They use a deterministic
pacer, not a Vulkan driver. Compile each with:

```bash
g++ -std=c++20 -I app/src/main/cpp/lsfg-vk-android/include docs/audit-evidence/lsfg-20261011/native_flow_recovery.cpp app/src/main/cpp/lsfg-vk-android/src/adaptive_flow_controller.cpp -o /tmp/lsfg-flow-audit
/tmp/lsfg-flow-audit
g++ -std=c++20 -I app/src/main/cpp/lsfg-vk-android/include docs/audit-evidence/lsfg-20261011/native_source_starvation.cpp app/src/main/cpp/lsfg-vk-android/src/adaptive_flow_controller.cpp -o /tmp/lsfg-source-audit
/tmp/lsfg-source-audit
```

Both return1 to expose the current recovery/target-tracking defects. Elapsed
seconds in these experiments are simulated controller time.

For the Kotlin reproductions set `KOTLIN_AUDIT_CLASSPATH` to a Java classpath
containing Kotlin2.1.20's JVM compiler and dependencies, and
`KOTLIN_AUDIT_STDLIB` to its standard-library JAR, then run:

```bash
bash docs/audit-evidence/lsfg-20261011/run_settings.sh
```

This compiles the actual Adaptive FPS Cap and controller sources with the
supplied engine/log stubs. `LegacyAck.kt` is the unchanged extracted baseline
reader/readiness logic with a minimal Container wrapper and test main.
Its fixture has no request-token parameter because the production reader does
not validate one. Assertions about device generation overlap are inferences
from the missing handoff contract, not results of this fixture.
