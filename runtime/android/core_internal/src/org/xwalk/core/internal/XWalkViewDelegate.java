// Copyright (c) 2013 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.core.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.StringBuilder;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;

import org.chromium.base.ApplicationStatusManager;
import org.chromium.base.BaseChromiumApplication;
import org.chromium.base.CommandLine;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.base.PathUtils;
import org.chromium.base.ResourceExtractor;
import org.chromium.base.LocaleUtils;
import org.chromium.base.BuildConfig;
//import org.chromium.base.ResourceExtractor.ResourceEntry;
import org.chromium.base.ResourceExtractor.ResourceInterceptor;
import org.chromium.base.ContextUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.base.library_loader.ProcessInitException;
import org.chromium.content.browser.BrowserStartupController;
import org.chromium.content.browser.DeviceUtils;
//import org.xwalk.core.internal.ResourceRewriter;

@JNINamespace("xwalk")
class XWalkViewDelegate {
    private static boolean sInitialized = false;
    private static boolean sLibraryLoaded = false;
    private static boolean sLoadedByHoudini = false;
    private static String sDeviceAbi;
    private static final String PRIVATE_DATA_DIRECTORY_SUFFIX = "xwalkcore";
    private static final String XWALK_CORE_EXTRACTED_DIR = "extracted_xwalkcore";
    private static final String META_XWALK_ENABLE_DOWNLOAD_MODE = "xwalk_enable_download_mode";
    private static final String META_XWALK_DOWNLOAD_MODE = "xwalk_download_mode";
    private static final Method mGetAssignedPackageIdentifiersMethod;

    static {
        try {
            mGetAssignedPackageIdentifiersMethod =
                        AssetManager.class.getMethod("getAssignedPackageIdentifiers");
        } catch (Exception e) {
            throw new RuntimeException("Invalid reflection", e);
        }
    }

    // TODO(rakuco,lincsoon): This list is also in generate_xwalk_core_library.py.
    // We should remove it from one of the places to avoid duplication.
    private static final String[] MANDATORY_PAKS = {
            "xwalk.pak",
            "icudtl.dat",
            "xwalk_100_percent.pak",
            "xwalk_200_percent.pak"
            // Please refer to XWALK-3516, disable v8 use external startup data,
            // reopen it if needed later.
            // "natives_blob.bin",
            // "snapshot_blob.bin"
    };

    private static final String[] MANDATORY_LIBRARIES = {
            //"sqlite_gate",
            "xwalkcore"
    };
    private static final String TAG = "XWalkLib";
    private static final String XWALK_RESOURCES_LIST_RES_NAME = "xwalk_resources_list";
    private static final String XWALK_PAK_NAME = "xwalk.pak";

    private static final String COMMAND_LINE_FILE = "xwalk-command-line";

    private static String[] readCommandLine(Context context) {
        InputStreamReader reader = null;

        try {
            InputStream input = context.getAssets().open(COMMAND_LINE_FILE,
                    AssetManager.ACCESS_BUFFER);
            int length;
            int size = 1024;
            char[] buffer = new char[size];
            StringBuilder builder = new StringBuilder();

            reader = new InputStreamReader(input, "UTF-8");
            while ((length = reader.read(buffer, 0, size)) != -1) {
                builder.append(buffer, 0, length);
            }

            return CommandLine.tokenizeQuotedAruments(
                    builder.toString().toCharArray());
        } catch (IOException e) {
            return null;
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close file reader.", e);
            }
        }
    }
