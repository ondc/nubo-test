package fi.vtt.nubotest;

import android.app.ListActivity;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Map;

import fi.vtt.nubomedia.kurentoroomclientandroid.RoomError;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomListener;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomNotification;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomResponse;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMMediaConfiguration;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMPeerConnection;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer;

import fi.vtt.nubotest.util.Constants;

/**
 * Activity for receiving the video stream of a peer
 * (based on PeerVideoActivity of Pubnub's video chat tutorial example.
 */
public class PeerVideoActivity extends ListActivity implements NBMWebRTCPeer.Observer, RoomListener {
    private static final String TAG = "PeerVideoActivity";

    private NBMMediaConfiguration peerConnectionParameters;
    private NBMWebRTCPeer nbmWebRTCPeer;

    private SessionDescription localSdp;
    private SessionDescription remoteSdp;

    private VideoRenderer.Callbacks localRender;
    private VideoRenderer.Callbacks remoteRender;
    private GLSurfaceView videoView;

    private SharedPreferences mSharedPreferences;

    private int publishVideoRequestId;
    private int sendIceCandidateRequestId;

    private TextView mCallStatus;

    private String  username, calluser;
    private boolean backPressed = false;
    private Thread  backPressedThread = null;

    private static final int LOCAL_X_CONNECTED = 72;
    private static final int LOCAL_Y_CONNECTED = 72;
    private static final int LOCAL_WIDTH_CONNECTED = 25;
    private static final int LOCAL_HEIGHT_CONNECTED = 25;
    // Remote video screen position
    private static final int REMOTE_X = 0;
    private static final int REMOTE_Y = 0;
    private static final int REMOTE_WIDTH = 100;
    private static final int REMOTE_HEIGHT = 100;

    private Handler mHandler;
    private CallState callState;

    private enum CallState{
        IDLE, PUBLISHING, PUBLISHED, WAITING_REMOTE_USER, RECEIVING_REMOTE_USER
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        callState = CallState.IDLE;

