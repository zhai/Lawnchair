/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zhaisoft.mylauncher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.zhaisoft.mylauncher.shortcuts.DeepShortcutManager;
import com.zhaisoft.mylauncher.shortcuts.ShortcutInfoCompat;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;

import java.net.URISyntaxException;
import java.util.*;

import com.zhaisoft.mylauncher.compat.LauncherActivityInfoCompat;
import com.zhaisoft.mylauncher.compat.LauncherAppsCompat;
import com.zhaisoft.mylauncher.compat.UserManagerCompat;
import com.zhaisoft.mylauncher.preferences.IPreferenceProvider;
import com.zhaisoft.mylauncher.util.PackageManagerHelper;
import com.zhaisoft.mylauncher.util.Thunk;

public class InstallShortcutReceiver extends BroadcastReceiver {
    private static final String TAG = "InstallShortcutReceiver";

    private static final String ACTION_INSTALL_SHORTCUT =
            "com.android.launcher.action.INSTALL_SHORTCUT";

    private static final String LAUNCH_INTENT_KEY = "intent.launch";
    private static final String DEEPSHORTCUT_TYPE_KEY = "isDeepShortcut";
    private static final String APP_WIDGET_TYPE_KEY = "isAppWidget";
    private static final String NAME_KEY = "name";
    private static final String ICON_KEY = "icon";
    private static final String ICON_RESOURCE_NAME_KEY = "iconResource";
    private static final String ICON_RESOURCE_PACKAGE_NAME_KEY = "iconResourcePackage";

    private static final String APP_SHORTCUT_TYPE_KEY = "isAppShortcut";
    private static final String USER_HANDLE_KEY = "userHandle";

    public static final int NEW_SHORTCUT_BOUNCE_DURATION = 450;
    public static final int NEW_SHORTCUT_STAGGER_DELAY = 85;

    private static final Object sLock = new Object();

    private static void addToInstallQueue(
            IPreferenceProvider sharedPrefs, PendingInstallShortcutInfo info) {
        synchronized (sLock) {
            String encoded = info.encodeToString();
            if (encoded != null) {
                Set<String> strings = sharedPrefs.getAppsPendingInstalls();
                if (strings == null) {
                    strings = new HashSet<>(1);
                } else {
                    strings = new HashSet<>(strings);
                }
                strings.add(encoded);
                sharedPrefs.setAppsPendingInstalls(strings);
            }
        }
    }

    public static void removeFromInstallQueue(Context context, HashSet<String> packageNames,
                                              UserHandle user) {
        if (packageNames.isEmpty()) {
            return;
        }
        IPreferenceProvider sp = Utilities.getPrefs(context);
        synchronized (sLock) {
            Set<String> strings = sp.getAppsPendingInstalls();
            if (strings != null) {
                Set<String> newStrings = new HashSet<>(strings);
                Iterator<String> newStringsIter = newStrings.iterator();
                while (newStringsIter.hasNext()) {
                    String encoded = newStringsIter.next();
                    PendingInstallShortcutInfo info = decode(encoded, context);
                    if (info == null || (packageNames.contains(info.getTargetPackage())
                            && user.equals(info.user))) {
                        newStringsIter.remove();
                    }
                }
                sp.setAppsPendingInstalls(newStrings);
            }
        }
    }

    private static ArrayList<PendingInstallShortcutInfo> getAndClearInstallQueue(
            IPreferenceProvider sharedPrefs, Context context) {
        synchronized (sLock) {
            Set<String> strings = sharedPrefs.getAppsPendingInstalls();
            if (strings == null) {
                return new ArrayList<>();
            }
            ArrayList<PendingInstallShortcutInfo> infos =
                    new ArrayList<>();
            for (String encoded : strings) {
                PendingInstallShortcutInfo info = decode(encoded, context);
                if (info != null) {
                    infos.add(info);
                }
            }
            sharedPrefs.setAppsPendingInstalls(new HashSet<String>());
            return infos;
        }
    }

