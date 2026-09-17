#!/usr/bin/env python3
import argparse
import pathlib

OLD_VK11 = '''      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES:
      {
         VkPhysicalDeviceVulkan11Properties *vk11_prop =
              (VkPhysicalDeviceVulkan11Properties *)prop;
         vk11_prop->subgroupSupportedOperations = 0;
         vk11_prop->subgroupSupportedStages = 0;
         break;
      }
'''
NEW_VK11 = '''      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES:
      {
         /* Preserve the underlying driver's Vulkan 1.1 subgroup capability
          * masks.  The wrapper must not hide or synthesize subgroup support. */
         break;
      }
'''
OLD_SUBGROUP = '''      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES:
      {
         VkPhysicalDeviceSubgroupProperties *subgroup_prop =
              (VkPhysicalDeviceSubgroupProperties *)prop;
         subgroup_prop->supportedOperations = 0;
         subgroup_prop->supportedStages = 0;
         break;
      }
'''
NEW_SUBGROUP = '''      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES:
      {
         /* GetPhysicalDeviceProperties2 already filled this structure from the
          * real ICD. Preserve those values byte-for-byte. */
         break;
      }
'''


def replace_exact(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"ERROR: expected exactly one {label} poison block, found {count}")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser(description="Remove GameNative wrapper subgroup capability poisoning.")
    parser.add_argument("source", type=pathlib.Path)
    args = parser.parse_args()

    text = args.source.read_text(encoding="utf-8")
    text = replace_exact(text, OLD_VK11, NEW_VK11, "Vulkan11Properties")
    text = replace_exact(text, OLD_SUBGROUP, NEW_SUBGROUP, "SubgroupProperties")
    args.source.write_text(text, encoding="utf-8")
    print(f"patched subgroup forwarding in {args.source}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
