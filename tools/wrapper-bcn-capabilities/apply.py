#!/usr/bin/env python3
"""Add capability-driven BC4/BC5 surrogate selection and RGBA fallback.

This transform runs after the descriptor/cache/staging/metrics transforms. BC4
and BC5 keep their compact R8/RG8 storage when the base driver supports the
exact image configuration. If not, they expand to a semantically equivalent
RGBA8 UNORM/SNORM surrogate instead of assuming narrow-format support.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
private_path = root / "src/vulkan/wrapper/wrapper_private.h"
header_path = root / "src/vulkan/wrapper/wrapper_bcdec.h"
bcdec_path = root / "src/vulkan/wrapper/wrapper_bcdec.c"
device_path = root / "src/vulkan/wrapper/wrapper_device.c"
physical_path = root / "src/vulkan/wrapper/wrapper_physical_device.c"

private = private_path.read_text()
header = header_path.read_text()
bcdec = bcdec_path.read_text()
device = device_path.read_text()
physical = physical_path.read_text()


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def n(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} matches, found {count}")
    return text.replace(old, new)


# Remember the actual surrogate chosen for each emulated image. The public
# VkImageCreateInfo copy remains the application's requested BC format so other
# wrapper bookkeeping keeps the original semantics.
private = one(
    private,
    """   VkImage dispatch_handle;
   VkImageCreateInfo info;

   bool is_emulated_bgra8;
""",
    """   VkImage dispatch_handle;
   VkImageCreateInfo info;
   VkFormat bcn_storage_format;          /* actual base-driver surrogate */

   bool is_emulated_bgra8;
""",
    "image surrogate field",
)

# Decoder APIs that explicitly take the selected target. Keep the legacy APIs
# as wrappers so no unrelated caller is forced to change.
header = one(
    header,
    """size_t
bcn_upload_size(VkFormat bcn_format, int w, int h);
""",
    """size_t
bcn_upload_size(VkFormat bcn_format, int w, int h);

size_t
bcn_upload_size_for_target(VkFormat bcn_format, VkFormat target_format,
                           int w, int h);

VkFormat
get_format_for_bcn_image(struct wrapper_physical_device *pdev,
                         VkFormat bcn_format, VkImageType type,
                         VkImageTiling tiling, VkImageUsageFlags usage,
                         VkImageCreateFlags flags, bool *supported);
""",
    "target-aware upload API",
)
header = one(
    header,
    """void
decompress_bcn_format(void *srcBuffer,
                      void *dstBuffer,
                      int w,
                      int h,
                      int src_w,
                      VkFormat format,
                      int offset);
""",
    """void
decompress_bcn_format(void *srcBuffer,
                      void *dstBuffer,
                      int w,
                      int h,
                      int src_w,
                      VkFormat format,
                      int offset);

void
decompress_bcn_format_to(void *srcBuffer,
                         void *dstBuffer,
                         int w,
                         int h,
                         int src_w,
                         VkFormat format,
                         VkFormat target_format,
                         int offset);