    // Determines whether to defer installing shortcuts immediately until
    // processAllPendingInstalls() is called.
    private static boolean mUseInstallQueue = false;

    @Override
    public void onReceive(Context context, Intent data) {
        if (!ACTION_INSTALL_SHORTCUT.equals(data.getAction())) {
            return;
        }
        PendingInstallShortcutInfo info = createPendingInfo(context, data);
        if (info != null) {
            if (!info.isLauncherActivity()) {
                // Since its a custom shortcut, verify that it is safe to launch.
                if (!PackageManagerHelper.hasPermissionForActivity(
                        context, info.launchIntent, null)) {
                    // Target cannot be launched, or requires some special permission to launch
                    Log.e(TAG, "Ignoring malicious intent " + info.launchIntent.toUri(0));
                    return;
                }
            }
            queuePendingShortcutInfo(info, context);
        }
    }

    /**
     * @return true is the extra is either null or is of type {@param type}
     */
    private static boolean isValidExtraType(Intent intent, String key, Class type) {
        Object extra = intent.getParcelableExtra(key);
        return extra == null || type.isInstance(extra);
    }

    /**
     * Verifies the intent and creates a {@link PendingInstallShortcutInfo}
     */
    private static PendingInstallShortcutInfo createPendingInfo(Context context, Intent data) {
        if (!isValidExtraType(data, Intent.EXTRA_SHORTCUT_INTENT, Intent.class) ||
                !(isValidExtraType(data, Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.class)) ||
                !(isValidExtraType(data, Intent.EXTRA_SHORTCUT_ICON, Bitmap.class))) {

            return null;
        }

        PendingInstallShortcutInfo info = new PendingInstallShortcutInfo(data, context);
        if (info.launchIntent == null || info.label == null) {
            return null;
        }

        return convertToLauncherActivityIfPossible(info);
    }

    public static ShortcutInfo fromShortcutIntent(Context context, Intent data) {
        PendingInstallShortcutInfo info = createPendingInfo(context, data);
        return info == null ? null : info.getShortcutInfo();
    }

    private static void queuePendingShortcutInfo(PendingInstallShortcutInfo info, Context context) {
        // Queue the item up for adding if launcher has not loaded properly yet
        LauncherAppState app = LauncherAppState.getInstance();
        boolean launcherNotLoaded = app.getModel().getCallback() == null;

        addToInstallQueue(Utilities.getPrefs(context), info);
        if (!mUseInstallQueue && !launcherNotLoaded) {
            flushInstallQueue(context);
        }
    }

    static void enableInstallQueue() {
        mUseInstallQueue = true;
    }

    static void disableAndFlushInstallQueue(Context context) {
        mUseInstallQueue = false;
        flushInstallQueue(context);
    }

    static void flushInstallQueue(Context context) {
        IPreferenceProvider sp = Utilities.getPrefs(context);
        ArrayList<PendingInstallShortcutInfo> installQueue = getAndClearInstallQueue(sp, context);
        if (!installQueue.isEmpty()) {
            Iterator<PendingInstallShortcutInfo> iter = installQueue.iterator();
            ArrayList<ItemInfo> addShortcuts = new ArrayList<>();
            while (iter.hasNext()) {
                final PendingInstallShortcutInfo pendingInfo = iter.next();

                // If the intent specifies a package, make sure the package exists
                String packageName = pendingInfo.getTargetPackage();
                if (!TextUtils.isEmpty(packageName)) {
                    UserHandle myUserHandle = Utilities.myUserHandle();
                    if (!LauncherModel.isValidPackage(context, packageName, myUserHandle)) {
                        continue;
                    }
                }

                // Generate a shortcut info to add into the model
                addShortcuts.add(pendingInfo.getShortcutInfo());
            }

            // Add the new apps to the model and bind them
            if (!addShortcuts.isEmpty()) {
                LauncherAppState app = LauncherAppState.getInstance();
                app.getModel().addAndBindAddedWorkspaceItems(context, addShortcuts);
            }
        }
    }

