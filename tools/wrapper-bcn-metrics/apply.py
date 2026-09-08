#!/usr/bin/env python3
"""Add low-overhead BCn performance metrics snapshots to transformed wrapper-25."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
private_path = root / "src/vulkan/wrapper/wrapper_private.h"
device_path = root / "src/vulkan/wrapper/wrapper_device.c"
header_path = root / "src/vulkan/wrapper/wrapper_bcdec.h"
bcdec_path = root / "src/vulkan/wrapper/wrapper_bcdec.c"
private = private_path.read_text()
device = device_path.read_text()
header = header_path.read_text()
bcdec = bcdec_path.read_text()


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


# Export a lock-safe cache snapshot so the device-side metrics writer can emit
# one coherent report without duplicating the disk-cache implementation.
header = one(
    header,
    """int
is_emulated_bcn(struct wrapper_physical_device *pdev, VkFormat format);

void
decompress_bcn_format""",
    """int
is_emulated_bcn(struct wrapper_physical_device *pdev, VkFormat format);

struct wrapper_bcn_cache_stats_snapshot {
   uint64_t hits, misses, writes, evictions;
   uint64_t bytes_read, bytes_written, bytes_evicted;
};

void
wrapper_bcn_cache_get_stats(struct wrapper_bcn_cache_stats_snapshot *out);

void
decompress_bcn_format""",
    "cache stats public snapshot declaration",
)

bcdec = one(
    bcdec,
    """static int
