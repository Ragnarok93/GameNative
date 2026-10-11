from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[3] / 'app/src/main/cpp/lsfg-vk-android'
src = (root / 'src/context.cpp').read_text()
mini = (root / 'src/mini/semaphore.cpp').read_text()
assert '::close(fd);\n        throw LSFG::vulkan_error(res, "Unable to import semaphore from fd")' in mini

def extract_if(marker):
    start = src.index(marker)
    brace = src.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if src[end] == '{': depth += 1
        elif src[end] == '}': depth -= 1
        end += 1
    return src[start:end]

generated = extract_if('    if (this->asyncFramegenCompletionEnabled_\n            && framegenSync.gpuDependenciesExported)')
history = extract_if('            if (historySync.gpuDependenciesExported\n                    && historySync.batchCompleteFd >= 0)')
prefix = r'''
#include <unistd.h>
#include <fcntl.h>
#include <vector>
#include <algorithm>
#include <stdexcept>
#include <iostream>
int importCalls=0, failAt=1, replacement=-1;
constexpr int VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT=1;
namespace Mini { struct Semaphore {
    Semaphore() = default;
    Semaphore(int,int fd,int) {
        if (++importCalls == failAt) {
            // Matches Mini constructor's import-failure close; emulate a
            // concurrent open reusing that same process-wide descriptor.
            ::close(fd);
            replacement=::open("/dev/null",O_RDONLY);
            if(replacement!=fd) { ::dup2(replacement,fd); ::close(replacement); replacement=fd; }
            throw std::runtime_error("injected driver import failure");
        }
        ::close(fd); // successful import consumes descriptor too.
    }
}; }
struct Sync { bool gpuDependenciesExported=true; std::vector<int> outputReadyFds; int batchCompleteFd=-1; };
struct Pass { std::vector<Mini::Semaphore> renderSemaphores{1}; Mini::Semaphore framegenBatchCompleteSemaphore; bool framegenBatchCompleteValid=false; };
struct Metrics { int windowHistoryAsyncReleases=0,totalHistoryAsyncReleases=0; };
struct Context {
 bool conservativeCrossDeviceSync_=false, asyncFramegenCompletionEnabled_=true;
 bool conservativePendingBatchCompleteValid_=false;
 int conservativePendingBatchCompletePollFd_=-1;
 Mini::Semaphore conservativePendingBatchCompleteSemaphore_;
 bool conservativePendingHistoryCompleteValid_=false,asyncHistoryCompletionEnabled_=false;
 Mini::Semaphore conservativePendingHistoryCompleteSemaphore_;
 void generated(Sync& framegenSync,Pass& pass) {
  struct { int device=1; } info;
  size_t generatedFrameCount=1;
  std::vector<bool> outputReadyWaitValid(1,false);
  bool requireHostCompletionWait=false;
'''
mid = r'''
 }
 void history(Sync& historySync,Pass& pass) {
  struct { int device=1; } info;
  bool historyReleaseImported=false,historyRequiresHostCompletionWait=false;
  Metrics metrics;
'''
suffix = r'''
 }
};
int main() {
 for (int caseId=0;caseId<3;++caseId) {
  importCalls=0; failAt=(caseId==1?2:1); replacement=-1;
  Context context; Sync sync; Pass pass;
  if(caseId<2) sync.outputReadyFds.push_back(::open("/dev/null",O_RDONLY));
  sync.batchCompleteFd=::open("/dev/null",O_RDONLY);
  if(caseId<2) context.generated(sync,pass); else context.history(sync,pass);
  bool stillOpen = replacement>=0 && ::fcntl(replacement,F_GETFD)!=-1;
  std::cout<<"case="<<(caseId==0?"generated-output":caseId==1?"generated-batch":"history-batch")
      <<" unrelated_reused_fd="<<replacement<<" still_open="<<stillOpen<<"\n";
  if(stillOpen) ::close(replacement);
 }
}
'''
with tempfile.TemporaryDirectory() as temp:
 out = Path(temp)/'legacy-fd-import'
 cpp=out.with_suffix('.cpp')
 cpp.write_text(prefix+generated+mid+history+suffix)
 subprocess.run(['g++','-std=c++20',str(cpp),'-o',str(out)],check=True)
 subprocess.run([str(out)],check=True)
