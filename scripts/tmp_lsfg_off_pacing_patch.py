#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/winlator/xserver/extensions/PresentExtension.java')
s = p.read_text()
old = '''        WindowTiming wt = windowTimings.computeIfAbsent(window.id, k -> new WindowTiming());
        if (wt.nextIdleNs <= now - frameNs) {
            wt.nextIdleNs = now + frameNs;
        } else {
            wt.nextIdleNs += frameNs;
        }
        long fireTime = wt.nextIdleNs - FIRE_EARLY_NS;
'''
new = '''        WindowTiming wt = windowTimings.computeIfAbsent(window.id, k -> new WindowTiming());
        // Re-arming the normal Present limiter after LSFG relinquishes pacing must
        // not inject a full-frame bubble. Let the first idle through immediately,
        // then seed the normal cadence for subsequent presents.
        if (wt.nextIdleNs == 0) {
            wt.nextIdleNs = now + frameNs;
            sendIdleNotify(window, pixmap, serial, idleFence);
            return;
        }
        if (wt.nextIdleNs <= now - frameNs) {
            wt.nextIdleNs = now + frameNs;
        } else {
            wt.nextIdleNs += frameNs;
        }
        long fireTime = wt.nextIdleNs - FIRE_EARLY_NS;
'''
if old not in s:
    raise SystemExit('PresentExtension pacing anchor not found')
p.write_text(s.replace(old, new, 1))
