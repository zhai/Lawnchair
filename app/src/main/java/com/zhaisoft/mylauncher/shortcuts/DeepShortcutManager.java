package com.zhaisoft.mylauncher.shortcuts;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;

import java.util.List;

import com.zhaisoft.mylauncher.EditableItemInfo;
import com.zhaisoft.mylauncher.ItemInfo;
import com.zhaisoft.mylauncher.LauncherSettings;
import com.zhaisoft.mylauncher.Utilities;
import com.zhaisoft.mylauncher.shortcuts.backport.DeepShortcutManagerBackport;

public abstract class DeepShortcutManager {
    private static DeepShortcutManager sInstance;
    private static final Object sInstanceLock = new Object();

    public static DeepShortcutManager getInstance(Context context) {
        DeepShortcutManager deepShortcutManager;
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                if (Utilities.ATLEAST_NOUGAT_MR1 && !Utilities.getPrefs(context).getEnableBackportShortcuts())
                    sInstance = new DeepShortcutManagerNative(context.getApplicationContext());
                else
                    sInstance = new DeepShortcutManagerBackport(context.getApplicationContext());
            }
            deepShortcutManager = sInstance;
        }
        return deepShortcutManager;
    }

    public static boolean supportsShortcuts(ItemInfo info) {
        return info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                && !info.isDisabled();
    }

    public static boolean supportsEdit(ItemInfo itemInfo) {
        return !itemInfo.isDisabled() && itemInfo instanceof EditableItemInfo;
    }

    public abstract boolean wasLastCallSuccess();

    public abstract void onShortcutsChanged(List<ShortcutInfoCompat> shortcuts);

    public abstract List<ShortcutInfoCompat> queryForFullDetails(String packageName,
                                                        List<String> shortcutIds, UserHandle user);

    public abstract List<ShortcutInfoCompat> queryForShortcutsContainer(ComponentName activity,
                                                               List<String> ids, UserHandle user);

    public abstract void unpinShortcut(ShortcutKey key);

    public abstract void pinShortcut(ShortcutKey key);

    public abstract void startShortcut(String packageName, String id, Rect sourceBounds,
                              Bundle startActivityOptions, UserHandle user);

    public abstract Drawable getShortcutIconDrawable(ShortcutInfoCompat shortcutInfo, int density);

    /**
     * Returns the id's of pinned shortcuts associated with the given package and user.
     *
     * If packageName is null, returns all pinned shortcuts regardless of package.
     */
    public final List<ShortcutInfoCompat> queryForPinnedShortcuts(String packageName, UserHandle user) {
        return query(2, packageName, null, null, user);
    }

    public final List<ShortcutInfoCompat> queryForAllShortcuts(UserHandle user) {
        return query(11, null, null, null, user);
    }

    protected abstract List<String> extractIds(List<ShortcutInfoCompat> shortcuts);

    protected abstract List<ShortcutInfoCompat> query(int flags, String packageName,
                                             ComponentName activity, List<String> shortcutIds, UserHandle user);

    public abstract boolean hasHostPermission();
}