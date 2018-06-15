var argscheck = require('cordova/argscheck'),
    utils = require('cordova/utils'),
    exec = require('cordova/exec');

var mediaObjects = {};

var AccurateAudio = function(src, successCallback, errorCallback, statusCallback) {
    argscheck.checkArgs('sFFF', 'AccurateAudio', arguments);
    this.id = utils.createUUID();
    mediaObjects[this.id] = this;
    this.src = src;
    this.successCallback = successCallback;
    this.errorCallback = errorCallback;
    this.statusCallback = statusCallback;
    this._duration = -1;
    this._position = -1;
    exec(null, this.errorCallback, "AccurateAudio", "create", [this.id, this.src]);
};


// Media messages
AccurateAudio.MEDIA_STATE = 1;
AccurateAudio.MEDIA_DURATION = 2;
AccurateAudio.MEDIA_POSITION = 3;
AccurateAudio.MEDIA_ERROR = 9;

// Media states
AccurateAudio.MEDIA_NONE = 0;
AccurateAudio.MEDIA_STARTING = 1;
AccurateAudio.MEDIA_RUNNING = 2;
AccurateAudio.MEDIA_PAUSED = 3;
AccurateAudio.MEDIA_STOPPED = 4;
AccurateAudio.MEDIA_MSG = ["None", "Starting", "Running", "Paused", "Stopped"];

// "static" function to return existing objs.
AccurateAudio.get = function(id) {
    return mediaObjects[id];
};

/**
 * Start or resume playing audio file.
 */
AccurateAudio.prototype.play = function(options) {
    exec(null, null, "AccurateAudio", "startPlayingAudio", [this.id, this.src, options]);
};

/**
 * Stop playing audio file.
 */
AccurateAudio.prototype.stop = function() {
    var me = this;
    exec(function() {
        me._position = 0;
    }, this.errorCallback, "AccurateAudio", "stopPlayingAudio", [this.id]);
};

/**
 * Seek or jump to a new time in the track..
 */
AccurateAudio.prototype.seekTo = function(milliseconds) {
    var me = this;
    exec(function(p) {
        me._position = p;
    }, this.errorCallback, "AccurateAudio", "seekToAudio", [this.id, milliseconds]);
};

/**
 * Pause playing audio file.
 */
AccurateAudio.prototype.pause = function() {
    exec(null, this.errorCallback, "AccurateAudio", "pausePlayingAudio", [this.id]);
};

/**
 * Get duration of an audio file.
 * The duration is only set for audio that is playing, paused or stopped.
 *
 * @return      duration or -1 if not known.
 */
AccurateAudio.prototype.getDuration = function() {
    return this._duration;
};

/**
 * Get position of audio.
 */
AccurateAudio.prototype.getCurrentPosition = function(success, fail) {
    var me = this;
    exec(function(p) {
        me._position = p;
        success(p);
    }, fail, "AccurateAudio", "getCurrentPositionAudio", [this.id]);
};

/**
 * Release the resources.
 */
AccurateAudio.prototype.release = function() {
    exec(null, this.errorCallback, "AccurateAudio", "release", [this.id]);
};

/**
 * Adjust the volume.
 */
AccurateAudio.prototype.setVolume = function(volume) {
    exec(null, null, "AccurateAudio", "setVolume", [this.id, volume]);
};

/**
 * Adjust the playback rate.
 */
AccurateAudio.prototype.setRate = function(rate) {
    if (cordova.platformId === 'ios'){
        exec(null, null, "AccurateAudio", "setRate", [this.id, rate]);
    } else {
        console.warn('media.setRate method is currently not supported for', cordova.platformId, 'platform.');
    }
};

/**
 * Get amplitude of audio.
 */
AccurateAudio.prototype.getCurrentAmplitude = function(success, fail) {
    exec(function(p) {
        success(p);
    }, fail, "AccurateAudio", "getCurrentAmplitudeAudio", [this.id]);
};

/**
 * Audio has status update.
 * PRIVATE
 *
 * @param id            The media object id (string)
 * @param msgType       The 'type' of update this is
 * @param value         Use of value is determined by the msgType
 */
AccurateAudio.onStatus = function(id, msgType, value) {

    var media = mediaObjects[id];

    if (media) {
        switch(msgType) {
            case AccurateAudio.MEDIA_STATE :
                if (media.statusCallback) {
                    media.statusCallback(value);
                }
                if (value == AccurateAudio.MEDIA_STOPPED) {
                    if (media.successCallback) {
                        media.successCallback();
                    }
                }
                break;
            case AccurateAudio.MEDIA_DURATION :
                media._duration = value;
                break;
            case AccurateAudio.MEDIA_ERROR :
                if (media.errorCallback) {
                    media.errorCallback(value);
                }
                break;
            case AccurateAudio.MEDIA_POSITION :
                media._position = Number(value);
                break;
            default :
                if (console.error) {
                    console.error("Unhandled AccurateAudio.onStatus :: " + msgType);
                }
                break;
        }
    } else if (console.error) {
        console.error("Received AccurateAudio.onStatus callback for unknown media :: " + id);
    }

};

module.exports = AccurateAudio;

function onMessageFromNative(msg) {
    if (msg.action == 'status') {
        AccurateAudio.onStatus(msg.status.id, msg.status.msgType, msg.status.value);
    } else {
        throw new Error('Unknown media action' + msg.action);
    }
}

if (cordova.platformId === 'android' || cordova.platformId === 'amazon-fireos' || cordova.platformId === 'windowsphone') {

    var channel = require('cordova/channel');

    channel.createSticky('onAccurateAudioPluginReady');
    channel.waitForInitialization('onAccurateAudioPluginReady');

    channel.onCordovaReady.subscribe(function() {
        exec(onMessageFromNative, undefined, 'AccurateAudio', 'messageChannel', []);
        channel.initializationComplete('onAccurateAudioPluginReady');
    });
}
