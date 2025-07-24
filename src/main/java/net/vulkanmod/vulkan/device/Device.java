package net.vulkanmod.vulkan.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

public class Device {
    final VkPhysicalDevice physicalDevice;
    final VkPhysicalDeviceProperties properties;

    private final int vendorId;
    public final String vendorIdString;
    public final String deviceName;
    public final String driverVersion;
    public final String vkVersion;

    public final VkPhysicalDeviceFeatures availableFeatures;
    public final boolean vulkan11Supported;

    private boolean drawIndirectSupported;

    public Device(VkPhysicalDevice device) {
        this.physicalDevice = device;

        properties = VkPhysicalDeviceProperties.malloc();
        vkGetPhysicalDeviceProperties(physicalDevice, properties);

        this.vendorId = properties.vendorID();
        this.vendorIdString = decodeVendor(properties.vendorID());
        this.deviceName = properties.deviceNameString();
        this.driverVersion = decodeDvrVersion(properties.driverVersion(), properties.vendorID());
        this.vkVersion = decDefVersion(getVkVer());

        // 检查Vulkan 1.1支持
        int apiVersion = properties.apiVersion();
        this.vulkan11Supported = VK_VERSION_MAJOR(apiVersion) > 1 || 
                                (VK_VERSION_MAJOR(apiVersion) == 1 && VK_VERSION_MINOR(apiVersion) >= 1);

        // 基础特性查询
        this.availableFeatures = VkPhysicalDeviceFeatures.calloc();
        vkGetPhysicalDeviceFeatures(physicalDevice, availableFeatures);

        // Vulkan 1.1特性需要特殊处理
        VkPhysicalDeviceVulkan11Features features11 = null;
        if (vulkan11Supported) {
            try (MemoryStack stack = stackPush()) {
                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack);
                features11 = VkPhysicalDeviceVulkan11Features.calloc(stack);
                features2.sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
                features2.pNext(features11.address());
                
                vkGetPhysicalDeviceFeatures2(physicalDevice, features2);
                
                this.drawIndirectSupported = availableFeatures.multiDrawIndirect() && 
                                           features11.shaderDrawParameters();
                return;
            }
        }

        // Vulkan 1.0的回退逻辑
        this.drawIndirectSupported = availableFeatures.multiDrawIndirect();
    }

    private static String decodeVendor(int i) {
        return switch (i) {
            case (0x10DE) -> "Nvidia";
            case (0x1022) -> "AMD";
            case (0x8086) -> "Intel";
            default -> "undef";
        };
    }

    static String decDefVersion(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }

    private static String decodeDvrVersion(int v, int i) {
        return switch (i) {
            case (0x10DE) -> decodeNvidia(v);
            case (0x1022) -> decDefVersion(v);
            case (0x8086) -> decIntelVersion(v);
            default -> decDefVersion(v);
        };
    }

    private static String decIntelVersion(int v) {
        return (glfwGetPlatform() == GLFW_PLATFORM_WIN32) ? (v >>> 14) + "." + (v & 0x3fff) : decDefVersion(v);
    }

    private static String decodeNvidia(int v) {
        return (v >>> 22 & 0x3FF) + "." + (v >>> 14 & 0xff) + "." + (v >>> 6 & 0xff) + "." + (v & 0xff);
    }

    static int getVkVer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = stack.mallocInt(1);
            int result = vkEnumerateInstanceVersion(a);
            
            if (result != VK_SUCCESS) {
                // 回退到Vulkan 1.0
                return VK_MAKE_VERSION(1, 0, 0);
            }
            
            int vkVer1 = a.get(0);
            return vkVer1;
        }
    }

    public Set<String> getUnsupportedExtensions(Set<String> requiredExtensions) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer extensionCount = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, extensionCount, availableExtensions);

            Set<String> extensions = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            Set<String> unsupportedExtensions = new HashSet<>(requiredExtensions);
            unsupportedExtensions.removeAll(extensions);

            return unsupportedExtensions;
        }
    }

    public boolean isDrawIndirectSupported() {
        return drawIndirectSupported;
    }

    public boolean isAMD() {
        return vendorId == 0x1022;
    }

    public boolean isNvidia() {
        return vendorId == 0x10DE;
    }

    public boolean isIntel() {
        return vendorId == 0x8086;
    }
    
    // 添加Vulkan版本检查方法
    public boolean isVulkan11Supported() {
        return vulkan11Supported;
    }
}
