#!/usr/bin/env python3
"""Apply GameNative BCn wrapper optimizations to GameNative/mesa:wrapper-25.

The transforms are deliberately assertion-based. If upstream changes the
expected code shape, this script exits without silently applying a partial or
misplaced edit.
"""
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
DEVICE = ROOT / "src/vulkan/wrapper/wrapper_device.c"
PRIVATE = ROOT / "src/vulkan/wrapper/wrapper_private.h"
BCDEC = ROOT / "src/vulkan/wrapper/wrapper_bcdec.c"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_n(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} matches, found {count}")
    return text.replace(old, new)


def transform_private(text: str) -> str:
    marker = """struct wrapper_push_pool {
   struct wrapper_push_pool *next;
   VkDescriptorPool pool;
   VkDescriptorSetLayout layout;   /* the set layout this pool is sized for */
   uint32_t remaining;             /* sets left before this pool is exhausted */
};

struct wrapper_command_buffer {"""
    replacement = """struct wrapper_push_pool {
   struct wrapper_push_pool *next;
   VkDescriptorPool pool;
   VkDescriptorSetLayout layout;   /* the set layout this pool is sized for */
   uint32_t remaining;             /* sets left before this pool is exhausted */
};

/* Chunked descriptor pools used by BCn compute transcode. Pools are owned by
 * one command buffer so descriptor sets remain valid until that recording is
 * no longer executable/pending. They are reset when the command buffer is
 * begun/reset again and destroyed with the command buffer. */
struct wrapper_bcn_pool {
   struct wrapper_bcn_pool *next;
   VkDescriptorPool pool;
   uint32_t remaining;
};

struct wrapper_command_buffer {"""
    text = replace_once(text, marker, replacement, "BCn pool structure insertion")
    text = replace_once(
        text,
        "   struct wrapper_push_pool *push_pools;   /* emulated push-descriptor pools */\n",
        "   struct wrapper_push_pool *push_pools;   /* emulated push-descriptor pools */\n"
        "   struct wrapper_bcn_pool *bcn_pools;     /* BCn compute descriptor pools */\n",
        "BCn command-buffer pool field",
    )
    return text