""",
    "target-aware decode API",
)

# A format capability decision must use the base driver rather than the
# wrapper's spoofed BC support. Query the exact image type/tiling/usage/flags.
capability_helpers = r'''
static bool
bcn_is_narrow_surrogate_format(VkFormat format)
{
   switch (format) {
   case VK_FORMAT_BC4_UNORM_BLOCK:
   case VK_FORMAT_BC4_SNORM_BLOCK:
   case VK_FORMAT_BC5_UNORM_BLOCK:
   case VK_FORMAT_BC5_SNORM_BLOCK:
      return true;
   default:
      return false;
   }
}

static VkFormat
bcn_rgba_fallback_format(VkFormat format)
{
   switch (format) {
   case VK_FORMAT_BC4_SNORM_BLOCK:
   case VK_FORMAT_BC5_SNORM_BLOCK:
      return VK_FORMAT_R8G8B8A8_SNORM;
   default:
      return VK_FORMAT_R8G8B8A8_UNORM;
   }
}

VkFormat
get_format_for_bcn_image(struct wrapper_physical_device *pdev,
                         VkFormat bcn_format, VkImageType type,
                         VkImageTiling tiling, VkImageUsageFlags usage,
                         VkImageCreateFlags flags, bool *supported)
{
   VkFormat preferred = get_format_for_bcn(bcn_format);
   if (supported)
      *supported = true;

   if (!bcn_is_narrow_surrogate_format(bcn_format))
      return preferred;

   /* The wrapper strips MUTABLE_FORMAT from emulated BC images before the base
    * create, so query the same flags that the base driver will actually see. */
   VkImageCreateFlags query_flags = flags & ~VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
   VkImageFormatProperties props;
   VkResult result = pdev->dispatch_table.GetPhysicalDeviceImageFormatProperties(
      pdev->dispatch_handle, preferred, type, tiling, usage, query_flags, &props);
   if (result == VK_SUCCESS)
      return preferred;

   VkFormat fallback = bcn_rgba_fallback_format(bcn_format);
   result = pdev->dispatch_table.GetPhysicalDeviceImageFormatProperties(
      pdev->dispatch_handle, fallback, type, tiling, usage, query_flags, &props);
   if (result == VK_SUCCESS) {
      WRAPPER_LOG(info,
         "BCn narrow surrogate %d unsupported for format=%d type=%d tiling=%d usage=0x%x flags=0x%x; using RGBA surrogate %d",
         preferred, bcn_format, type, tiling, usage, query_flags, fallback);
      return fallback;
   }

   if (supported)
      *supported = false;
   WRAPPER_LOG(error,
      "No BCn surrogate supports format=%d type=%d tiling=%d usage=0x%x flags=0x%x (preferred=%d fallback=%d)",
      bcn_format, type, tiling, usage, query_flags, preferred, fallback);
   return preferred;
}
'''
bcdec = one(
    bcdec,
    """VkFormat 
