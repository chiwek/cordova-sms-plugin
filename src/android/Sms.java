package com.cordova.plugins.sms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public class Sms extends CordovaPlugin {
    private static final String ACTION_SEND = "send";
    private static final String ACTION_HAS_PERMISSION = "has_permission";

    private static final String INTENT_SENT = "SMS_SENT";
    private static final String INTENT_DELIVERED = "SMS_DELIVERED";

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext cb) throws JSONException {
        if (ACTION_SEND.equals(action)) {
            sendSMS(args, cb);
            return true;
        } else if (ACTION_HAS_PERMISSION.equals(action)) {
            boolean ok = cordova.hasPermission(android.Manifest.permission.SEND_SMS);
            cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, ok));
            return true;
        }
        return false;
    }

    private void sendSMS(final JSONArray args, final CallbackContext cb) {
        // keep callback alive for SENT & DELIVERED
        PluginResult noResult = new PluginResult(PluginResult.Status.NO_RESULT);
        noResult.setKeepCallback(true);
        cb.sendPluginResult(noResult);

        cordova.getThreadPool().execute(() -> {
            try {
                String sep = Build.MANUFACTURER.equalsIgnoreCase("Samsung") ? "," : ";";
                String phoneNumber = args.getJSONArray(0).join(sep).replace("\"", "");
                String message = args.getString(1);
                boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));
                if (replaceLineBreaks) {
                    message = message.replace("\\n", System.getProperty("line.separator"));
                }

                // permission check
                if (!cordova.hasPermission(android.Manifest.permission.SEND_SMS)) {
                    cordova.requestPermission(this, 0, android.Manifest.permission.SEND_SMS);
                    return;
                }

                if (!isSupported()) {
                    cb.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "SMS not supported"));
                    return;
                }

                // perform send with isolated callbacks
                sendWithDelivery(phoneNumber, message, cb);

            } catch (JSONException e) {
                cb.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
            }
        });
    }

    private boolean isSupported() {
        Context ctx = cordova.getActivity();
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private void sendWithDelivery(String phoneNumber, String message, final CallbackContext cb) {
        SmsManager manager = SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(message);

        // unique actions per message
        String sentAction = INTENT_SENT + UUID.randomUUID().toString();
        String delAction  = INTENT_DELIVERED + UUID.randomUUID().toString();
        Context ctx = cordova.getActivity();

        // SENT receiver
        BroadcastReceiver sentRcvr = new BroadcastReceiver() {
            int remaining = parts.size();
            @Override public void onReceive(Context c, Intent intent) {
                PluginResult pr = new PluginResult(PluginResult.Status.OK, "SENT");
                pr.setKeepCallback(true);
                cb.sendPluginResult(pr);
                if (--remaining == 0) {
                    ctx.unregisterReceiver(this);
                }
            }
        };

        // DELIVERED receiver
        BroadcastReceiver delRcvr = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                PluginResult pr = new PluginResult(PluginResult.Status.OK, "DELIVERED");
                pr.setKeepCallback(true);
                cb.sendPluginResult(pr);
                ctx.unregisterReceiver(this);
            }
        };

        // register receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(
                sentRcvr,
                new IntentFilter(sentAction),
                /* permission= */ null,
                /* scheduler= */ null,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            ctx.registerReceiver(sentRcvr, new IntentFilter(sentAction));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(
                delRcvr,
                new IntentFilter(delAction),
                /* permission= */ null,
                /* scheduler= */ null,
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            ctx.registerReceiver(delRcvr, new IntentFilter(delAction));
        }


        // PendingIntents
        PendingIntent piSent = PendingIntent.getBroadcast(
            ctx, 0, new Intent(sentAction).setPackage(ctx.getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        PendingIntent piDel = PendingIntent.getBroadcast(
            ctx, 1, new Intent(delAction).setPackage(ctx.getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // send SMS
        if (parts.size() > 1) {
            ArrayList<PendingIntent> sentIntents = new ArrayList<>(), delIntents = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(piSent);
                delIntents.add(piDel);
            }
            manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, delIntents);
        } else {
            manager.sendTextMessage(phoneNumber, null, message, piSent, piDel);
        }
    }
}
