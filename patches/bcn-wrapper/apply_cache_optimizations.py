#!/usr/bin/env python3
"""Add bounded/correct BCn disk-cache handling and gated telemetry."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = root / "src/vulkan/wrapper/wrapper_bcdec.c"
text = path.read_text()


def once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


once(
    "#include <unistd.h>\n",
    "#include <unistd.h>\n#include <dirent.h>\n#include <sys/stat.h>\n#include <utime.h>\n",
    "cache support includes",
)

helpers = r'''
#define WRAPPER_BCN_CACHE_DEFAULT_MB 512
#define WRAPPER_BCN_CACHE_DEFAULT_MIN_KB 32
#define WRAPPER_BCN_CACHE_PRUNE_INTERVAL 64
#define WRAPPER_BCN_CACHE_KEY_VERSION 2

struct wrapper_bcn_cache_entry {
   char *path;
   off_t size;
   time_t mtime;
};

struct wrapper_bcn_cache_stats {
   uint64_t hits, misses, writes, evictions;
   uint64_t bytes_read, bytes_written, bytes_evicted;
};

static pthread_mutex_t wrapper_bcn_cache_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct wrapper_bcn_cache_stats wrapper_bcn_cache_stats;
static uint64_t wrapper_bcn_cache_write_count;
static int wrapper_bcn_telemetry = -1;

static int
wrapper_bcn_telemetry_enabled(void)
{
   if (wrapper_bcn_telemetry == -1)
      wrapper_bcn_telemetry = getenv("WRAPPER_BCN_TELEMETRY") ?
         atoi(getenv("WRAPPER_BCN_TELEMETRY")) : 0;
   return wrapper_bcn_telemetry;
}

static size_t
wrapper_bcn_cache_budget_bytes(void)
{
   static size_t budget = SIZE_MAX;
   if (budget == SIZE_MAX) {
      const char *env = getenv("WRAPPER_BCN_CACHE_BUDGET_MB");
      long long mb = env ? atoll(env) : WRAPPER_BCN_CACHE_DEFAULT_MB;
      /* 0 explicitly requests the legacy unlimited-cache behavior. */
      budget = mb <= 0 ? 0 : (size_t)mb * 1024u * 1024u;
   }
   return budget;
}

static bool
wrapper_bcn_cache_admit(VkFormat format, size_t output_size)
{
   const char *env = getenv("WRAPPER_BCN_CACHE_MIN_KB");
   long long kb = env ? atoll(env) : WRAPPER_BCN_CACHE_DEFAULT_MIN_KB;
   if (kb <= 0)
      return true;

   size_t threshold = (size_t)kb * 1024u;
   VkFormat target = get_format_for_bcn(format);
   /* ASTC encode and BC6H/BC7 decode are expensive enough to retain smaller
    * entries; cheap BC4/BC5 decodes need more reuse value to justify I/O. */
   if (is_astc(target) || format == VK_FORMAT_BC6H_SFLOAT_BLOCK ||
       format == VK_FORMAT_BC6H_UFLOAT_BLOCK ||
       format == VK_FORMAT_BC7_UNORM_BLOCK ||
       format == VK_FORMAT_BC7_SRGB_BLOCK)
      threshold /= 4;
   return output_size >= threshold;
}

static void
wrapper_bcn_cache_note_lookup(bool hit, size_t bytes)
{
   if (!wrapper_bcn_telemetry_enabled())
      return;
   struct wrapper_bcn_cache_stats snapshot;
   bool log_now;
   pthread_mutex_lock(&wrapper_bcn_cache_mutex);
   if (hit) {
      wrapper_bcn_cache_stats.hits++;
      wrapper_bcn_cache_stats.bytes_read += bytes;
   } else {
      wrapper_bcn_cache_stats.misses++;
   }
   uint64_t lookups = wrapper_bcn_cache_stats.hits + wrapper_bcn_cache_stats.misses;
   log_now = (lookups % 128u) == 0;
   snapshot = wrapper_bcn_cache_stats;
   pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
   if (log_now) {
      WRAPPER_LOG(bcn,
         "BCn cache: hits=%llu misses=%llu hit-rate=%llu%% read=%lluMiB written=%lluMiB evicted=%lluMiB entries-evicted=%llu",
         (unsigned long long)snapshot.hits,
         (unsigned long long)snapshot.misses,
         (unsigned long long)(lookups ? snapshot.hits * 100u / lookups : 0),
         (unsigned long long)(snapshot.bytes_read / (1024u * 1024u)),
         (unsigned long long)(snapshot.bytes_written / (1024u * 1024u)),
         (unsigned long long)(snapshot.bytes_evicted / (1024u * 1024u)),
         (unsigned long long)snapshot.evictions);
   }
}

static void
wrapper_bcn_cache_note_write(size_t bytes)
{
   if (!wrapper_bcn_telemetry_enabled())
      return;
   pthread_mutex_lock(&wrapper_bcn_cache_mutex);
   wrapper_bcn_cache_stats.writes++;
   wrapper_bcn_cache_stats.bytes_written += bytes;
   pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
}

static int
wrapper_bcn_cache_entry_cmp(const void *a, const void *b)
{
   const struct wrapper_bcn_cache_entry *ea = a, *eb = b;
   if (ea->mtime < eb->mtime) return -1;
   if (ea->mtime > eb->mtime) return 1;
   return strcmp(ea->path, eb->path);
}

static void
wrapper_bcn_cache_prune(const char *dir_path, const char *exe,
                        const char *protected_path)
{
   size_t budget = wrapper_bcn_cache_budget_bytes();
   if (!budget)
      return;

   pthread_mutex_lock(&wrapper_bcn_cache_mutex);
   wrapper_bcn_cache_write_count++;
   if (wrapper_bcn_cache_write_count != 1 &&
       (wrapper_bcn_cache_write_count % WRAPPER_BCN_CACHE_PRUNE_INTERVAL) != 0) {
      pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
      return;
   }

   DIR *dir = opendir(dir_path);
   if (!dir) {
      pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
      return;
   }

   struct wrapper_bcn_cache_entry *entries = NULL;
   size_t count = 0, capacity = 0, total = 0;
   size_t exe_len = strlen(exe);
   struct dirent *de;
   while ((de = readdir(dir)) != NULL) {
      size_t name_len = strlen(de->d_name);
      if (name_len <= exe_len + 7 || strncmp(de->d_name, exe, exe_len) != 0 ||
          de->d_name[exe_len] != '_' || strcmp(de->d_name + name_len - 6, ".cache") != 0)
         continue;
      char *full = malloc(strlen(dir_path) + name_len + 2);
      if (!full)
         continue;
      sprintf(full, "%s/%s", dir_path, de->d_name);
      struct stat st;
      if (stat(full, &st) != 0 || !S_ISREG(st.st_mode)) {
         free(full);
         continue;
      }
      if (count == capacity) {
         size_t next = capacity ? capacity * 2 : 64;
         void *grown = realloc(entries, next * sizeof(*entries));
         if (!grown) {
            free(full);
            break;
         }
         entries = grown;
         capacity = next;
      }
      entries[count++] = (struct wrapper_bcn_cache_entry) {
         .path = full, .size = st.st_size, .mtime = st.st_mtime,
      };
      total += st.st_size > 0 ? (size_t)st.st_size : 0;
   }
   closedir(dir);

   qsort(entries, count, sizeof(*entries), wrapper_bcn_cache_entry_cmp);
   for (size_t i = 0; total > budget && i < count; i++) {
      struct wrapper_bcn_cache_entry *entry = &entries[i];
      if (protected_path && strcmp(entry->path, protected_path) == 0)
         continue;
      if (unlink(entry->path) == 0) {
         size_t bytes = entry->size > 0 ? (size_t)entry->size : 0;
         total = total > bytes ? total - bytes : 0;
         if (wrapper_bcn_telemetry_enabled()) {
            wrapper_bcn_cache_stats.evictions++;
            wrapper_bcn_cache_stats.bytes_evicted += bytes;
         }
      }
   }
   for (size_t i = 0; i < count; i++)
      free(entries[i].path);
   free(entries);
   pthread_mutex_unlock(&wrapper_bcn_cache_mutex);
}

static XXH64_hash_t
wrapper_bcn_cache_hash(const char *src, int block_x, int block_x_src,
                       int block_y, int block_size, VkFormat format,
                       int w, int h, int src_w, int output_size)
{
   XXH64_state_t state;
   XXH64_reset(&state, 0);
   /* Hash only real BC blocks, not bufferRowLength padding that may contain
    * unstable bytes and should not affect the decoded texture. */
   for (int y = 0; y < block_y; y++)
      XXH64_update(&state, src + (size_t)y * block_x_src * block_size,
                   (size_t)block_x * block_size);
   uint64_t meta[] = {
      WRAPPER_BCN_CACHE_KEY_VERSION,
      (uint32_t)format, (uint32_t)w, (uint32_t)h, (uint32_t)src_w,
      (uint32_t)get_format_for_bcn(format), (uint32_t)output_size,
   };
   return XXH64(meta, sizeof(meta), XXH64_digest(&state));
}
'''

once(
    '#define WRAPPER_CACHE_DIR "/data/data/app.gamenative/files/imagefs/usr/cache"\n',
    '#define WRAPPER_CACHE_DIR "/data/data/app.gamenative/files/imagefs/usr/cache"\n' + helpers,
    "cache helpers",
)

old_read = r'''   /* Optional disk cache of the transcoded output, keyed by a hash of the
    * compressed source. Skips decode+encode on subsequent loads. Only touched
    * when explicitly enabled, so there is zero overhead by default. */
   char *cache_filename = NULL;
   if (wrapper_use_bcn_cache) {
      CREATE_FOLDER(wrapper_cache_path, 0700);
      XXH64_hash_t hash = XXH64(src, compressed_size, 0);
      asprintf(&cache_filename, "%s/%s_%llu.cache", wrapper_cache_path,
         get_executable_name(), (unsigned long long)hash);

      if (access(cache_filename, F_OK) == 0) {
         FILE *fp = fopen(cache_filename, "rb");
         if (fp) {
            size_t length = fread(dst, 1, uncompressed_size, fp);
            fclose(fp);
            if (length == uncompressed_size) {
               WRAPPER_LOG(bcn, "Restored texture %s from cache", cache_filename);
               free(cache_filename);
               return;
            }
            unlink(cache_filename);
         }
      }
   }
'''

new_read = r'''   /* Optional disk cache. v2 keys include dimensions, source row layout,
    * BC format and output format, and hash only real blocks (not row padding).
    * Small cheap decodes are not admitted by default to avoid filesystem churn. */
   char *cache_filename = NULL;
   bool cache_eligible = wrapper_use_bcn_cache &&
      wrapper_bcn_cache_admit(format, (size_t)uncompressed_size);
   if (cache_eligible) {
      CREATE_FOLDER(wrapper_cache_path, 0700);
      XXH64_hash_t hash = wrapper_bcn_cache_hash(src, block_x, block_x_src,
         block_y, block_size, format, w, h, src_w, uncompressed_size);
      asprintf(&cache_filename, "%s/%s_v%d_%llu.cache", wrapper_cache_path,
         get_executable_name(), WRAPPER_BCN_CACHE_KEY_VERSION,
         (unsigned long long)hash);

      bool hit = false;
      struct stat st;
      if (cache_filename && stat(cache_filename, &st) == 0 &&
          st.st_size == uncompressed_size) {
         FILE *fp = fopen(cache_filename, "rb");
         if (fp) {
            size_t length = fread(dst, 1, uncompressed_size, fp);
            fclose(fp);
            if (length == (size_t)uncompressed_size) {
               hit = true;
               utime(cache_filename, NULL); /* LRU touch */
               WRAPPER_LOG(bcn, "Restored texture %s from cache", cache_filename);
            }
         }
      } else if (cache_filename && access(cache_filename, F_OK) == 0) {
         unlink(cache_filename); /* stale/corrupt entry */
      }
      wrapper_bcn_cache_note_lookup(hit, hit ? (size_t)uncompressed_size : 0);
      if (hit) {
         free(cache_filename);
         return;
      }
   }
'''
once(old_read, new_read, "cache lookup")

old_write = r'''   if (wrapper_use_bcn_cache && cache_filename) {
      FILE *fp = fopen(cache_filename, "wb");
      if (fp) {
         size_t length = fwrite(dst, 1, uncompressed_size, fp);
         fclose(fp);
         if (length == uncompressed_size)
            WRAPPER_LOG(bcn, "Saved texture %s to cache", cache_filename);
         else {
            WRAPPER_LOG(bcn, "Failed to save texture %s to cache", cache_filename);
            unlink(cache_filename);
         }
      }
   }

   free(cache_filename);
'''

new_write = r'''   if (cache_eligible && cache_filename) {
      char *tmp = NULL;
      asprintf(&tmp, "%s.tmp.XXXXXX", cache_filename);
      int fd = tmp ? mkstemp(tmp) : -1;
      FILE *fp = fd >= 0 ? fdopen(fd, "wb") : NULL;
      if (fp) {
         size_t length = fwrite(dst, 1, uncompressed_size, fp);
         int close_result = fclose(fp);
         if (length == (size_t)uncompressed_size && close_result == 0 &&
             rename(tmp, cache_filename) == 0) {
            WRAPPER_LOG(bcn, "Saved texture %s to cache", cache_filename);
            wrapper_bcn_cache_note_write((size_t)uncompressed_size);
            wrapper_bcn_cache_prune(wrapper_cache_path, get_executable_name(),
                                    cache_filename);
         } else {
            WRAPPER_LOG(bcn, "Failed to save texture %s to cache", cache_filename);
            unlink(tmp);
         }
      } else {
         if (fd >= 0) close(fd);
         if (tmp) unlink(tmp);
      }
      free(tmp);
   }

   free(cache_filename);
'''
once(old_write, new_write, "cache write")

if "WRAPPER_BCN_CACHE_BUDGET_MB" not in text or "WRAPPER_BCN_TELEMETRY" not in text:
    raise RuntimeError("cache optimization postcondition failed")

path.write_text(text)
print("Applied BCn cache v2: corrected keys, atomic writes, byte budget/LRU pruning, telemetry")
