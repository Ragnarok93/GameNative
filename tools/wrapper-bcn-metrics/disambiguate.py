#!/usr/bin/env python3
"""Make the generic push-descriptor allocation block structurally distinct.

This is a semantics-preserving source normalization so the BCn metrics transform
can assert it is instrumenting wrapper_bcn_alloc_set rather than the unrelated
push-descriptor allocator.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = root / "src/vulkan/wrapper/wrapper_device.c"
text = path.read_text()
start = text.index("wrapper_push_alloc_set")
head, tail = text[:start], text[start:]
old = """   VkResult r = dt->AllocateDescriptorSets(device->dispatch_handle, &ai, out);
   if (r == VK_SUCCESS)
      p->remaining--;
   return r;
}"""
new = """   VkResult r = dt->AllocateDescriptorSets(device->dispatch_handle, &ai, out);
   if (r == VK_SUCCESS) {
      p->remaining--;
   }
   return r;
}"""
count = tail.count(old)
if count != 1:
    raise RuntimeError(f"push descriptor allocation normalization: expected 1 match, found {count}")
path.write_text(head + tail.replace(old, new, 1))
print("Normalized push-descriptor allocation scope for BCn instrumentation")