    /**
     * Ensures that we have a valid, non-null name.  If the provided name is null, we will return
     * the application name instead.
     */
    @Thunk
    static CharSequence ensureValidName(Context context, Intent intent, CharSequence name) {
        if (name == null) {
            try {
                PackageManager pm = context.getPackageManager();
                ActivityInfo info = pm.getActivityInfo(intent.getComponent(), 0);
                name = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException nnfe) {
                return "";
            }
        }
        return name;
    }

    private static class PendingInstallShortcutInfo {

        final LauncherActivityInfoCompat activityInfo;
        final ShortcutInfoCompat shortcutInfo;
        final AppWidgetProviderInfo providerInfo;

        final Intent data;
        final Context mContext;
        final Intent launchIntent;
        final String label;
        final UserHandle user;

        /**
         * Initializes a PendingInstallShortcutInfo received from a different app.
         */
        public PendingInstallShortcutInfo(Intent data, Context context) {
            this.data = data;
            mContext = context;

            launchIntent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
            label = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
            user = Utilities.myUserHandle();
            activityInfo = null;
            shortcutInfo = null;
            providerInfo = null;
        }

        /**
         * Initializes a PendingInstallShortcutInfo to represent a launcher target.
         */
        public PendingInstallShortcutInfo(LauncherActivityInfoCompat info, Context context) {
            this.data = null;
            mContext = context;
            activityInfo = info;
            shortcutInfo = null;
            providerInfo = null;
            user = info.getUser();

            launchIntent = AppInfo.makeLaunchIntent(context, info, user);
            label = info.getLabel().toString();
        }

        /**
         * Initializes a PendingInstallShortcutInfo to represent a launcher target.
         */
        public PendingInstallShortcutInfo(ShortcutInfoCompat info, Context context) {
            activityInfo = null;
            shortcutInfo = info;
            providerInfo = null;

            data = null;
            mContext = context;
            user = info.getUserHandle();

            launchIntent = info.makeIntent(context);
            label = info.getShortLabel().toString();
        }

        /**
         * Initializes a PendingInstallShortcutInfo to represent a launcher target.
         */
        public PendingInstallShortcutInfo(
                AppWidgetProviderInfo info, int widgetId, Context context) {
            activityInfo = null;
            shortcutInfo = null;
            providerInfo = info;

            data = null;
            mContext = context;
            user = info.getProfile();

            launchIntent = new Intent().setComponent(info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            label = info.label;
        }

        public String encodeToString() {
            if (activityInfo != null) {
                try {
                    // If it a launcher target, we only need component name, and user to
                    // recreate this.
                    return new JSONStringer()
                            .object()
                            .key(LAUNCH_INTENT_KEY).value(launchIntent.toUri(0))
                            .key(APP_SHORTCUT_TYPE_KEY).value(true)
                            .key(USER_HANDLE_KEY).value(UserManagerCompat.getInstance(mContext)
                                    .getSerialNumberForUser(user))
                            .endObject().toString();
                } catch (JSONException e) {
                    Log.d(TAG, "Exception when adding shortcut: " + e);
                    return null;
                }
            } else if (shortcutInfo != null) {
                try {
                    return new JSONStringer()
                            .object()
                            .key(LAUNCH_INTENT_KEY).value(launchIntent.toUri(0))
                            .key(DEEPSHORTCUT_TYPE_KEY).value(true)
                            .key(USER_HANDLE_KEY).value(UserManagerCompat.getInstance(mContext)
                                    .getSerialNumberForUser(user))
                            .endObject().toString();
                } catch (JSONException e) {
                    Log.d(TAG, "Exception when adding shortcut: " + e);
                    return null;
                }
            } else if (providerInfo != null) {
                try {
                    // If it a launcher target, we only need component name, and user to
                    // recreate this.
                    return new JSONStringer()
                            .object()
                            .key(LAUNCH_INTENT_KEY).value(launchIntent.toUri(0))
                            .key(APP_WIDGET_TYPE_KEY).value(true)
                            .key(USER_HANDLE_KEY).value(UserManagerCompat.getInstance(mContext)
                                    .getSerialNumberForUser(user))
                            .endObject().toString();
                } catch (JSONException e) {
                    Log.d(TAG, "Exception when adding shortcut: " + e);
                    return null;
                }
            }

            if (launchIntent.getAction() == null) {
                launchIntent.setAction(Intent.ACTION_VIEW);
            } else if (launchIntent.getAction().equals(Intent.ACTION_MAIN) &&
                    launchIntent.getCategories() != null &&
                    launchIntent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            }

            // This name is only used for comparisons and notifications, so fall back to activity
            // name if not supplied
            String name = ensureValidName(mContext, launchIntent, label).toString();
            Bitmap icon = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);
            Intent.ShortcutIconResource iconResource =
                    data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);

