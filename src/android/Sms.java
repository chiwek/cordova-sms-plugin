package com.cordova.plugins.sms;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public class Sms extends CordovaPlugin {
    public final String ACTION_SEND_SMS = "send";
    public final String ACTION_HAS_PERMISSION = "has_permission";
    public final String ACTION_REQUEST_PERMISSION = "request_permission";

    private static final String INTENT_FILTER_SMS_SENT = "SMS_SENT";
    private static final String INTENT_FILTER_SMS_DELIVERED = "SMS_DELIVERED";

    private static final int SEND_SMS_REQ_CODE = 0;
    private static final int REQUEST_PERMISSION_REQ_CODE = 1;

    private CallbackContext callbackContext;
    private JSONArray args;

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        this.args = args;

        if (ACTION_SEND_SMS.equals(action)) {
            boolean isIntent = false;
            try {
                isIntent = "INTENT".equalsIgnoreCase(args.getString(2));
            } catch (Exception ignored) {}

            if (isIntent || hasPermission()) {
                sendSMS();
            } else {
                requestPermission(SEND_SMS_REQ_CODE);
            }
            return true;

        } else if (ACTION_HAS_PERMISSION.equals(action)) {
            callbackContext.sendPluginResult(
                new PluginResult(PluginResult.Status.OK, hasPermission())
            );
            return true;

        } else if (ACTION_REQUEST_PERMISSION.equals(action)) {
            requestPermission(REQUEST_PERMISSION_REQ_CODE);
            return true;
        }

        return false;
    }

    private boolean hasPermission() {
        return cordova.hasPermission(android.Manifest.permission.SEND_SMS);
    }

    private void requestPermission(int requestCode) {
        cordova.requestPermission(
            this,
            requestCode,
            android.Manifest.permission.SEND_SMS
        );
    }

    @Override
    public void onRequestPermissionResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) throws JSONException {
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                callbackContext.sendPluginResult(
                    new PluginResult(PluginResult.Status.ERROR, "Permission denied")
                );
                return;
            }
        }

        if (requestCode == SEND_SMS_REQ_CODE) {
            sendSMS();
        } else {
            callbackContext.sendPluginResult(
                new PluginResult(PluginResult.Status.OK, true)
            );
        }
    }

    private boolean sendSMS() {
        PluginResult noResult = new PluginResult(PluginResult.Status.NO_RESULT);
        noResult.setKeepCallback(true);
        callbackContext.sendPluginResult(noResult);

        cordova.getThreadPool().execute(() -> {
            try {
                String separator = ";";
                if (Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
                    separator = ",";
                }
                String phoneNumber = args
                    .getJSONArray(0)
                    .join(separator)
                    .replace("\"", "");
                String message = args.getString(1);
                String method = args.getString(2);
                boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));

                if (replaceLineBreaks) {
                    message = message.replace(
                        "\\n",
                        System.getProperty("line.separator")
                    );
                }

                if (!isSmsSupported()) {
                    callbackContext.sendPluginResult(
                        new PluginResult(
                            PluginResult.Status.ERROR,
                            "SMS not supported"
                        )
                    );
                    return;
                }

                if ("INTENT".equalsIgnoreCase(method)) {
                    launchSmsIntent(phoneNumber, message);
                    callbackContext.sendPluginResult(
                        new PluginResult(PluginResult.Status.OK)
                    );
                } else {
                    sendWithDelivery(phoneNumber, message);
                }

            } catch (JSONException e) {
                callbackContext.sendPluginResult(
                    new PluginResult(PluginResult.Status.JSON_EXCEPTION)
                );
            }
        });
        return true;
    }

    private boolean isSmsSupported() {
        Activity activity = cordova.getActivity();
        return activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    @SuppressLint("NewApi")
    private void launchSmsIntent(String phoneNumber, String message) {
        Intent intent;
        Activity activity = cordova.getActivity();

        if (phoneNumber.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String defaultPackage = Telephony.Sms.getDefaultSmsPackage(activity);
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, message);
            if (defaultPackage != null) {
                intent.setPackage(defaultPackage);
            }
        } else {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra("sms_body", message);
            intent.putExtra("address", phoneNumber);
            intent.setData(
                Uri.parse("smsto:" + Uri.encode(phoneNumber))
            );
        }

        activity.startActivity(intent);
    }

    private void sendWithDelivery(String phoneNumber, String message) {
        SmsManager manager = SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(message);

        String sentAction = INTENT_FILTER_SMS_SENT + UUID.randomUUID().toString();
        String deliveredAction = INTENT_FILTER_SMS_DELIVERED + UUID.randomUUID().toString();

        Context ctx = cordova.getActivity();

        BroadcastReceiver sentReceiver = new BroadcastReceiver() {
            int remaining = parts.size();
            boolean anyError = false;

            @Override
            public void onReceive(Context context, Intent intent) {
                PluginResult result;
                int code = getResultCode();
                if (code == Activity.RESULT_OK || code == SmsManager.STATUS_ON_ICC_SENT) {
                    result = new PluginResult(PluginResult.Status.OK, "SENT");
                } else {
                    anyError = true;
                    result = new PluginResult(PluginResult.Status.ERROR, "SEND_FAILED");
                }
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                remaining--;
                if (remaining == 0) {
                    ctx.unregisterReceiver(this);
                }
            }
        };

        BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                PluginResult result;
                if (getResultCode() == Activity.RESULT_OK) {
                    result = new PluginResult(PluginResult.Status.OK, "DELIVERED");
                } else {
                    result = new PluginResult(PluginResult.Status.ERROR, "DELIVERY_FAILED");
                }
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                ctx.unregisterReceiver(this);
            }
        };

        IntentFilter filterSent = new IntentFilter(sentAction);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(sentReceiver, filterSent, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(sentReceiver, filterSent);
        }

        IntentFilter filterDelivered = new IntentFilter(deliveredAction);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(deliveredReceiver, filterDelivered, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(deliveredReceiver, filterDelivered);
        }

        Intent intentSent = new Intent(sentAction);
        // ensure broadcast targets this app
        intentSent.setPackage(ctx.getPackageName());
        PendingIntent piSent = PendingIntent.getBroadcast(ctx, 0, intentSent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent intentDelivered = new Intent(deliveredAction);
        // ensure broadcast targets this app
        intentDelivered.setPackage(ctx.getPackageName());
        PendingIntent piDelivered = PendingIntent.getBroadcast(ctx, 1, intentDelivered, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        if (parts.size() > 1) {
            ArrayList<PendingIntent> listSent = new ArrayList<>();
            ArrayList<PendingIntent> listDelivered = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                listSent.add(piSent);
                listDelivered.add(piDelivered);
            }
            manager.sendMultipartTextMessage(phoneNumber, null, parts, listSent, listDelivered);
        } else {
            manager.sendTextMessage(phoneNumber, null, message, piSent, piDelivered);
        }
    }
}
