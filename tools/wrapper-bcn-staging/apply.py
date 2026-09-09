#!/usr/bin/env python3
"""Apply fence-safe BCn transient buffer reuse to transformed wrapper-25 source.

BCn internal buffers stay owned by the command buffer that recorded references
to them. They are eligible for reuse only when that command buffer is begun,
reset, or destroyed, which is the Vulkan point at which it can no longer be
pending. Released buffers enter a bounded per-device cache.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
private_path = root / "src/vulkan/wrapper/wrapper_private.h"
device_path = root / "src/vulkan/wrapper/wrapper_device.c"
private = private_path.read_text()
device = device_path.read_text()


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# Structures: one global free pool per VkDevice, one active list per command
# buffer, and enough metadata to match buffers without rebinding/reallocating.
private = one(
    private,
    """   VkPipeline bcn_pipeline;
   VkDeviceSize bcn_gpu_inflight;        /* transient GPU-transcode bytes not yet freed */
""",
    """   VkPipeline bcn_pipeline;
   VkDeviceSize bcn_gpu_inflight;        /* active GPU-transcode bytes */
   struct list_head bcn_buffer_cache;    /* safe-to-reuse internal BCn buffers */
   VkDeviceSize bcn_buffer_cache_bytes;
   VkDeviceSize bcn_buffer_cache_limit;
""",
    "device BCn buffer cache fields",
)
private = one(
    private,
    """   struct wrapper_push_pool *push_pools;   /* emulated push-descriptor pools */
   struct wrapper_bcn_pool *bcn_pools;     /* BCn compute descriptor pools */
""",
    """   struct wrapper_push_pool *push_pools;   /* emulated push-descriptor pools */
   struct wrapper_bcn_pool *bcn_pools;     /* BCn compute descriptor pools */
   struct list_head bcn_buffers;           /* internal buffers referenced by this recording */
""",
    "command-buffer BCn active list",
)
private = one(
    private,
    """   VkDescriptorPool desc_pool;
   VkDeviceSize bcn_inflight;
   VkExternalMemoryHandleTypeFlags handle_types;
""",
    """   VkDescriptorPool desc_pool;
   VkDeviceSize bcn_inflight;
   VkBufferUsageFlags bcn_usage;
   VkMemoryPropertyFlags bcn_memory_flags;
   VkExternalMemoryHandleTypeFlags handle_types;
""",
    "internal buffer reuse metadata",
)

# Initialize the global free list and its bounded retention policy.
device = one(
    device,
    """   list_inithead(&device->buffer_list);
   list_inithead(&device->fence_list);
""",
    """   list_inithead(&device->buffer_list);
   list_inithead(&device->fence_list);
   list_inithead(&device->bcn_buffer_cache);
""",
    "BCn cache list init",
)
device = one(
    device,
    """   device->bcn_gpu_state = 0;
   device->physical = physical_device;
""",
    """   device->bcn_gpu_state = 0;
   {
      const char *cache_env = getenv("WRAPPER_BCN_BUFFER_CACHE_MB");
      long long cache_mb = cache_env ? atoll(cache_env) : 64;
      if (cache_mb < 0) cache_mb = 0;
      device->bcn_buffer_cache_limit = (VkDeviceSize)cache_mb * 1024 * 1024;
   }
   device->physical = physical_device;
""",
    "BCn cache budget init",
)

# Insert buffer cache helpers immediately after the descriptor-set helper, which
# is already injected by apply_optimizations.py and appears before Begin/Reset.
anchor = """   if (r == VK_SUCCESS)
      p->remaining--;
   return r;
}