        setContentView(R.layout.activity_video_chat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler = new Handler();
        Bundle extras = getIntent().getExtras();
        if (extras == null || !extras.containsKey(Constants.USER_NAME)) {
            ;
            Toast.makeText(this, "Need to pass username to PeerVideoActivity in intent extras (Constants.USER_NAME).",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        this.username      = extras.getString(Constants.USER_NAME, "");
        Log.i(TAG, "username: " + username);

        if (extras.containsKey(Constants.CALL_USER)) {
            this.calluser      = extras.getString(Constants.CALL_USER, "");
            Log.i(TAG, "callUser: " + calluser);
        }

        this.mCallStatus   = (TextView) findViewById(R.id.call_status);
        TextView prompt   = (TextView) findViewById(R.id.receive_prompt);
        prompt.setText("Receive from " + calluser);

        this.videoView = (GLSurfaceView) findViewById(R.id.gl_surface);
        // Set up the List View for chatting
        RendererCommon.ScalingType scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL;
        VideoRendererGui.setView(videoView, null);

        remoteRender = VideoRendererGui.create( REMOTE_X, REMOTE_Y,
                REMOTE_WIDTH, REMOTE_HEIGHT,
                scalingType, false);
        localRender = VideoRendererGui.create(	LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED,
                LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED,
                scalingType, true);
        NBMMediaConfiguration.NBMVideoFormat receiverVideoFormat = new NBMMediaConfiguration.NBMVideoFormat(352, 288, PixelFormat.RGB_888, 20);
        peerConnectionParameters = new NBMMediaConfiguration(   NBMMediaConfiguration.NBMRendererType.OPENGLES,
                NBMMediaConfiguration.NBMAudioCodec.OPUS, 0,
                NBMMediaConfiguration.NBMVideoCodec.VP8, 0,
                receiverVideoFormat,
                NBMMediaConfiguration.NBMCameraPosition.FRONT);
        nbmWebRTCPeer = new NBMWebRTCPeer(peerConnectionParameters, this, localRender, this);
        nbmWebRTCPeer.initialize();
        Log.i(TAG, "Initializing PeerVideoActivity...");
        mHandler.post(publishWhenReady);

        MainActivity.getKurentoRoomAPIInstance().addObserver(this);

        callState = CallState.PUBLISHING;
        mCallStatus.setText("Publishing...");
    }

    private Runnable publishWhenReady = new Runnable() {
        @Override
        public void run() {
            while(!nbmWebRTCPeer.isInitialized()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.i(TAG, "PeerVideoActivity initialized");
            nbmWebRTCPeer.generateOffer("derp", true);
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_video_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();

    }

    @Override
    protected void onPause() {
        nbmWebRTCPeer.stopLocalMedia();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nbmWebRTCPeer.startLocalMedia();
    }

    @Override
    protected void onStop() {
        endCall();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Data channel test code
        /*DataChannel channel = nbmWebRTCPeer.getDataChannel("derp", "test_channel_static");
        if (channel.state() == DataChannel.State.OPEN) {
            sendHelloMessage(channel);
            Log.i(TAG, "[datachannel] Datachannel open, sending hello");
        }
        else {
            Log.i(TAG, "[datachannel] Channel is not open! State: " + channel.state());
        }
        Log.i(TAG, "[DataChannel] Testing for existing channel");
        DataChannel channel =  nbmWebRTCPeer.getDataChannel("derp", "default");
        if (channel == null) {
            DataChannel.Init init = new DataChannel.Init();
            init.negotiated = false;
            init.ordered = true;
            Log.i(TAG, "[DataChannel] Channel does not exist, creating...");
            channel = nbmWebRTCPeer.createDataChannel("derp", "test_channel", init);
        }
        else {
            Log.i(TAG, "[DataChannel] Channel already exists. State: " + channel.state());
            sendHelloMessage(channel);
        }*/

        // If back button has not been pressed in a while then trigger thread and toast notification
        if (!this.backPressed){
            this.backPressed = true;
            Toast.makeText(this,"Press back again to end.",Toast.LENGTH_SHORT).show();
            this.backPressedThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000);
                        backPressed = false;
                    } catch (InterruptedException e){ Log.d("VCA-oBP","Successfully interrupted"); }
                }
            });
            this.backPressedThread.start();
        }
        // If button pressed the second time then call super back pressed
        // (eventually calls onDestroy)
        else {
            if (this.backPressedThread != null)
                this.backPressedThread.interrupt();
            super.onBackPressed();
        }
    }

    public void hangup(View view) {
        finish();
    }