            // Only encode the parameters which are supported by the API.
            try {
                JSONStringer json = new JSONStringer()
                        .object()
                        .key(LAUNCH_INTENT_KEY).value(launchIntent.toUri(0))
                        .key(NAME_KEY).value(name);
                if (icon != null) {
                    byte[] iconByteArray = Utilities.flattenBitmap(icon);
                    json = json.key(ICON_KEY).value(
                            Base64.encodeToString(
                                    iconByteArray, 0, iconByteArray.length, Base64.DEFAULT));
                }
                if (iconResource != null) {
                    json = json.key(ICON_RESOURCE_NAME_KEY).value(iconResource.resourceName);
                    json = json.key(ICON_RESOURCE_PACKAGE_NAME_KEY)
                            .value(iconResource.packageName);
                }
                return json.endObject().toString();
            } catch (JSONException e) {
                Log.d(TAG, "Exception when adding shortcut: " + e);
            }
            return null;
        }

        public ShortcutInfo getShortcutInfo() {
            if (activityInfo != null) {
                return new ShortcutInfo(activityInfo, mContext);
            } else if (shortcutInfo != null) {
                return new ShortcutInfo(shortcutInfo, mContext);
            } else {
                return LauncherAppState.getInstance().getModel().infoFromShortcutIntent(mContext, data);
            }
        }

        public String getTargetPackage() {
            String packageName = launchIntent.getPackage();
            if (packageName == null) {
                packageName = launchIntent.getComponent() == null ? null :
                        launchIntent.getComponent().getPackageName();
            }
            return packageName;
        }

