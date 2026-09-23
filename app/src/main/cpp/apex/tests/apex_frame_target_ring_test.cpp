#include "../apex_frame_target_ring.h"

#include <cassert>
#include <iostream>

using gamenative::apex::FrameTargetRing;

int main() {
    FrameTargetRing ring(3);

    const int a = ring.acquireForProducer();
    const int b = ring.acquireForProducer();
    const int c = ring.acquireForProducer();

    assert(a == 0);
    assert(b == 1);
    assert(c == 2);
    assert(ring.acquireForProducer() == -1);

    const auto seqA = ring.markReady(a);
    const auto seqB = ring.markReady(b);
    const auto seqC = ring.markReady(c);
    assert(seqA < seqB && seqB < seqC);

    const auto tokenA = ring.dequeueForConsumer();
    assert(tokenA.valid());
    assert(tokenA.slot == a);
    assert(tokenA.sequence == seqA);

    // A consumer-owned slot must not be recycled.
    assert(ring.acquireForProducer() == -1);

    assert(ring.releaseFromConsumer(tokenA.slot, tokenA.sequence));
    assert(ring.acquireForProducer() == a);

    // Stale releases may not free a newly reused slot.
    assert(!ring.releaseFromConsumer(tokenA.slot, tokenA.sequence));

    std::cout << "Apex frame target ring tests passed\n";
    return 0;
}