def transform_device(text: str) -> str:
    helper_anchor = """   wcb->push_pools = NULL;
}

/* Allocate one descriptor set of `layout` from a per-CB chunked pool sized from
 * the layout's bindings. Pools specialize per layout: an allocation that can't
 * be served by an existing pool spins up a new one. */"""
    helpers = """   wcb->push_pools = NULL;
}

#define WRAPPER_BCN_POOL_CHUNK 64

static void
wrapper_bcn_pool_reset_all(struct wrapper_command_buffer *wcb)
{
   struct wrapper_device *device = wcb->device;
   for (struct wrapper_bcn_pool *p = wcb->bcn_pools; p; p = p->next) {
      device->dispatch_table.ResetDescriptorPool(device->dispatch_handle,
                                                  p->pool, 0);
      p->remaining = WRAPPER_BCN_POOL_CHUNK;
   }
}

static void
wrapper_bcn_pool_destroy_all(struct wrapper_command_buffer *wcb)
{
   struct wrapper_device *device = wcb->device;
   struct wrapper_bcn_pool *p = wcb->bcn_pools;
   while (p) {
      struct wrapper_bcn_pool *next = p->next;
      device->dispatch_table.DestroyDescriptorPool(device->dispatch_handle,
                                                    p->pool, NULL);
      free(p);
      p = next;
   }
   wcb->bcn_pools = NULL;
}

static VkResult
wrapper_bcn_alloc_set(struct wrapper_command_buffer *wcb, VkDescriptorSet *out)
{
   struct wrapper_device *device = wcb->device;
   const struct vk_device_dispatch_table *dt = &device->dispatch_table;
   struct wrapper_bcn_pool *p = wcb->bcn_pools;
   for (; p; p = p->next)
      if (p->remaining > 0)
         break;

   if (!p) {
      VkDescriptorPoolSize size = {
         .type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
         .descriptorCount = 2 * WRAPPER_BCN_POOL_CHUNK,
      };
      VkDescriptorPoolCreateInfo ci = {
         .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
         .maxSets = WRAPPER_BCN_POOL_CHUNK,
         .poolSizeCount = 1,
         .pPoolSizes = &size,
      };
      VkDescriptorPool pool;
      VkResult r = dt->CreateDescriptorPool(device->dispatch_handle, &ci, NULL, &pool);
      if (r != VK_SUCCESS)
         return r;
      p = calloc(1, sizeof(*p));
      if (!p) {
         dt->DestroyDescriptorPool(device->dispatch_handle, pool, NULL);
         return VK_ERROR_OUT_OF_HOST_MEMORY;
      }
      p->pool = pool;
      p->remaining = WRAPPER_BCN_POOL_CHUNK;
      p->next = wcb->bcn_pools;
      wcb->bcn_pools = p;
   }

   VkDescriptorSetAllocateInfo ai = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      .descriptorPool = p->pool,
      .descriptorSetCount = 1,
      .pSetLayouts = &device->bcn_set_layout,
   };
   VkResult r = dt->AllocateDescriptorSets(device->dispatch_handle, &ai, out);
   if (r == VK_SUCCESS)
      p->remaining--;
   return r;
}

/* Allocate one descriptor set of `layout` from a per-CB chunked pool sized from
 * the layout's bindings. Pools specialize per layout: an allocation that can't
 * be served by an existing pool spins up a new one. */"""
    text = replace_once(text, helper_anchor, helpers, "BCn descriptor helpers")

    reset_old = """   if (wcb->device->emulate_push_descriptor)
      wrapper_push_pool_reset_all(wcb);
"""
    reset_new = reset_old + "   wrapper_bcn_pool_reset_all(wcb);\n"
    text = replace_n(text, reset_old, reset_new, 2, "BCn pools on command-buffer begin/reset")

    destroy_old = """   if (device->emulate_push_descriptor)
      wrapper_push_pool_destroy_all(wcb);

   device->dispatch_table.FreeCommandBuffers("""
    destroy_new = """   if (device->emulate_push_descriptor)
      wrapper_push_pool_destroy_all(wcb);
   wrapper_bcn_pool_destroy_all(wcb);

   device->dispatch_table.FreeCommandBuffers("""
    text = replace_once(text, destroy_old, destroy_new, "BCn pool destruction")

    allocation_old = """      VkDescriptorPoolSize psz = { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2 };
      VkDescriptorPoolCreateInfo dpci = {
         .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
         .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &psz,
      };
      VkDescriptorPool pool;
      VkDescriptorSet set;
      VkDescriptorSetAllocateInfo dsai = {
         .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
         .descriptorSetCount = 1, .pSetLayouts = &device->bcn_set_layout,
      };
      if (device->dispatch_table.CreateDescriptorPool(device->dispatch_handle,
            &dpci, NULL, &pool) != VK_SUCCESS) {
         wrapper_bcn_free_buffer(device, srcb);
         wrapper_bcn_free_buffer(device, dstb);
         simple_mtx_lock(&device->bcn_gpu_mutex);
         device->bcn_gpu_inflight -= total;
         simple_mtx_unlock(&device->bcn_gpu_mutex);
         return false;
      }
      dsai.descriptorPool = pool;
      if (device->dispatch_table.AllocateDescriptorSets(device->dispatch_handle,
            &dsai, &set) != VK_SUCCESS) {
         device->dispatch_table.DestroyDescriptorPool(device->dispatch_handle, pool, NULL);
         wrapper_bcn_free_buffer(device, srcb);
         wrapper_bcn_free_buffer(device, dstb);
         simple_mtx_lock(&device->bcn_gpu_mutex);
         device->bcn_gpu_inflight -= total;
         simple_mtx_unlock(&device->bcn_gpu_mutex);
         return false;
      }
"""
    allocation_new = """      VkDescriptorSet set;
      if (wrapper_bcn_alloc_set(wcb, &set) != VK_SUCCESS) {
         wrapper_bcn_free_buffer(device, srcb);
         wrapper_bcn_free_buffer(device, dstb);
         simple_mtx_lock(&device->bcn_gpu_mutex);
         device->bcn_gpu_inflight -= total;
         simple_mtx_unlock(&device->bcn_gpu_mutex);
         return false;
      }
"""
    text = replace_once(text, allocation_old, allocation_new, "BCn descriptor allocation")

    loop_old = """   for (uint32_t i = 0; i < regionCount; i++) {
      VkBufferImageCopy region = pRegions[i];"""
    loop_new = """   bool pipeline_bound = false;
   for (uint32_t i = 0; i < regionCount; i++) {
      VkBufferImageCopy region = pRegions[i];"""
    # This shape is unique inside wrapper_bcn_gpu_copy in the pinned source.
    text = replace_once(text, loop_old, loop_new, "BCn batch pipeline state")

    bind_old = """      device->dispatch_table.CmdBindPipeline(wcb->dispatch_handle,
         VK_PIPELINE_BIND_POINT_COMPUTE, device->bcn_pipeline);
"""
    bind_new = """      if (!pipeline_bound) {
         device->dispatch_table.CmdBindPipeline(wcb->dispatch_handle,
            VK_PIPELINE_BIND_POINT_COMPUTE, device->bcn_pipeline);
         pipeline_bound = true;
      }
"""
    text = replace_once(text, bind_old, bind_new, "BCn pipeline bind batching")

    # Descriptor pools are no longer owned by one transient destination buffer.
    text = replace_once(text, "      dstb->desc_pool = pool;\n", "", "legacy BCn descriptor-pool ownership")
    return text


def verify_existing_narrow_formats(text: str) -> None:
    required = (
        "case VK_FORMAT_BC4_UNORM_BLOCK:\n         return VK_FORMAT_R8_UNORM;",
        "case VK_FORMAT_BC4_SNORM_BLOCK:\n         return VK_FORMAT_R8_SNORM;",
        "case VK_FORMAT_BC5_UNORM_BLOCK:\n          return VK_FORMAT_R8G8_UNORM;",
        "case VK_FORMAT_BC5_SNORM_BLOCK:\n         return VK_FORMAT_R8G8_SNORM;",
    )
    missing = [entry for entry in required if entry not in text]
    if missing:
        raise RuntimeError("BC4/BC5 narrow surrogate assertion failed; upstream behavior changed")


def main() -> None:
    for path in (DEVICE, PRIVATE, BCDEC):
        if not path.is_file():
            raise RuntimeError(f"missing Mesa wrapper source: {path}")

    bcdec = BCDEC.read_text()
    verify_existing_narrow_formats(bcdec)

    private = PRIVATE.read_text()
    device = DEVICE.read_text()
    if "struct wrapper_bcn_pool" in private or "wrapper_bcn_alloc_set" in device:
        raise RuntimeError("BCn descriptor optimization already appears to be applied")

    PRIVATE.write_text(transform_private(private))
    DEVICE.write_text(transform_device(device))
    print("Applied BCn optimizations: narrow-format guard, descriptor recycling, pipeline-bind batching")


if __name__ == "__main__":
    main()
