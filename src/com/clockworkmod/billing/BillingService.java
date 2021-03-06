package com.clockworkmod.billing;

import java.util.ArrayList;

import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences.Editor;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.android.vending.billing.IMarketBillingService;
import com.clockworkmod.billing.Consts.ResponseCode;

public class BillingService extends Service {
    static String mSandboxPurchaseRequestId = null;
    static String mSandboxProductId = null;
    static String mSandboxBuyerId = null;
    static String REFRESH_MARKET = BillingReceiver.class.getName() + ".REFRESH_MARKET";
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private static final String LOGTAG = "ClockworkBilling";
    static void reportAndroidPurchase(final Context context, final String signedData, final String signature) throws Exception {
        Log.i(LOGTAG, "Reporting in app purchases...");
        HttpPost post = new HttpPost(String.format(ClockworkModBillingClient.INAPP_NOTIFY_URL, ClockworkModBillingClient.mInstance.mSellerId));
        ArrayList<BasicNameValuePair> pairs = new ArrayList<BasicNameValuePair>();
        pairs.add(new BasicNameValuePair("signed_data", signedData));
        pairs.add(new BasicNameValuePair("signature", signature));
        if (mSandboxPurchaseRequestId != null)
            pairs.add(new BasicNameValuePair("sandbox_purchase_request_id", mSandboxPurchaseRequestId));
        if (mSandboxProductId != null)
            pairs.add(new BasicNameValuePair("sandbox_product_id", mSandboxProductId));
        if (mSandboxBuyerId != null)
            pairs.add(new BasicNameValuePair("sandbox_buyer_id", mSandboxBuyerId));
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(pairs);
        post.setEntity(entity);
        String result = StreamUtility.downloadUriAsString(post);
        Log.i(LOGTAG, result);
    }

    Handler mHandler = new Handler();
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = null;
        if (intent != null)
            action = intent.getAction();
        if (action != null) {
            Log.i(LOGTAG, action);
        }

