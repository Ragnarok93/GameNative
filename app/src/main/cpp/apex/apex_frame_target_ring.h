#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace gamenative::apex {

class FrameTargetRing {
public:
    struct ConsumerToken {
        int slot{-1};
        uint64_t sequence{0};

        bool valid() const noexcept {
            return slot >= 0 && sequence != 0;
        }
    };

    explicit FrameTargetRing(std::size_t slotCount);

    int acquireForProducer() noexcept;
    uint64_t markReady(int slot) noexcept;
    bool cancelProducer(int slot) noexcept;
    ConsumerToken dequeueForConsumer() noexcept;
    bool releaseFromConsumer(int slot, uint64_t sequence) noexcept;
    void reset() noexcept;

    std::size_t slotCount() const noexcept { return slots_.size(); }
    bool hasOutstandingConsumer() const noexcept;

private:
    enum class State : uint8_t {
        Free,
        ProducerOwned,
        Ready,
        ConsumerOwned,
    };

    struct Slot {
        State state{State::Free};
        uint64_t sequence{0};
    };

    bool validSlot(int slot) const noexcept {
        return slot >= 0 && static_cast<std::size_t>(slot) < slots_.size();
    }

    std::vector<Slot> slots_;
    std::size_t producerHint_{0};
    uint64_t nextSequence_{1};
};

} // namespace gamenative::apex
