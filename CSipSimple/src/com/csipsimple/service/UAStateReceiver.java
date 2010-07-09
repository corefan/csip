/**
 * Copyright (C) 2010 Regis Montoya (aka r3gis - www.r3gis.fr)
 * This file is part of CSipSimple.
 *
 *  CSipSimple is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  CSipSimple is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CSipSimple.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.csipsimple.service;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pjsip.pjsua.Callback;
import org.pjsip.pjsua.SWIGTYPE_p_p_pjmedia_port;
import org.pjsip.pjsua.SWIGTYPE_p_pjmedia_session;
import org.pjsip.pjsua.SWIGTYPE_p_pjsip_rx_data;
import org.pjsip.pjsua.pjsip_event;
import org.pjsip.pjsua.pjsip_inv_state;
import org.pjsip.pjsua.pjsua;
import org.pjsip.pjsua.pjsua_call_info;
import org.pjsip.pjsua.pjsua_call_media_status;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.provider.CallLog.Calls;
import android.text.TextUtils;
import android.view.View;

import com.csipsimple.R;
import com.csipsimple.db.DBAdapter;
import com.csipsimple.models.CallInfo;
import com.csipsimple.utils.CallLogHelper;
import com.csipsimple.utils.Compatibility;
import com.csipsimple.utils.Log;
import com.csipsimple.utils.PhoneUtils;
import com.csipsimple.utils.Ringer;

public class UAStateReceiver extends Callback {
	static String THIS_FILE = "SIP UA Receiver";


	private int savedVibrateRing;
	private int savedVibradeNotif;
	private int savedWifiPolicy;
	private int savedVolume;
	private boolean savedSpeakerPhone;
	//private boolean savedMicrophoneMute;
	//private boolean savedBluetooth;
	//private int savedRouting;
	//private int savedMode;
	
	
	private boolean autoAcceptCurrent = false;

	private NotificationManager notificationManager;
	private SipService service;


	private Ringer ringer;
	private boolean isSavedAudioState = false;
	
	@Override
	public void on_incoming_call(int acc_id, final int callId, SWIGTYPE_p_pjsip_rx_data rdata) {
		updateCallInfo(callId);
		msgHandler.sendMessage(msgHandler.obtainMessage(ON_INCOMING_CALL));
	}
	
	
	@Override
	public void on_call_state(int callId, pjsip_event e) {
		//Get current infos
		updateCallInfo(callId);
		pjsip_inv_state callState = currentCallInfo.getCallState();
		if (callState.equals(pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED)) {
			
			
			stopRing();
			// Call is now ended
			service.stopDialtoneGenerator();
			unsetAudioInCall();
		}
		msgHandler.sendMessage(msgHandler.obtainMessage(ON_CALL_STATE));
	}

	@Override
	public void on_reg_state(int accountId) {
		Log.d(THIS_FILE, "New reg state for : " + accountId);
		msgHandler.sendMessage(msgHandler.obtainMessage(ON_REGISTRATION_STATE, accountId));
	}

	@Override
	public void on_stream_created(int call_id, SWIGTYPE_p_pjmedia_session sess, long stream_idx, SWIGTYPE_p_p_pjmedia_port p_port) {
		Log.d(THIS_FILE, "Stream created");
	}
	
	@Override
	public void on_stream_destroyed(int callId, SWIGTYPE_p_pjmedia_session sess, long streamIdx) {
		Log.d(THIS_FILE, "Stream destroyed");
	}

	@Override
	public void on_call_media_state(int callId) {
		stopRing();
		pjsua_call_info info = new pjsua_call_info();
		pjsua.call_get_info(callId, info);
		if (info.getMedia_status() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
			pjsua.conf_connect(info.getConf_slot(), 0);
			pjsua.conf_connect(0, info.getConf_slot());
			setAudioInCall();
			
		}
	}
	
	
	
	// -------
	// Current call management -- assume for now one unique call is managed
	// -------

	private CallInfo currentCallInfo;
	private long currentCallStart = 0;
	
	public CallInfo getCurrentCallInfo() {
		return currentCallInfo;
	}
	


	private WorkerHandler msgHandler;
	private AudioManager audioManager;
	private HandlerThread handlerThread;

	private WifiLock wifiLock;


	private void updateCallInfo(int callId) {
		if(currentCallInfo == null) {
			currentCallInfo = new CallInfo(callId);
			
		}else {
			if(currentCallInfo.getCallId() == callId) {
				currentCallInfo.updateFromPj();
			}else {
				currentCallInfo = null;
				System.gc();
				
				currentCallInfo = new CallInfo(callId);
				Log.w(THIS_FILE, "Multiple call management is not yet implemented");
			}
		}
		if(currentCallInfo != null) {
			Log.i(THIS_FILE, "State of call " + callId + " :: " + currentCallInfo.getStringCallState());
			
		}
	}
	
	private static final int ON_INCOMING_CALL = 1;
	private static final int ON_CALL_STATE = 2;
	private static final int ON_REGISTRATION_STATE = 3;



    
	private class WorkerHandler extends Handler {

		public WorkerHandler(Looper looper) {
            super(looper);
        }
			
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case ON_INCOMING_CALL:{
				int callId = currentCallInfo.getCallId();
				currentCallInfo.setIncoming(true);
				
				// Automatically answer incoming calls with 180/RINGING
				service.callAnswer(callId, 180);
				startRing();
				showNotificationForCall();
				if (autoAcceptCurrent) {
					// Automatically answer incoming calls with 200/OK
					service.callAnswer(callId, 200);
					autoAcceptCurrent = false;
				} else {
					launchCallHandler();
				}
				
				break;
			}
			
			case ON_CALL_STATE:{
				pjsip_inv_state callState = currentCallInfo.getCallState();
				
				if (callState.equals(pjsip_inv_state.PJSIP_INV_STATE_INCOMING) || 
						callState.equals(pjsip_inv_state.PJSIP_INV_STATE_CALLING)) {
					showNotificationForCall();
					launchCallHandler();
		
				} else if (callState.equals(pjsip_inv_state.PJSIP_INV_STATE_EARLY)) {
					//Nothing to do...
				} else if(callState.equals(pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED)) {
					currentCallStart = System.currentTimeMillis();
				}else if (callState.equals(pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED)) {
					notificationManager.cancel(SipService.CALL_NOTIF_ID);
					Log.d(THIS_FILE, "Finish call2");
					
					//CallLog
					ContentValues cv = CallLogHelper.logValuesForCall(currentCallInfo, currentCallStart);
					
					//Fill our own database
					DBAdapter database = new DBAdapter(service);
					database.open();
					database.insertCallLog(cv);
					database.close();
					
					//If needed fill native database
					if(service.prefsWrapper.useIntegrateCallLogs()) {
						//Reformat number for callogs
						Pattern p = Pattern.compile("^(?:\")?([^<\"]*)(?:\")?[ ]*(?:<)?sip(?:s)?:([^@]*)@[^>]*(?:>)?");
						Matcher m = p.matcher(cv.getAsString(Calls.NUMBER));
						if (m.matches()) {
							
						//	remoteContact = m.group(1);
							String phoneNumber =  m.group(2);
							if(!TextUtils.isEmpty(phoneNumber)) {
								cv.put(Calls.NUMBER, phoneNumber);
								CallLogHelper.addCallLog(service.getContentResolver(), cv);
							}
						}
						
					}
					currentCallInfo.setIncoming(false);
					currentCallStart = 0;
				}
				onBroadcastCallState(currentCallInfo);
				break;
			}
			case ON_REGISTRATION_STATE:{
				Log.d(THIS_FILE, "In reg state");
				// Update sip service (for notifications
				((SipService) service).updateRegistrationsState();
				// Send a broadcast message that for an account
				// registration state has changed
				Intent regStateChangedIntent = new Intent(SipService.ACTION_SIP_REGISTRATION_CHANGED);
				service.sendBroadcast(regStateChangedIntent);
				break;
			}
			}
		}
	};

	
	private void startRing() {
		saveAudioState();
		if(!ringer.isRinging()) {
			String ringtoneUri = service.getPrefs().getRingtone();
			ringer.setCustomRingtoneUri(Uri.parse(ringtoneUri));
			ringer.ring();
			PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_RINGING);
		}
	}
	
	private void stopRing() {
		if(ringer.isRinging()) {
			ringer.stopRing();
		}
	}
	
	// -------
	// Public configuration for receiver
	// -------
	public void setAutoAnswerNext(boolean auto_response) {
		autoAcceptCurrent = auto_response;
	}

	public void initService(SipService srv) {
		service = srv;
        audioManager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
		notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
		ringer = new Ringer(service);
		
		handlerThread = new HandlerThread("UAStateAsyncWorker");
		handlerThread.start();
        msgHandler = new WorkerHandler(handlerThread.getLooper());
	}
	

	public void stopService() {
		if(handlerThread != null) {
			boolean fails = true;
			
			if(Compatibility.isCompatible(5)) {
				try {
					Method method = handlerThread.getClass().getDeclaredMethod("quit", View.class);
					method.invoke(handlerThread);
					fails = false;
				} catch (Exception e) {
					Log.d(THIS_FILE, "Something is wrong with api level declared use fallback method");
				}
			}
			if(fails) {
				handlerThread.stop();
			}
		}
	}

	// --------
	// Private methods
	// --------
	

	private void onBroadcastCallState(final CallInfo callInfo) {	
		Intent callStateChangedIntent = new Intent(SipService.ACTION_SIP_CALL_CHANGED);
		callStateChangedIntent.putExtra("call_info", callInfo);
		service.sendBroadcast(callStateChangedIntent);	
	}

	
	
	private void showNotificationForCall() {
		// This is the pending call notification
		int icon = R.drawable.ic_incall_ongoing;
		CharSequence tickerText =  service.getText(R.string.ongoing_call);
		long when = System.currentTimeMillis();

		Notification notification = new Notification(icon, tickerText, when);
		Context context = service.getApplicationContext();

		Intent notificationIntent = new Intent(SipService.ACTION_SIP_CALL_UI);
		notificationIntent.putExtra("call_info", currentCallInfo);
		notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent contentIntent = PendingIntent.getActivity(service, 0, notificationIntent, 0);

		
		notification.setLatestEventInfo(context, service.getText(R.string.ongoing_call), 
				currentCallInfo.getRemoteContact(), contentIntent);
		notification.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
		// notification.flags = Notification.FLAG_FOREGROUND_SERVICE;

		notificationManager.notify(SipService.CALL_NOTIF_ID, notification);
	}
	
	

	
	/**
	 * 
	 * @param callInfo
	 */
	private void launchCallHandler() {
		
		// Launch activity to choose what to do with this call
		Intent callHandlerIntent = new Intent(SipService.ACTION_SIP_CALL_UI); //new Intent(service, getInCallClass());
		callHandlerIntent.putExtra("call_info", currentCallInfo);
		callHandlerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

		Log.i(THIS_FILE, "Anounce call activity please");
		service.startActivity(callHandlerIntent);

	}

	
	private void saveAudioState() {
		if(isSavedAudioState) {
			return;
		}
		ContentResolver ctntResolver = service.getContentResolver();
		
		savedVibrateRing = audioManager.getVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
		savedVibradeNotif = audioManager.getVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);
		savedWifiPolicy = android.provider.Settings.System.getInt(ctntResolver, android.provider.Settings.System.WIFI_SLEEP_POLICY, Settings.System.WIFI_SLEEP_POLICY_DEFAULT);
		savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
		
		if(Compatibility.isCompatible(5)) {
			savedSpeakerPhone = audioManager.isSpeakerphoneOn();
		}
		
		isSavedAudioState = true;
	}
	
	/**
	 * Set the audio mode as in call
	 */
	private void setAudioInCall() {
		saveAudioState();
		
		audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
		audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
		
		PhoneUtils.setAudioMode(service, AudioManager.MODE_IN_CALL);
		audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 
				audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)*3/4, 
				0);
		
		
		
		//Wifi management if necessary
		ContentResolver ctntResolver = service.getContentResolver();
		Settings.System.putInt(ctntResolver, Settings.System.WIFI_SLEEP_POLICY, Settings.System.WIFI_SLEEP_POLICY_NEVER);
		//Acquire wifi lock
		WifiManager wman = (WifiManager) service.getSystemService(Context.WIFI_SERVICE);
		if(wifiLock == null) {
			wifiLock = wman.createWifiLock("com.csipsimple.InCallLock");
		}
		WifiInfo winfo = wman.getConnectionInfo();
		if(winfo != null) {
			DetailedState dstate = WifiInfo.getDetailedStateOf(winfo.getSupplicantState());
			//We assume that if obtaining ip addr, we are almost connected so can keep wifi lock
			if(dstate == DetailedState.OBTAINING_IPADDR || dstate == DetailedState.CONNECTED) {
				wifiLock.acquire();
			}
		}
	}
	
	
	
	/**
	 * Reset the audio mode
	 */
	private void unsetAudioInCall() {
		if(!isSavedAudioState) {
			return;
		}
		
		ContentResolver ctntResolver = service.getContentResolver();

		Settings.System.putInt(ctntResolver, Settings.System.WIFI_SLEEP_POLICY, savedWifiPolicy);
		audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, savedVibrateRing);
		audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, savedVibradeNotif);

		
		audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0);
		
		PhoneUtils.setAudioControlState(PhoneUtils.AUDIO_IDLE);
		if(Compatibility.isCompatible(5)) {
			audioManager.setSpeakerphoneOn(savedSpeakerPhone);
		}else {
			PhoneUtils.setAudioMode(service, AudioManager.MODE_NORMAL);
		}
		
		if(wifiLock != null && wifiLock.isHeld()) {
			wifiLock.release();
		}
		
		isSavedAudioState = false;
	}

}