private static void displayFiles (AssetManager mgr, String path, int level) {

     Log.v(TAG,"enter displayFiles("+path+")");
    try {
        String list[] = mgr.list(path);
         Log.v(TAG,"L"+level+": list:"+ Arrays.asList(list));

        if (list != null)
            for (int i=0; i<list.length; ++i)
                {
                    if(level>=1){
                      displayFiles(mgr, path + "/" + list[i], level+1);
                    }else{
                         displayFiles(mgr, list[i], level+1);
                    }
                }
    } catch (IOException e) {
        Log.v(TAG,"List error: can't list" + path);
    }

}
    public static void init(Context libContext, Context appContext) {
        if (!loadXWalkLibrary(libContext, null)) {
            throw new RuntimeException("Failed to load native library");
        }

        if (sInitialized)
            return;

        if ( libContext != null ) {
            Log.d("iotto", "lib context: " + libContext.getPackageName());
        }
        Log.d("iotto", "app context: " + appContext.getPackageName() + " cls: " + appContext.toString());

        Context context = libContext == null ? appContext
                : new MixedContext(libContext, appContext);

        ContextUtils.initApplicationContext(context);
        PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIRECTORY_SUFFIX);

        // Initialize chromium resources. Assign them the correct ids in xwalk core.
        XWalkInternalResources.resetIds(context);
