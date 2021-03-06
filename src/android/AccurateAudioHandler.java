package cordova.accurate.audio;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaResourceApi;
import org.apache.cordova.PermissionHelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.net.Uri;
import android.os.Build;

import java.security.Permission;
import java.util.ArrayList;

import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;

import java.util.Timer;
import java.util.TimerTask;

public class AccurateAudioHandler extends CordovaPlugin {

    public static String TAG = "AccurateAudioHandler";
    HashMap<String, AccurateAudioPlayer> players;  // Audio player object
    ArrayList<AccurateAudioPlayer> pausedForPhone; // Audio players that were paused when phone call came in
    ArrayList<AccurateAudioPlayer> pausedForFocus; // Audio players that were paused when focus was lost
    private int origVolumeStream = -1;
    private CallbackContext messageChannel;


    public static String [] permissions = { Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    public static int RECORD_AUDIO = 0;
    public static int WRITE_EXTERNAL_STORAGE = 1;

    public static final int PERMISSION_DENIED_ERROR = 20;

    private String recordId;
    private String fileUriStr;

    /**
     * Constructor.
     */
    public AccurateAudioHandler() {
        this.players = new HashMap<String, AccurateAudioPlayer>();
        this.pausedForPhone = new ArrayList<AccurateAudioPlayer>();
        this.pausedForFocus = new ArrayList<AccurateAudioPlayer>();
    }


    protected void getWritePermission(int requestCode)
    {
        PermissionHelper.requestPermission(this, requestCode, permissions[WRITE_EXTERNAL_STORAGE]);
    }


    protected void getMicPermission(int requestCode)
    {
        PermissionHelper.requestPermission(this, requestCode, permissions[RECORD_AUDIO]);
    }

    CallbackContext retorno;
    /**
     * Executes the request and returns PluginResult.
     * @param action        The action to execute.
     * @param args          JSONArry of arguments for the plugin.
     * @param callbackContext       The callback context used when calling back into JavaScript.
     * @return              A PluginResult object with a status and message.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        CordovaResourceApi resourceApi = webView.getResourceApi();
        PluginResult.Status status = PluginResult.Status.OK;
        String result = "";
        retorno = callbackContext;

        if (action.equals("startPlayingAudio")) {
            String target = args.getString(1);
            String fileUriStr;
            try {
                Uri targetUri = resourceApi.remapUri(Uri.parse(target));
                fileUriStr = targetUri.toString();
            } catch (IllegalArgumentException e) {
                fileUriStr = target;
            }
            this.webView.loadUrl("javascript:console.log('execute " + args.getInt(2) + "');");
            this.startPlayingAudio(args.getString(0), FileHelper.stripFileProtocol(fileUriStr), args.getInt(2));
        }
        else if (action.equals("seekToAudio")) {
            this.seekToAudio(args.getString(0), args.getInt(1));
        }
        else if (action.equals("pausePlayingAudio")) {
            this.pausePlayingAudio(args.getString(0));
        }
        else if (action.equals("stopPlayingAudio")) {
            this.stopPlayingAudio(args.getString(0));
        } else if (action.equals("setVolume")) {
           try {
               this.setVolume(args.getString(0), Float.parseFloat(args.getString(1)));
           } catch (NumberFormatException nfe) {
               //no-op
           }
        } else if (action.equals("getCurrentPositionAudio")) {
            float f = this.getCurrentPositionAudio(args.getString(0));
            callbackContext.sendPluginResult(new PluginResult(status, f));
            return true;
        }
        else if (action.equals("getDurationAudio")) {
            float f = this.getDurationAudio(args.getString(0), args.getString(1));
            callbackContext.sendPluginResult(new PluginResult(status, f));
            return true;
        }
        else if (action.equals("create")) {
            String id = args.getString(0);
            String src = FileHelper.stripFileProtocol(args.getString(1));
            getOrCreatePlayer(id, src);
        }
        else if (action.equals("release")) {
            boolean b = this.release(args.getString(0));
            callbackContext.sendPluginResult(new PluginResult(status, b));
            return true;
        }
        else if (action.equals("messageChannel")) {
            messageChannel = callbackContext;
            return true;
        } else if (action.equals("getCurrentAmplitudeAudio")) {
            float f = this.getCurrentAmplitudeAudio(args.getString(0));
            callbackContext.sendPluginResult(new PluginResult(status, f));
            return true;
        }
        else { // Unrecognized action.
            return false;
        }

        callbackContext.sendPluginResult(new PluginResult(status, result));

        return true;
    }

    /**
     * Stop all audio players and recorders.
     */
    public void onDestroy() {
        if (!players.isEmpty()) {
            onLastPlayerReleased();
        }
        for (AccurateAudioPlayer audio : this.players.values()) {
            audio.destroy();
        }
        this.players.clear();
    }

    /**
     * Stop all audio players and recorders on navigate.
     */
    @Override
    public void onReset() {
        onDestroy();
    }

    /**
     * Called when a message is sent to plugin.
     *
     * @param id            The message id
     * @param data          The message data
     * @return              Object to stop propagation or null
     */
    public Object onMessage(String id, Object data) {

        // If phone message
        if (id.equals("telephone")) {

            // If phone ringing, then pause playing
            if ("ringing".equals(data) || "offhook".equals(data)) {

                // Get all audio players and pause them
                for (AccurateAudioPlayer audio : this.players.values()) {
                    if (audio.getState() == AccurateAudioPlayer.STATE.MEDIA_RUNNING.ordinal()) {
                        this.pausedForPhone.add(audio);
                        audio.pausePlaying();
                    }
                }

            }

            // If phone idle, then resume playing those players we paused
            else if ("idle".equals(data)) {
                for (AccurateAudioPlayer audio : this.pausedForPhone) {
                    audio.startPlaying(null);
                }
                this.pausedForPhone.clear();
            }
        }
        return null;
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    private AccurateAudioPlayer getOrCreatePlayer(String id, String file) {
        AccurateAudioPlayer ret = players.get(id);
        if (ret == null) {
            if (players.isEmpty()) {
                onFirstPlayerCreated();
            }
            ret = new AccurateAudioPlayer(this, id, file);
            players.put(id, ret);
        }
        return ret;
    }

    /**
     * Release the audio player instance to save memory.
     * @param id                The id of the audio player
     */
    private boolean release(String id) {
        AccurateAudioPlayer audio = players.remove(id);
        if (audio == null) {
            return false;
        }
        if (players.isEmpty()) {
            onLastPlayerReleased();
        }
        audio.destroy();
        return true;
    }

    /**
     * Start or resume playing audio file.
     * @param id                The id of the audio player
     * @param file              The name of the audio file.
     * @param when              Quando vai tocar
     */
    public void startPlayingAudio(String id, String file, int when) {
       this.webView.loadUrl("javascript:console.log('startPlayingAudio');");
        AccurateAudioPlayer audio = getOrCreatePlayer(id, file);
        audio.agendaPlay(file, when);
        getAudioFocus();        
    }

       
    /**
     * Seek to a location.
     * @param id                The id of the audio player
     * @param milliseconds      int: number of milliseconds to skip 1000 = 1 second
     */
    public void seekToAudio(String id, int milliseconds) {
        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            audio.seekToPlaying(milliseconds);
        }
    }

    /**
     * Pause playing.
     * @param id                The id of the audio player
     */
    public void pausePlayingAudio(String id) {
        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            audio.pausePlaying();
        }
    }

