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

package com.android.systemui.statusbar.powerwidget;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wimax.WimaxHelper;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.view.ViewGroup;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PowerWidget extends FrameLayout {
    private static final String TAG = "PowerWidget";

    public static final String BUTTON_DELIMITER = "|";

    private static final String BUTTONS_DEFAULT = PowerButton.BUTTON_WIFI
                             + BUTTON_DELIMITER + PowerButton.BUTTON_BLUETOOTH
                             + BUTTON_DELIMITER + PowerButton.BUTTON_GPS
                             + BUTTON_DELIMITER + PowerButton.BUTTON_SOUND
			     + BUTTON_DELIMITER + PowerButton.BUTTON_FASTCHARGE;

    private static final FrameLayout.LayoutParams WIDGET_LAYOUT_PARAMS = new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, // width = match_parent
                                        ViewGroup.LayoutParams.WRAP_CONTENT  // height = wrap_content
                                        );

    private static final LinearLayout.LayoutParams BUTTON_LAYOUT_PARAMS = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT, // width = wrap_content
                                        ViewGroup.LayoutParams.MATCH_PARENT, // height = match_parent
                                        1.0f                                    // weight = 1
                                        );

    private static final int LAYOUT_SCROLL_BUTTON_THRESHOLD = 6;

    // this is a list of all possible buttons and their corresponding classes
    private static final HashMap<String, Class<? extends PowerButton>> sPossibleButtons =
            new HashMap<String, Class<? extends PowerButton>>();

    static {
        sPossibleButtons.put(PowerButton.BUTTON_WIFI, WifiButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_GPS, GPSButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_BLUETOOTH, BluetoothButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_BRIGHTNESS, BrightnessButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_SOUND, SoundButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_FASTCHARGE, FastChargeButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_SYNC, SyncButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_WIFIAP, WifiApButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_SCREENTIMEOUT, ScreenTimeoutButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_MOBILEDATA, MobileDataButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_LOCKSCREEN, LockScreenButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_NETWORKMODE, NetworkModeButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_AUTOROTATE, AutoRotateButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_AIRPLANE, AirplaneButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_FLASHLIGHT, FlashlightButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_SLEEP, SleepButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_MEDIA_PLAY_PAUSE, MediaPlayPauseButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_MEDIA_PREVIOUS, MediaPreviousButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_MEDIA_NEXT, MediaNextButton.class);
        sPossibleButtons.put(PowerButton.BUTTON_WIMAX, WimaxButton.class);
    }

    // this is a list of our currently loaded buttons
    private final HashMap<String, PowerButton> mButtons = new HashMap<String, PowerButton>();
    private final ArrayList<String> mButtonNames = new ArrayList<String>();

    private View.OnClickListener mAllButtonClickListener;
    private View.OnLongClickListener mAllButtonLongClickListener;

    private Context mContext;
    private Handler mHandler;
    private LayoutInflater mInflater;
    private WidgetBroadcastReceiver mBroadcastReceiver = null;
    private WidgetSettingsObserver mObserver = null;

    private LinearLayout mButtonLayout;
    private HorizontalScrollView mScrollView;

    public PowerWidget(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mHandler = new Handler();
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // get an initial width
        updateButtonLayoutWidth();
        setupWidget();
        updateVisibility();
    }

    public void destroyWidget() {
        Log.i(TAG, "Clearing any old widget stuffs");
        // remove all views from the layout
        removeAllViews();

        // unregister our content receiver
        if (mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
        }
        // unobserve our content
        if (mObserver != null) {
            mObserver.unobserve();
            mObserver = null;
        }

        // clear the button instances
        unloadAllButtons();
    }


    public void setupWidget() {
        destroyWidget();

        Log.i(TAG, "Setting up widget");

        String buttons = Settings.System.getString(mContext.getContentResolver(), Settings.System.WIDGET_BUTTONS);
        if (buttons == null) {
            Log.i(TAG, "Default buttons being loaded");
            buttons = BUTTONS_DEFAULT;
            // Add the WiMAX button if it's supported
            if (WimaxHelper.isWimaxSupported(mContext)) {
                buttons += BUTTON_DELIMITER + PowerButton.BUTTON_WIMAX;
            }
        }
        Log.i(TAG, "Button list: " + buttons);

        for (String button : buttons.split("\\|")) {
            if (loadButton(button)) {
                mButtonNames.add(button);
            } else {
                Log.e(TAG, "Error setting up button: " + button);
            }
        }
        recreateButtonLayout();
        updateHapticFeedbackSetting();

        // set up a broadcast receiver for our intents, based off of what our power buttons have been loaded
        setupBroadcastReceiver();
        IntentFilter filter = getMergedBroadcastIntentFilter();
        // we add this so we can update views and such if the settings for our widget change
        filter.addAction(Settings.SETTINGS_CHANGED);
        // we need to detect orientation changes and update the static button width value appropriately
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        // register the receiver
        mContext.registerReceiver(mBroadcastReceiver, filter);
        // register our observer
        mObserver = new WidgetSettingsObserver(mHandler);
        mObserver.observe();
    }

    private boolean loadButton(String key) {
        // first make sure we have a valid button
        if (!sPossibleButtons.containsKey(key)) {
            return false;
        }

        if (mButtons.containsKey(key)) {
            return true;
        }

        try {
            // we need to instantiate a new button and add it
            PowerButton pb = sPossibleButtons.get(key).newInstance();
            pb.setExternalClickListener(mAllButtonClickListener);
            pb.setExternalLongClickListener(mAllButtonLongClickListener);
            // save it
            mButtons.put(key, pb);
        } catch (Exception e) {
            Log.e(TAG, "Error loading button: " + key, e);
            return false;
        }

        return true;
    }

    private void unloadButton(String key) {
        // first make sure we have a valid button
        if (mButtons.containsKey(key)) {
            // wipe out the button view
            mButtons.get(key).setupButton(null);
            // remove the button from our list of loaded ones
            mButtons.remove(key);
        }
    }

    private void unloadAllButtons() {
        // cycle through setting the buttons to null
        for (PowerButton pb : mButtons.values()) {
            pb.setupButton(null);
        }

        // clear our list
        mButtons.clear();
        mButtonNames.clear();
    }

    private void recreateButtonLayout() {
        removeAllViews();

        // create a linearlayout to hold our buttons
        mButtonLayout = new LinearLayout(mContext);
        mButtonLayout.setOrientation(LinearLayout.HORIZONTAL);
        mButtonLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        for (String button : mButtonNames) {
            PowerButton pb = mButtons.get(button);
            if (pb != null) {
                View buttonView = mInflater.inflate(R.layout.power_widget_button, null, false);
                pb.setupButton(buttonView);
                mButtonLayout.addView(buttonView, BUTTON_LAYOUT_PARAMS);
            }
        }

        // we determine if we're using a horizontal scroll view based on a threshold of button counts
        if (mButtonLayout.getChildCount() > LAYOUT_SCROLL_BUTTON_THRESHOLD) {
            // we need our horizontal scroll view to wrap the linear layout
            mScrollView = new HorizontalScrollView(mContext);
            // make the fading edge the size of a button (makes it more noticible that we can scroll
            mScrollView.setFadingEdgeLength(mContext.getResources().getDisplayMetrics().widthPixels / LAYOUT_SCROLL_BUTTON_THRESHOLD);
            mScrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            mScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // set the padding on the linear layout to the size of our scrollbar, so we don't have them overlap
            mButtonLayout.setPadding(mButtonLayout.getPaddingLeft(),
                    mButtonLayout.getPaddingTop(),
                    mButtonLayout.getPaddingRight(),
                    mScrollView.getVerticalScrollbarWidth());
            mScrollView.addView(mButtonLayout, WIDGET_LAYOUT_PARAMS);
            updateScrollbar();
            addView(mScrollView, WIDGET_LAYOUT_PARAMS);
        } else {
            // not needed, just add the linear layout
            addView(mButtonLayout, WIDGET_LAYOUT_PARAMS);
        }
    }

    public void updateAllButtons() {
        // cycle through our buttons and update them
        for (PowerButton pb : mButtons.values()) {
            pb.update(mContext);
        }
    }

    private IntentFilter getMergedBroadcastIntentFilter() {
        IntentFilter filter = new IntentFilter();

        for (PowerButton button : mButtons.values()) {
            IntentFilter tmp = button.getBroadcastIntentFilter();

            // cycle through these actions, and see if we need them
            int num = tmp.countActions();
            for (int i = 0; i < num; i++) {
                String action = tmp.getAction(i);
                if(!filter.hasAction(action)) {
                    filter.addAction(action);
                }
            }
        }

        // return our merged filter
        return filter;
    }

    private List<Uri> getAllObservedUris() {
        List<Uri> uris = new ArrayList<Uri>();

        for (PowerButton button : mButtons.values()) {
            List<Uri> tmp = button.getObservedUris();

            for (Uri uri : tmp) {
                if (!uris.contains(uri)) {
                    uris.add(uri);
                }
            }
        }

        return uris;
    }

    public void setGlobalButtonOnClickListener(View.OnClickListener listener) {
        mAllButtonClickListener = listener;
        for (PowerButton pb : mButtons.values()) {
            pb.setExternalClickListener(listener);
        }
    }

    public void setGlobalButtonOnLongClickListener(View.OnLongClickListener listener) {
        mAllButtonLongClickListener = listener;
        for (PowerButton pb : mButtons.values()) {
            pb.setExternalLongClickListener(listener);
        }
    }

    private void setupBroadcastReceiver() {
        if (mBroadcastReceiver == null) {
            mBroadcastReceiver = new WidgetBroadcastReceiver();
        }
    }

    private void updateButtonLayoutWidth() {
        // use our context to set a valid button width
        BUTTON_LAYOUT_PARAMS.width = mContext.getResources().getDisplayMetrics().widthPixels / LAYOUT_SCROLL_BUTTON_THRESHOLD;
    }

    private void updateVisibility() {
        // now check if we need to display the widget still
        boolean displayPowerWidget = Settings.System.getInt(mContext.getContentResolver(),
                   Settings.System.EXPANDED_VIEW_WIDGET, 1) == 1;
        if(!displayPowerWidget) {
            setVisibility(View.GONE);
        } else {
            setVisibility(View.VISIBLE);
        }
    }

    private void updateScrollbar() {
        if (mScrollView == null) return;
        boolean hideScrollBar = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.EXPANDED_HIDE_SCROLLBAR, 0) == 1;
        mScrollView.setHorizontalScrollBarEnabled(!hideScrollBar);
    }

    private void updateHapticFeedbackSetting() {
        ContentResolver cr = mContext.getContentResolver();
        int expandedHapticFeedback = Settings.System.getInt(cr,
                Settings.System.EXPANDED_HAPTIC_FEEDBACK, 2);
        long[] clickPattern = null, longClickPattern = null;
        boolean hapticFeedback;

        if (expandedHapticFeedback == 2) {
             hapticFeedback = Settings.System.getInt(cr,
                     Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1;
        } else {
            hapticFeedback = (expandedHapticFeedback == 1);
        }

        if (hapticFeedback) {
            clickPattern = Settings.System.getLongArray(cr,
                    Settings.System.HAPTIC_DOWN_ARRAY, null);
            longClickPattern = Settings.System.getLongArray(cr,
                    Settings.System.HAPTIC_LONG_ARRAY, null);
        }

        for (PowerButton button : mButtons.values()) {
            button.setHapticFeedback(hapticFeedback, clickPattern, longClickPattern);
        }
    }

    // our own broadcast receiver :D
    private class WidgetBroadcastReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                updateButtonLayoutWidth();
                recreateButtonLayout();
            } else {
                // handle the intent through our power buttons
                for (PowerButton button : mButtons.values()) {
                    // call "onReceive" on those that matter
                    if (button.getBroadcastIntentFilter().hasAction(action)) {
                        button.onReceive(context, intent);
                    }
                }
            }

            // update our widget
            updateAllButtons();
        }
    };

    // our own settings observer :D
    private class WidgetSettingsObserver extends ContentObserver {
        public WidgetSettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            // watch for display widget
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET),
                            false, this);

            // watch for scrollbar hiding
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_HIDE_SCROLLBAR),
                            false, this);

            // watch for haptic feedback
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_HAPTIC_FEEDBACK),
                            false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED),
                            false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_DOWN_ARRAY),
                            false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_LONG_ARRAY),
                            false, this);

            // watch for changes in buttons
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS),
                            false, this);

            // watch for changes in color
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET_COLOR),
                            false, this);

            // watch for power-button specifc stuff that has been loaded
            for(Uri uri : getAllObservedUris()) {
                resolver.registerContentObserver(uri, false, this);
            }
        }

        public void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChangeUri(Uri uri, boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            Resources res = mContext.getResources();

            // first check if our widget buttons have changed
            if(uri.equals(Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS))) {
                setupWidget();
            // now check if we change visibility
            } else if(uri.equals(Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET))) {
                updateVisibility();
            // now check for scrollbar hiding
            } else if(uri.equals(Settings.System.getUriFor(Settings.System.EXPANDED_HIDE_SCROLLBAR))) {
                updateScrollbar();
            }

            if (uri.equals(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED))
                    || uri.equals(Settings.System.getUriFor(Settings.System.HAPTIC_DOWN_ARRAY))
                    || uri.equals(Settings.System.getUriFor(Settings.System.HAPTIC_LONG_ARRAY))
                    || uri.equals(Settings.System.getUriFor(Settings.System.EXPANDED_HAPTIC_FEEDBACK))) {
                updateHapticFeedbackSetting();
            }

            // do whatever the individual buttons must
            for (PowerButton button : mButtons.values()) {
                if (button.getObservedUris().contains(uri)) {
                    button.onChangeUri(resolver, uri);
                }
            }

            // something happened so update the widget
            updateAllButtons();
        }
    }
}