        public boolean isLauncherActivity() {
            return activityInfo != null;
        }
    }

    private static PendingInstallShortcutInfo decode(String encoded, Context context) {
        try {
            Decoder object = new Decoder(encoded, context);
            Intent launcherIntent = Intent.parseUri(object.getString(LAUNCH_INTENT_KEY), 0);

            if (object.optBoolean(APP_SHORTCUT_TYPE_KEY)) {
                // The is an internal launcher target shortcut.
                UserHandle user = UserManagerCompat.getInstance(context)
                        .getUserForSerialNumber(object.getLong(USER_HANDLE_KEY));
                if (user == null) {
                    return null;
                }

                LauncherActivityInfoCompat info = LauncherAppsCompat.getInstance(context)
                        .resolveActivity(launcherIntent, user);
                return info == null ? null : new PendingInstallShortcutInfo(info, context);
            } else if (object.optBoolean(DEEPSHORTCUT_TYPE_KEY)) {
                DeepShortcutManager sm = DeepShortcutManager.getInstance(context);
                List<ShortcutInfoCompat> si = sm.queryForFullDetails(
                        object.launcherIntent.getPackage(),
                        Arrays.asList(object.launcherIntent.getStringExtra(
                                ShortcutInfoCompat.EXTRA_SHORTCUT_ID)),
                        object.user);
                if (si.isEmpty()) {
                    return null;
                } else {
                    return new PendingInstallShortcutInfo(si.get(0), context);
                }
            } else if (object.optBoolean(APP_WIDGET_TYPE_KEY)) {
                int widgetId = object.launcherIntent
                        .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
                AppWidgetProviderInfo info = AppWidgetManager.getInstance(context)
                        .getAppWidgetInfo(widgetId);
                if (info == null || !info.provider.equals(object.launcherIntent.getComponent()) ||
                        !info.getProfile().equals(object.user)) {
                    return null;
                }
                return new PendingInstallShortcutInfo(info, widgetId, context);
            }

            Intent data = new Intent();
            data.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launcherIntent);
            data.putExtra(Intent.EXTRA_SHORTCUT_NAME, object.getString(NAME_KEY));

            String iconBase64 = object.optString(ICON_KEY);
            String iconResourceName = object.optString(ICON_RESOURCE_NAME_KEY);
            String iconResourcePackageName = object.optString(ICON_RESOURCE_PACKAGE_NAME_KEY);
            if (iconBase64 != null && !iconBase64.isEmpty()) {
                byte[] iconArray = Base64.decode(iconBase64, Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(iconArray, 0, iconArray.length);
                data.putExtra(Intent.EXTRA_SHORTCUT_ICON, b);
            } else if (iconResourceName != null && !iconResourceName.isEmpty()) {
                Intent.ShortcutIconResource iconResource =
                        new Intent.ShortcutIconResource();
                iconResource.resourceName = iconResourceName;
                iconResource.packageName = iconResourcePackageName;
                data.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);
            }

            return new PendingInstallShortcutInfo(data, context);
        } catch (JSONException | URISyntaxException e) {
            Log.d(TAG, "Exception reading shortcut to add: " + e);
        }
        return null;
    }

    private static class Decoder extends JSONObject {
        public final Intent launcherIntent;
        public final UserHandle user;

        private Decoder(String encoded, Context context) throws JSONException, URISyntaxException {
            super(encoded);
            launcherIntent = Intent.parseUri(getString(LAUNCH_INTENT_KEY), 0);
            user = has(USER_HANDLE_KEY) ? UserManagerCompat.getInstance(context)
                    .getUserForSerialNumber(getLong(USER_HANDLE_KEY))
                    : Process.myUserHandle();
            if (user == null) {
                throw new JSONException("Invalid user");
            }
        }
    }

    /**
     * Tries to create a new PendingInstallShortcutInfo which represents the same target,
     * but is an app target and not a shortcut.
     *
     * @return the newly created info or the original one.
     */
    private static PendingInstallShortcutInfo convertToLauncherActivityIfPossible(
            PendingInstallShortcutInfo original) {
        if (original.isLauncherActivity()) {
            // Already an activity target
            return original;
        }
        if (!Utilities.isLauncherAppTarget(original.launchIntent)
                || !original.user.equals(Utilities.myUserHandle())) {
            // We can only convert shortcuts which point to a main activity in the current user.
            return original;
        }

        PackageManager pm = original.mContext.getPackageManager();
        ResolveInfo info = pm.resolveActivity(original.launchIntent, 0);

        if (info == null) {
            return original;
        }

        // Ignore any conflicts in the label name, as that can change based on locale.
        LauncherActivityInfoCompat launcherInfo = LauncherActivityInfoCompat.create(original.mContext, original.user, original.launchIntent);
        return new PendingInstallShortcutInfo(launcherInfo, original.mContext);
    }

    public static void queueShortcut(ShortcutInfoCompat info, Context context) {
        queuePendingShortcutInfo(new PendingInstallShortcutInfo(info, context), context);
    }

    public static void queueWidget(AppWidgetProviderInfo info, int widgetId, Context context) {
        queuePendingShortcutInfo(new PendingInstallShortcutInfo(info, widgetId, context), context);
    }
}
