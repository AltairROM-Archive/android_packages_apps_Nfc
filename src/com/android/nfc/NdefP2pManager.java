/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.nfc;

import com.android.nfc.ndefpush.NdefPushClient;
import com.android.nfc.ndefpush.NdefPushServer;
import com.android.nfc.snep.SnepClient;
import com.android.nfc.snep.SnepMessage;
import com.android.nfc.snep.SnepServer;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.nfc.INdefPushCallback;
import android.nfc.INfcAdapter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Profile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

public class NdefP2pManager {
    public static final String ANDROID_SNEP_SERVICE = "urn:nfc:xsn:android.com:snep";
    // Disable Large-ndef-over-BT while we stabilize me-over-BT   (old value 5*1024)
    private static final int MAX_SNEP_SIZE_BYTES = Integer.MAX_VALUE;

    // TODO dynamically assign SAP values
    private static final int NDEFPUSH_SAP = 0x10;
    private static final int ANDROIDSNEP_SAP = 0x11;

    private static final String TAG = "P2PManager";
    private static final boolean DBG = true;
    private final INfcAdapter.Stub mNfcAdapter;
    private final NdefPushServer mNdefPushServer;
    private final SnepServer mDefaultSnepServer;
    private final SnepServer mAndroidSnepServer;
    final BluetoothDropbox mBluetoothDropbox;
    private final ActivityManager mActivityManager;
    private final PackageManager mPackageManager;
    private final Context mContext;