get_decode_format_for_bcn(VkFormat bcn_format)
{""",
    capability_helpers + "\nVkFormat \nget_decode_format_for_bcn(VkFormat bcn_format)\n{",
    "capability helper insertion",
)

# Target-aware upload sizing. RGBA fallback is 4 bytes/texel; narrow R/RG
# remains 1/2 bytes/texel, preserving the intended memory win when supported.
bcdec = one(
    bcdec,
    """size_t
bcn_upload_size(VkFormat bcn_format, int w, int h)
{
   VkFormat img = get_format_for_bcn(bcn_format);
   if (is_astc_8x8(img))
      return (size_t)((w + 7) / 8) * ((h + 7) / 8) * 16;
   if (is_astc_4x4(img))
      return (size_t)((w + 3) / 4) * ((h + 3) / 4) * 16;
   return (size_t)w * h *
      get_texel_size_for_format(get_decode_format_for_bcn(bcn_format));
}
""",
    """size_t
bcn_upload_size_for_target(VkFormat bcn_format, VkFormat target_format,
                           int w, int h)
{
   if (is_astc_8x8(target_format))
      return (size_t)((w + 7) / 8) * ((h + 7) / 8) * 16;
   if (is_astc_4x4(target_format))
      return (size_t)((w + 3) / 4) * ((h + 3) / 4) * 16;
   return (size_t)w * h * get_texel_size_for_format(target_format);
}

size_t
bcn_upload_size(VkFormat bcn_format, int w, int h)
{
   return bcn_upload_size_for_target(bcn_format, get_format_for_bcn(bcn_format),
                                     w, h);
}
""",
    "target-aware upload sizing",
)

# The cache key must distinguish narrow and RGBA output for identical compressed
# source data, or a cache entry produced on one capability path could be reused
# incorrectly on another.
bcdec = one(
    bcdec,
    """wrapper_bcn_cache_hash(const char *src, int block_x, int block_x_src,
                       int block_y, int block_size, VkFormat format,
                       int w, int h, int src_w, int output_size)
""",
    """wrapper_bcn_cache_hash(const char *src, int block_x, int block_x_src,
                       int block_y, int block_size, VkFormat format,
                       VkFormat target_format,
                       int w, int h, int src_w, int output_size)
""",
    "cache hash target parameter",
)
bcdec = one(
    bcdec,
    """      (uint32_t)get_format_for_bcn(format), (uint32_t)output_size,
""",
    """      (uint32_t)target_format, (uint32_t)output_size,
""",
    "cache hash target metadata",
)

# Decoder workers need image dimensions for safe clipping of partial BC blocks
# when expanding R/RG into RGBA, plus the selected target format.
bcdec = one(
    bcdec,
    """   int has_alpha;
   VkFormat format;
   char *src;
""",
    """   int has_alpha;
   int width;
   int height;
   VkFormat format;
   VkFormat target_format;
   char *src;
""",
    "decode worker target metadata",
)

# Expand BC4/BC5 only when the capability path selected RGBA. The bytes match
# Vulkan sampling semantics for an R/RG image: missing G/B are 0 and A is +1.
bcdec = one(
    bcdec,
    """            case VK_FORMAT_BC4_UNORM_BLOCK:
            case VK_FORMAT_BC4_SNORM_BLOCK:
               bcdec_bc4(src, dst, params->stride, params->format == VK_FORMAT_BC4_SNORM_BLOCK);
               break;
""",
    """            case VK_FORMAT_BC4_UNORM_BLOCK:
            case VK_FORMAT_BC4_SNORM_BLOCK:
               if (params->target_format == VK_FORMAT_R8G8B8A8_UNORM ||
                   params->target_format == VK_FORMAT_R8G8B8A8_SNORM) {
                  uint8_t scratch[16];
                  bool snorm = params->format == VK_FORMAT_BC4_SNORM_BLOCK;
                  bcdec_bc4(src, scratch, 4, snorm);
                  for (int yy = 0; yy < 4 && pixel_y + yy < params->height; yy++) {
                     for (int xx = 0; xx < 4 && pixel_x + xx < params->width; xx++) {
                        uint8_t *pixel = (uint8_t *)dst + yy * params->stride + xx * 4;
                        pixel[0] = scratch[yy * 4 + xx];
                        pixel[1] = 0;
                        pixel[2] = 0;
                        pixel[3] = snorm ? 127 : 255;
                     }
                  }
               } else {
                  bcdec_bc4(src, dst, params->stride,
                            params->format == VK_FORMAT_BC4_SNORM_BLOCK);
               }
               break;
""",
    "BC4 RGBA expansion",
)
bcdec = one(
    bcdec,
    """            case VK_FORMAT_BC5_SNORM_BLOCK:
            case VK_FORMAT_BC5_UNORM_BLOCK:
               bcdec_bc5(src, dst, params->stride, params->format == VK_FORMAT_BC5_SNORM_BLOCK);
               break;
""",
    """            case VK_FORMAT_BC5_SNORM_BLOCK:
            case VK_FORMAT_BC5_UNORM_BLOCK:
               if (params->target_format == VK_FORMAT_R8G8B8A8_UNORM ||
                   params->target_format == VK_FORMAT_R8G8B8A8_SNORM) {
                  uint8_t scratch[32];
                  bool snorm = params->format == VK_FORMAT_BC5_SNORM_BLOCK;
                  bcdec_bc5(src, scratch, 8, snorm);
                  for (int yy = 0; yy < 4 && pixel_y + yy < params->height; yy++) {
                     for (int xx = 0; xx < 4 && pixel_x + xx < params->width; xx++) {
                        uint8_t *pixel = (uint8_t *)dst + yy * params->stride + xx * 4;
                        pixel[0] = scratch[(yy * 4 + xx) * 2 + 0];
                        pixel[1] = scratch[(yy * 4 + xx) * 2 + 1];
                        pixel[2] = 0;
                        pixel[3] = snorm ? 127 : 255;
                     }
                  }
               } else {
                  bcdec_bc5(src, dst, params->stride,
                            params->format == VK_FORMAT_BC5_SNORM_BLOCK);
               }
               break;
""",
    "BC5 RGBA expansion",
)

# Preserve the legacy decoder entry point while adding an explicit target-aware
# implementation used by the image-specific capability path.
bcdec = one(
    bcdec,
    """void
decompress_bcn_format(void *srcBuffer,
\t\t\t\t\t  void *dstBuffer,
\t\t\t\t\t  int w,
\t\t\t\t\t  int h,
\t\t\t\t\t  int src_w,
\t\t\t\t\t  VkFormat format,
\t\t\t\t\t  int offset)
{
""",
    """void
decompress_bcn_format(void *srcBuffer,
                      void *dstBuffer,
                      int w,
                      int h,
                      int src_w,
                      VkFormat format,
                      int offset)
{
   decompress_bcn_format_to(srcBuffer, dstBuffer, w, h, src_w, format,
                            get_format_for_bcn(format), offset);
}

void
decompress_bcn_format_to(void *srcBuffer,
                         void *dstBuffer,
                         int w,
                         int h,
                         int src_w,
                         VkFormat format,
                         VkFormat target_format,
                         int offset)
{
""",
    "target-aware decode implementation",
)
bcdec = one(
    bcdec,
    """   int astc = is_astc_4x4(get_format_for_bcn(format));
   int astc8 = is_astc_8x8(get_format_for_bcn(format));
   int has_alpha = bcn_has_alpha(format);
   int texel_size = get_texel_size_for_format(get_decode_format_for_bcn(format));
""",
    """   int astc = is_astc_4x4(target_format);
   int astc8 = is_astc_8x8(target_format);
   int has_alpha = bcn_has_alpha(format);
   int texel_size = get_texel_size_for_format(target_format);
""",
    "target-aware decode sizing",
)
bcdec = one(
    bcdec,
    """      XXH64_hash_t hash = wrapper_bcn_cache_hash(src, block_x, block_x_src,
         block_y, block_size, format, w, h, src_w, uncompressed_size);
""",
    """      XXH64_hash_t hash = wrapper_bcn_cache_hash(src, block_x, block_x_src,
         block_y, block_size, format, target_format, w, h, src_w,
         uncompressed_size);
""",
    "target-aware cache key call",
)

# Avoid leaving uninitialised channels in the diagnostic fill path when RGBA
# fallback is active.
bcdec = one(
    bcdec,
    """               case VK_FORMAT_BC4_UNORM_BLOCK:
               case VK_FORMAT_BC4_SNORM_BLOCK:
                  /* Red */
                  dst[0] = 0xFF;
                  break;
""",
    """               case VK_FORMAT_BC4_UNORM_BLOCK:
               case VK_FORMAT_BC4_SNORM_BLOCK:
                  /* Red */
                  dst[0] = 0xFF;
                  if (texel_size == 4) {
                     dst[1] = 0;
                     dst[2] = 0;
                     dst[3] = format == VK_FORMAT_BC4_SNORM_BLOCK ? 127 : 255;
                  }
                  break;
""",
    "BC4 diagnostic fill",
)
bcdec = one(
    bcdec,
    """               case VK_FORMAT_BC5_UNORM_BLOCK:
               case VK_FORMAT_BC5_SNORM_BLOCK:
                  /* Green */
                  dst[0] = 0;
                  dst[1] = 0xFF;
                  break;
""",
    """               case VK_FORMAT_BC5_UNORM_BLOCK:
               case VK_FORMAT_BC5_SNORM_BLOCK:
                  /* Green */
                  dst[0] = 0;
                  dst[1] = 0xFF;
                  if (texel_size == 4) {
                     dst[2] = 0;
                     dst[3] = format == VK_FORMAT_BC5_SNORM_BLOCK ? 127 : 255;
                  }
                  break;
""",
    "BC5 diagnostic fill",
)

# Populate target/dimensions in each worker shape: two args[i] threaded paths
# and one main-thread args[0] path.
bcdec = n(
    bcdec,
    """         args[i].format = format;
         args[i].block_y_count = rows;
""",
    """         args[i].format = format;
         args[i].target_format = target_format;
         args[i].width = w;
         args[i].height = h;
         args[i].block_y_count = rows;
""",
    2,
    "threaded target metadata",
)
bcdec = one(
    bcdec,
    """      args[0].format = format;
      args[0].block_y_count = block_y;
""",
    """      args[0].format = format;
      args[0].target_format = target_format;
      args[0].width = w;
      args[0].height = h;
      args[0].block_y_count = block_y;
""",
    "main-thread target metadata",
)

# Create/memory-requirement/view/copy paths all use the same selected surrogate.
device = one(
    device,
    """      create_info.format = get_format_for_bcn(pCreateInfo->format);
""",
    """      bool bcn_surrogate_supported = false;
      create_info.format = get_format_for_bcn_image(
         device->physical, pCreateInfo->format, pCreateInfo->imageType,
         pCreateInfo->tiling, pCreateInfo->usage, pCreateInfo->flags,
         &bcn_surrogate_supported);
      if (!bcn_surrogate_supported)
         return VK_ERROR_FORMAT_NOT_SUPPORTED;
""",
    "CreateImage surrogate selection",
)
device = one(
    device,
    """                  ((VkFormat *)fl->pViewFormats)[i] =
                     get_format_for_bcn(fl->pViewFormats[i]);
""",
    """                  ((VkFormat *)fl->pViewFormats)[i] =
                     get_format_for_bcn_image(device->physical,
                        fl->pViewFormats[i], pCreateInfo->imageType,
                        pCreateInfo->tiling, pCreateInfo->usage,
                        pCreateInfo->flags, NULL);
""",
    "format-list surrogate selection",
)
device = one(
    device,
    """   wi->device = device;
   wi->info = *pCreateInfo;
   wi->dispatch_handle = *pImage;
""",
    """   wi->device = device;
   wi->info = *pCreateInfo;
   wi->bcn_storage_format = is_emulated_bcn(device->physical, pCreateInfo->format)
      ? create_info.format : VK_FORMAT_UNDEFINED;
   wi->dispatch_handle = *pImage;
""",
    "store selected surrogate",
)
device = one(
    device,
    """      ci = *pInfo->pCreateInfo;
      ci.format = get_format_for_bcn(pInfo->pCreateInfo->format);
      ci.flags &= ~VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
""",
    """      ci = *pInfo->pCreateInfo;
      ci.format = get_format_for_bcn_image(device->physical,
         pInfo->pCreateInfo->format, ci.imageType, ci.tiling, ci.usage,
         ci.flags, NULL);
      ci.flags &= ~VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
""",
    "memory-requirement surrogate selection",
)
device = one(
    device,
    """   if (is_emulated_bcn(device->physical, pCreateInfo->format)) {
      create_info.format = get_format_for_bcn(pCreateInfo->format);
   }
""",
    """   if (is_emulated_bcn(device->physical, pCreateInfo->format)) {
      struct wrapper_image *image =
         get_wrapper_image_from_handle(device, pCreateInfo->image);
      create_info.format = image && image->bcn_storage_format != VK_FORMAT_UNDEFINED
         ? image->bcn_storage_format : get_format_for_bcn(pCreateInfo->format);
   }
""",
    "image-view surrogate selection",
)

# Device-image subresource-layout fallback creates a temporary base image; it
# must use the same emulated surrogate or it would attempt unsupported BC.
device = one(
    device,
    """   /* Emulate via a transient image: create it, query the subresource layout,
    * destroy it. Use the base dispatch directly to avoid wrapper bookkeeping. */
   VkImage tmp;
   if (device->dispatch_table.CreateImage(device->dispatch_handle,
          pInfo->pCreateInfo, NULL, &tmp) == VK_SUCCESS) {
""",
    """   /* Emulate via a transient image: create it, query the subresource layout,
    * destroy it. Use the base dispatch directly to avoid wrapper bookkeeping. */
   VkImageCreateInfo bcn_ci;
   const VkImageCreateInfo *base_ci = pInfo->pCreateInfo;
   if (base_ci && is_emulated_bcn(device->physical, base_ci->format)) {
      bcn_ci = *base_ci;
      bcn_ci.format = get_format_for_bcn_image(device->physical, base_ci->format,
         bcn_ci.imageType, bcn_ci.tiling, bcn_ci.usage, bcn_ci.flags, NULL);
      bcn_ci.flags &= ~VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
      bcn_ci.pNext = NULL;
      base_ci = &bcn_ci;
   }
   VkImage tmp;
   if (device->dispatch_table.CreateImage(device->dispatch_handle,
          base_ci, NULL, &tmp) == VK_SUCCESS) {
""",
    "subresource-layout surrogate selection",
)

# CPU transcode is the only BC4/BC5 upload path today. Use the image's actual
# storage format for allocation, decoding, diagnostics, and cache identity.
device = one(
    device,
    """   VkResult res;

   simple_mtx_lock(&device->resource_mutex);
""",
    """   VkResult res;
   struct wrapper_image *bcn_dst_image =
      get_wrapper_image_from_handle(device, dstImage);
   VkFormat bcn_target_format = bcn_dst_image &&
      bcn_dst_image->bcn_storage_format != VK_FORMAT_UNDEFINED
      ? bcn_dst_image->bcn_storage_format : get_format_for_bcn(format);

   simple_mtx_lock(&device->resource_mutex);
""",
    "copy target lookup",
)
device = one(
    device,
    """      VkDeviceSize upload_size = bcn_upload_size(format, w, h);
""",
    """      VkDeviceSize upload_size =
         bcn_upload_size_for_target(format, bcn_target_format, w, h);
""",
    "target-aware staging size",
)
device = one(
    device,
    """      decompress_bcn_format(wb->mapped_address, staging_wb->mapped_address,
                            w, h, src_w, format, offset);
""",
    """      decompress_bcn_format_to(wb->mapped_address, staging_wb->mapped_address,
                               w, h, src_w, format, bcn_target_format, offset);
""",
    "target-aware CPU decode",
)
device = one(
    device,
    """            VkFormat tgt = get_format_for_bcn(format);
""",
    """            VkFormat tgt = bcn_target_format;
""",
    "diagnostic target format",
)

# Physical-device image-format queries should not promise a BC4/BC5 image
# configuration when neither the narrow nor RGBA surrogate is supported.
legacy_gate = """      if (pdevice->emulate_bcn < 1)
         break;
      
      if (type & VK_IMAGE_TYPE_1D) {
"""
legacy_repl = """      if (pdevice->emulate_bcn < 1)
         break;

      if (is_emulated_bcn(pdevice, format)) {
         bool surrogate_supported = false;
         (void)get_format_for_bcn_image(pdevice, format, type, tiling, usage,
                                        flags, &surrogate_supported);
         if (!surrogate_supported)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
      }
      
      if (type & VK_IMAGE_TYPE_1D) {
"""
physical = one(physical, legacy_gate, legacy_repl, "legacy image-format capability gate")

props2_gate = """      if (pdevice->emulate_bcn < 1)
         break;
      
      if (pImageFormatInfo->type & VK_IMAGE_TYPE_1D) {
"""
props2_repl = """      if (pdevice->emulate_bcn < 1)
         break;

      if (is_emulated_bcn(pdevice, pImageFormatInfo->format)) {
         bool surrogate_supported = false;
         (void)get_format_for_bcn_image(pdevice, pImageFormatInfo->format,
            pImageFormatInfo->type, pImageFormatInfo->tiling,
            pImageFormatInfo->usage, pImageFormatInfo->flags,
            &surrogate_supported);
         if (!surrogate_supported)
            return VK_ERROR_FORMAT_NOT_SUPPORTED;
      }
      
      if (pImageFormatInfo->type & VK_IMAGE_TYPE_1D) {
"""
physical = one(physical, props2_gate, props2_repl, "properties2 image-format capability gate")

for token, text in (
    ("bcn_storage_format", private),
    ("get_format_for_bcn_image", bcdec),
    ("bcn_upload_size_for_target", bcdec),
    ("VK_FORMAT_R8G8B8A8_SNORM", bcdec),
    ("decompress_bcn_format_to", device),
    ("surrogate_supported", physical),
):
    if token not in text:
        raise RuntimeError(f"BCn capability fallback postcondition missing: {token}")

private_path.write_text(private)
header_path.write_text(header)
bcdec_path.write_text(bcdec)
device_path.write_text(device)
physical_path.write_text(physical)
print("Applied BC4/BC5 capability fallback: R/RG preferred, RGBA UNORM/SNORM fallback")