        // after we have reported purchases, success or
        // failure, schedule this service to kill itself
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, 5 * 60 * 1000);

        Intent i = new Intent("com.android.vending.billing.MarketBillingService.BIND");
        ResolveInfo ri = getPackageManager().resolveService(i, 0);
        if (REFRESH_MARKET.equals(action)) {
            bindService(i.setClassName(ri.serviceInfo.packageName, ri.serviceInfo.name), new ServiceConnection() {
                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
                
                @Override
                public void onServiceConnected(ComponentName name, final IBinder service) {
                    try {
                        final IMarketBillingService s = IMarketBillingService.Stub.asInterface(service);
                        Bundle bundle = BillingReceiver.makeRequestBundle(BillingService.this, Consts.METHOD_RESTORE_TRANSACTIONS);
                        bundle.putLong(Consts.BILLING_REQUEST_NONCE, ClockworkModBillingClient.generateNonce(BillingService.this));
                        s.sendBillingRequest(bundle);
                        
                        unbindService(this);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, Context.BIND_AUTO_CREATE);
        }
        else if (Consts.ACTION_PURCHASE_STATE_CHANGED.equals(action)) {
            final ClockworkModBillingClient client = ClockworkModBillingClient.mInstance;
            if (client == null)
                return super.onStartCommand(intent, flags, startId);

            final String signedData = intent.getStringExtra(Consts.INAPP_SIGNED_DATA);
            final String signature = intent.getStringExtra(Consts.INAPP_SIGNATURE);
            if (signedData != null)
                Log.i(LOGTAG, signedData);
            if (signature != null)
                Log.i(LOGTAG, signature);

            Log.i(LOGTAG, "Connecting to Market Service to acknowledge transactions.");
            bindService(new Intent("com.android.vending.billing.MarketBillingService.BIND"), new ServiceConnection() {
                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
                
                @Override
                public void onServiceConnected(ComponentName name, final IBinder service) {
                    final ServiceConnection sc = this;
                    ThreadingRunnable.background(new ThreadingRunnable() {
                        @Override
                        public void run() {
                            try {
                                Log.i(LOGTAG, "Connected to Market Service.");
                                final IMarketBillingService s = IMarketBillingService.Stub.asInterface(service);
                                JSONObject purchase = new JSONObject(signedData);
                                JSONArray orders = purchase.getJSONArray("orders");
                                Log.i(LOGTAG, "Reporting " + orders.length() + " orders to Market Service.");
                                if (orders.length() != 0) {
                                    Editor orderData = ClockworkModBillingClient.getInstance().getOrderData().edit();
                                    ArrayList<String> notificationIds = new ArrayList<String>();
                                    JSONObject proof = new JSONObject();
                                    proof.put("signedData", signedData);
                                    proof.put("signature", signature);
                                    String proofString = proof.toString();
                                    for (int i = 0; i < orders.length(); i++) {
                                        JSONObject order = orders.getJSONObject(i);
                                        String orderId = order.optString("orderId", null);
                                        if (orderId != null)
                                            orderData.putString(orderId, proofString);

                                        String notificationId = order.optString("notificationId", null);
                                        if (notificationId != null)
                                            notificationIds.add(order.getString("notificationId"));
                                    }
                                    orderData.commit();
                                    reportAndroidPurchase(BillingService.this, signedData, signature);
                                    if (notificationIds.size() > 0) {
                                        String[] nids = new String[notificationIds.size()];
                                        nids = notificationIds.toArray(nids);
                                        Bundle bundle = BillingReceiver.makeRequestBundle(BillingService.this, Consts.METHOD_CONFIRM_NOTIFICATIONS);
                                        bundle.putStringArray(Consts.BILLING_REQUEST_NOTIFY_IDS, nids);
                                        s.sendBillingRequest(bundle);
                                    }
                                }
                                Intent intent = new Intent(BillingReceiver.SUCCEEDED);
                                intent.putExtra("orders", orders.toString());
                                sendBroadcast(intent);
                            }
                            catch (Exception ex) {
                                ex.printStackTrace();
                                Intent intent = new Intent(BillingReceiver.FAILED);
                                sendBroadcast(intent);
                            }
                            try {
                                unbindService(sc);
                            }
                            catch (Exception ex) {
                            }
                        }
                    });
                }
            }, Context.BIND_AUTO_CREATE);

        } else if (Consts.ACTION_NOTIFY.equals(action)) {
            final String notifyId = intent.getStringExtra(Consts.NOTIFICATION_ID);
            bindService(new Intent("com.android.vending.billing.MarketBillingService.BIND"), new ServiceConnection() {
                @Override
                public void onServiceDisconnected(ComponentName name) {
                }
                
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        final IMarketBillingService s = IMarketBillingService.Stub.asInterface(service);
                        Bundle request = BillingReceiver.makeRequestBundle(BillingService.this, Consts.METHOD_GET_PURCHASE_INFORMATION);
                        request.putLong(Consts.BILLING_REQUEST_NONCE, System.currentTimeMillis());
                        request.putStringArray(Consts.BILLING_REQUEST_NOTIFY_IDS, new String[] { notifyId });
                        s.sendBillingRequest(request);
                        
                        unbindService(this);
                    }
                    catch (Exception e) {
                    }
                }
            }, Context.BIND_AUTO_CREATE);
        } else if (Consts.ACTION_RESPONSE_CODE.equals(action)) {
            long requestId = intent.getLongExtra(Consts.INAPP_REQUEST_ID, -1);
            int responseCodeIndex = intent.getIntExtra(Consts.INAPP_RESPONSE_CODE,
                    ResponseCode.RESULT_ERROR.ordinal());
            Log.i(LOGTAG, "Response Code: " + requestId + ": " + responseCodeIndex);
        } else {
        }
        
        return super.onStartCommand(intent, flags, startId);
    }

}