    final static Uri mProfileUri = Profile.CONTENT_VCARD_URI.buildUpon().
            appendQueryParameter(Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true").
            build();

    /** Locked on NdefP2pManager.class */
    NdefMessage mForegroundMsg;
    INdefPushCallback mCallback;

    public NdefP2pManager(Context context, INfcAdapter.Stub adapter) {
        mNfcAdapter = adapter;
        mNdefPushServer = new NdefPushServer(NDEFPUSH_SAP);
        mDefaultSnepServer = new SnepServer(mDefaultSnepCallback);
        mAndroidSnepServer = new SnepServer(ANDROID_SNEP_SERVICE, ANDROIDSNEP_SAP,
                mAndroidSnepCallback);
        mBluetoothDropbox = new BluetoothDropbox(context);
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = context.getPackageManager();
        mContext = context;
    }

    public void enableNdefServer() {
        // Default
        mDefaultSnepServer.start();
        // Custom
        mAndroidSnepServer.start();
        // Legacy
        mNdefPushServer.start();
        // Out-of-band
        // TODO: Hack to make sure Bluetooth is available
        new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {}
                mBluetoothDropbox.start();
            };
        }.start();
    }

    public void disableNdefServer() {
        mDefaultSnepServer.stop();
        mAndroidSnepServer.stop();
        mNdefPushServer.stop();
        mBluetoothDropbox.stop();
    }

    public boolean setForegroundMessage(NdefMessage msg) {
        synchronized (this) {
            boolean set = mForegroundMsg != null;
            mForegroundMsg = msg;
            return set;
        }
    }

    public boolean setForegroundCallback(INdefPushCallback callback) {
        synchronized (this) {
            boolean set = mCallback != null;
            mCallback = callback;
            return set;
        }
    }

    public NdefMessage getMeProfile() {
        NdefMessage ndefMsg = null;
        ContentResolver resolver = mContext.getContentResolver();
        ByteArrayOutputStream ndefBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int r;
        try {
            InputStream vcardInputStream = resolver.openInputStream(mProfileUri);
            while ((r = vcardInputStream.read(buffer)) > 0) {
                ndefBytes.write(buffer, 0, r);
            }
            if (ndefBytes.size() > 0) {
                NdefRecord vcardRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA,
                        "text/x-vCard".getBytes(), new byte[] {},
                        ndefBytes.toByteArray());
                ndefMsg = new NdefMessage(new NdefRecord[] {vcardRecord});
            } else {
                Log.w(TAG, "Me profile vcard size is empty.");
            }
        } catch (IOException e) {
            Log.w(TAG, "IOException on creating me profile.");
        }

        return ndefMsg;
    }

    public void sendMeProfile(NdefMessage target, NdefMessage profile) {
        try {
            mBluetoothDropbox.sendContent(target, profile);
        } catch (IOException e) {
            Log.e(TAG, "Failed to send me profile via BT dropbox");
            if (DBG) Log.e(TAG, "error:", e);
        }
    }

    public NdefMessage getForegroundMessage() {
        synchronized (this) {
            return mForegroundMsg;
        }
    }

    /*package*/ void llcpActivated() {
        if (DBG) Log.d(TAG, "LLCP connection up and running");

        NdefMessage foregroundMsg;
        synchronized (this) {
            foregroundMsg = mForegroundMsg;
        }

        INdefPushCallback callback;
        synchronized (this) {
            callback = mCallback;
        }

        if (callback != null) {
            try {
                foregroundMsg = callback.onConnect();
            } catch (RemoteException e) {
                // Ignore
            }
        }

        if (foregroundMsg == null) {
            List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
            if (tasks.size() > 0) {
                String pkg = tasks.get(0).baseActivity.getPackageName();
                try {
                    ApplicationInfo appInfo = mPackageManager.getApplicationInfo(pkg, 0);
                    if (0 == (appInfo.flags & ApplicationInfo.FLAG_SYSTEM)) {
                        NdefRecord appRecord = NdefRecord.createUri(
                                Uri.parse("http://market.android.com/search?q=pname:" + pkg));
                        foregroundMsg = new NdefMessage(new NdefRecord[] { appRecord });
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Bad package returned from ActivityManager: " + pkg);
                }
            } else {
                Log.d(TAG, "no foreground activity");
            }
        }

        if (foregroundMsg != null) {
            if (DBG) Log.d(TAG, "sending foreground");
            new P2pTask().execute(foregroundMsg);
        } else {
            if (DBG) Log.d(TAG, "no foreground msg");
            new P2pTask().execute();
        }
    }

    /*package*/ void llcpDeactivated() {
        if (DBG) Log.d(TAG, "LLCP deactivated.");
    }

    final class P2pTask extends AsyncTask<NdefMessage, Void, Void> {
        @Override
        public Void doInBackground(NdefMessage... msgs) {
            NdefMessage dropboxTarget = getDropboxTarget();
            try {
                if (msgs.length > 0) {
                    NdefMessage foregroundMsg = msgs[0];
                    if (dropboxTarget != null &&
                            foregroundMsg.toByteArray().length > MAX_SNEP_SIZE_BYTES) {
                        if (DBG) Log.d(TAG, "Sending large ndef to dropbox");
                        mBluetoothDropbox.sendContent(dropboxTarget, foregroundMsg);
                    } else {
                        doSnepProtocol(msgs[0]);
                    }
                }
            } catch (IOException e) {
                if (DBG) Log.d(TAG, "Failed to connect over SNEP, trying NPP");
                new NdefPushClient().push(msgs);
            }

            INdefPushCallback callback;
            synchronized (this) {
                callback = mCallback;
            }

            if (callback != null) {
                try {
                    callback.onMessagePushed();
                } catch (RemoteException e) {
                    // Ignore
                }
            }

            // Me profile
            NdefMessage me = getMeProfile();
            if (dropboxTarget != null && me != null) {
                if (DBG) Log.d(TAG, "Sending me profile");
                try {
                    mBluetoothDropbox.handleOutboundMeProfile(dropboxTarget, me);
                } catch (IOException e) {
                    if (DBG) Log.d(TAG, "Failed to send me profile");
                }
            }

            return null;
        }
    }

    void doSnepProtocol(NdefMessage msg) throws IOException {
        SnepClient snepClient = new SnepClient();
        try {
            snepClient.connect();
        } catch (IOException e) {
            // Throw exception to fall back to NPP.
            snepClient.close();
            throw new IOException("SNEP not available.", e);
        }

        try {
            snepClient.put(msg);
        } catch (IOException e) {
            // SNEP available but had errors, don't fall back to NPP.
        } finally {
            snepClient.close();
        }
    }

    /**
     * Retrieves the remote dropbox address, and grants the remote device access
     * to our local dropbox for a short period of time.
     * @return
     */
    NdefMessage getDropboxTarget() {
        NdefRecord key = new NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE,
                BluetoothDropbox.MIME_TYPE.getBytes(), new byte[0], new byte[0]);
        NdefMessage request = new NdefMessage(new NdefRecord[] { key });
        SnepClient client = new SnepClient(ANDROID_SNEP_SERVICE);
        try {
            client.connect();
            SnepMessage response = client.get(request);
            if (response.getField() == SnepMessage.RESPONSE_SUCCESS) {
                NdefMessage remoteDropboxAddress = response.getNdefMessage();
                mBluetoothDropbox.grantAccess(remoteDropboxAddress);
                return remoteDropboxAddress;
            } else {
                Log.w(TAG, "Error sending content to dropbox.");
            }
        } catch (IOException e) {
            if (DBG) Log.w(TAG, "IOException during bluetooth dropbox");
        } finally {
            client.close();
        }
        return null;
    }

    private final SnepServer.Callback mDefaultSnepCallback = new SnepServer.Callback() {
        @Override
        public SnepMessage doPut(NdefMessage msg) {
            NfcService.getInstance().sendMockNdefTag(msg);
            return SnepMessage.getMessage(SnepMessage.RESPONSE_SUCCESS);
        }

        @Override
        public SnepMessage doGet(int acceptableLength, NdefMessage msg) {
            if (DBG) Log.d(TAG, "GET not supported.");
            return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_IMPLEMENTED);
        }
    };

    private final SnepServer.Callback mAndroidSnepCallback = new SnepServer.Callback() {
        @Override
        public SnepMessage doPut(NdefMessage msg) {
            return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_IMPLEMENTED);
        }

        @Override
        public SnepMessage doGet(int acceptableLength, NdefMessage msg) {
            if (msg.getRecords().length == 0) {
                return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_FOUND);
            }

            NdefRecord key = msg.getRecords()[0];
            if (key.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE &&
                    Arrays.equals(BluetoothDropbox.MIME_TYPE.getBytes(), key.getType())) {

                NdefMessage dropboxAddress = mBluetoothDropbox.getDropboxAddressNdef();
                if (dropboxAddress != null) {
                    if (DBG) Log.d(TAG, "responding with dropbox invitation");
                    return SnepMessage.getSuccessResponse(dropboxAddress);
                } else {
                    if (DBG) Log.d(TAG, "denying dropbox");
                    return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_FOUND);
                }
            }
            return SnepMessage.getMessage(SnepMessage.RESPONSE_NOT_FOUND);
        }
    };
}