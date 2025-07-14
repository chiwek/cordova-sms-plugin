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
        if (action.equals(ACTION_SEND_SMS)) {
            boolean isIntent = false;
            try {
                isIntent = args.getString(2).equalsIgnoreCase("INTENT");
            } catch (NullPointerException ignored) {}

            if (isIntent || hasPermission()) {
                sendSMS();
            } else {
                requestPermission(SEND_SMS_REQ_CODE);
            }
            return true;
        } else if (action.equals(ACTION_HAS_PERMISSION)) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, hasPermission()));
            return true;
        } else if (action.equals(ACTION_REQUEST_PERMISSION)) {
            requestPermission(REQUEST_PERMISSION_REQ_CODE);
            return true;
        }
        return false;
    }

    private boolean hasPermission() {
        return cordova.hasPermission(android.Manifest.permission.SEND_SMS);
    }

    private void requestPermission(int requestCode) {
        cordova.requestPermission(this, requestCode, android.Manifest.permission.SEND_SMS);
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "User has denied permission"));
                return;
            }
        }
        if (requestCode == SEND_SMS_REQ_CODE) {
            sendSMS();
            return;
        }
        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, true));
    }

    private boolean sendSMS() {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // parse arguments
                    String separator = ";";
                    if (Build.MANUFACTURER.equalsIgnoreCase("Samsung")) separator = ",";

                    String phoneNumber = args.getJSONArray(0).join(separator).replace("\"", "");
                    String message = args.getString(1);
                    String method = args.getString(2);
                    boolean replaceLineBreaks = Boolean.parseBoolean(args.getString(3));

                    if (replaceLineBreaks) {
                        message = message.replace("\\n", System.getProperty("line.separator"));
                    }
                    if (!checkSupport()) {
                        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "SMS not supported on this platform"));
                        return;
                    }

                    if (method.equalsIgnoreCase("INTENT")) {
                        invokeSMSIntent(phoneNumber, message);
                        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
                    } else {
                        sendWithDelivery(callbackContext, phoneNumber, message);
                    }
                } catch (JSONException ex) {
                    callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
                }
            }
        });
        return true;
    }

    private boolean checkSupport() {
        Activity ctx = this.cordova.getActivity();
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    @SuppressLint("NewApi")
    private void invokeSMSIntent(String phoneNumber, String message) {
        Intent sendIntent;
        if ("".equals(phoneNumber) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String defaultSmsPackageName = Telephony.Sms.getDefaultSmsPackage(this.cordova.getActivity());
            sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_TEXT, message);
            if (defaultSmsPackageName != null) sendIntent.setPackage(defaultSmsPackageName);
        } else {
            sendIntent = new Intent(Intent.ACTION_VIEW);
            sendIntent.putExtra("sms_body", message);
            sendIntent.putExtra("address", phoneNumber);
            sendIntent.setData(Uri.parse("smsto:" + Uri.encode(phoneNumber)));
        }
        this.cordova.getActivity().startActivity(sendIntent);
    }

    private void sendWithDelivery(final CallbackContext callbackContext, String phoneNumber, String message) {
        SmsManager manager = SmsManager.getDefault();
        final ArrayList<String> parts = manager.divideMessage(message);

        // SENT broadcast receiver
        final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
            boolean anyError = false;
            int partsCount = parts.size();
            @Override
            public void onReceive(Context context, Intent intent) {
                // keep callback open
                PluginResult result;
                switch (getResultCode()) {
                    case SmsManager.STATUS_ON_ICC_SENT:
                    case Activity.RESULT_OK:
                        result = new PluginResult(PluginResult.Status.OK, "SENT");
                        break;
                    default:
                        anyError = true;
                        result = new PluginResult(PluginResult.Status.ERROR, "SEND_FAILED");
                        break;
                }
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                partsCount--;
                if (partsCount == 0) {
                    context.unregisterReceiver(this);
                }
            }
        };

        // DELIVERED broadcast receiver
        final BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
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
                context.unregisterReceiver(this);
            }
        };

        // randomize intent actions
        String sentAction = INTENT_FILTER_SMS_SENT + UUID.randomUUID().toString();
        String deliveredAction = INTENT_FILTER_SMS_DELIVERED + UUID.randomUUID().toString();

        // register sent receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.cordova.getActivity().registerReceiver(
                sentReceiver,
                new IntentFilter(sentAction),
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            this.cordova.getActivity().registerReceiver(sentReceiver, new IntentFilter(sentAction));
        }

        // register delivered receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.cordova.getActivity().registerReceiver(
                deliveredReceiver,
                new IntentFilter(deliveredAction),
                Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            this.cordova.getActivity().registerReceiver(deliveredReceiver, new IntentFilter(deliveredAction));
        }

        // create PendingIntents
        PendingIntent sentPI = PendingIntent.getBroadcast(
            this.cordova.getActivity(),
            0,
            new Intent(sentAction),
            PendingIntent.FLAG_IMMUTABLE
        );
        PendingIntent deliveredPI = PendingIntent.getBroadcast(
            this.cordova.getActivity(),
            0,
            new Intent(deliveredAction),
            PendingIntent.FLAG_IMMUTABLE
        );

        // send SMS with both intents
        if (parts.size() > 1) {
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                sentIntents.add(sentPI);
                deliveryIntents.add(deliveredPI);
            }
            manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveryIntents);
        } else {
            manager.sendTextMessage(phoneNumber, null, message, sentPI, deliveredPI);
        }
    }
}
