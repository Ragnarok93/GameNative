package com.winlator.renderer;

import android.util.Log;
import androidx.annotation.Keep;
import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;

public class GPUImage extends NativeTexture {
    private long hardwareBufferPtr;
    private long imageKHRPtr;
    private ByteBuffer virtualData;
    private short stride;
    private static boolean supported = false;

    static {
        System.loadLibrary("extras");
    }

    public GPUImage(short width, short height) {
        hardwareBufferPtr = createHardwareBuffer(width, height);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    public GPUImage(int socketFd) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        if (hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                System.err.println("Error: Failed to lock hardware buffer");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        } else {
            System.err.println("Error: Failed to create hardware buffer");
        }
    }

    @Override
    public void allocateTexture(short width, short height, ByteBuffer data) {
        if (isAllocated()) return;
        super.allocateTexture(width, height, null);
        if (hardwareBufferPtr != 0) {
            imageKHRPtr = createImageKHR(hardwareBufferPtr, textureId);
            if (imageKHRPtr == 0) {
                System.err.println("Error: Failed to create EGL image");
                destroyHardwareBuffer(hardwareBufferPtr);
                hardwareBufferPtr = 0;
            }
        }
    }

    @Override
    public void updateFromDrawable(Drawable drawable) {
        if (!isAllocated()) allocateTexture(drawable.width, drawable.height, null);
        needsUpdate = false;
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (imageKHRPtr != 0) {
            destroyImageKHR(imageKHRPtr);
            imageKHRPtr = 0;
        }
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
        super.destroy();
    }

    public static boolean isSupported() {
        return supported;
    }

    public static void checkIsSupported() {
        // This is a generic GL/EGL GPUImage capability check, not a Native LSFG
        // Vulkan requirement. Avoid tiny 4x4/8x8 allocations that some Android
        // gralloc implementations reject even though realistic buffers work.
        final short size = 64;
        final boolean descriptorSupported =
            isHardwareBufferConfigurationSupported(size, size);
        if (!descriptorSupported) {
            supported = false;
            Log.i("GPUImageProbe",
                "event=support-probe descriptor_supported=0 allocation_attempted=0 size=" + size);
            return;
        }

        GPUImage gpuImage = new GPUImage(size, size);
        gpuImage.allocateTexture(size, size, null);
        supported = gpuImage.hardwareBufferPtr != 0
            && gpuImage.imageKHRPtr != 0
            && gpuImage.virtualData != null;
        Log.i("GPUImageProbe",
            "event=support-probe descriptor_supported=1 allocation_attempted=1 size="
                + size + " supported=" + (supported ? 1 : 0));
        gpuImage.destroy();
    }

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    public void lock() {
        if (hardwareBufferPtr != 0 && virtualData == null) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    public int unlock() {
        if (hardwareBufferPtr != 0 && virtualData != null) {
            int fenceFd = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fenceFd;
        }
        return -1;
    }

    private native long hardwareBufferFromSocket(int fd);

    private static native boolean isHardwareBufferConfigurationSupported(
        short width, short height);

    private native long createHardwareBuffer(short width, short height);

    private native void destroyHardwareBuffer(long hardwareBufferPtr);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);

    private native long createImageKHR(long hardwareBufferPtr, int textureId);

    private native void destroyImageKHR(long imageKHRPtr);
}