    /**
     * Stop playing the audio file.
     * @param id                The id of the audio player
     */
    public void stopPlayingAudio(String id) {
        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            audio.stopPlaying();
        }
    }

    /**
     * Get current position of playback.
     * @param id                The id of the audio player
     * @return                  position in msec
     */
    public float getCurrentPositionAudio(String id) {
        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            return (audio.getCurrentPosition() / 1000.0f);
        }
        return -1;
    }

    /**
     * Get the duration of the audio file.
     * @param id                The id of the audio player
     * @param file              The name of the audio file.
     * @return                  The duration in msec.
     */
    public float getDurationAudio(String id, String file) {
        AccurateAudioPlayer audio = getOrCreatePlayer(id, file);
        return audio.getDuration(file);
    }

    /**
     * Set the audio device to be used for playback.
     *
     * @param output            1=earpiece, 2=speaker
     */
    @SuppressWarnings("deprecation")
    public void setAudioOutputDevice(int output) {
        String TAG1 = "AccurateAudioHandler.setAudioOutputDevice(): Error : ";

        AudioManager audiMgr = (AudioManager) this.cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
        if (output == 2) {
            audiMgr.setRouting(AudioManager.MODE_NORMAL, AudioManager.ROUTE_SPEAKER, AudioManager.ROUTE_ALL);
        }
        else if (output == 1) {
            audiMgr.setRouting(AudioManager.MODE_NORMAL, AudioManager.ROUTE_EARPIECE, AudioManager.ROUTE_ALL);
        }
        else {
             LOG.e(TAG1," Unknown output device");
        }
    }

    public void pauseAllLostFocus() {
        for (AccurateAudioPlayer audio : this.players.values()) {
            if (audio.getState() == AccurateAudioPlayer.STATE.MEDIA_RUNNING.ordinal()) {
                this.pausedForFocus.add(audio);
                audio.pausePlaying();
            }
        }
    }

    public void resumeAllGainedFocus() {
        for (AccurateAudioPlayer audio : this.pausedForFocus) {
            audio.startPlaying(null);
        }
        this.pausedForFocus.clear();
    }

    /**
     * Get the the audio focus
     */
    private OnAudioFocusChangeListener focusChangeListener = new OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) :
                case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) :
                case (AudioManager.AUDIOFOCUS_LOSS) :
                    pauseAllLostFocus();
                    break;
                case (AudioManager.AUDIOFOCUS_GAIN):
                    resumeAllGainedFocus();
                    break;
                default:
                    break;
                }
            }
        };

    public void getAudioFocus() {
        String TAG2 = "AccurateAudioHandler.getAudioFocus(): Error : ";

        AudioManager am = (AudioManager) this.cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
        int result = am.requestAudioFocus(focusChangeListener,
                                          AudioManager.STREAM_MUSIC,
                                          AudioManager.AUDIOFOCUS_GAIN);

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            LOG.e(TAG2,result + " instead of " + AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        }

    }


    /**
     * Get the audio device to be used for playback.
     *
     * @return                  1=earpiece, 2=speaker
     */
    @SuppressWarnings("deprecation")
    public int getAudioOutputDevice() {
        AudioManager audiMgr = (AudioManager) this.cordova.getActivity().getSystemService(Context.AUDIO_SERVICE);
        if (audiMgr.getRouting(AudioManager.MODE_NORMAL) == AudioManager.ROUTE_EARPIECE) {
            return 1;
        }
        else if (audiMgr.getRouting(AudioManager.MODE_NORMAL) == AudioManager.ROUTE_SPEAKER) {
            return 2;
        }
        else {
            return -1;
        }
    }

    /**
     * Set the volume for an audio device
     *
     * @param id                The id of the audio player
     * @param volume            Volume to adjust to 0.0f - 1.0f
     */
    public void setVolume(String id, float volume) {
        String TAG3 = "AccurateAudioHandler.setVolume(): Error : ";

        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            audio.setVolume(volume);
        } else {
          LOG.e(TAG3,"Unknown Audio Player " + id);
        }
    }

    private void onFirstPlayerCreated() {
        origVolumeStream = cordova.getActivity().getVolumeControlStream();
        cordova.getActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    private void onLastPlayerReleased() {
        if (origVolumeStream != -1) {
            cordova.getActivity().setVolumeControlStream(origVolumeStream);
            origVolumeStream = -1;
        }
    }

    void retornaJS(String msg) {
        PluginResult resultado = new PluginResult(PluginResult.Status.OK, msg);
        resultado.setKeepCallback(true);
        retorno.sendPluginResult(resultado);
    }

    void sendEventMessage(String action, JSONObject actionData) {
        JSONObject message = new JSONObject();
        try {
            message.put("action", action);
            if (actionData != null) {
                message.put(action, actionData);
            }
        } catch (JSONException e) {
            LOG.e(TAG, "Failed to create event message", e);
        }

        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, message);
        pluginResult.setKeepCallback(true);
        if (messageChannel != null) {
            messageChannel.sendPluginResult(pluginResult);
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                this.messageChannel.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return;
            }
        }
        promptForRecord();
    }

    /*
     * This little utility method catch-all work great for multi-permission stuff.
     *
     */

    private void promptForRecord()
    {
        if(PermissionHelper.hasPermission(this, permissions[WRITE_EXTERNAL_STORAGE])  &&
                PermissionHelper.hasPermission(this, permissions[RECORD_AUDIO])) {
            
        }
        else if(PermissionHelper.hasPermission(this, permissions[RECORD_AUDIO]))
        {
            getWritePermission(WRITE_EXTERNAL_STORAGE);
        }
        else
        {
            getMicPermission(RECORD_AUDIO);
        }

    }

    /**
     * Get current amplitude of recording.
     * @param id                The id of the audio player
     * @return                  amplitude
     */
    public float getCurrentAmplitudeAudio(String id) {
        AccurateAudioPlayer audio = this.players.get(id);
        if (audio != null) {
            return (audio.getCurrentAmplitude());
        }
        return 0;
    }
}
