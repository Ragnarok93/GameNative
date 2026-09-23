#include "apex_frame_target_ring.h"

#include <limits>

namespace gamenative::apex {

FrameTargetRing::FrameTargetRing(std::size_t slotCount)
    : slots_(slotCount) {}

int FrameTargetRing::acquireForProducer() noexcept {
    if (slots_.empty()) return -1;

    for (std::size_t offset = 0; offset < slots_.size(); ++offset) {
        const std::size_t index = (producerHint_ + offset) % slots_.size();
        Slot& slot = slots_[index];
        if (slot.state != State::Free) continue;

        slot.state = State::ProducerOwned;
        slot.sequence = 0;
        producerHint_ = (index + 1) % slots_.size();
        return static_cast<int>(index);
    }

    return -1;
}

uint64_t FrameTargetRing::markReady(int slotIndex) noexcept {
    if (!validSlot(slotIndex)) return 0;
    Slot& slot = slots_[static_cast<std::size_t>(slotIndex)];
    if (slot.state != State::ProducerOwned) return 0;

    uint64_t sequence = nextSequence_++;
    if (sequence == 0) {
        sequence = nextSequence_++;
    }
    slot.sequence = sequence;
    slot.state = State::Ready;
    return sequence;
}

bool FrameTargetRing::cancelProducer(int slotIndex) noexcept {
    if (!validSlot(slotIndex)) return false;
    Slot& slot = slots_[static_cast<std::size_t>(slotIndex)];
    if (slot.state != State::ProducerOwned) return false;

    slot = {};
    producerHint_ = static_cast<std::size_t>(slotIndex);
    return true;
}

FrameTargetRing::ConsumerToken FrameTargetRing::dequeueForConsumer() noexcept {
    int selected = -1;
    uint64_t oldestSequence = std::numeric_limits<uint64_t>::max();

    for (std::size_t index = 0; index < slots_.size(); ++index) {
        const Slot& slot = slots_[index];
        if (slot.state == State::Ready && slot.sequence < oldestSequence) {
            selected = static_cast<int>(index);
            oldestSequence = slot.sequence;
        }
    }

    if (selected < 0) return {};

    Slot& slot = slots_[static_cast<std::size_t>(selected)];
    slot.state = State::ConsumerOwned;
    return {
        .slot = selected,
        .sequence = slot.sequence,
    };
}

bool FrameTargetRing::releaseFromConsumer(int slotIndex, uint64_t sequence) noexcept {
    if (!validSlot(slotIndex) || sequence == 0) return false;

    Slot& slot = slots_[static_cast<std::size_t>(slotIndex)];
    if (slot.state != State::ConsumerOwned || slot.sequence != sequence) {
        return false;
    }

    slot = {};
    producerHint_ = static_cast<std::size_t>(slotIndex);
    return true;
}

void FrameTargetRing::reset() noexcept {
    for (Slot& slot : slots_) slot = {};
    producerHint_ = 0;
    nextSequence_ = 1;
}

bool FrameTargetRing::hasOutstandingConsumer() const noexcept {
    for (const Slot& slot : slots_) {
        if (slot.state == State::ConsumerOwned) return true;
    }
    return false;
}

} // namespace gamenative::apex
