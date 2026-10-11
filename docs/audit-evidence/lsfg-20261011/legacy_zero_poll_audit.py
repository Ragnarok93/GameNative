from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[3] / 'app/src/main/cpp/lsfg-vk-android/framegen'
with tempfile.TemporaryDirectory() as temp:
    for variant in ['v3.1_src', 'v3.1p_src']:
        source = (root / variant / 'context.cpp').read_text()
        begin = source.index('bool Context::waitForLastPresent(')
        end = source.index('\nbool Context::waitForCompletion(', begin)
        method = source[begin:end]
        prefix = r'''#include <chrono>
#include <cstdint>
#include <vector>
#include <iostream>
struct Vulkan { int device=1; };
struct Fence { int* calls; bool signaled=true; bool wait(int,uint64_t) { ++*calls; return signaled; } };
struct Data { bool shouldWait=true; size_t generationCount; Fence preprocessingFence; std::vector<Fence> completionFences; };
struct Context { size_t frameIdx=1; std::vector<Data> data; bool waitForLastPresent(Vulkan&,uint64_t); };
'''
        suffix = r'''
int main() { Vulkan vk; for(size_t generated: {0U,1U,3U}) {int calls=0; Context c; c.data.push_back(Data{true,generated,Fence{&calls},std::vector<Fence>(generated,Fence{&calls})}); bool ready=c.waitForLastPresent(vk,0); std::cout << "completed_generated=" << generated << " ready=" << ready << " fence_checks=" << calls << " should_wait=" << c.data[0].shouldWait << "\n"; } }
'''
        out = Path(temp) / variant
        cpp = out.with_suffix('.cpp')
        cpp.write_text(prefix + method + suffix)
        subprocess.run(['g++', '-std=c++20', str(cpp), '-o', str(out)], check=True)
        print(variant, flush=True)
        subprocess.run([str(out)], check=True)
