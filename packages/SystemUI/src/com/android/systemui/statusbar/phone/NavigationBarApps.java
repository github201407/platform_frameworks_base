/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.android.internal.content.PackageMonitor;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for application icons that appear in the navigation bar. Their appearance is similar
 * to the launcher hotseat. Clicking an icon launches or activates the associated activity. A long
 * click will trigger a drag to allow the icons to be reordered. As an icon is dragged the other
 * icons shift to make space for it to be dropped. These layout changes are animated.
 * Navigation bar contains both pinned and unpinned apps: pinned in the left part, unpinned in the
 * right part, with no separator in between.
 */
class NavigationBarApps extends LinearLayout
        implements NavigationBarAppsModel.OnAppsChangedListener {
    public final static boolean DEBUG = false;
    private final static String TAG = "NavigationBarApps";

    /**
     * Intent extra to store user serial number.
     */
    static final String EXTRA_PROFILE = "profile";

    // There are separate NavigationBarApps view instances for landscape vs. portrait, but they
    // share the data model.
    private static NavigationBarAppsModel sAppsModel;

    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final LayoutInflater mLayoutInflater;
    private final AppPackageMonitor mAppPackageMonitor;
    private final WindowManager mWindowManager;


    // This view has two roles:
    // 1) If the drag started outside the pinned apps list, it is a placeholder icon with a null
    // tag.
    // 2) If the drag started inside the pinned apps list, it is the icon for the app being dragged
    // with the associated AppInfo tag.
    // The icon is set invisible for the duration of the drag, creating a visual space for a drop.
    // When the user is not dragging this member is null.
    private ImageView mDragView;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                int currentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                onUserSwitched(currentUserId);
            } else if (Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(action)) {
                UserHandle removedProfile = intent.getParcelableExtra(Intent.EXTRA_USER);
                onManagedProfileRemoved(removedProfile);
            }
        }
    };

    // Layout params for the window that contains the anchor for the popup menus.
    // We need to create a window for a popup menu because the NavBar window is too narrow and can't
    // contain the menu.
    private final WindowManager.LayoutParams mPopupAnchorLayoutParams;
    // View that contains the anchor for popup menus. The view occupies the whole screen, and
    // has a child that will be moved to make the menu to appear where we need it.
    private final ViewGroup mPopupAnchor;
    private final PopupMenu mPopupMenu;

    /**
     * True if popup menu code is busy with a popup operation.
     * Attempting  to show a popup menu or to add menu items while it's returning true will
     * corrupt/crash the app.
     */
    private boolean mIsPopupInUse = false;
    private final int [] mClickedIconLocation = new int[2];

    public NavigationBarApps(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (sAppsModel == null) {
            sAppsModel = new NavigationBarAppsModel(context);
        }
        mPackageManager = context.getPackageManager();
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mLayoutInflater = LayoutInflater.from(context);
        mAppPackageMonitor = new AppPackageMonitor();

        // Dragging an icon removes and adds back the dragged icon. Use the layout transitions to
        // trigger animation. By default all transitions animate, so turn off the unneeded ones.
        LayoutTransition transition = new LayoutTransition();
        // Don't trigger on disappear. Adding the view will trigger the layout animation.
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        // Don't animate the dragged icon itself.
        transition.disableTransitionType(LayoutTransition.APPEARING);
        // When an icon is dragged off the shelf, start sliding the other icons over immediately
        // to match the parent view's animation.
        transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        transition.setStagger(LayoutTransition.CHANGE_DISAPPEARING, 0);
        setLayoutTransition(transition);

        TaskStackListener taskStackListener = new TaskStackListener();
        IActivityManager iam = ActivityManagerNative.getDefault();
        try {
            iam.registerTaskStackListener(taskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "registerTaskStackListener failed", e);
        }

        mPopupAnchorLayoutParams =
                new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                        WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
        mPopupAnchorLayoutParams.setTitle("ShelfMenuAnchor");

        mPopupAnchor = (ViewGroup) mLayoutInflater.inflate(R.layout.shelf_menu_anchor, null);

        ImageView anchorButton =
                (ImageView) mPopupAnchor.findViewById(R.id.shelf_menu_anchor_anchor);
        mPopupMenu = new PopupMenu(context, anchorButton);
    }

    // Monitor that catches events like "app uninstalled".
    private class AppPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            postUnpinIfUnlauncheable(packageName, new UserHandle(getChangingUserId()));
            super.onPackageRemoved(packageName, uid);
        }

        @Override
        public void onPackageModified(String packageName) {
            postUnpinIfUnlauncheable(packageName, new UserHandle(getChangingUserId()));
            super.onPackageModified(packageName);
        }

        @Override
        public void onPackagesAvailable(String[] packages) {
            if (isReplacing()) {
                UserHandle user = new UserHandle(getChangingUserId());

                for (String packageName : packages) {
                    postUnpinIfUnlauncheable(packageName, user);
                }
            }
            super.onPackagesAvailable(packages);
        }

        @Override
        public void onPackagesUnavailable(String[] packages) {
            if (!isReplacing()) {
                UserHandle user = new UserHandle(getChangingUserId());

                for (String packageName : packages) {
                    postUnpinIfUnlauncheable(packageName, user);
                }
            }
            super.onPackagesUnavailable(packages);
        }
    }

    private void postUnpinIfUnlauncheable(final String packageName, final UserHandle user) {
        // This method doesn't necessarily get called in the main thread. Redirect the call into
        // the main thread.
        post(new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                unpinIfUnlauncheable(packageName, user);
            }
        });
    }

    private void unpinIfUnlauncheable(String packageName, UserHandle user) {
        // Unpin icons for all apps that match a package that perhaps became unlauncheable.
        boolean appsWereUnpinned = false;
        for(int i = getChildCount() - 1; i >= 0; --i) {
            View child = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)child.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.

            if (!appButtonData.pinned) continue;

            AppInfo appInfo = appButtonData.appInfo;
            if (!appInfo.getUser().equals(user)) continue;

            ComponentName appComponentName = appInfo.getComponentName();
            if (!appComponentName.getPackageName().equals(packageName)) continue;

            if (sAppsModel.buildAppLaunchIntent(appInfo) != null) {
                continue;
            }

            appButtonData.pinned = false;
            appsWereUnpinned = true;

            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }
        if (appsWereUnpinned) {
            savePinnedApps();
        }
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
        // When an icon is dragged out of the pinned area this view's width changes, which causes
        // the parent container's layout to change and the divider and recents icons to shift left.
        // Animate the parent's CHANGING transition.
        ViewGroup parent = (ViewGroup) getParent();
        LayoutTransition transition = new LayoutTransition();
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.DISAPPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.enableTransitionType(LayoutTransition.CHANGING);
        parent.setLayoutTransition(transition);

        sAppsModel.setCurrentUser(ActivityManager.getCurrentUser());
        recreatePinnedAppButtons();
        updateRecentApps();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mAppPackageMonitor.register(mContext, null, UserHandle.ALL, true);
        sAppsModel.addOnAppsChangedListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.unregisterReceiver(mBroadcastReceiver);
        mAppPackageMonitor.unregister();
        sAppsModel.removeOnAppsChangedListener(this);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (mIsPopupInUse && !isShown()) {
            // Hide the popup if current view became invisible.
            shutdownPopupMenu();
        }
    }

    private void addAppButton(AppButtonData appButtonData) {
        ImageView button = createAppButton(appButtonData);
        addView(button);

        AppInfo app = appButtonData.appInfo;
        CharSequence appLabel = getAppLabel(mPackageManager, app.getComponentName());
        button.setContentDescription(appLabel);

        // Load the icon asynchronously.
        new GetActivityIconTask(mPackageManager, button).execute(appButtonData);
    }

    private List<AppInfo> getPinnedApps() {
        List<AppInfo> apps = new ArrayList<AppInfo>();
        int childCount = getChildCount();
        for (int i = 0; i != childCount; ++i) {
            View child = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)child.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.
            if(!appButtonData.pinned) continue;
            apps.add(appButtonData.appInfo);
        }
        return apps;
    }

    /**
     * Creates an ImageView icon for each pinned app. Removes any existing icons. May be called
     * to synchronize the current view with the shared data mode.
     */
    private void recreatePinnedAppButtons() {
        // Remove any existing icon buttons.
        removeAllViews();

        List<AppInfo> apps = sAppsModel.getApps();
        int appCount = apps.size();
        for (int i = 0; i < appCount; i++) {
            AppInfo app = apps.get(i);
            addAppButton(new AppButtonData(app, true /* pinned */));
        }
    }

    /**
     * Saves pinned apps stored in app icons into the data model.
     */
    private void savePinnedApps() {
        sAppsModel.setApps(getPinnedApps());
    }

    /**
     * Creates a new ImageView for an app, inflated from R.layout.navigation_bar_app_item.
     */
    private ImageView createAppButton(AppButtonData appButtonData) {
        ImageView button = (ImageView) mLayoutInflater.inflate(
                R.layout.navigation_bar_app_item, this, false /* attachToRoot */);
        button.setOnHoverListener(new AppHoverListener());
        button.setOnClickListener(new AppClickListener());
        button.setOnContextClickListener(new AppContextClickListener());
        // TODO: Ripple effect. Use either KeyButtonRipple or the default ripple background.
        button.setOnLongClickListener(new AppLongClickListener());
        button.setOnDragListener(new AppIconDragListener());
        button.setTag(appButtonData);
        return button;
    }

    private class AppLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(View v) {
            mDragView = (ImageView) v;
            AppButtonData appButtonData = (AppButtonData) v.getTag();
            startAppDrag(mDragView, appButtonData.appInfo);
            return true;
        }
    }

    /**
     * Returns the human-readable name for an activity's package or null.
     * TODO: Cache the labels, perhaps in an LruCache.
     */
    @Nullable
    static CharSequence getAppLabel(PackageManager packageManager,
                                    ComponentName activityName) {
        String packageName = activityName.getPackageName();
        ApplicationInfo info;
        try {
            info = packageManager.getApplicationInfo(packageName, 0x0 /* flags */);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package not found " + packageName);
            return null;
        }
        return packageManager.getApplicationLabel(info);
    }

    /** Helper function to start dragging an app icon (either pinned or recent). */
    static void startAppDrag(ImageView icon, AppInfo appInfo) {
        // The drag data is an Intent to launch the activity.
        Intent mainIntent = Intent.makeMainActivity(appInfo.getComponentName());
        UserManager userManager =
                (UserManager) icon.getContext().getSystemService(Context.USER_SERVICE);
        long userSerialNumber = userManager.getSerialNumberForUser(appInfo.getUser());
        mainIntent.putExtra(EXTRA_PROFILE, userSerialNumber);
        ClipData dragData = ClipData.newIntent("", mainIntent);
        // Use the ImageView to create the shadow.
        View.DragShadowBuilder shadow = new AppIconDragShadowBuilder(icon);
        // Use a global drag because the icon might be dragged into the launcher.
        icon.startDrag(dragData, shadow, null /* myLocalState */, View.DRAG_FLAG_GLOBAL);
    }

    @Override
    public boolean dispatchDragEvent(DragEvent event) {
        // ACTION_DRAG_ENTERED is handled by each individual app icon drag listener.
        boolean childHandled = super.dispatchDragEvent(event);

        // Other drag types are handled once per drag by this view. This is handled explicitly
        // because attaching a DragListener to this ViewGroup does not work -- the DragListener in
        // the children consumes the drag events.
        boolean handled = false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                handled = onDragStarted(event);
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                handled = onDragEnded();
                break;
            case DragEvent.ACTION_DROP:
                handled = onDrop(event);
                break;
            case DragEvent.ACTION_DRAG_EXITED:
                handled = onDragExited();
                break;
        }

        return handled || childHandled;
    }

    /** Returns true if a drag should be handled. */
    private static boolean canAcceptDrag(DragEvent event) {
        // Poorly behaved apps might not provide a clip description.
        if (event.getClipDescription() == null) {
            return false;
        }
        // The event must contain an intent.
        return event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_INTENT);
    }

    /**
     * Sets up for a drag. Runs once per drag operation. Returns true if the data represents
     * an app shortcut and will be accepted for a drop.
     */
    private boolean onDragStarted(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDragStarted");

        // Ensure that an app shortcut is being dragged.
        if (!canAcceptDrag(event)) {
            return false;
        }

        // If there are no pinned apps this view will be collapsed, but the user still needs some
        // empty space to use as a drag target.
        if (getChildCount() == 0) {
            mDragView = createPlaceholderDragView(0);
        }

        // If this is an existing icon being reordered, hide the app icon. The drag shadow will
        // continue to draw.
        if (mDragView != null) {
            mDragView.setVisibility(View.INVISIBLE);
        }

        // Listen for the drag end event.
        return true;
    }

    /**
     * Creates a blank icon-sized View to create an empty space during a drag.
     */
    private ImageView createPlaceholderDragView(int index) {
        ImageView button = createAppButton(null);
        addView(button, index);
        return button;
    }

    /**
     * Returns initial index for a new app that doesn't exist in Shelf.
     * Such apps get created by dragging them into Shelf from other apps or by dragging from Shelf
     * and then back, or by removing from shelf as an intermediate step of pinning an app via menu.
     * @param indexHint Initial proposed position for the item.
     * @param isAppPinned True if the app being dragged is pinned.
     */
    int getNewAppIndex(int indexHint, boolean isAppPinned) {
        int i;
        if (isAppPinned) {
            // For a pinned app, find the rightmost position to the left of the target that has a
            // pinned app. We'll insert to the right of that position.
            for (i = indexHint; i > 0; --i) {
                View v = getChildAt(i - 1);
                AppButtonData targetButtonData = (AppButtonData) v.getTag();
                if (targetButtonData.pinned) break;
            }
        } else {
            // For an unpinned app, find the leftmost position to the right of the target that has
            // an unpinned app. We'll insert to the left of that position.
            int childCount = getChildCount();
            for (i = indexHint; i < childCount; ++i) {
                View v = getChildAt(i);
                AppButtonData targetButtonData = (AppButtonData) v.getTag();
                if (!targetButtonData.pinned) break;
            }
        }
        return i;
    }

    /**
     * Handles a drag entering an existing icon. Not implemented in the drag listener because it
     * needs to use LinearLayout/ViewGroup methods.
     */
    private void onDragEnteredIcon(View target) {
        if (DEBUG) Slog.d(TAG, "onDragEntered " + indexOfChild(target));

        int targetIndex = indexOfChild(target);

        // If the drag didn't start from an existing shelf icon, add an invisible placeholder to
        // create empty space for the user to drag into.
        if (mDragView == null) {
            mDragView = createPlaceholderDragView(getNewAppIndex(targetIndex, true));
            return;
        }

        // If the user is dragging on top of the original icon location, do nothing.
        if (target == mDragView) {
            return;
        }

        // "Move" the dragged app by removing it and adding it back at the target location.
        AppButtonData targetButtonData = (AppButtonData) target.getTag();
        int dragViewIndex = indexOfChild(mDragView);
        AppButtonData dragViewButtonData = (AppButtonData) mDragView.getTag();
        // Calculating whether the dragged app is pinned. If the app came from outside if the shelf,
        // in which case dragViewButtonData == null, it's a new app that we'll pin. Otherwise, the
        // button data is defined, and we look whether that existing app is pinned.
        boolean isAppPinned = dragViewButtonData == null || dragViewButtonData.pinned;

        if (dragViewIndex == -1) {
            // Drag view exists, but is not a child, which means that the drag has started at or
            // already visited shelf, then left it, and now is entering it again.
            targetIndex = getNewAppIndex(targetIndex, isAppPinned);
        } else if (dragViewIndex < targetIndex) {
            // The dragged app is currently at the left of the view where the drag is.
            // We shouldn't allow moving a pinned app to the right of the unpinned app.
            if (!targetButtonData.pinned && isAppPinned) return;
        } else {
            // The dragged app is currently at the right of the view where the drag is.
            // We shouldn't allow moving a unpinned app to the left of the pinned app.
            if (targetButtonData.pinned && !isAppPinned) return;
        }

        // This works, but is subtle:
        // * If dragViewIndex > targetIndex then the dragged app is moving from right to left and
        //   the dragged app will be added in front of the target.
        // * If dragViewIndex < targetIndex then the dragged app is moving from left to right.
        //   Removing the drag view will shift the later views one position to the left. Adding
        //   the view at targetIndex will therefore place the app *after* the target.
        removeView(mDragView);
        addView(mDragView, targetIndex);
    }

    private boolean onDrop(DragEvent event) {
        if (DEBUG) Slog.d(TAG, "onDrop");

        // An earlier drag event might have canceled the drag. If so, there is nothing to do.
        if (mDragView == null) {
            return true;
        }

        boolean dragResult = true;
        AppInfo appInfo = getAppFromDragEvent(event);
        if (appInfo == null) {
            // This wasn't a valid drop. Clean up the placeholder.
            removePlaceholderDragViewIfNeeded();
            dragResult = false;
        } else if (mDragView.getTag() == null) {
            // This is a drag that adds a new app. Convert the placeholder to a real icon.
            updateApp(mDragView, new AppButtonData(appInfo, true /* pinned */));
        }
        endDrag();
        return dragResult;
    }

    /** Cleans up at the end of a drag. */
    private void endDrag() {
        // An earlier drag event might have canceled the drag. If so, there is nothing to do.
        if (mDragView == null) return;

        mDragView.setVisibility(View.VISIBLE);
        mDragView = null;
        savePinnedApps();
        // Add recent tasks to the info of the potentially added app.
        updateRecentApps();
    }

    /** Returns an app info from a DragEvent, or null if the data wasn't valid. */
    private AppInfo getAppFromDragEvent(DragEvent event) {
        ClipData data = event.getClipData();
        if (data == null) {
            return null;
        }
        if (data.getItemCount() != 1) {
            return null;
        }
        ClipData.Item item = data.getItemAt(0);
        if (item == null) {
            return null;
        }
        Intent intent = item.getIntent();
        if (intent == null) {
            return null;
        }
        long userSerialNumber = intent.getLongExtra(EXTRA_PROFILE, -1);
        if (userSerialNumber == -1) {
            return null;
        }
        UserHandle appUser = mUserManager.getUserForSerialNumber(userSerialNumber);
        if (appUser == null) {
            return null;
        }
        ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            return null;
        }
        AppInfo appInfo = new AppInfo(componentName, appUser);
        if (sAppsModel.buildAppLaunchIntent(appInfo) == null) {
            return null;
        }
        return appInfo;
    }

    /** Updates the app at a given view index. */
    private void updateApp(ImageView button, AppButtonData appButtonData) {
        button.setTag(appButtonData);
        new GetActivityIconTask(mPackageManager, button).execute(appButtonData);
    }

    /** Removes the empty placeholder view. */
    private void removePlaceholderDragViewIfNeeded() {
        // If the drag has ended already there is nothing to do.
        if (mDragView == null) {
            return;
        }
        removeView(mDragView);
    }

    /** Cleans up at the end of the drag. */
    private boolean onDragEnded() {
        if (DEBUG) Slog.d(TAG, "onDragEnded");
        // If the icon wasn't already dropped into the app list then remove the placeholder.
        removePlaceholderDragViewIfNeeded();
        endDrag();
        return true;
    }

    /** Handles the dragged icon exiting the bounds of this view during the drag. */
    private boolean onDragExited() {
        if (DEBUG) Slog.d(TAG, "onDragExited");
        // Remove the placeholder. It will be added again if the user drags the icon back over
        // the shelf.
        removePlaceholderDragViewIfNeeded();
        return true;
    }

    /** Drag listener for individual app icons. */
    private class AppIconDragListener implements View.OnDragListener {
        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: {
                    // Every button listens for drag events in order to detect enter/exit.
                    return canAcceptDrag(event);
                }
                case DragEvent.ACTION_DRAG_ENTERED: {
                    // Forward to NavigationBarApps.
                    onDragEnteredIcon(v);
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * Brings the menu popup to closed state.
     * Can be called at any stage of the asynchronous process of showing a menu.
     */
    private void shutdownPopupMenu() {
        mWindowManager.removeView(mPopupAnchor);
        mPopupMenu.dismiss();
    }

    /**
     * Shows already prepopulated popup menu using appIcon for anchor location.
     */
    private void showPopupMenu(ImageView appIcon) {
        // Movable view inside the popup anchor view. It serves as the actual anchor for the
        // menu.
        final ImageView anchorButton =
                (ImageView) mPopupAnchor.findViewById(R.id.shelf_menu_anchor_anchor);
        // Set same drawable as for the clicked button to have same size.
        anchorButton.setImageDrawable(appIcon.getDrawable());

        // Move the anchor button to the position of the app button.
        appIcon.getLocationOnScreen(mClickedIconLocation);
        anchorButton.setTranslationX(mClickedIconLocation[0]);
        anchorButton.setTranslationY(mClickedIconLocation[1]);

        final OnAttachStateChangeListener onAttachStateChangeListener =
                new OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        mPopupMenu.show();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {}
                };
        anchorButton.addOnAttachStateChangeListener(onAttachStateChangeListener);

        mPopupMenu.setOnDismissListener(new PopupMenu.OnDismissListener() {
            @Override
            public void onDismiss(PopupMenu menu) {
                // FYU: thorough testing for closing menu either by the user or via
                // shutdownPopupMenu() called at various moments of the menu creation, revealed that
                // 'onDismiss' is guaranteed to be called after each invocation of showPopupMenu.
                mWindowManager.removeView(mPopupAnchor);
                anchorButton.removeOnAttachStateChangeListener(onAttachStateChangeListener);
                mPopupMenu.setOnDismissListener(null);
                mPopupMenu.getMenu().clear();
                mIsPopupInUse = false;
            }
        });

        mWindowManager.addView(mPopupAnchor, mPopupAnchorLayoutParams);
        mIsPopupInUse = true;
    }

    private void activateTask(int taskPersistentId) {
        // Launch or bring the activity to front.
        IActivityManager manager = ActivityManagerNative.getDefault();
        try {
            manager.startActivityFromRecents(taskPersistentId, null /* options */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception when activating a recent task", e);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Exception when activating a recent task", e);
        }
    }

    /**
     * Adds to the popup menu items for activating each of tasks in the specified list.
     */
    private void populateLaunchMenu(AppButtonData appButtonData) {
        Menu menu = mPopupMenu.getMenu();
        int taskCount = appButtonData.getTaskCount();
        for (int i = 0; i < taskCount; ++i) {
            final RecentTaskInfo taskInfo = appButtonData.tasks.get(i);
            MenuItem item = menu.add(getActivityForTask(taskInfo).flattenToShortString());
            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    activateTask(taskInfo.persistentId);
                    return true;
                }
            });
        }
    }

    /**
     * Shows a task selection menu for clicked or hovered-over apps that have more than 1 running
     * tasks.
     */
    void maybeShowLaunchMenu(ImageView appIcon) {
        if (mIsPopupInUse) return;
        AppButtonData appButtonData = (AppButtonData) appIcon.getTag();
        if (appButtonData.getTaskCount() <= 1) return;

        populateLaunchMenu(appButtonData);
        showPopupMenu(appIcon);
    }

    /**
     * A listener for hovering over an app icon.
     */
    private class AppHoverListener implements View.OnHoverListener {
        private final long DELAY_MILLIS = 1000;
        private Runnable mShowMenuCallback;

        @Override
        public boolean onHover(final View v, MotionEvent event) {
            if (mShowMenuCallback == null) {
                mShowMenuCallback = new Runnable() {
                    @Override
                    public void run() {
                        maybeShowLaunchMenu((ImageView) v);
                    }
                };
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    postDelayed(mShowMenuCallback, DELAY_MILLIS);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    removeCallbacks(mShowMenuCallback);
                    break;
            }
            return true;
        }
    }

    /**
     * A click listener that launches an activity.
     */
    private class AppClickListener implements View.OnClickListener {
        private void launchApp(AppInfo appInfo, View anchor) {
            Intent launchIntent = sAppsModel.buildAppLaunchIntent(appInfo);
            if (launchIntent == null) {
                Toast.makeText(
                        getContext(), R.string.activity_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            // Play a scale-up animation while launching the activity.
            // TODO: Consider playing a different animation, or no animation, if the activity is
            // already open in a visible window. In that case we should move the task to front
            // with minimal animation, perhaps using ActivityManager.moveTaskToFront().
            Rect sourceBounds = new Rect();
            anchor.getBoundsOnScreen(sourceBounds);
            ActivityOptions opts =
                    ActivityOptions.makeScaleUpAnimation(
                            anchor, 0, 0, anchor.getWidth(), anchor.getHeight());
            Bundle optsBundle = opts.toBundle();
            launchIntent.setSourceBounds(sourceBounds);

            mContext.startActivityAsUser(launchIntent, optsBundle, appInfo.getUser());
        }

        @Override
        public void onClick(View v) {
            AppButtonData appButtonData = (AppButtonData) v.getTag();

            if (appButtonData.getTaskCount() == 0) {
                launchApp(appButtonData.appInfo, v);
            } else {
                // Activate latest task.
                activateTask(appButtonData.tasks.get(0).persistentId);

                maybeShowLaunchMenu((ImageView) v);
            }
        }
    }

    /**
     * Context click listener that shows app's context menu.
     */
    private class AppContextClickListener implements View.OnContextClickListener {
        void updateState(ImageView appIcon) {
            savePinnedApps();
            if (DEBUG) {
                AppButtonData appButtonData = (AppButtonData) appIcon.getTag();
                new GetActivityIconTask(mPackageManager, appIcon).execute(appButtonData);
            }
        }

        /**
         * Adds to the popup menu items for pinning and unpinning the app in the shelf.
         */
        void populateContextMenu(final ImageView appIcon) {
            final AppButtonData appButtonData = (AppButtonData) appIcon.getTag();
            Menu menu = mPopupMenu.getMenu();
            if (appButtonData.pinned) {
                menu.add("Unpin").
                        setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                            @Override
                            public boolean onMenuItemClick(MenuItem item) {
                                appButtonData.pinned = false;
                                removeView(appIcon);
                                if (!appButtonData.isEmpty()) {
                                    // If the app has running tasks, re-add it to the end of shelf
                                    // after unpinning.
                                    addView(appIcon);
                                }
                                updateState(appIcon);
                                return true;
                            }
                        });
            } else {
                menu.add("Pin").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        appButtonData.pinned = true;
                        removeView(appIcon);
                        // Re-add the pinned icon to the end of the pinned list.
                        addView(appIcon, getNewAppIndex(getChildCount(), true));
                        updateState(appIcon);
                        return true;
                    }
                });
            }
        }

        @Override
        public boolean onContextClick(View v) {
            if (mIsPopupInUse) return true;
            ImageView appIcon = (ImageView) v;
            populateContextMenu(appIcon);
            showPopupMenu(appIcon);
            return true;
        }
    }

    private void onUserSwitched(int currentUserId) {
        sAppsModel.setCurrentUser(currentUserId);
        recreatePinnedAppButtons();
    }

    private void onManagedProfileRemoved(UserHandle removedProfile) {
        // Unpin apps from the removed profile.
        boolean itemsWereUnpinned = false;
        for(int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            if (!appButtonData.pinned) continue;
            if (!appButtonData.appInfo.getUser().equals(removedProfile)) continue;

            appButtonData.pinned = false;
            itemsWereUnpinned = true;
            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }
        if (itemsWereUnpinned) {
            savePinnedApps();
        }
    }

    /**
     * Returns app data for a button that matches the provided app info, if it exists, or null
     * otherwise.
     */
    private AppButtonData findAppButtonData(AppInfo appInfo) {
        int size = getChildCount();
        for (int i = 0; i < size; ++i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) continue;  // Skip the drag placeholder.
            if (appButtonData.appInfo.equals(appInfo)) {
                return appButtonData;
            }
        }
        return null;
    }

    private void updateTasks(List<RecentTaskInfo> tasks) {
        // Remove tasks from all app buttons.
        for (int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            appButtonData.clearTasks();
        }

        // Re-add tasks to app buttons, adding new buttons if needed.
        int size = tasks.size();
        for (int i = 0; i != size; ++i) {
            RecentTaskInfo task = tasks.get(i);
            AppInfo taskAppInfo = taskToAppInfo(task);
            if (taskAppInfo == null) continue;
            AppButtonData appButtonData = findAppButtonData(taskAppInfo);
            if (appButtonData == null) {
                appButtonData = new AppButtonData(taskAppInfo, false);
                addAppButton(appButtonData);
            }
            appButtonData.addTask(task);
        }

        // Remove unpinned apps that now have no tasks.
        for (int i = getChildCount() - 1; i >= 0; --i) {
            View view = getChildAt(i);
            AppButtonData appButtonData = (AppButtonData)view.getTag();
            if (appButtonData == null) return;  // Skip the drag placeholder.
            if (appButtonData.isEmpty()) {
                removeViewAt(i);
            }
        }

        if (DEBUG) {
            for (int i = getChildCount() - 1; i >= 0; --i) {
                View view = getChildAt(i);
                AppButtonData appButtonData = (AppButtonData)view.getTag();
                if (appButtonData == null) return;  // Skip the drag placeholder.
                new GetActivityIconTask(mPackageManager, (ImageView )view).execute(appButtonData);

            }
        }
    }

    private void updateRecentApps() {
        ActivityManager activityManager =
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        // TODO: Should this be getRunningTasks?
        List<RecentTaskInfo> recentTasks = activityManager.getRecentTasksForUser(
                ActivityManager.getMaxAppRecentsLimitStatic(),
                ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                        ActivityManager.RECENT_INCLUDE_PROFILES,
                UserHandle.USER_CURRENT);
        if (DEBUG) Slog.d(TAG, "Got recents " + recentTasks.size());
        updateTasks(recentTasks);
    }

    private static ComponentName getActivityForTask(RecentTaskInfo task) {
        // If the task was started from an alias, return the actual activity component that was
        // initially started.
        if (task.origActivity != null) {
            return task.origActivity;
        }
        // Prefer the first activity of the task.
        if (task.baseActivity != null) {
            return task.baseActivity;
        }
        // Then goes the activity that started the task.
        if (task.realActivity != null) {
            return task.realActivity;
        }
        // This should not happen, but fall back to the base intent's activity component name.
        return task.baseIntent.getComponent();
    }

    private ComponentName getLaunchComponentForPackage(String packageName, int userId) {
        // This code is based on ApplicationPackageManager.getLaunchIntentForPackage.
        PackageManager packageManager = mContext.getPackageManager();

        // First see if the package has an INFO activity; the existence of
        // such an activity is implied to be the desired front-door for the
        // overall package (such as if it has multiple launcher entries).
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = packageManager.queryIntentActivitiesAsUser(
                intentToResolve, 0, userId);

        // Otherwise, try to find a main launcher activity.
        if (ris == null || ris.size() <= 0) {
            // reuse the intent instance
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = packageManager.queryIntentActivitiesAsUser(intentToResolve, 0, userId);
        }
        if (ris == null || ris.size() <= 0) {
            Slog.e(TAG, "Failed to build intent for " + packageName);
            return null;
        }
        return new ComponentName(ris.get(0).activityInfo.packageName,
                ris.get(0).activityInfo.name);
    }

    private AppInfo taskToAppInfo(RecentTaskInfo task) {
        ComponentName componentName = getActivityForTask(task);
        UserHandle taskUser = new UserHandle(task.userId);
        AppInfo appInfo = new AppInfo(componentName, taskUser);

        if (sAppsModel.buildAppLaunchIntent(appInfo) == null) {
            // If task's activity is not launcheable, fall back to a launch component of the
            // task's package.
            ComponentName component = getLaunchComponentForPackage(
                    componentName.getPackageName(), task.userId);

            if (component == null) {
                return null;
            }

            appInfo = new AppInfo(component, taskUser);
        }

        return appInfo;
    }

    /**
     * A listener that updates the app buttons whenever the recents task stack changes.
     */
    private class TaskStackListener extends ITaskStackListener.Stub {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            // Post the message back to the UI thread.
            post(new Runnable() {
                @Override
                public void run() {
                    if (isAttachedToWindow()) {
                        updateRecentApps();
                    }
                }
            });
        }
    }

    @Override
    public void onPinnedAppsChanged() {
        if (getPinnedApps().equals(sAppsModel.getApps())) return;
        recreatePinnedAppButtons();
        updateRecentApps();
    }
}