//        ResourceRewriter.rewriteRValues(
//            getPackageId(context.getResources(), ContextUtils.getApplicationContext().getPackageName()));
        

        // Last place to initialize CommandLine object. If you haven't initialize
        // the CommandLine object before XWalkViewContent is created, here will create
        // the object to guarantee the CommandLine object is not null and the
        // consequent prodedure does not crash.
        if (!CommandLine.isInitialized()) {
            CommandLine.init(readCommandLine(context.getApplicationContext()));
        }

        try {
            setupResourceInterceptor(context);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Use MixedContext to initialize the ResourceExtractor, as the pak file
        // is in the library apk if in shared apk mode.
//        ResourceExtractor.get();
//        ResourceExtractor resourceExtractor = ResourceExtractor.get();
//        resourceExtractor.startExtractingResources();

//        resourceExtractor.waitForCompletion();

//        final AssetManager mgr = context.getApplicationContext().getAssets();
//        displayFiles(mgr, "",0);

//        XWalkInternalResources.resetIds(appContext);

        startBrowserProcess(context);

        if (appContext instanceof Activity) {
            ApplicationStatusManager.init((BaseChromiumApplication)((Activity) appContext).getApplication());
        } else if (appContext instanceof Service) {
            ApplicationStatusManager.init((BaseChromiumApplication)((Service) appContext).getApplication());
        }

        XWalkPresentationHost.createInstanceOnce(context);
//        ContextUtils.initApplicationContextForNative();

        sInitialized = true;
    }

    // Keep this function to preserve backward compatibility.
    public static boolean loadXWalkLibrary(Context context) {
        return loadXWalkLibrary(context, null);
    }

    // If context is null, it's running in embedded mode, otherwise in shared mode.
    public static boolean loadXWalkLibrary(Context context, String libDir)
            throws UnsatisfiedLinkError {
        if (sLibraryLoaded)
            return true;

        try {
            Log.d(TAG, "!!!!!!!!!!!!!!!!!! LibraryLoader.get");
            LibraryLoader libraryLoader = LibraryLoader.get(LibraryProcessType.PROCESS_BROWSER);
//            libraryLoader.ensureInitialized();
        } catch (ProcessInitException e) {
        }

//TODO(iotto) workarround for lint error using System.load

/*        if (libDir != null && sLoadedByHoudini == false) {
            for (String library : MANDATORY_LIBRARIES) {
                System.load(libDir + File.separator + "lib" + library + ".so");
            }
        } else {
*/
            for (String library : MANDATORY_LIBRARIES) {
                System.loadLibrary(library);
            }
//        }

        // Load libraries what is wrote in NativeLibraries.java at compile time. It may duplicate
        // with System.loadLibrary("xwalkcore") above, but same library won't be loaded repeatedly.
        try {
            LibraryLoader libraryLoader = LibraryLoader.get(LibraryProcessType.PROCESS_BROWSER);
            libraryLoader.loadNow();
//            libraryLoader.ensureInitialized();
        } catch (ProcessInitException e) {
        }

        if (nativeIsLibraryBuiltForIA()) {
            Log.d(TAG, "Native library is built for IA");
        } else {
            Log.d(TAG, "Native library is built for ARM");
            if (isIaDevice()) {
                Log.d(TAG, "Crosswalk's native library does not support Houdini");
                sLoadedByHoudini = true;
                return false;
            }
        }

        sLibraryLoaded = true;
        return true;
    }

    private static void startBrowserProcess(final Context context) {
        ThreadUtils.runOnUiThreadBlocking(new Runnable() {
            @Override
            public void run() {
                try {
                    LibraryLoader.get(LibraryProcessType.PROCESS_BROWSER).ensureInitialized();
                } catch (ProcessInitException e) {
                    throw new RuntimeException("Cannot initialize Crosswalk Core", e);
                }
                DeviceUtils.addDeviceSpecificUserAgentSwitch(context);
                CommandLine.getInstance().appendSwitchWithValue(
                        XWalkSwitches.PROFILE_NAME,
                        XWalkPreferencesInternal
                                .getStringValue(XWalkPreferencesInternal.PROFILE_NAME));

                if (XWalkPreferencesInternal
                        .getValue(XWalkPreferencesInternal.ANIMATABLE_XWALK_VIEW) &&
                        !CommandLine.getInstance()
                                .hasSwitch(XWalkSwitches.DISABLE_GPU_RASTERIZATION)) {
                    CommandLine.getInstance().appendSwitch(XWalkSwitches.DISABLE_GPU_RASTERIZATION);
                }

                try {
                    BrowserStartupController.get(LibraryProcessType.PROCESS_BROWSER)
                            .startBrowserProcessesSync(true);
                } catch (ProcessInitException e) {
                    throw new RuntimeException("Cannot initialize Crosswalk Core", e);
                }

            }
        });
    }

    /**
     * Plugs an instance of ResourceExtractor.ResourceIntercepter() into ResourceExtractor. It is
     * responsible for loading resources from the right locations depending on whether Crosswalk is
     * being used in shared or embedded mode.
     */
    private static void setupResourceInterceptor(final Context context) throws IOException {
        final boolean isSharedMode = !context.getPackageName()
                .equals(context.getApplicationContext().getPackageName());

        String enable = getApplicationMetaData(context, META_XWALK_DOWNLOAD_MODE);
        if (enable == null) {
            enable = getApplicationMetaData(context, META_XWALK_ENABLE_DOWNLOAD_MODE);
        }
        final boolean isDownloadMode = enable != null
                && (enable.equalsIgnoreCase("enable") || enable.equalsIgnoreCase("true"));

        // The test APKs (XWalkCoreShell, XWalkCoreInternalShell etc) are different from normal
        // Crosswalk apps: even though they use Crosswalk in embedded mode, the resources are stored
        // in assets/ with the rest of the app's assets.
        // XWalkRuntimeClientShell is the only exception, as it uses Crosswalk in shared mode.
        final boolean isTestApk = !isSharedMode
                && Arrays.asList(context.getAssets().list("")).contains(XWALK_PAK_NAME);

        ArrayList<String> resourceList = new ArrayList<String>();
//        HashMap<String, ResourceEntry> resourceList = new HashMap<String, ResourceEntry>();
        // code fragment from ResourceExtractor
        Locale defaultLocale = Locale.getDefault();
        String language = LocaleUtils.getUpdatedLanguageForChromium(defaultLocale.getLanguage());
        // Currenty (Oct 2016), this array can be as big as 4 entries, so using a capacity
        // that allows a bit of growth, but is still in the right ballpark..
        ArrayList<String> activeLocalePakFiles = new ArrayList<String>(6);
        for (String locale : BuildConfig.COMPRESSED_LOCALES) {
            if (locale.startsWith(language)) {
                resourceList.add(locale + ".pak");
            }
        }
        if (resourceList.isEmpty() && BuildConfig.COMPRESSED_LOCALES.length > 0) {
            assert Arrays.asList(BuildConfig.COMPRESSED_LOCALES).contains(ResourceExtractor.FALLBACK_LOCALE);
            resourceList.add(ResourceExtractor.FALLBACK_LOCALE + ".pak");
        }

        try {
            int resourceListId = getResourceId(context, XWALK_RESOURCES_LIST_RES_NAME, "array");
            String[] crosswalkResources = context.getResources().getStringArray(resourceListId);
            for (String resource : crosswalkResources) {
                resourceList.add(resource);
            }
        } catch (NotFoundException e) {
            for (String resource : MANDATORY_PAKS) {
                resourceList.add(resource);
            }
        }
        ResourceExtractor.setResourcesToExtract(
                resourceList.toArray(new String[resourceList.size()]));

        // For shouldInterceptLoadRequest(), which needs a final value.
        final HashSet<String> interceptableResources = new HashSet<String>(resourceList);

        // For shared mode, assets are in library package.
        // For embedded mode, assets are in res/raw.
        ResourceExtractor.setResourceInterceptor(new ResourceInterceptor() {
            @Override
            public boolean shouldInterceptLoadRequest(String resource) {
                return interceptableResources.contains(resource);
            }

            @Override
            public InputStream openRawResource(String resource) {
                if (isSharedMode || isTestApk) {
                    try {
                        return context.getAssets().open(resource);
                    } catch (IOException e) {
                        throw new RuntimeException(resource + " can't be found in assets.");
                    }
                } else if (isDownloadMode) {
                    try {
                        final String resDir = context.getApplicationContext().getDir(
                                XWALK_CORE_EXTRACTED_DIR, Context.MODE_PRIVATE).getAbsolutePath();
                        return new FileInputStream(new File(resDir, resource));
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(resource + " can't be found.");
                    }
                } else {
                    String resourceName = resource.split("\\.")[0];
                    int resourceId = getResourceId(context, resourceName, "raw");
                    try {
                        return context.getResources().openRawResource(resourceId);
                    } catch (NotFoundException e) {
                        throw new RuntimeException("R.raw." + resourceName + " can't be found.");
                    }
                }
            }
        });
    }

    /**
     * Returns a resource identifier for a given resource name and type. Basically a wrapper around
     * Resources.getIdentifier() that also works with applications that change their package name at
     * build time (see XWALK-3569).
     */
    private static int getResourceId(final Context context, final String resourceName,
            final String resourceType) {
        int resourceId = context.getResources().getIdentifier(
                resourceName, resourceType, context.getClass().getPackage().getName());
        if (resourceId == 0) {
            resourceId = context.getResources().getIdentifier(
                    resourceName, resourceType, context.getPackageName());
        }
        return resourceId;
    }

    private static String getApplicationMetaData(Context context, String name) {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            return appInfo.metaData.get(name).toString();
        } catch (NameNotFoundException | NullPointerException e) {
            return null;
        }
    }

    private static boolean isIaDevice() {
        String abi = getDeviceAbi();
        return abi.equals("x86") || abi.equals("x86_64");
    }

    private static String getDeviceAbi() {
        if (sDeviceAbi == null) {
            try {
                sDeviceAbi = Build.SUPPORTED_ABIS[0].toLowerCase(Locale.getDefault());
            } catch (NoSuchFieldError e) {
                try {
                    Process process = Runtime.getRuntime().exec("getprop ro.product.cpu.abi");
                    InputStreamReader ir = new InputStreamReader(process.getInputStream());
                    BufferedReader input = new BufferedReader(ir);
                    sDeviceAbi = input.readLine().toLowerCase(Locale.getDefault());
                    input.close();
                    ir.close();
                } catch (IOException ex) {
                    throw new RuntimeException("Can not detect device's ABI");
                }
            }
            Log.d(TAG, "Device ABI: " + sDeviceAbi);
        }
        return sDeviceAbi;
    }

        public static int getPackageId(Resources resources, String packageName) {
            try {
                SparseArray packageIdentifiers =
                        (SparseArray) mGetAssignedPackageIdentifiersMethod.invoke(
                                resources.getAssets());
                for (int i = 0; i < packageIdentifiers.size(); i++) {
                    final String name = (String) packageIdentifiers.valueAt(i);

                    if (packageName.equals(name)) {
                        return packageIdentifiers.keyAt(i);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Invalid reflection", e);
            }
            throw new RuntimeException("Package not found: " + packageName);
        }

    private static native boolean nativeIsLibraryBuiltForIA();
}