/* Allocate one descriptor set of `layout` from a per-CB chunked pool sized from
"""
helpers = """   if (r == VK_SUCCESS)
      p->remaining--;
   return r;
}

static void
wrapper_bcn_destroy_internal_buffer(struct wrapper_device *device,
                                    struct wrapper_buffer *b)
{
   if (!b) return;
   if (b->mapped_address) {
      device->dispatch_table.UnmapMemory(device->dispatch_handle, b->memory);
      b->mapped_address = NULL;
   }
   if (b->dispatch_handle)
      device->dispatch_table.DestroyBuffer(device->dispatch_handle,
                                            b->dispatch_handle, NULL);
   if (b->memory)
      device->dispatch_table.FreeMemory(device->dispatch_handle, b->memory, NULL);
   vk_object_free(&device->vk, &device->vk.alloc, b);
}

static struct wrapper_buffer *
wrapper_bcn_acquire_buffer(struct wrapper_command_buffer *wcb,
                           VkDeviceSize size, VkBufferUsageFlags usage,
                           VkMemoryPropertyFlags memory_flags)
{
   struct wrapper_device *device = wcb->device;
   struct wrapper_buffer *best = NULL;

   simple_mtx_lock(&device->bcn_gpu_mutex);
   list_for_each_entry(struct wrapper_buffer, candidate,
                       &device->bcn_buffer_cache, link) {
      if (candidate->bcn_usage != usage ||
          candidate->bcn_memory_flags != memory_flags ||
          candidate->size < size)
         continue;
      if (!best || candidate->size < best->size)
         best = candidate;
   }
   if (best) {
      list_del(&best->link);
      device->bcn_buffer_cache_bytes -= best->size;
   }
   simple_mtx_unlock(&device->bcn_gpu_mutex);

   if (!best) {
      best = vk_object_zalloc(&device->vk, &device->vk.alloc,
                              sizeof(*best), VK_OBJECT_TYPE_BUFFER);
      if (!best) return NULL;
      best->device = device;
      best->size = size;
      best->bcn_usage = usage;
      best->bcn_memory_flags = memory_flags;

      VkBufferCreateInfo bci = {
         .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
         .size = size,
         .usage = usage,
         .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
      };
      if (device->dispatch_table.CreateBuffer(device->dispatch_handle, &bci,
            NULL, &best->dispatch_handle) != VK_SUCCESS)
         goto fail;

      VkMemoryRequirements mr;
      device->dispatch_table.GetBufferMemoryRequirements(device->dispatch_handle,
                                                          best->dispatch_handle, &mr);
      VkMemoryAllocateInfo ai = {
         .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
         .allocationSize = mr.size,
         .memoryTypeIndex = wrapper_select_device_memory_type(device, memory_flags),
      };
      if (device->dispatch_table.AllocateMemory(device->dispatch_handle, &ai,
            NULL, &best->memory) != VK_SUCCESS)
         goto fail;
      if (device->dispatch_table.BindBufferMemory(device->dispatch_handle,
            best->dispatch_handle, best->memory, 0) != VK_SUCCESS)
         goto fail;

      if (memory_flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
         if (device->dispatch_table.MapMemory(device->dispatch_handle, best->memory,
               0, VK_WHOLE_SIZE, 0, &best->mapped_address) != VK_SUCCESS)
            goto fail;
      }
   }

   best->wcb = wcb;
   best->bcn_inflight = 0;
   list_add(&best->link, &wcb->bcn_buffers);
   return best;

fail:
   wrapper_bcn_destroy_internal_buffer(device, best);
   return NULL;
}

static void
wrapper_bcn_release_cb_buffers(struct wrapper_command_buffer *wcb)
{
   struct wrapper_device *device = wcb->device;
   list_for_each_entry_safe(struct wrapper_buffer, b, &wcb->bcn_buffers, link) {
      list_del(&b->link);
      if (b->bcn_inflight) {
         simple_mtx_lock(&device->bcn_gpu_mutex);
         device->bcn_gpu_inflight -= b->bcn_inflight;
         simple_mtx_unlock(&device->bcn_gpu_mutex);
         b->bcn_inflight = 0;
      }
      b->wcb = NULL;

      bool cache = false;
      simple_mtx_lock(&device->bcn_gpu_mutex);
      if (device->bcn_buffer_cache_limit &&
          device->bcn_buffer_cache_bytes + b->size <= device->bcn_buffer_cache_limit) {
         list_add(&b->link, &device->bcn_buffer_cache);
         device->bcn_buffer_cache_bytes += b->size;
         cache = true;
      }
      simple_mtx_unlock(&device->bcn_gpu_mutex);
      if (!cache)
         wrapper_bcn_destroy_internal_buffer(device, b);
   }
}

static void
wrapper_bcn_flush_buffer_cache(struct wrapper_device *device)
{
   list_for_each_entry_safe(struct wrapper_buffer, b,
                            &device->bcn_buffer_cache, link) {
      list_del(&b->link);
      wrapper_bcn_destroy_internal_buffer(device, b);
   }
   device->bcn_buffer_cache_bytes = 0;
}

/* Allocate one descriptor set of `layout` from a per-CB chunked pool sized from
"""
device = one(device, anchor, helpers, "BCn transient cache helpers")

# Active resource lifetime follows command-buffer lifetime, not a previously
# remembered fence. Reset/begin is only legal once prior execution is complete.
device = device.replace(
    "   wrapper_bcn_pool_reset_all(wcb);\n",
    "   wrapper_bcn_release_cb_buffers(wcb);\n   wrapper_bcn_pool_reset_all(wcb);\n",
)
if device.count("wrapper_bcn_release_cb_buffers(wcb);") != 2:
    raise RuntimeError("expected BCn release hook on BeginCommandBuffer and ResetCommandBuffer")

# ResetCommandPool also makes every child command buffer safe for reuse.
# Current wrapper-25 also tracks dynamic-rendering objects in this walk. BCn
# work must participate in the same lifetime boundary even when none of the
# other emulation modes are active.
old_pool_reset = """   /* Both of these walk the device's whole command-buffer list under its lock,
 * so do it only when something can have put objects there. */
   if (device->emulate_push_descriptor ||
       device->physical->emulate_imageless_framebuffer ||
       device->physical->emulate_vulkan13) {
      simple_mtx_lock(&device->resource_mutex);
      list_for_each_entry(struct wrapper_command_buffer, wcb,
                          &device->command_buffer_list, link) {
         if (wcb->pool == commandPool) {
            wrapper_dynamic_render_objects_reset(wcb);
            if (device->emulate_push_descriptor)
               wrapper_push_pool_reset_all(wcb);
         }
      }
      simple_mtx_unlock(&device->resource_mutex);
   }
   return device->dispatch_table.ResetCommandPool(device->dispatch_handle,
"""
new_pool_reset = """   /* Dynamic-rendering, push-descriptor, and BCn transient objects all follow
 * command-buffer lifetime. Include BCn in the gate so a pool reset releases
 * its internal buffers even when the other emulation modes are inactive. */
   if (device->emulate_push_descriptor ||
       device->physical->emulate_imageless_framebuffer ||
       device->physical->emulate_vulkan13 ||
       device->physical->emulate_bcn > 1) {
      simple_mtx_lock(&device->resource_mutex);
      list_for_each_entry(struct wrapper_command_buffer, wcb,
                          &device->command_buffer_list, link) {
         if (wcb->pool == commandPool) {
            wrapper_dynamic_render_objects_reset(wcb);
            if (device->emulate_push_descriptor)
               wrapper_push_pool_reset_all(wcb);
            wrapper_bcn_release_cb_buffers(wcb);
            wrapper_bcn_pool_reset_all(wcb);
         }
      }
      simple_mtx_unlock(&device->resource_mutex);
   }
   return device->dispatch_table.ResetCommandPool(device->dispatch_handle,
"""
device = one(device, old_pool_reset, new_pool_reset, "command-pool reset lifetime")

# New command buffers start with an empty active internal-buffer list.
device = one(
    device,
    """   wcb->pool = pool;
   wcb->dispatch_handle = dispatch_handle;
   list_add(&wcb->link, &device->command_buffer_list);
""",
    """   wcb->pool = pool;
   wcb->dispatch_handle = dispatch_handle;
   list_inithead(&wcb->bcn_buffers);
   list_add(&wcb->link, &device->command_buffer_list);
""",
    "command-buffer active list init",
)

# Destroying a command buffer is also a safe completion boundary.
device = one(
    device,
    """   if (device->emulate_push_descriptor)
      wrapper_push_pool_destroy_all(wcb);
   wrapper_bcn_pool_destroy_all(wcb);

   device->dispatch_table.FreeCommandBuffers(""",
    """   wrapper_bcn_release_cb_buffers(wcb);
   if (device->emulate_push_descriptor)
      wrapper_push_pool_destroy_all(wcb);
   wrapper_bcn_pool_destroy_all(wcb);

   device->dispatch_table.FreeCommandBuffers(""",
    "command-buffer destroy lifetime",
)

# Replace fresh device-local GPU allocations with the bounded cache.
start = device.index("static struct wrapper_buffer *\nwrapper_bcn_make_buffer")
end = device.index("\n/* Destroy an untracked transcode buffer", start)
device = device[:start] + """static struct wrapper_buffer *
wrapper_bcn_make_buffer(struct wrapper_command_buffer *wcb, VkDeviceSize size,
                        VkBufferUsageFlags usage)
{
   return wrapper_bcn_acquire_buffer(wcb, size, usage,
                                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
}
""" + device[end:]
device = device.replace(
    "wrapper_bcn_make_buffer(device, src_size,",
    "wrapper_bcn_make_buffer(wcb, src_size,",
)
device = device.replace(
    "wrapper_bcn_make_buffer(device, dst_size,",
    "wrapper_bcn_make_buffer(wcb, dst_size,",
)
if device.count("wrapper_bcn_make_buffer(wcb,") != 2:
    raise RuntimeError("GPU BCn buffer acquisition replacement failed")

# Error-path cleanup must detach an active internal buffer before destruction.
old_free = """static void
wrapper_bcn_free_buffer(struct wrapper_device *device, struct wrapper_buffer *b)
{
   if (!b) return;
   device->dispatch_table.DestroyBuffer(device->dispatch_handle, b->dispatch_handle, NULL);
   device->dispatch_table.FreeMemory(device->dispatch_handle, b->memory, NULL);
   vk_object_free(&device->vk, &device->vk.alloc, b);
}
"""
new_free = """static void
wrapper_bcn_free_buffer(struct wrapper_device *device, struct wrapper_buffer *b)
{
   if (!b) return;
   if (b->wcb) {
      list_del(&b->link);
      b->wcb = NULL;
   }
   wrapper_bcn_destroy_internal_buffer(device, b);
}
"""
device = one(device, old_free, new_free, "GPU error cleanup")

# Successful GPU buffers already live on wcb->bcn_buffers. Do not attach them to
# a fence whose completion does not prove the command buffer will not be reused.
old_gpu_fence = """      srcb->wcb = wcb;
      dstb->wcb = wcb;
      if (wcb->fence) {
         list_add(&srcb->link, &wcb->fence->staging_buffers_list);
         list_add(&dstb->link, &wcb->fence->staging_buffers_list);
      }
"""
device = one(device, old_gpu_fence, "", "GPU fence ownership removal")

# CPU BCn staging uses the same cache; host-visible buffers remain persistently
# mapped while cached, eliminating repeated buffer/memory/map setup after warmup.
cpu_start = device.index("      struct wrapper_buffer *staging_wb = vk_object_zalloc", device.index("wrapper_bcn_do_copy"))
cpu_end_marker = "      int src_w = copy_region.bufferRowLength ? (int)copy_region.bufferRowLength : w;\n"
cpu_end = device.index(cpu_end_marker, cpu_start)
device = device[:cpu_start] + """      struct wrapper_buffer *staging_wb = wrapper_bcn_acquire_buffer(
         wcb, upload_size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
      if (!staging_wb) {
         WRAPPER_LOG(error, "Failed to acquire BCn staging buffer");
         simple_mtx_unlock(&device->resource_mutex);
         return;
      }

""" + device[cpu_end:]

old_cpu_fence = """      staging_wb->wcb = wcb;
      staging_wb->device = device;

      if (wcb->fence)
         list_add(&staging_wb->link, &wcb->fence->staging_buffers_list);
"""
device = one(device, old_cpu_fence, "", "CPU fence ownership removal")

# Device destruction first releases every command buffer, then drops the free
# cache before destroying VkDevice.
device = one(
    device,
    """   simple_mtx_unlock(&device->resource_mutex);
   
   list_for_each_entry_safe(struct wrapper_buffer, wb,
""",
    """   simple_mtx_unlock(&device->resource_mutex);

   wrapper_bcn_flush_buffer_cache(device);
   
   list_for_each_entry_safe(struct wrapper_buffer, wb,
""",
    "device cache flush",
)

# Legacy fence GC remains for compatibility with any other staging producer,
# but optimized BCn buffers no longer enter that list.
if "WRAPPER_BCN_BUFFER_CACHE_MB" not in device or "&wcb->bcn_buffers" not in device:
    raise RuntimeError("BCn buffer-reuse postcondition failed")

private_path.write_text(private)
device_path.write_text(device)
print("Applied BCn transient reuse: command-buffer lifetime + bounded 64MiB device cache")