wrapper_bcn_telemetry_enabled(void)
{
   if (wrapper_bcn_telemetry == -1)
      wrapper_bcn_telemetry = getenv(\"WRAPPER_BCN_TELEMETRY\") ?
         atoi(getenv(\"WRAPPER_BCN_TELEMETRY\")) : 0;
   return wrapper_bcn_telemetry;
}
""",
    """static int
wrapper_bcn_telemetry_enabled(void)
{
   if (wrapper_bcn_telemetry == -1) {
      int explicit_telemetry = getenv(\"WRAPPER_BCN_TELEMETRY\") ?
         atoi(getenv(\"WRAPPER_BCN_TELEMETRY\")) : 0;
      int perf_metrics = getenv(\"WRAPPER_BCN_METRICS\") ?
         atoi(getenv(\"WRAPPER_BCN_METRICS\")) : 0;
      wrapper_bcn_telemetry = explicit_telemetry || perf_metrics;
   }
   return wrapper_bcn_telemetry;
}

void
wrapper_bcn_cache_get_stats(struct wrapper_bcn_cache_stats_snapshot *out)
{
   if (!out)
      return;
   memset(out, 0, sizeof(*out));
   pthread_mutex_lock(&wrapper_bcn_cache_mutex);
   out->hits = wrapper_bcn_cache_stats.hits;
   out->misses = wrapper_bcn_cache_stats.misses;
   out->writes = wrapper_bcn_cache_stats.writes;
   out->evictions = wrapper_bcn_cache_stats.evictions;
   out->bytes_read = wrapper_bcn_cache_stats.bytes_read;
   out->bytes_written = wrapper_bcn_cache_stats.bytes_written;
   out->bytes_evicted = wrapper_bcn_cache_stats.bytes_evicted;
   pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
}
""",
    "cache stats snapshot implementation",
)

# Per-device counters: no process globals, so multiple wrapper devices cannot
# contaminate each other's performance delta report.
private = one(
    private,
    """   VkDeviceSize bcn_buffer_cache_limit;

   /* Private queue and synchronous command buffer for host query reset. */""",
    """   VkDeviceSize bcn_buffer_cache_limit;

   /* Low-overhead BCn performance counters. File snapshots are gated by
    * WRAPPER_BCN_METRICS and written only periodically / at teardown. */
   simple_mtx_t bcn_metrics_mutex;
   bool bcn_metrics_enabled;
   const char *bcn_metrics_path;
   uint64_t bcn_metrics_events;
   uint64_t bcn_cpu_calls, bcn_cpu_total_ns, bcn_cpu_max_ns;
   uint64_t bcn_cpu_input_bytes, bcn_cpu_output_bytes;
   uint64_t bcn_gpu_jobs, bcn_gpu_regions, bcn_gpu_dispatches, bcn_gpu_fallbacks;
   uint64_t bcn_gpu_input_bytes, bcn_gpu_output_bytes, bcn_gpu_record_ns;
   uint64_t bcn_descriptor_pool_creates, bcn_descriptor_set_allocs;
   uint64_t bcn_staging_reuse_hits, bcn_staging_reuse_misses;
   uint64_t bcn_staging_new_allocs, bcn_staging_reused_bytes;

   /* Private queue and synchronous command buffer for host query reset. */""",
    "device performance counters",
)

# Clock + snapshot helpers are inserted near the top so teardown and all BCn
# helpers can call them without forward declarations.
device = one(device, "#include <fcntl.h>\n", "#include <fcntl.h>\n#include <time.h>\n", "metrics time include")
anchor = """static void
wrapper_filter_enabled_extensions(const struct wrapper_device *device,
"""
helpers = r'''static uint64_t
wrapper_bcn_metrics_now_ns(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint64_t
wrapper_bcn_compressed_bytes(VkFormat format, uint32_t w, uint32_t h)
{
   uint32_t block_bytes = 16;
   switch (format) {
   case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
   case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
   case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
   case VK_FORMAT_BC1_RGBA_SRGB_BLOCK:
   case VK_FORMAT_BC4_UNORM_BLOCK:
   case VK_FORMAT_BC4_SNORM_BLOCK:
      block_bytes = 8;
      break;
   default:
      break;
   }
   return (uint64_t)((w + 3) / 4) * ((h + 3) / 4) * block_bytes;
}

static void
wrapper_bcn_metrics_write_snapshot(struct wrapper_device *device, bool force)
{
   if (!device || !device->bcn_metrics_enabled || !device->bcn_metrics_path ||
       !device->bcn_metrics_path[0])
      return;

   struct {
      uint64_t events;
      uint64_t cpu_calls, cpu_total_ns, cpu_max_ns, cpu_in, cpu_out;
      uint64_t gpu_jobs, gpu_regions, gpu_dispatches, gpu_fallbacks;
      uint64_t gpu_in, gpu_out, gpu_record_ns;
      uint64_t pool_creates, set_allocs;
      uint64_t reuse_hits, reuse_misses, new_allocs, reused_bytes;
   } s;

   simple_mtx_lock(&device->bcn_metrics_mutex);
   if (!force && device->bcn_metrics_events != 1 &&
       (device->bcn_metrics_events % 128u) != 0) {
      simple_mtx_unlock(&device->bcn_metrics_mutex);
      return;
   }
   s.events = device->bcn_metrics_events;
   s.cpu_calls = device->bcn_cpu_calls;
   s.cpu_total_ns = device->bcn_cpu_total_ns;
   s.cpu_max_ns = device->bcn_cpu_max_ns;
   s.cpu_in = device->bcn_cpu_input_bytes;
   s.cpu_out = device->bcn_cpu_output_bytes;
   s.gpu_jobs = device->bcn_gpu_jobs;
   s.gpu_regions = device->bcn_gpu_regions;
   s.gpu_dispatches = device->bcn_gpu_dispatches;
   s.gpu_fallbacks = device->bcn_gpu_fallbacks;
   s.gpu_in = device->bcn_gpu_input_bytes;
   s.gpu_out = device->bcn_gpu_output_bytes;
   s.gpu_record_ns = device->bcn_gpu_record_ns;
   s.pool_creates = device->bcn_descriptor_pool_creates;
   s.set_allocs = device->bcn_descriptor_set_allocs;
   s.reuse_hits = device->bcn_staging_reuse_hits;
   s.reuse_misses = device->bcn_staging_reuse_misses;
   s.new_allocs = device->bcn_staging_new_allocs;
   s.reused_bytes = device->bcn_staging_reused_bytes;
   simple_mtx_unlock(&device->bcn_metrics_mutex);

   struct wrapper_bcn_cache_stats_snapshot cache;
   wrapper_bcn_cache_get_stats(&cache);

   char tmp[1024];
   if (snprintf(tmp, sizeof(tmp), "%s.tmp", device->bcn_metrics_path) >= (int)sizeof(tmp))
      return;
   FILE *f = fopen(tmp, "w");
   if (!f)
      return;

   uint64_t lookups = cache.hits + cache.misses;
   const char *appid = getenv("WRAPPER_BCN_METRICS_APPID");
   const char *astc = getenv("WRAPPER_ASTC_BLOCK");
   const char *gpu = getenv("WRAPPER_BCN_GPU");
   const char *disk_cache = getenv("WRAPPER_USE_BCN_CACHE");
   const char *cache_budget = getenv("WRAPPER_BCN_CACHE_BUDGET_MB");
   const char *buffer_budget = getenv("WRAPPER_BCN_BUFFER_CACHE_MB");

   fprintf(f, "BCN_PERF_VERSION=1\n");
   fprintf(f, "app_id=%s\n", appid ? appid : "unknown");
   fprintf(f, "emulation_mode=%d\n", device->physical ? device->physical->emulate_bcn : -1);
   fprintf(f, "transcoder=%s\n", (gpu && atoi(gpu)) ? "gpu" : "cpu");
   fprintf(f, "astc_block=%s\n", astc ? astc : "4x4");
   fprintf(f, "cache_enabled=%d\n", (disk_cache && atoi(disk_cache)) ? 1 : 0);
   fprintf(f, "cache_budget_mb=%s\n", cache_budget ? cache_budget : "512");
   fprintf(f, "buffer_cache_budget_mb=%s\n", buffer_budget ? buffer_budget : "64");
   fprintf(f, "events=%llu\n", (unsigned long long)s.events);
   fprintf(f, "cpu_calls=%llu\n", (unsigned long long)s.cpu_calls);
   fprintf(f, "cpu_total_ns=%llu\n", (unsigned long long)s.cpu_total_ns);
   fprintf(f, "cpu_avg_ns=%llu\n", (unsigned long long)(s.cpu_calls ? s.cpu_total_ns / s.cpu_calls : 0));
   fprintf(f, "cpu_max_ns=%llu\n", (unsigned long long)s.cpu_max_ns);
   fprintf(f, "cpu_input_bytes=%llu\n", (unsigned long long)s.cpu_in);
   fprintf(f, "cpu_output_bytes=%llu\n", (unsigned long long)s.cpu_out);
   fprintf(f, "gpu_jobs=%llu\n", (unsigned long long)s.gpu_jobs);
   fprintf(f, "gpu_regions=%llu\n", (unsigned long long)s.gpu_regions);
   fprintf(f, "gpu_dispatches=%llu\n", (unsigned long long)s.gpu_dispatches);
   fprintf(f, "gpu_fallbacks=%llu\n", (unsigned long long)s.gpu_fallbacks);
   fprintf(f, "gpu_input_bytes=%llu\n", (unsigned long long)s.gpu_in);
   fprintf(f, "gpu_output_bytes=%llu\n", (unsigned long long)s.gpu_out);
   fprintf(f, "gpu_record_cpu_ns=%llu\n", (unsigned long long)s.gpu_record_ns);
   fprintf(f, "gpu_record_cpu_avg_ns=%llu\n", (unsigned long long)(s.gpu_jobs ? s.gpu_record_ns / s.gpu_jobs : 0));
   fprintf(f, "descriptor_pool_creates=%llu\n", (unsigned long long)s.pool_creates);
   fprintf(f, "descriptor_set_allocs=%llu\n", (unsigned long long)s.set_allocs);
   fprintf(f, "staging_reuse_hits=%llu\n", (unsigned long long)s.reuse_hits);
   fprintf(f, "staging_reuse_misses=%llu\n", (unsigned long long)s.reuse_misses);
   fprintf(f, "staging_new_allocs=%llu\n", (unsigned long long)s.new_allocs);
   fprintf(f, "staging_reused_bytes=%llu\n", (unsigned long long)s.reused_bytes);
   fprintf(f, "staging_free_pool_bytes=%llu\n",
           (unsigned long long)device->bcn_buffer_cache_bytes);
   fprintf(f, "cache_hits=%llu\n", (unsigned long long)cache.hits);
   fprintf(f, "cache_misses=%llu\n", (unsigned long long)cache.misses);
   fprintf(f, "cache_hit_pct=%llu\n", (unsigned long long)(lookups ? cache.hits * 100u / lookups : 0));
   fprintf(f, "cache_read_bytes=%llu\n", (unsigned long long)cache.bytes_read);
   fprintf(f, "cache_write_bytes=%llu\n", (unsigned long long)cache.bytes_written);
   fprintf(f, "cache_evicted_bytes=%llu\n", (unsigned long long)cache.bytes_evicted);
   fprintf(f, "cache_evictions=%llu\n", (unsigned long long)cache.evictions);
   fprintf(f, "decodes_avoided=%llu\n", (unsigned long long)cache.hits);
   fprintf(f, "note_gpu_timing=gpu_record_cpu_ns measures CPU command recording/setup only, not GPU execution\n");

   if (fclose(f) == 0)
      rename(tmp, device->bcn_metrics_path);
   else
      unlink(tmp);
}

static void
wrapper_bcn_metrics_event(struct wrapper_device *device)
{
   if (!device->bcn_metrics_enabled)
      return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_metrics_events++;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
   wrapper_bcn_metrics_write_snapshot(device, false);
}

static void
wrapper_bcn_metrics_note_cpu(struct wrapper_device *device, VkFormat format,
                             uint32_t w, uint32_t h, uint64_t output_bytes,
                             uint64_t elapsed_ns)
{
   if (!device->bcn_metrics_enabled)
      return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_cpu_calls++;
   device->bcn_cpu_total_ns += elapsed_ns;
   if (elapsed_ns > device->bcn_cpu_max_ns)
      device->bcn_cpu_max_ns = elapsed_ns;
   device->bcn_cpu_input_bytes += wrapper_bcn_compressed_bytes(format, w, h);
   device->bcn_cpu_output_bytes += output_bytes;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
   wrapper_bcn_metrics_event(device);
}

static void
wrapper_bcn_metrics_note_gpu_job(struct wrapper_device *device,
                                 uint32_t regions, bool success,
                                 uint64_t record_ns)
{
   if (!device->bcn_metrics_enabled)
      return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_gpu_jobs++;
   device->bcn_gpu_record_ns += record_ns;
   if (!success)
      device->bcn_gpu_fallbacks++;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
   wrapper_bcn_metrics_event(device);
}

static void
wrapper_bcn_metrics_note_gpu_region(struct wrapper_device *device,
                                    uint64_t input_bytes, uint64_t output_bytes)
{
   if (!device->bcn_metrics_enabled)
      return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_gpu_regions++;
   device->bcn_gpu_dispatches++;
   device->bcn_gpu_input_bytes += input_bytes;
   device->bcn_gpu_output_bytes += output_bytes;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
}

static void
wrapper_bcn_metrics_note_descriptor_pool(struct wrapper_device *device)
{
   if (!device->bcn_metrics_enabled) return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_descriptor_pool_creates++;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
}

static void
wrapper_bcn_metrics_note_descriptor_set(struct wrapper_device *device)
{
   if (!device->bcn_metrics_enabled) return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   device->bcn_descriptor_set_allocs++;
   simple_mtx_unlock(&device->bcn_metrics_mutex);
}

static void
wrapper_bcn_metrics_note_staging_acquire(struct wrapper_device *device,
                                         bool reused, uint64_t bytes)
{
   if (!device->bcn_metrics_enabled) return;
   simple_mtx_lock(&device->bcn_metrics_mutex);
   if (reused) {
      device->bcn_staging_reuse_hits++;
      device->bcn_staging_reused_bytes += bytes;
   } else {
      device->bcn_staging_reuse_misses++;
      device->bcn_staging_new_allocs++;
   }
   simple_mtx_unlock(&device->bcn_metrics_mutex);
}

static void
wrapper_filter_enabled_extensions(const struct wrapper_device *device,
'''
device = one(device, anchor, helpers, "metrics helpers")

# Initialise the gate/path before any BCn activity. The launcher supplies the
# stable app-owned path; unknown wrappers can simply ignore these env vars.
device = one(
    device,
    """   simple_mtx_init(&device->resource_mutex, mtx_plain);
   simple_mtx_init(&device->bcn_gpu_mutex, mtx_plain);
   simple_mtx_init(&device->query_reset_mutex, mtx_plain);
""",
    """   simple_mtx_init(&device->resource_mutex, mtx_plain);
   simple_mtx_init(&device->bcn_gpu_mutex, mtx_plain);
   simple_mtx_init(&device->bcn_metrics_mutex, mtx_plain);
   simple_mtx_init(&device->query_reset_mutex, mtx_plain);
   device->bcn_metrics_enabled = getenv("WRAPPER_BCN_METRICS") ?
      atoi(getenv("WRAPPER_BCN_METRICS")) != 0 : false;
   device->bcn_metrics_path = getenv("WRAPPER_BCN_METRICS_FILE");
   if (!device->bcn_metrics_path || !device->bcn_metrics_path[0])
      device->bcn_metrics_enabled = false;
""",
    "metrics initialization",
)

# Ensure the final snapshot survives a normal device teardown.
device = one(
    device,
    """wrapper_DestroyDevice(VkDevice _device, const VkAllocationCallbacks* pAllocator)
{
   VK_FROM_HANDLE(wrapper_device, device, _device);
""",
    """wrapper_DestroyDevice(VkDevice _device, const VkAllocationCallbacks* pAllocator)
{
   VK_FROM_HANDLE(wrapper_device, device, _device);
   wrapper_bcn_metrics_write_snapshot(device, true);
""",
    "metrics teardown flush",
)

# Descriptor churn counters quantify the improvement from the chunked pool.
device = one(
    device,
    """      p->next = wcb->bcn_pools;
      wcb->bcn_pools = p;
   }

   VkDescriptorSetAllocateInfo ai = {""",
    """      p->next = wcb->bcn_pools;
      wcb->bcn_pools = p;
      wrapper_bcn_metrics_note_descriptor_pool(device);
   }

   VkDescriptorSetAllocateInfo ai = {""",
    "descriptor pool metric",
)
device = one(
    device,
    """   VkResult r = dt->AllocateDescriptorSets(device->dispatch_handle, &ai, out);
   if (r == VK_SUCCESS)
      p->remaining--;
   return r;
}""",
    """   VkResult r = dt->AllocateDescriptorSets(device->dispatch_handle, &ai, out);
   if (r == VK_SUCCESS) {
      p->remaining--;
      wrapper_bcn_metrics_note_descriptor_set(device);
   }
   return r;
}""",
    "descriptor set metric",
)

# Buffer cache hit/miss/new-allocation counters. The staging transformer already
# guarantees the buffer is safe to reuse before it reaches this cache.
device = one(
    device,
    """   simple_mtx_unlock(&device->bcn_gpu_mutex);

   if (!best) {
""",
    """   simple_mtx_unlock(&device->bcn_gpu_mutex);
   bool reused = best != NULL;

   if (!best) {
""",
    "staging reuse classification",
)
device = one(
    device,
    """   best->bcn_inflight = 0;
   list_add(&best->link, &wcb->bcn_buffers);
   return best;
""",
    """   best->bcn_inflight = 0;
   list_add(&best->link, &wcb->bcn_buffers);
   wrapper_bcn_metrics_note_staging_acquire(device, reused, best->size);
   return best;
""",
    "staging acquire metric",
)

# GPU command-record CPU time is measured around the existing GPU recording
# path. It is deliberately not labeled as GPU execution time.
device = one(
    device,
    """   if (fmt_id >= 0 && wrapper_bcn_gpu_ready(device)) {
      if (wrapper_bcn_gpu_copy(wcb, device, wb, dstImage, dstLayout, format,
            fmt_id, regionCount, pRegions))
         return;
      /* else fall through to CPU transcode */
   }
""",
    """   if (fmt_id >= 0 && wrapper_bcn_gpu_ready(device)) {
      uint64_t metric_start = wrapper_bcn_metrics_now_ns();
      bool gpu_ok = wrapper_bcn_gpu_copy(wcb, device, wb, dstImage, dstLayout,
                                         format, fmt_id, regionCount, pRegions);
      wrapper_bcn_metrics_note_gpu_job(device, regionCount, gpu_ok,
         wrapper_bcn_metrics_now_ns() - metric_start);
      if (gpu_ok)
         return;
      /* else fall through to CPU transcode */
   }
""",
    "GPU job timing",
)

device = one(
    device,
    """      device->dispatch_table.CmdDispatch(wcb->dispatch_handle,
         (block_x + 7) / 8, (block_y + 7) / 8, 1);

      VkMemoryBarrier mb2 = {""",
    """      device->dispatch_table.CmdDispatch(wcb->dispatch_handle,
         (block_x + 7) / 8, (block_y + 7) / 8, 1);
      wrapper_bcn_metrics_note_gpu_region(device, src_size, dst_size);

      VkMemoryBarrier mb2 = {""",
    "GPU region counters",
)

# CPU wall time wraps the decode/transcode call, including a disk-cache hit if
# one occurs; that is intentional because this measures the cost seen by the
# upload path and lets cache-enabled/disabled runs be compared directly.
device = one(
    device,
    """      decompress_bcn_format(wb->mapped_address, staging_wb->mapped_address, w, h, src_w, format, offset);
""",
    """      uint64_t metric_start = wrapper_bcn_metrics_now_ns();
      decompress_bcn_format(wb->mapped_address, staging_wb->mapped_address,
                            w, h, src_w, format, offset);
      wrapper_bcn_metrics_note_cpu(device, format, w, h, upload_size,
         wrapper_bcn_metrics_now_ns() - metric_start);
""",
    "CPU transcode timing",
)

for required in (
    "WRAPPER_BCN_METRICS_FILE",
    "gpu_record_cpu_ns",
    "staging_reuse_hits",
    "wrapper_bcn_cache_get_stats",
):
    if required not in device and required not in bcdec and required not in header:
        raise RuntimeError(f"metrics postcondition missing: {required}")

private_path.write_text(private)
device_path.write_text(device)
header_path.write_text(header)
bcdec_path.write_text(bcdec)
print("Applied BCn performance metrics: CPU timing, GPU record timing, descriptor/staging/cache counters")
