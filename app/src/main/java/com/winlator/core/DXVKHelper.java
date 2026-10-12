package com.winlator.core;

import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.StringJoiner;

public class DXVKHelper {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    /**
     * Build the actual DXVK_CONFIG environment-variable value, not a shell
     * command-line argument. DXVK's config.cpp splits environment config on
     * semicolons, and does not accept an outer quote around the whole value.
     *
     * Do not interpret maxDeviceMemory as a Vulkan allocation cap. It only
     * changes memory reported through DXGI to the game.
     */
    public static String buildDxvkConfig(KeyValueSet config) {
        StringJoiner options = new StringJoiner("; ");
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            options.add("dxgi.maxDeviceMemory = " + maxDeviceMemory);
            options.add("dxgi.maxSharedMemory = " + maxDeviceMemory);
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            options.add("d3d11.maxFeatureLevel = " + maxFeatureLevel);
        }

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":", 3);
            if (parts.length == 3) {
                options.add("dxgi.customDeviceId = " + parts[0]);
                options.add("dxgi.customVendorId = " + parts[1]);
                options.add("d3d9.customDeviceId = " + parts[0]);
                options.add("d3d9.customVendorId = " + parts[1]);
                options.add("dxgi.customDeviceDesc = \"" + parts[2] + "\"");
                options.add("d3d9.customDeviceDesc = \"" + parts[2] + "\"");
            }
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            options.add("d3d11.constantBufferRangeCheck = \"True\"");
        }

        return options.toString();
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", "/data/data/app.gamenative/files/imagefs" + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = imageFs.getRootDir();

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0"))
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0"))
            envVars.put("DXVK_FRAME_RATE", framerate);

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");

        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH + "/dxvk.conf");
        envVars.put("DXVK_CONFIG", buildDxvkConfig(config));
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
    }
}