    public void receiveFromRemote(View view){
        if (callState == CallState.PUBLISHED){
            callState = CallState.WAITING_REMOTE_USER;
            nbmWebRTCPeer.generateOffer("remote", false);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mCallStatus.setText("Waiting remote stream...");
                }
            });
        }
    }

    /**
     * Terminates the current call and ends activity
     */
    private void endCall() {
        callState = CallState.IDLE;
        try
        {
            if (nbmWebRTCPeer != null) {
                nbmWebRTCPeer.close();
                nbmWebRTCPeer = null;
            }
        }
        catch (Exception e){e.printStackTrace();}
    }

    @Override
    public void onLocalSdpOfferGenerated(final SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
            localSdp = sessionDescription;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.getKurentoRoomAPIInstance() != null) {
                        Log.d(TAG, "Sending " + sessionDescription.type);
                        publishVideoRequestId = ++Constants.id;

//                    String sender = calluser + "_webcam";
//                    MainActivity.getKurentoRoomAPIInstance().sendReceiveVideoFrom(sender, localSdp.description, publishVideoRequestId);

                        MainActivity.getKurentoRoomAPIInstance().sendPublishVideo(localSdp.description, false, publishVideoRequestId);
                    }
                }
            });
        } else { // Asking for remote user video
            remoteSdp = sessionDescription;
//            nbmWebRTCPeer.selectCameraPosition(NBMMediaConfiguration.NBMCameraPosition.BACK);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.getKurentoRoomAPIInstance() != null) {
                        Log.d(TAG, "Sending " + sessionDescription.type);
                        publishVideoRequestId = ++Constants.id;

                        String sender = calluser + "_webcam";
                        MainActivity.getKurentoRoomAPIInstance().sendReceiveVideoFrom(sender, remoteSdp.description, publishVideoRequestId);
                    }
                }
            });
        }
    }

    @Override
    public void onLocalSdpAnswerGenerated(SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate, NBMPeerConnection nbmPeerConnection) {
        sendIceCandidateRequestId = ++Constants.id;
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED){
            MainActivity.getKurentoRoomAPIInstance().sendOnIceCandidate(this.username, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), sendIceCandidateRequestId);
        } else {
            MainActivity.getKurentoRoomAPIInstance().sendOnIceCandidate(this.calluser, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), sendIceCandidateRequestId);
        }
    }

    @Override
    public void onIceStatusChanged(PeerConnection.IceConnectionState iceConnectionState, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onIceStatusChanged");
    }

    @Override
    public void onRemoteStreamAdded(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamAdded");
        nbmWebRTCPeer.attachRendererToRemoteStream(remoteRender, mediaStream);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCallStatus.setText("");
            }
        });
    }

    @Override
    public void onRemoteStreamRemoved(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamRemoved");
    }

    @Override
    public void onPeerConnectionError(String s) {
        Log.e(TAG, "onPeerConnectionError:" + s);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel, NBMPeerConnection connection) {
        Log.i(TAG, "[datachannel] Peer opened data channel");
    }

    @Override
    public void onBufferedAmountChange(long l, NBMPeerConnection connection, DataChannel channel) {

    }

    public void sendHelloMessage(DataChannel channel) {
        byte[] rawMessage = "Hello Peer!".getBytes(Charset.forName("UTF-8"));
        ByteBuffer directData = ByteBuffer.allocateDirect(rawMessage.length);
        directData.put(rawMessage);
        directData.flip();
        DataChannel.Buffer data = new DataChannel.Buffer(directData, false);
        channel.send(data);
    }

    @Override
    public void onStateChange(NBMPeerConnection connection, DataChannel channel) {
        Log.i(TAG, "[datachannel] DataChannel onStateChange: " + channel.state());
        if (channel.state() == DataChannel.State.OPEN) {
            sendHelloMessage(channel);
            Log.i(TAG, "[datachannel] Datachannel open, sending first hello");
        }
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, NBMPeerConnection connection, DataChannel channel) {
        Log.i(TAG, "[datachannel] Message received: " + buffer.toString());
        sendHelloMessage(channel);
    }

    @Override
    public void onRoomResponse(RoomResponse response) {
        Log.d(TAG, "OnRoomResponse:" + response);
        if (Integer.valueOf(response.getId()) == publishVideoRequestId){
            SessionDescription sd = new SessionDescription(SessionDescription.Type.ANSWER,
                                                            response.getValue("sdpAnswer").get(0));
            if (callState == CallState.PUBLISHING){
                callState = CallState.PUBLISHED;
                nbmWebRTCPeer.processAnswer(sd, "derp");
            } else if (callState == CallState.WAITING_REMOTE_USER){
                callState = CallState.RECEIVING_REMOTE_USER;
                nbmWebRTCPeer.processAnswer(sd, "remote");
            } else {
                //NOP
            }
        }
    }

    @Override
    public void onRoomError(RoomError error) {
        Log.e(TAG, "OnRoomError:" + error);
    }

    @Override
    public void onRoomNotification(RoomNotification notification) {
        Log.i(TAG, "OnRoomNotification (state=" + callState.toString() + "):" + notification);

        if(notification.getMethod().equals("iceCandidate"))
        {
            Map<String, Object> map = notification.getParams();

            String sdpMid = map.get("sdpMid").toString();
            int sdpMLineIndex = Integer.valueOf(map.get("sdpMLineIndex").toString());
            String sdp = map.get("candidate").toString();

            IceCandidate ic = new IceCandidate(sdpMid, sdpMLineIndex, sdp);

            if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "derp");
            } else {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "remote");
            }
        }
    }

    @Override
    public void onRoomConnected() {

    }

    @Override
    public void onRoomDisconnected() {

    }

}