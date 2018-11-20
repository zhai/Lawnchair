package com.zhaisoft.mylauncher;

import android.graphics.Bitmap;

public abstract class ItemInfoWithIcon extends ItemInfo {
    public Bitmap iconBitmap;
    public boolean usingLowResIcon;

    protected ItemInfoWithIcon() {
    }

    protected ItemInfoWithIcon(ItemInfoWithIcon itemInfoWithIcon) {
        super(itemInfoWithIcon);
        this.iconBitmap = itemInfoWithIcon.iconBitmap;
        this.usingLowResIcon = itemInfoWithIcon.usingLowResIcon;
    }
}